package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncAttachmentManifestEntity
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.passkeys.PasskeyCrypto
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
internal class MissingRecordDependencyException : Exception("A linked item is still waiting to sync")

internal class IncomingEntryChangeApplier(private val database: VaultDatabase,
    private val photoBlobs: PhotoSyncBlobs? = null, private val context: Context? = null,
    private val announceRemoteChanges: Boolean = true) {
    /**
     * Applies one verified operation. [skipPhotoBlob] records a photo upsert without its image; the
     * caller sets it only when a later operation from the same author removes that image anyway.
     */
    suspend fun apply(operation: SyncOperationEntity, vaultKey: ByteArray,
        skipPhotoBlob: Boolean = false): IncomingResult {
        require(vaultKey.size == 32) { "Invalid vault key" }
        val member = requireNotNull(database.syncDao().membership(operation.vaultId, operation.deviceId)) {
            "Device is not a vault member"
        }.toMembership()
        member.validate()
        require(member.status == MemberStatus.ACTIVE && member.vaultId == operation.vaultId &&
            member.deviceId == operation.deviceId && member.keyEpoch >= 1L) {
            "Device is not an active vault member"
        }
        require(operation.entityType == "entry" || operation.entityType == "group" ||
            operation.entityType == "passkey" || operation.entityType == "photo" ||
            operation.entityType == "photo_cover" || operation.entityType == "device_name") {
            "Unsupported sync entity"
        }
        val change = operation.toChangeRecord()
        change.validate()
        val signature = Base64.getUrlDecoder().decode(change.deviceSignature)
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey), change.signingBytes(), signature)) {
            "Invalid device signature"
        }
        val contentKey = database.syncContentKey(vaultKey)
        val payload = try { decrypt(change, contentKey) } finally { contentKey.fill(0) }
        val deviceName = if (change.entityType == "device_name") {
            require(change.kind == ChangeKind.UPSERT && change.entityId == change.deviceId) {
                "A device can only name itself"
            }
            payload.getString("name").also {
                require(it.isNotBlank() && it.length <= 40 && it.none(Char::isISOControl)) {
                    "Invalid device name"
                }
            }
        } else null
        val entry = if (change.kind == ChangeKind.UPSERT && change.entityType == "entry")
            SyncEntryCodec.parseEntry(payload.getJSONObject("entry").toString(), change.entityId) else null
        val group = if (change.kind == ChangeKind.UPSERT && change.entityType == "group")
            parseGroup(payload.getJSONObject("group"), change.entityId) else null
        val passkey = if (change.kind == ChangeKind.UPSERT && change.entityType == "passkey")
            parsePasskey(payload.getJSONObject("passkey"), change.entityId) else null
        val photo = if (change.kind == ChangeKind.UPSERT && change.entityType == "photo")
            SyncEntryCodec.parsePhoto(payload.getJSONObject("photo").toString(), change.entityId) else null
        val coverPhotoId = if (change.entityType == "photo_cover") {
            require(change.kind == ChangeKind.UPSERT) { "Invalid cover change" }
            payload.getString("coverPhotoId").also { require(it.isNotBlank() && it.length <= 128) }
        } else null
        // The image is not needed when its entry is known to be deleted here, when a later change
        // from the same author replaces or removes it, or when this change is already outdated.
        val skipPhoto = photo != null && (skipPhotoBlob || entryDeletedHere(photo.entryId) ||
            database.syncDao().recordState("photo", change.entityId)?.let {
                change.recordVersion.relationTo(RecordVersion.parse(it.recordVersionJson)) in
                    setOf(VersionRelation.BEFORE, VersionRelation.EQUAL)
            } == true)
        if (photo != null && !skipPhoto && photoBlobs?.has(photo.ref) != true)
            throw MissingPhotoBlobException(photo.ref.hash, photo.id, photo.entryId)
        val groupIds = if (entry != null) payload.optJSONArray("groupIds").let { array ->
            if (array == null) emptySet() else (0 until array.length()).map(array::getString).toSet()
        } else if (change.kind == ChangeKind.DELETE) {
            require(payload.getBoolean("deleted")) { "Invalid delete payload" }
            emptySet()
        } else emptySet()
        val retiredPhotos = mutableListOf<VaultPhoto>()
        var photoEntryId: String? = null
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
            val relation = ConflictVersions.incomingRelation(change.recordVersion, state?.let { localVersion })
            val result = when (relation) {
                VersionRelation.AFTER -> {
                    if (deviceName != null) syncDao.upsertMembership(
                        SyncMembershipEntity.from(currentMember.copy(displayName = deviceName)))
                    else if (entry != null) {
                        val groups = database.dao().allGroupsWithEntries().associate { it.group.id to it.group.folderType }
                        val deletedGroups = groupIds.filterTo(hashSetOf()) {
                            it !in groups && syncDao.tombstone("group", it) != null
                        }
                        val linkedId = entry.linkedAuthenticatorId
                        val linkedType = linkedId.takeIf(String::isNotBlank)?.let { database.dao().entry(it)?.entry?.type }
                        val linkedDeleted = linkedId.isNotBlank() && linkedType == null &&
                            syncDao.tombstone("entry", linkedId) != null
                        when (val resolved = EntryLinkRules.resolve(entry, groupIds,
                            EntryLinkRules.Context(groups, deletedGroups, linkedType, linkedDeleted))) {
                            EntryLinkRules.Resolution.Waiting -> throw MissingRecordDependencyException()
                            is EntryLinkRules.Resolution.Ready -> {
                                val previousType = database.dao().entry(entry.id)?.entry?.type
                                // A peer may turn an authenticator into another type, for example a note.
                                // Logins linked to it lose the link here, as they do when it is deleted.
                                if (previousType == EntryType.AUTHENTICATOR && entry.type != EntryType.AUTHENTICATOR)
                                    database.dao().clearAuthenticatorLinks(entry.id)
                                database.dao().saveEntryExact(resolved.entry, resolved.groupIds)
                            }
                        }
                    }
                    else if (group != null) database.dao().insertGroup(group)
                    else if (passkey != null) {
                        val saved = database.dao().allPasskeys().firstOrNull { it.id == passkey.id }
                        if (saved == null) database.dao().insertPasskeys(listOf(passkey))
                        else require(saved == passkey) { "Passkey credential ID conflict" }
                    }
                    else if (photo != null && (skipPhoto || database.dao().entry(photo.entryId) == null)) {
                        // Record the version only; the image never reaches this device's photos.
                        if (!skipPhoto && !entryDeletedHere(photo.entryId)) throw MissingPhotoDependencyException()
                    }
                    else if (photo != null) {
                        photoEntryId = photo.entryId
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
                        val selected = database.dao().photo(coverPhotoId)
                        if (selected == null) {
                            // A cover for a deleted entry or a deleted photo has nothing left to mark.
                            if (!entryDeletedHere(change.entityId) && syncDao.tombstone("photo", coverPhotoId) == null)
                                throw MissingPhotoDependencyException()
                        } else {
                            require(selected.entryId == change.entityId) { "Cover belongs to another entry" }
                            database.dao().setPhotoCover(selected.copy(isCover = true))
                            photoEntryId = change.entityId
                        }
                    }
                    else {
                        if (change.entityType == "entry") {
                            database.dao().entry(change.entityId)?.let {
                                retiredPhotos.addAll(it.photos)
                                database.dao().deleteEntryAndLinks(it.entry)
                            }
                            syncDao.deleteAttachmentManifestsForOwner("entry", change.entityId)
                        }
                        else if (change.entityType == "passkey") database.dao().deletePasskey(change.entityId)
                        else if (change.entityType == "photo") {
                            database.dao().photo(change.entityId)
                                ?.let { retiredPhotos += it; photoEntryId = it.entryId; database.dao().deletePhoto(it) }
                            syncDao.deleteAttachmentManifest(change.entityId)
                        }
                        else database.dao().allGroupsWithEntries().firstOrNull { it.group.id == change.entityId }
                            ?.let { database.dao().deleteGroup(it.group) }
                        syncDao.upsertTombstone(SyncTombstoneEntity(change.mutationId, change.entityType, change.entityId,
                            change.deviceId, change.sequence, change.recordVersion.toJson(), change.occurredAtUtc, change.hash))
                    }
                    syncDao.upsertRecordState(SyncRecordStateEntity(change.entityType, change.entityId,
                        change.baseRevision + 1L, change.recordVersion.toJson()))
                    syncDao.conflicts().filter { it.entityType == change.entityType && it.entityId == change.entityId &&
                        it.resolvedAtUtc.isEmpty() }.forEach { conflict ->
                        val dominates = ConflictVersions.settles(change.recordVersion,
                            RecordVersion.parse(conflict.localVersionJson), RecordVersion.parse(conflict.remoteVersionJson))
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
        if (announceRemoteChanges) when {
            change.entityType == "entry" && outcome == IncomingResult.APPLIED ->
                RemoteEntryChanges.record(change.entityId, deleted = change.kind == ChangeKind.DELETE)
            change.entityType == "entry" && outcome == IncomingResult.CONFLICT ->
                RemoteEntryChanges.record(change.entityId, deleted = false, conflict = true)
            outcome == IncomingResult.APPLIED && photoEntryId != null ->
                RemoteEntryChanges.record(requireNotNull(photoEntryId), deleted = false, photosOnly = true)
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
        val plaintext = decryptBytes(change, vaultKey)
        return try { JSONObject(plaintext.toString(Charsets.UTF_8)) }
        finally { plaintext.fill(0) }
    }

    companion object {
        internal fun decryptBytes(change: SyncChangeRecord, vaultKey: ByteArray): ByteArray {
            require(change.payloadCiphertext.length <= 1_500_000) { "Sync payload is too large" }
            val ciphertext = Base64.getUrlDecoder().decode(change.payloadCiphertext)
            val nonce = Base64.getUrlDecoder().decode(change.payloadNonce)
            require(nonce.size == 12 && ciphertext.size <= 1_048_592) { "Invalid sync payload size" }
            val payloadKey = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(vaultKey, "HmacSHA256"))
                doFinal("nuvori-sync-payload-key-v1".toByteArray())
            }
            return try {
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                    updateAAD("nuvori-sync-${change.entityType}-v1:${change.vaultId}:${change.mutationId}:${change.entityId}".toByteArray())
                    doFinal(ciphertext)
                }
            } finally { payloadKey.fill(0) }
        }
    }

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

    /** The entry is absent here and its delete tombstone is known, so it is not merely late. */
    private suspend fun entryDeletedHere(entryId: String): Boolean =
        database.dao().entry(entryId) == null && database.syncDao().tombstone("entry", entryId) != null

    private fun SyncOperationEntity.toChangeRecord() = SyncChangeRecord(formatVersion, vaultId, deviceId, sequence,
        previousHash, mutationId, entityType, entityId, if (kind == "upsert") ChangeKind.UPSERT else ChangeKind.DELETE,
        baseRevision, RecordVersion.parse(recordVersionJson), occurredAtUtc,
        payloadCiphertext, payloadNonce, deviceSignature, hash)
}
