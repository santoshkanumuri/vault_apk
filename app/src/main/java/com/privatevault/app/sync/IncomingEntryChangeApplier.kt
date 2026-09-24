package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncAttachmentManifestEntity
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.passkeys.PasskeyCrypto
import com.privatevault.app.security.Totp
import com.privatevault.app.security.EncryptedPhotoStore
import org.json.JSONObject
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

enum class IncomingResult { APPLIED, REPLAY, CONFLICT, OBSOLETE }
internal class MissingPhotoDependencyException : Exception("Cover photo is still waiting for its image")

internal class IncomingEntryChangeApplier(private val database: VaultDatabase,
    private val photoBlobs: PhotoSyncBlobs? = null, private val context: Context? = null) {
    suspend fun apply(operation: SyncOperationEntity, vaultKey: ByteArray): IncomingResult {
        require(vaultKey.size == 32) { "Invalid vault key" }
        val member = requireNotNull(database.syncDao().membership(operation.vaultId, operation.deviceId)) {
            "Device is not a vault member"
        }.toMembership()
        member.validate()
        require(member.status == MemberStatus.ACTIVE && member.vaultId == operation.vaultId &&
            member.deviceId == operation.deviceId && member.keyEpoch == 1L) {
            "Device is not an active vault member"
        }
        require(operation.entityType == "entry" || operation.entityType == "group" ||
            operation.entityType == "passkey" || operation.entityType == "photo" ||
            operation.entityType == "photo_cover") { "Unsupported sync entity" }
        val change = operation.toChangeRecord()
        change.validate()
        val signature = Base64.getUrlDecoder().decode(change.deviceSignature)
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey), change.signingBytes(), signature)) {
            "Invalid device signature"
        }
        val contentKey = database.syncContentKey(vaultKey)
        val payload = try { decrypt(change, contentKey) } finally { contentKey.fill(0) }
        val entry = if (change.kind == ChangeKind.UPSERT && change.entityType == "entry")
            parseEntry(payload.getJSONObject("entry"), change.entityId) else null
        val group = if (change.kind == ChangeKind.UPSERT && change.entityType == "group")
            parseGroup(payload.getJSONObject("group"), change.entityId) else null
        val passkey = if (change.kind == ChangeKind.UPSERT && change.entityType == "passkey")
            parsePasskey(payload.getJSONObject("passkey"), change.entityId) else null
        val photo = if (change.kind == ChangeKind.UPSERT && change.entityType == "photo")
            parsePhoto(payload.getJSONObject("photo"), change.entityId) else null
        val coverPhotoId = if (change.entityType == "photo_cover") {
            require(change.kind == ChangeKind.UPSERT) { "Invalid cover change" }
            payload.getString("coverPhotoId").also { require(it.isNotBlank() && it.length <= 128) }
        } else null
        if (photo != null && photoBlobs?.has(photo.ref) != true) throw MissingPhotoBlobException(photo.ref.hash)
        val groupIds = if (entry != null) payload.getJSONArray("groupIds").let { array ->
            (0 until array.length()).map(array::getString).toSet()
        } else if (change.kind == ChangeKind.DELETE) {
            require(payload.getBoolean("deleted")) { "Invalid delete payload" }
            emptySet()
        } else emptySet()
        val retiredPhotos = mutableListOf<VaultPhoto>()
        val outcome = database.withTransaction {
            val settings = requireNotNull(database.dao().settings()) { "Vault settings are missing" }
            require(settings.vaultId == change.vaultId) { "Wrong vault" }
            val syncDao = database.syncDao()
            val currentMember = requireNotNull(syncDao.membership(change.vaultId, change.deviceId)) {
                "Device is not a vault member"
            }.toMembership()
            require(currentMember == member && currentMember.status == MemberStatus.ACTIVE) {
                "Device membership changed"
            }
            syncDao.operation(change.mutationId)?.let { existing ->
                require(existing.hash == change.hash) { "Mutation ID was reused" }
                return@withTransaction IncomingResult.REPLAY
            }
            val head = syncDao.deviceHead(change.vaultId, change.deviceId)
            require(change.sequence == (head?.sequence ?: 0L) + 1L && change.previousHash == (head?.hash ?: GENESIS_HASH)) {
                "Broken device change chain"
            }
            val state = syncDao.recordState(change.entityType, change.entityId)
            val localVersion = state?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            val relation = if (state == null) VersionRelation.AFTER else change.recordVersion.relationTo(localVersion)
            val result = when (relation) {
                VersionRelation.AFTER -> {
                    if (entry != null) database.dao().saveEntryExact(entry, groupIds)
                    else if (group != null) database.dao().insertGroup(group)
                    else if (passkey != null) {
                        val saved = database.dao().allPasskeys().firstOrNull { it.id == passkey.id }
                        if (saved == null) database.dao().insertPasskeys(listOf(passkey))
                        else require(saved == passkey) { "Passkey credential ID conflict" }
                    }
                    else if (photo != null) {
                        if (database.dao().entry(photo.entryId) == null) throw MissingPhotoDependencyException()
                        val store = EncryptedPhotoStore(requireNotNull(context))
                        val key = database.syncContentKey(vaultKey)
                        val names = try { requireNotNull(photoBlobs).materialize(photo.ref, store,
                            vaultKey, key, change.vaultId, photo.id) } finally { key.fill(0) }
                        val existingPhoto = database.dao().photo(photo.id)
                        if (existingPhoto != null) retiredPhotos += existingPhoto
                        val saved = VaultPhoto(photo.id, photo.entryId, names.first, names.second,
                            existingPhoto?.isCover ?: photo.isCover, photo.addedAt)
                        database.dao().insertPhoto(saved)
                        if (saved.isCover && existingPhoto == null) database.dao().setPhotoCover(saved)
                        syncDao.upsertAttachmentManifest(SyncAttachmentManifestEntity(photo.id, "entry",
                            photo.entryId, names.first, photo.ref.hash, photo.ref.size, 1))
                    }
                    else if (coverPhotoId != null) {
                        val selected = database.dao().photo(coverPhotoId) ?: throw MissingPhotoDependencyException()
                        require(selected.entryId == change.entityId) { "Cover belongs to another entry" }
                        database.dao().setPhotoCover(selected.copy(isCover = true))
                    }
                    else {
                        if (change.entityType == "entry")
                            database.dao().entry(change.entityId)?.let {
                                retiredPhotos.addAll(it.photos)
                                database.dao().deleteEntryAndLinks(it.entry)
                            }
                        else if (change.entityType == "passkey") database.dao().deletePasskey(change.entityId)
                        else if (change.entityType == "photo") database.dao().photo(change.entityId)
                            ?.let { retiredPhotos += it; database.dao().deletePhoto(it) }
                        else database.dao().allGroupsWithEntries().firstOrNull { it.group.id == change.entityId }
                            ?.let { database.dao().deleteGroup(it.group) }
                        syncDao.upsertTombstone(SyncTombstoneEntity(change.mutationId, change.entityType, change.entityId,
                            change.deviceId, change.sequence, change.recordVersion.toJson(), change.occurredAtUtc, change.hash))
                    }
                    syncDao.upsertRecordState(SyncRecordStateEntity(change.entityType, change.entityId,
                        change.baseRevision + 1L, change.recordVersion.toJson()))
                    syncDao.conflicts().filter { it.entityType == change.entityType && it.entityId == change.entityId &&
                        it.resolvedAtUtc.isEmpty() }.forEach { conflict ->
                        val dominates = listOf(conflict.localVersionJson, conflict.remoteVersionJson).all {
                            change.recordVersion.relationTo(RecordVersion.parse(it)) in setOf(VersionRelation.AFTER, VersionRelation.EQUAL)
                        }
                        if (dominates) syncDao.resolveConflict(conflict.conflictId, Instant.now().toString())
                    }
                    IncomingResult.APPLIED
                }
                VersionRelation.CONCURRENT -> {
                    syncDao.insertConflict(SyncConflictEntity(UUID.randomUUID().toString(), change.entityType, change.entityId,
                        syncDao.operationsForEntity(change.entityType, change.entityId).firstOrNull {
                            RecordVersion.parse(it.recordVersionJson).relationTo(localVersion) == VersionRelation.EQUAL
                        }?.hash ?: GENESIS_HASH,
                        change.hash, localVersion.toJson(), change.recordVersion.toJson(), Instant.now().toString()))
                    IncomingResult.CONFLICT
                }
                else -> IncomingResult.OBSOLETE
            }
            syncDao.insertOperation(operation)
            syncDao.upsertDeviceHead(SyncDeviceHeadEntity(change.vaultId, change.deviceId, change.sequence, change.hash))
            result
        }
        if (outcome == IncomingResult.APPLIED && retiredPhotos.isNotEmpty() && context != null) {
            val photos = EncryptedPhotoStore(context)
            retiredPhotos.forEach {
                photos.delete(it.encryptedFileName)
                if (it.encryptedThumbnailFileName.isNotBlank()) photos.delete(it.encryptedThumbnailFileName)
            }
        }
        return outcome
    }

    internal fun decrypt(change: SyncChangeRecord, vaultKey: ByteArray): JSONObject {
        require(change.payloadCiphertext.length <= 1_500_000) { "Sync payload is too large" }
        val ciphertext = Base64.getUrlDecoder().decode(change.payloadCiphertext)
        val nonce = Base64.getUrlDecoder().decode(change.payloadNonce)
        require(nonce.size == 12 && ciphertext.size <= 1_048_592) { "Invalid sync payload size" }
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vaultKey, "HmacSHA256"))
            doFinal("nuvori-sync-payload-key-v1".toByteArray())
        }
        return try {
            val plaintext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("nuvori-sync-${change.entityType}-v1:${change.vaultId}:${change.mutationId}:${change.entityId}".toByteArray())
                doFinal(ciphertext)
            }
            try { JSONObject(plaintext.toString(Charsets.UTF_8)) }
            finally { plaintext.fill(0) }
        } finally { payloadKey.fill(0) }
    }

    private fun parseEntry(json: JSONObject, expectedId: String): VaultEntry = try {
        VaultEntry(
            id = json.getString("id"), type = EntryType.valueOf(json.getString("type")), title = json.getString("title"),
            primaryValue = json.getString("primaryValue"), secondaryValue = json.getString("secondaryValue"),
            tertiaryValue = json.getString("tertiaryValue"), fourthValue = json.getString("fourthValue"),
            cardKind = CardKind.valueOf(json.getString("cardKind")), network = json.getString("network"),
            totpAlgorithm = json.getString("totpAlgorithm"), totpDigits = json.getInt("totpDigits"),
            totpPeriod = json.getInt("totpPeriod"), linkedApps = json.getString("linkedApps"),
            autofillSignatures = json.getString("autofillSignatures"), autofillOrigins = json.getString("autofillOrigins"),
            linkedAuthenticatorId = json.getString("linkedAuthenticatorId"), notes = json.getString("notes"),
            color = json.getLong("color"), tags = json.getString("tags"), favorite = json.getBoolean("favorite"),
            lastOpenedAt = json.getLong("lastOpenedAt"), sortOrder = json.getLong("sortOrder"),
            createdAt = json.getLong("createdAt"), updatedAt = json.getLong("updatedAt"),
        ).also {
            require(it.id == expectedId && it.title.isNotBlank()) { "Invalid sync entry" }
            if (it.type == EntryType.AUTHENTICATOR) Totp.validate(it.secondaryValue, it.totpAlgorithm, it.totpDigits, it.totpPeriod)
        }
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync entry") }

    private fun parseGroup(json: JSONObject, expectedId: String): VaultGroup = try {
        VaultGroup(json.getString("id"), json.getString("name"), json.getString("notes"),
            json.getString("folderType").takeIf(String::isNotBlank)?.let(EntryType::valueOf)).also {
            require(it.id == expectedId && it.name.isNotBlank()) { "Invalid sync group" }
        }
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync group") }

    private fun parsePasskey(json: JSONObject, expectedId: String): VaultPasskey = try {
        VaultPasskey(json.getString("id"), json.getString("rpId"), json.getString("userHandle"),
            json.getString("username"), json.getString("displayName"), json.getString("privateKey"),
            json.getString("publicKey"), json.getLong("createdAt")).also {
            require(it.id == expectedId) { "Invalid passkey ID" }
            PasskeyCrypto.validateStored(it)
        }
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync passkey") }

    private data class PhotoChange(val id: String, val entryId: String, val isCover: Boolean,
        val addedAt: Long, val ref: PhotoBlobRef)

    private fun parsePhoto(json: JSONObject, expectedId: String): PhotoChange = try {
        PhotoChange(json.getString("id"), json.getString("entryId"), json.getBoolean("isCover"),
            json.getLong("addedAt"), PhotoBlobRef(json.getString("blobHash"), json.getLong("blobSize"))).also {
            require(it.id == expectedId && it.entryId.isNotBlank() &&
                it.ref.hash.matches(Regex("[a-f0-9]{64}")) &&
                it.ref.size in 28..PhotoSyncBlobs.MAX_PHOTO_BYTES + 28)
        }
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync photo") }

    private fun SyncOperationEntity.toChangeRecord() = SyncChangeRecord(formatVersion, vaultId, deviceId, sequence,
        previousHash, mutationId, entityType, entityId, if (kind == "upsert") ChangeKind.UPSERT else ChangeKind.DELETE,
        baseRevision, RecordVersion.parse(recordVersionJson), occurredAtUtc,
        payloadCiphertext, payloadNonce, deviceSignature, hash)
}
