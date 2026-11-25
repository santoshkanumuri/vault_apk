package com.privatevault.app.sync

import android.content.Context
import androidx.room.withTransaction
import com.privatevault.app.appearance.AppearanceStore
import com.privatevault.app.data.SyncedSiteIcon
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.logPasskeyEvents
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

/**
 * [DEFERRED]: M4, a kind of change this app version doesn't know. It joined its author's chain (so the
 * changes after it apply, it counts as applied and it is relayed) and applies after an app update
 * ([LockedSyncStore.applyDeferred]).
 */
enum class IncomingResult { APPLIED, REPLAY, CONFLICT, OBSOLETE, DEFERRED }
internal class MissingPhotoDependencyException : Exception("Cover photo is still waiting for its image")
internal class MissingRecordDependencyException : Exception("A linked item is still waiting to sync")
/** A stored change of a kind this app version doesn't know: it waits for an app update ([SUPPORTED_ENTITY_TYPES]). */
internal class UnsupportedSyncEntityException(val entityType: String) : Exception("Update this app to apply a newer kind of change")

/**
 * The kinds of change this app version applies. Anything else is stored without applying (M4,
 * [IncomingResult.DEFERRED]). A build writes a new kind only when every other active member announced
 * the feature that reads it ([SyncFeatures]), as appearance, site icons and sign-in methods do. The
 * version that adds a kind must also apply held changes of that kind after a pairing snapshot: an
 * older host stored them without applying them.
 */
internal val SUPPORTED_ENTITY_TYPES = setOf("entry", "group", "passkey", "photo", "photo_cover", "device_name",
    AppearanceSyncRules.ENTITY_TYPE, SiteIconSyncRules.ENTITY_TYPE, SignInMethodSyncRules.ENTITY_TYPE)

internal class IncomingEntryChangeApplier(private val database: VaultDatabase,
    private val photoBlobs: PhotoSyncBlobs? = null, private val context: Context? = null,
    private val announceRemoteChanges: Boolean = true) {
    /**
     * Applies one verified operation. [skipPhotoBlob] records a photo upsert without its image; the
     * caller sets it only when a later operation from the same author removes that image anyway.
     */
    /**
     * M13: the image hash a queued photo change will need, read without applying it, so every missing
     * image can be asked for at once. Null for other changes, or when its key hasn't arrived.
     */
    suspend fun photoBlobHash(operation: SyncOperationEntity, vaultKey: ByteArray): String? {
        if (operation.entityType != "photo" || operation.kind != "upsert") return null
        require(vaultKey.size == 32) { "Invalid vault key" }
        val change = operation.toChangeRecord()
        change.validate()
        val keyring = database.syncKeyring(vaultKey)
        val payload = try { decrypt(change, keyring) } finally { keyring.wipe() }
        return SyncEntryCodec.parsePhoto(payload.getJSONObject("photo").toString(), change.entityId).ref.hash
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    }

    /**
     * [stored]: M4, a change already in its author's chain (deferred by an earlier app version, or
     * held in a pairing snapshot) applies now, after an app update. Its chain place is not checked
     * again or stored twice; applied a second time, it is outdated against what it already set.
     */
    suspend fun apply(operation: SyncOperationEntity, vaultKey: ByteArray,
        skipPhotoBlob: Boolean = false, stored: Boolean = false): IncomingResult {
        require(vaultKey.size == 32) { "Invalid vault key" }
        val member = requireNotNull(database.syncDao().membership(operation.vaultId, operation.deviceId)) {
            "Device is not a vault member"
        }.toMembership()
        member.validate()
        // A removed device's changes up to its cutoff still apply (see RemovalCutoff).
        val withinCutoff = member.status != MemberStatus.ACTIVE && RemovalCutoff.allows(
            RemovalCutoff.cutoffsOf(database.syncDao().membershipEvents(operation.vaultId))[operation.deviceId],
            operation.sequence, operation.hash)
        require((member.status == MemberStatus.ACTIVE || withinCutoff) && member.vaultId == operation.vaultId &&
            member.deviceId == operation.deviceId && member.keyEpoch >= 1L) {
            "Device is not an active vault member"
        }
        // "Mark as lost" on this device: nothing after the lost device's last known change.
        require(LostManagerFreeze.allowsChange(LostManagerFreeze.of(database.syncDao().lostManager(operation.vaultId)),
            operation.deviceId, operation.sequence, operation.hash)) { "That device was marked as lost" }
        val change = operation.toChangeRecord()
        change.validate()
        val signature = Base64.getUrlDecoder().decode(change.deviceSignature)
        require(DeviceIdentityCrypto.verify(Base64.getUrlDecoder().decode(member.identityPublicKey), change.signingBytes(), signature)) {
            "Invalid device signature"
        }
        // M4: a kind of change a newer app version made. It joins its author's chain unapplied, so the
        // author's later changes still apply here, it counts as applied and it is relayed; it applies
        // after an app update. The rule is shared with Windows. Setting it aside would drop it.
        if (operation.entityType !in SUPPORTED_ENTITY_TYPES) {
            if (stored) throw UnsupportedSyncEntityException(operation.entityType)
            return defer(operation, member, withinCutoff)
        }
        // Entity-ID HMACs use the epoch-1 key; the payload uses the key of its own epoch ([SyncKeyring]).
        val contentKey = database.syncContentKey(vaultKey)
        val keyring = database.syncKeyring(vaultKey)
        // A site icon's entity id is an HMAC of its site under the content key: check it while the key is here.
        var siteIcon: SiteIconSyncRules.Icon? = null
        // A sign-in method's id is an HMAC of its site, provider and account: checked here too. A delete carries no
        // method, so the row it removes is found by its id (recomputed when the row came from another group's key).
        var signIn: SignInMethodSyncRules.Method? = null
        var signInRow: SignInMethodKey? = null
        val payload = try {
            decrypt(change, keyring).also {
                if (change.entityType == SiteIconSyncRules.ENTITY_TYPE) {
                    require(change.kind == ChangeKind.UPSERT) { "Invalid site icon change" }
                    siteIcon = SiteIconSyncRules.parsePayload(it.toString(), change.entityId, contentKey)
                }
                if (change.entityType == SignInMethodSyncRules.ENTITY_TYPE) {
                    if (change.kind == ChangeKind.UPSERT) signIn = SignInMethodSyncRules.parsePayload(it.toString(), change.entityId, contentKey)
                    else {
                        require(SignInMethodSyncRules.isDelete(it.toString())) { "Invalid delete payload" }
                        signInRow = SignInMethodSync.rowFor(database, contentKey, change.entityId)
                    }
                }
            }
        } finally { contentKey.fill(0); keyring.wipe() }
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
        // One record per vault, never deleted. An invalid id makes the whole change invalid.
        val appearance = if (change.entityType == AppearanceSyncRules.ENTITY_TYPE) {
            require(change.kind == ChangeKind.UPSERT && change.entityId == AppearanceSyncRules.ENTITY_ID) {
                "Invalid appearance change"
            }
            AppearanceSyncRules.parsePayload(payload.toString())
        } else null
        // The stored appearance lives in the app's preferences. Every unlocked path (LockedSyncStore)
        // passes a context; without one the change waits instead of being set aside.
        val appearanceContext = if (appearance != null) checkNotNull(context) { "Appearance needs the app context" } else null
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
            require(currentMember == member && (currentMember.status == MemberStatus.ACTIVE || withinCutoff)) {
                "Device membership changed"
            }
            if (stored) require(syncDao.operation(change.mutationId)?.hash == change.hash) { "Deferred change is not stored" }
            else {
                syncDao.operation(change.mutationId)?.let { existing ->
                    require(existing.hash == change.hash) { "Mutation ID was reused" }
                    return@withTransaction IncomingResult.REPLAY
                }
                val head = syncDao.deviceHead(change.vaultId, change.deviceId)
                require(change.sequence == (head?.sequence ?: 0L) + 1L && change.previousHash == (head?.hash ?: GENESIS_HASH)) {
                    "Broken device change chain"
                }
            }
            val state = syncDao.recordState(change.entityType, change.entityId)
            val localVersion = state?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            val relation = ConflictVersions.incomingRelation(change.recordVersion, state?.let { localVersion })
            val result = if (appearance != null) {
                // Never a conflict to review: last writer wins (AppearanceSyncRules.decide). The stored
                // value is read and set inside this transaction, so a local write cannot interleave.
                // Applying never marks the value pending and never writes a change: no echo.
                val appContext = requireNotNull(appearanceContext)
                val stored = AppearanceStore.read(appContext)
                val localId = if (stored.pendingSync && stored.updatedByDevice == null)
                    AndroidDeviceIdentityStore(appContext).getOrCreate().deviceId else null
                val outcome = AppearanceSyncRules.decide(relation, stored, localId, change.occurredAtUtc, change.deviceId)
                if (outcome != AppearanceOutcome.OBSOLETE) syncDao.upsertRecordState(SyncRecordStateEntity(
                    change.entityType, change.entityId, maxOf(state?.revision ?: 0L, change.baseRevision) + 1L,
                    AppearanceSyncRules.merge(localVersion, change.recordVersion).toJson()))
                if (outcome == AppearanceOutcome.APPLY) {
                    AppearanceStore.applyRemote(appContext, appearance, change.occurredAtUtc, change.deviceId)
                    IncomingResult.APPLIED
                } else IncomingResult.OBSOLETE
            } else if (change.entityType == SignInMethodSyncRules.ENTITY_TYPE) {
                // Never a conflict to review (SignInMethodSyncRules.decide). Stored whatever the device's settings:
                // each method was confirmed by the user on some device.
                SignInMethodSync.apply(database, change, signIn, signInRow, state, localVersion, relation)
            } else if (siteIcon != null) {
                // Never a conflict to review: last writer wins (SiteIconSyncRules.decide). This phone never writes icons.
                val icon = requireNotNull(siteIcon)
                val icons = database.syncedSiteIcons()
                val outcome = SiteIconSyncRules.decide(relation, icons.icon(icon.site), change.occurredAtUtc, change.deviceId)
                if (outcome != AppearanceOutcome.OBSOLETE) syncDao.upsertRecordState(SyncRecordStateEntity(
                    change.entityType, change.entityId, maxOf(state?.revision ?: 0L, change.baseRevision) + 1L,
                    AppearanceSyncRules.merge(localVersion, change.recordVersion).toJson()))
                if (outcome == AppearanceOutcome.APPLY) {
                    icons.save(SyncedSiteIcon(icon.site, icon.png, change.occurredAtUtc,
                        SiteIconSyncRules.millis(change.occurredAtUtc), change.deviceId))
                    icons.trim()
                    IncomingResult.APPLIED
                } else IncomingResult.OBSOLETE
            } else when (relation) {
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
                        if (saved == null) {
                            database.dao().insertPasskeys(listOf(passkey))
                            database.logPasskeyEvents(listOf(passkey), PasskeyEventKind.SYNCED_IN,
                                detail = syncDao.membership(change.vaultId, change.deviceId)?.displayName.orEmpty())
                        }
                        else require(saved == passkey) { "Passkey credential ID conflict" }
                    }
                    else if (photo != null && (skipPhoto || database.dao().entry(photo.entryId) == null)) {
                        // Record the version only; the image never reaches this device's photos.
                        if (!skipPhoto && !entryDeletedHere(photo.entryId)) throw MissingPhotoDependencyException()
                    }
                    else if (photo != null) {
                        photoEntryId = photo.entryId
                        val store = EncryptedPhotoStore(requireNotNull(context))
                        // The blob is sealed with the content key of the epoch the photo names.
                        val keyring = database.syncKeyring(vaultKey)
                        val names = try { requireNotNull(photoBlobs).materialize(photo.ref, store, vaultKey,
                            keyring.key(photo.ref.keyEpoch) ?: throw MissingEpochKeyException(photo.ref.keyEpoch),
                            change.vaultId, photo.id) } finally { keyring.wipe() }
                        val existingPhoto = database.dao().photo(photo.id)
                        if (existingPhoto != null) retiredPhotos += existingPhoto
                        val saved = VaultPhoto(photo.id, photo.entryId, names.first, names.second,
                            existingPhoto?.isCover ?: photo.isCover, photo.addedAt)
                        database.dao().insertPhoto(saved)
                        if (saved.isCover && existingPhoto == null) database.dao().setPhotoCover(saved)
                        syncDao.upsertAttachmentManifest(SyncAttachmentManifestEntity(photo.id, "entry",
                            photo.entryId, names.first, photo.ref.hash, photo.ref.size, photo.ref.keyEpoch))
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
                                database.dao().deleteEntryAndLinks(it.entry, elsewhere = true)
                            }
                            syncDao.deleteAttachmentManifestsForOwner("entry", change.entityId)
                        }
                        else if (change.entityType == "passkey") {
                            database.dao().allPasskeys().firstOrNull { it.id == change.entityId }?.let { gone ->
                                database.logPasskeyEvents(listOf(gone), PasskeyEventKind.DELETED_ON_OTHER_DEVICE,
                                    detail = syncDao.membership(change.vaultId, change.deviceId)?.displayName.orEmpty())
                            }
                            database.dao().deletePasskey(change.entityId)
                            database.passkeyHistory().deleteOrigin(change.entityId)
                        }
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
                // M4: a stored change tried again after an app update (applyDeferred retries until
                // nothing waits) makes its conflict once.
                VersionRelation.CONCURRENT -> if (stored && syncDao.hasConflictWith(change.hash)) IncomingResult.OBSOLETE else {
                    syncDao.insertConflict(SyncConflictEntity(UUID.randomUUID().toString(), change.entityType, change.entityId,
                        syncDao.operationsForEntity(change.entityType, change.entityId).firstOrNull {
                            RecordVersion.parse(it.recordVersionJson).relationTo(localVersion) == VersionRelation.EQUAL
                        }?.hash ?: GENESIS_HASH,
                        change.hash, localVersion.toJson(), change.recordVersion.toJson(), Instant.now().toString()))
                    IncomingResult.CONFLICT
                }
                else -> IncomingResult.OBSOLETE
            }
            if (!stored) {
                syncDao.insertOperation(operation)
                syncDao.upsertDeviceHead(SyncDeviceHeadEntity(change.vaultId, change.deviceId, change.sequence, change.hash))
            }
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
        if (outcome == IncomingResult.APPLIED) siteIcon?.let { SyncedSiteIconCache.put(it.site, it.png) }
        if (outcome == IncomingResult.APPLIED && retiredPhotos.isNotEmpty() && context != null) {
            val photos = EncryptedPhotoStore(context)
            retiredPhotos.forEach {
                photos.delete(it.encryptedFileName)
                if (it.encryptedThumbnailFileName.isNotBlank()) photos.delete(it.encryptedThumbnailFileName)
            }
        }
        return outcome
    }

    /**
     * M4: stores a verified change of an unknown kind in its author's chain without reading its
     * payload: the same membership, replay and chain checks as any change, so the chain stays intact.
     */
    private suspend fun defer(operation: SyncOperationEntity, member: DeviceMembership, withinCutoff: Boolean): IncomingResult =
        database.withTransaction {
            val settings = requireNotNull(database.dao().settings()) { "Vault settings are missing" }
            require(settings.vaultId == operation.vaultId) { "Wrong vault" }
            val syncDao = database.syncDao()
            val currentMember = requireNotNull(syncDao.membership(operation.vaultId, operation.deviceId)) {
                "Device is not a vault member"
            }.toMembership()
            require(currentMember == member && (currentMember.status == MemberStatus.ACTIVE || withinCutoff)) {
                "Device membership changed"
            }
            syncDao.operation(operation.mutationId)?.let { existing ->
                require(existing.hash == operation.hash) { "Mutation ID was reused" }
                return@withTransaction IncomingResult.REPLAY
            }
            val head = syncDao.deviceHead(operation.vaultId, operation.deviceId)
            require(operation.sequence == (head?.sequence ?: 0L) + 1L && operation.previousHash == (head?.hash ?: GENESIS_HASH)) {
                "Broken device change chain"
            }
            syncDao.insertOperation(operation)
            syncDao.upsertDeviceHead(SyncDeviceHeadEntity(operation.vaultId, operation.deviceId, operation.sequence, operation.hash))
            IncomingResult.DEFERRED
        }

    internal fun decrypt(change: SyncChangeRecord, keyring: SyncKeyring): JSONObject {
        val plaintext = SyncPayloadCrypto.decrypt(change, keyring)
        return try { JSONObject(plaintext.toString(Charsets.UTF_8)) }
        finally { plaintext.fill(0) }
    }

    companion object {
        /** With the epoch-1 content key only: a change of a later epoch throws [MissingEpochKeyException]. */
        internal fun decryptBytes(change: SyncChangeRecord, vaultKey: ByteArray): ByteArray =
            SyncPayloadCrypto.decrypt(change, SyncKeyring(mapOf(1L to vaultKey.copyOf())))

        internal fun decryptBytes(change: SyncChangeRecord, keyring: SyncKeyring): ByteArray =
            SyncPayloadCrypto.decrypt(change, keyring)
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
