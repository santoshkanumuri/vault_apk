package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultGroup
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LocalGroupChangeWriter(
    private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun save(group: VaultGroup, vaultKey: ByteArray) = write(group, vaultKey, ChangeKind.UPSERT)
    suspend fun delete(group: VaultGroup, vaultKey: ByteArray) = write(group, vaultKey, ChangeKind.DELETE)

    private suspend fun write(group: VaultGroup, vaultKey: ByteArray, kind: ChangeKind) {
        require(vaultKey.size == 32 && group.name.isNotBlank()) { "Invalid group change" }
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val settings = requireNotNull(database.dao().settings()) { "Vault settings are missing" }
            require(settings.vaultId.isNotBlank()) { "Vault ID is missing" }
            database.requireLocalSyncAuthor(settings.vaultId, identity.deviceId)
            val syncDao = database.syncDao()
            val head = syncDao.deviceHead(settings.vaultId, identity.deviceId)
            val state = syncDao.recordState("group", group.id)
            val currentVersion = state?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val recordVersion = RecordVersion(currentVersion.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val payload = if (kind == ChangeKind.UPSERT) JSONObject().put("group", JSONObject()
                .put("id", group.id).put("name", group.name).put("notes", group.notes)
                .put("folderType", group.folderType?.name ?: "")) else JSONObject().put("deleted", true)
            val contentKey = database.syncContentKey(vaultKey)
            val encrypted = try { encrypt(payload.toString().toByteArray(Charsets.UTF_8), contentKey,
                settings.vaultId, mutationId, group.id) } finally { contentKey.fill(0) }
            val change = SyncChangeRecord.createSigned(settings.vaultId, identity.deviceId, sequence,
                head?.hash ?: GENESIS_HASH, mutationId, "group", group.id, kind, state?.revision ?: 0L,
                recordVersion, Instant.now(clock).toString(), encrypted.first, encrypted.second, identityStore::sign)
            if (kind == ChangeKind.UPSERT) database.dao().insertGroup(group)
            else database.dao().deleteGroup(group)
            syncDao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId,
                change.deviceId, change.sequence, change.previousHash, change.entityType, change.entityId,
                change.kind.name.lowercase(), change.baseRevision, change.recordVersion.toJson(),
                change.occurredAtUtc, change.payloadCiphertext, change.payloadNonce, change.deviceSignature, change.hash))
            if (kind == ChangeKind.DELETE) syncDao.upsertTombstone(SyncTombstoneEntity(mutationId, "group", group.id,
                identity.deviceId, sequence, recordVersion.toJson(), change.occurredAtUtc, change.hash))
            syncDao.upsertDeviceHead(SyncDeviceHeadEntity(settings.vaultId, identity.deviceId, sequence, change.hash))
            syncDao.upsertRecordState(SyncRecordStateEntity("group", group.id, change.baseRevision + 1L,
                recordVersion.toJson()))
        }
    }

    private fun encrypt(plaintext: ByteArray, vaultKey: ByteArray, vaultId: String, mutationId: String,
        groupId: String): Pair<String, String> {
        val nonce = ByteArray(12).also(random::nextBytes)
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vaultKey, "HmacSHA256"))
            doFinal("nuvori-sync-payload-key-v1".toByteArray())
        }
        return try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("nuvori-sync-group-v1:$vaultId:$mutationId:$groupId".toByteArray())
                doFinal(plaintext)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext) to
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        } finally {
            payloadKey.fill(0)
            plaintext.fill(0)
        }
    }
}
