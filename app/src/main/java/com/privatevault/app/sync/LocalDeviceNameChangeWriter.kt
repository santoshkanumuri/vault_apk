package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.VaultDatabase
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class LocalDeviceNameChangeWriter(private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore) {
    suspend fun save(name: String, vaultKey: ByteArray) {
        require(name.isNotBlank() && name.length <= 40 && name.none { it.isISOControl() }) {
            "Enter a device name of up to 40 characters"
        }
        require(vaultKey.size == 32)
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val vaultId = requireNotNull(database.dao().settings()).vaultId
            database.requireLocalSyncAuthor(vaultId, identity.deviceId)
            val dao = database.syncDao()
            val member = requireNotNull(dao.membership(vaultId, identity.deviceId))
            require(member.status == MemberStatus.ACTIVE.name)
            if (member.displayName == name) return@withTransaction
            val head = dao.deviceHead(vaultId, identity.deviceId)
            val state = dao.recordState("device_name", identity.deviceId)
            val current = state?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val version = RecordVersion(current.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val payload = JSONObject().put("name", name).toString().toByteArray(Charsets.UTF_8)
            val sealed = try {
                SyncPayloadCrypto.sealFor(database, vaultKey, "device_name", vaultId, mutationId, identity.deviceId, payload)
            } finally { payload.fill(0) }
            val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, sequence,
                head?.hash ?: GENESIS_HASH, mutationId, "device_name", identity.deviceId,
                ChangeKind.UPSERT, state?.revision ?: 0L, version, Instant.now().toString(),
                sealed.ciphertext, sealed.nonce, identityStore::sign)
            dao.upsertMembership(member.copy(displayName = name))
            dao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion,
                change.vaultId, change.deviceId, change.sequence, change.previousHash,
                change.entityType, change.entityId, change.kind.name.lowercase(), change.baseRevision,
                change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext,
                change.payloadNonce, change.deviceSignature, change.hash))
            dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, sequence, change.hash))
            dao.upsertRecordState(SyncRecordStateEntity("device_name", identity.deviceId,
                change.baseRevision + 1L, version.toJson()))
        }
    }
}
