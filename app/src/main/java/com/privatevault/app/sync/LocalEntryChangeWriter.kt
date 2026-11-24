package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** The entry JSON shared with Windows: sync payloads, and this phone's Recently deleted copies. */
internal fun VaultEntry.toSyncJson(): JSONObject = JSONObject()
    .put("id", id).put("type", type.name).put("title", title)
    .put("primaryValue", primaryValue).put("secondaryValue", secondaryValue)
    .put("tertiaryValue", tertiaryValue).put("fourthValue", fourthValue)
    .put("cardKind", cardKind.name).put("network", network)
    .put("totpAlgorithm", totpAlgorithm).put("totpDigits", totpDigits).put("totpPeriod", totpPeriod)
    .put("linkedApps", linkedApps).put("autofillSignatures", autofillSignatures)
    .put("autofillOrigins", autofillOrigins)
    .put("linkedAuthenticatorId", linkedAuthenticatorId).put("notes", notes)
    .put("color", color).put("tags", tags).put("favorite", favorite)
    .put("lastOpenedAt", lastOpenedAt).put("sortOrder", sortOrder)
    .put("createdAt", createdAt).put("updatedAt", updatedAt)

class LocalEntryChangeWriter(
    private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun save(entry: VaultEntry, groupIds: Set<String>, vaultKey: ByteArray) =
        write(entry, groupIds, vaultKey, ChangeKind.UPSERT)

    suspend fun delete(entry: VaultEntry, vaultKey: ByteArray) =
        write(entry, emptySet(), vaultKey, ChangeKind.DELETE)

    suspend fun recordDeletedEntry(entry: VaultEntry, vaultKey: ByteArray) =
        write(entry, emptySet(), vaultKey, ChangeKind.DELETE, alreadyDeleted = true)

    private suspend fun write(entry: VaultEntry, groupIds: Set<String>, vaultKey: ByteArray, kind: ChangeKind,
        alreadyDeleted: Boolean = false) {
        require(vaultKey.size == 32) { "Vault key must be 32 bytes" }
        val identity = identityStore.getOrCreate()
        val savedEntry = entry.copy(updatedAt = clock.millis())

        database.withTransaction {
            val settings = database.dao().settings() ?: error("Vault settings are missing")
            require(settings.vaultId.isNotBlank()) { "Vault ID is missing" }
            database.requireLocalSyncAuthor(settings.vaultId, identity.deviceId)
            if (kind == ChangeKind.DELETE) deletePhotosOf(entry.id, alreadyDeleted, vaultKey)
            val syncDao = database.syncDao()
            val head = syncDao.deviceHead(settings.vaultId, identity.deviceId)
            val state = syncDao.recordState(ENTITY_TYPE, savedEntry.id)
            val currentVersion = state?.let {
                RecordVersion.parse(it.recordVersionJson)
            } ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val counters = currentVersion.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            }
            val recordVersion = RecordVersion(counters)
            val mutationId = UUID.randomUUID().toString()
            if (kind == ChangeKind.DELETE) {
                val present = database.dao().entry(entry.id) != null
                require(present != alreadyDeleted) { "Entry deletion state changed" }
            }
            val plaintext = JSONObject().apply {
                if (kind == ChangeKind.UPSERT) {
                    put("entry", savedEntry.toSyncJson())
                    put("groupIds", JSONArray(groupIds.sorted()))
                } else put("deleted", true)
            }.toString().toByteArray(Charsets.UTF_8)
            val encrypted = try {
                SyncPayloadCrypto.sealFor(database, vaultKey, ENTITY_TYPE, settings.vaultId, mutationId, savedEntry.id, plaintext)
                    .let { EncryptedPayload(it.ciphertext, it.nonce) }
            } finally { plaintext.fill(0) }
            val change = SyncChangeRecord.createSigned(
                vaultId = settings.vaultId,
                deviceId = identity.deviceId,
                sequence = sequence,
                previousHash = head?.hash ?: GENESIS_HASH,
                mutationId = mutationId,
                entityType = ENTITY_TYPE,
                entityId = savedEntry.id,
                kind = kind,
                baseRevision = state?.revision ?: 0L,
                recordVersion = recordVersion,
                occurredAtUtc = Instant.now(clock).toString(),
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                signer = identityStore::sign,
            )

            if (kind == ChangeKind.UPSERT) database.dao().saveEntryExact(savedEntry, groupIds)
            else if (!alreadyDeleted) database.dao().deleteEntryAndLinks(entry)
            syncDao.insertOperation(change.toEntity())
            if (kind == ChangeKind.DELETE) syncDao.upsertTombstone(SyncTombstoneEntity(
                mutationId, ENTITY_TYPE, entry.id, identity.deviceId, sequence,
                recordVersion.toJson(), change.occurredAtUtc, change.hash,
            ))
            syncDao.upsertDeviceHead(
                SyncDeviceHeadEntity(change.vaultId, change.deviceId, change.sequence, change.hash),
            )
            syncDao.upsertRecordState(
                SyncRecordStateEntity(
                    ENTITY_TYPE,
                    savedEntry.id,
                    change.baseRevision + 1L,
                    recordVersion.toJson(),
                ),
            )
        }
    }

    /**
     * Signs a delete for each photo of a deleted entry before the entry's own delete, so every device
     * drops the photos and their transfer data, and a late photo change resolves against a tombstone.
     */
    private suspend fun deletePhotosOf(entryId: String, alreadyDeleted: Boolean, vaultKey: ByteArray) {
        val photoWriter = LocalPhotoChangeWriter(database, identityStore, null, null)
        if (!alreadyDeleted) database.dao().entry(entryId)?.photos.orEmpty()
            .sortedBy { it.id }.forEach { photoWriter.delete(it, vaultKey) }
        else database.syncDao().attachmentsForOwner("entry", entryId).sortedBy { it.attachmentId }.forEach {
            if (database.dao().photo(it.attachmentId) == null) photoWriter.recordDeleted(it.attachmentId, entryId, vaultKey)
        }
        database.syncDao().deleteAttachmentManifestsForOwner("entry", entryId)
    }

    private fun SyncChangeRecord.toEntity() = SyncOperationEntity(
        mutationId, formatVersion, vaultId, deviceId, sequence, previousHash,
        entityType, entityId, kind.name.lowercase(), baseRevision, recordVersion.toJson(),
        occurredAtUtc, payloadCiphertext, payloadNonce, deviceSignature, hash,
    )

    private data class EncryptedPayload(val ciphertext: String, val nonce: String)

    private companion object {
        const val ENTITY_TYPE = "entry"
    }
}
