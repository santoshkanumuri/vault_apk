package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncAttachmentManifestEntity
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.EncryptedPhotoStore
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** [blobs] and [photos] are needed only to save a photo; deletions work without them. */
internal class LocalPhotoChangeWriter(private val database: VaultDatabase,
    private val identity: AndroidDeviceIdentityStore, private val blobs: PhotoSyncBlobs?,
    private val photos: EncryptedPhotoStore?) {

    suspend fun save(photo: VaultPhoto, vaultKey: ByteArray) = write(photo, vaultKey, ChangeKind.UPSERT)
    suspend fun delete(photo: VaultPhoto, vaultKey: ByteArray) = write(photo, vaultKey, ChangeKind.DELETE)
    /** Signs the delete of a photo whose row is already gone, for example with its entry. */
    suspend fun recordDeleted(photoId: String, entryId: String, vaultKey: ByteArray) =
        write(VaultPhoto(photoId, entryId, ""), vaultKey, ChangeKind.DELETE, alreadyDeleted = true)
    suspend fun setCover(photo: VaultPhoto, vaultKey: ByteArray) =
        write(photo.copy(isCover = true), vaultKey, ChangeKind.UPSERT, coverOnly = true)

    private suspend fun write(photo: VaultPhoto, vaultKey: ByteArray, kind: ChangeKind,
        coverOnly: Boolean = false, alreadyDeleted: Boolean = false) {
        // The blob is sealed before the transaction; new keys that arrive meanwhile seal it again (L4).
        repeat(3) { if (attempt(photo, vaultKey, kind, coverOnly, alreadyDeleted)) return }
        error("Sync keys changed while saving a photo")
    }

    /** False when the keys this device writes with changed after the blob was sealed: nothing was stored. */
    private suspend fun attempt(photo: VaultPhoto, vaultKey: ByteArray, kind: ChangeKind,
        coverOnly: Boolean, alreadyDeleted: Boolean): Boolean {
        require(vaultKey.size == 32)
        val device = identity.getOrCreate()
        val settings = requireNotNull(database.dao().settings())
        val vaultId = settings.vaultId
        val ref = if (kind == ChangeKind.UPSERT && !coverOnly) {
            val blobs = requireNotNull(blobs)
            val known = database.syncDao().attachment(photo.id)
            val keyring = database.syncKeyring(vaultKey)
            try {
                val writing = keyring.writing()
                if (known?.encryptedFileName == photo.encryptedFileName && known.keyEpoch == writing.epoch &&
                    blobs.has(PhotoBlobRef(known.ciphertextHash, known.sizeBytes)))
                    PhotoBlobRef(known.ciphertextHash, known.sizeBytes, writing.epoch)
                else blobs.create(requireNotNull(photos), photo.encryptedFileName, vaultKey, writing.key, vaultId, photo.id)
                    .copy(keyEpoch = writing.epoch)
            } finally { keyring.wipe() }
        } else null
        return database.withTransaction {
            if (ref != null) {
                val keyring = database.syncKeyring(vaultKey)
                val epoch = try { keyring.writing().epoch } finally { keyring.wipe() }
                if (epoch != ref.keyEpoch) return@withTransaction false
            }
            database.requireLocalSyncAuthor(vaultId, device.deviceId)
            val dao = database.syncDao()
            val existing = database.dao().photo(photo.id)
            if (kind == ChangeKind.DELETE) require(if (alreadyDeleted) existing == null else existing == photo) {
                "Photo changed before deletion"
            }
            else if (coverOnly) require(existing != null && existing.entryId == photo.entryId) { "Photo is missing" }
            else require(existing == null || existing.entryId == photo.entryId) { "Photo owner changed" }
            val savedPhoto = photo.copy(isCover = existing?.isCover ?: photo.isCover)
            val head = dao.deviceHead(vaultId, device.deviceId)
            val entityType = if (coverOnly) "photo_cover" else "photo"
            val entityId = if (coverOnly) photo.entryId else photo.id
            val state = dao.recordState(entityType, entityId)
            val counters = (state?.let { RecordVersion.parse(it.recordVersionJson).counters }
                ?: emptyMap()).toMutableMap()
            counters[device.deviceId] = Math.addExact(counters[device.deviceId] ?: 0L, 1L)
            val version = RecordVersion(counters)
            val mutation = UUID.randomUUID().toString()
            val plain = JSONObject().apply {
                if (kind == ChangeKind.DELETE) put("deleted", true)
                else if (coverOnly) put("coverPhotoId", photo.id)
                else put("photo", JSONObject().put("id", savedPhoto.id).put("entryId", savedPhoto.entryId)
                    .put("isCover", savedPhoto.isCover).put("addedAt", savedPhoto.addedAt)
                    .put("blobHash", ref!!.hash).put("blobSize", ref.size)
                    // A blob of epoch N >= 2 is sealed with K(N) ([EpochKeys]); epoch 1 photos say nothing, as before.
                    .apply { if (ref.keyEpoch > 1L) put("keyEpoch", ref.keyEpoch) })
            }.toString().toByteArray(Charsets.UTF_8)
            val sealed = try { SyncPayloadCrypto.sealFor(database, vaultKey, entityType, vaultId, mutation, entityId, plain) }
                finally { plain.fill(0) }
            val change = SyncChangeRecord.createSigned(vaultId, device.deviceId,
                Math.addExact(head?.sequence ?: 0L, 1L), head?.hash ?: GENESIS_HASH,
                mutation, entityType, entityId, kind, state?.revision ?: 0L, version,
                Instant.now().toString(), sealed.ciphertext, sealed.nonce, identity::sign)
            if (kind == ChangeKind.DELETE) {
                if (!alreadyDeleted) database.dao().deletePhoto(photo)
                dao.deleteAttachmentManifest(photo.id)
                dao.upsertTombstone(SyncTombstoneEntity(mutation, "photo", photo.id,
                    device.deviceId, change.sequence, version.toJson(), change.occurredAtUtc, change.hash))
            } else if (coverOnly) database.dao().setPhotoCover(requireNotNull(existing).copy(isCover = true))
            else {
                database.dao().insertPhoto(savedPhoto)
                if (existing == null && savedPhoto.isCover) database.dao().setPhotoCover(savedPhoto)
                dao.upsertAttachmentManifest(SyncAttachmentManifestEntity(photo.id, "entry", photo.entryId,
                    photo.encryptedFileName, ref!!.hash, ref.size, ref.keyEpoch))
            }
            dao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId,
                change.deviceId, change.sequence, change.previousHash, change.entityType, change.entityId,
                change.kind.name.lowercase(), change.baseRevision, change.recordVersion.toJson(),
                change.occurredAtUtc, change.payloadCiphertext, change.payloadNonce, change.deviceSignature, change.hash))
            dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, device.deviceId, change.sequence, change.hash))
            dao.upsertRecordState(SyncRecordStateEntity(entityType, entityId,
                change.baseRevision + 1L, version.toJson()))
            true
        }
    }
}
