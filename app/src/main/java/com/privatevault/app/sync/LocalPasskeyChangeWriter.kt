package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.passkeys.PasskeyCrypto
import org.json.JSONObject
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class LocalPasskeyChangeWriter(
    private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore,
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun save(passkey: VaultPasskey, vaultKey: ByteArray) = write(passkey, vaultKey, ChangeKind.UPSERT)
    suspend fun recordSaved(passkey: VaultPasskey, vaultKey: ByteArray) =
        write(passkey, vaultKey, ChangeKind.UPSERT, alreadySaved = true)
    suspend fun delete(passkey: VaultPasskey, vaultKey: ByteArray) = write(passkey, vaultKey, ChangeKind.DELETE)

    private suspend fun write(passkey: VaultPasskey, vaultKey: ByteArray, kind: ChangeKind,
        alreadySaved: Boolean = false) {
        require(vaultKey.size == 32) { "Invalid vault key" }
        if (kind == ChangeKind.UPSERT) PasskeyCrypto.validateStored(passkey)
        val identity = identityStore.getOrCreate()
        database.withTransaction {
            val vaultId = requireNotNull(database.dao().settings()?.vaultId?.takeIf(String::isNotBlank)) {
                "Vault ID is missing"
            }
            database.requireLocalSyncAuthor(vaultId, identity.deviceId)
            val dao = database.syncDao()
            val head = dao.deviceHead(vaultId, identity.deviceId)
            val state = dao.recordState("passkey", passkey.id)
            val previous = state?.let { RecordVersion.parse(it.recordVersionJson) }
                ?: RecordVersion(emptyMap())
            val version = RecordVersion(previous.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            })
            val mutationId = UUID.randomUUID().toString()
            val sequence = (head?.sequence ?: 0L) + 1L
            val payload = if (kind == ChangeKind.UPSERT) JSONObject().put("passkey", JSONObject()
                .put("id", passkey.id).put("rpId", passkey.rpId).put("userHandle", passkey.userHandle)
                .put("username", passkey.username).put("displayName", passkey.displayName)
                .put("privateKey", passkey.privateKey).put("publicKey", passkey.publicKey)
                .put("createdAt", passkey.createdAt)) else JSONObject().put("deleted", true)
            val contentKey = database.syncContentKey(vaultKey)
            val encrypted = try { encrypt(payload.toString().toByteArray(Charsets.UTF_8), contentKey,
                "nuvori-sync-passkey-v1:$vaultId:$mutationId:${passkey.id}") } finally { contentKey.fill(0) }
            val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, sequence,
                head?.hash ?: GENESIS_HASH, mutationId, "passkey", passkey.id, kind,
                state?.revision ?: 0L, version, Instant.now().toString(), encrypted.first,
                encrypted.second, identityStore::sign)
            if (kind == ChangeKind.UPSERT) {
                if (alreadySaved) require(database.dao().allPasskeys().any { it == passkey }) {
                    "Passkey changed during import"
                } else database.dao().insertPasskeys(listOf(passkey))
            }
            else {
                require(database.dao().allPasskeys().any { it.id == passkey.id }) { "Passkey is missing" }
                database.dao().deletePasskey(passkey.id)
            }
            dao.insertOperation(SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId,
                change.deviceId, change.sequence, change.previousHash, change.entityType, change.entityId,
                change.kind.name.lowercase(), change.baseRevision, change.recordVersion.toJson(),
                change.occurredAtUtc, change.payloadCiphertext, change.payloadNonce, change.deviceSignature, change.hash))
            if (kind == ChangeKind.DELETE) dao.upsertTombstone(SyncTombstoneEntity(mutationId, "passkey", passkey.id,
                identity.deviceId, sequence, version.toJson(), change.occurredAtUtc, change.hash))
            dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, sequence, change.hash))
            dao.upsertRecordState(SyncRecordStateEntity("passkey", passkey.id,
                change.baseRevision + 1L, version.toJson()))
        }
    }

    private fun encrypt(plaintext: ByteArray, vaultKey: ByteArray, aad: String): Pair<String, String> {
        val nonce = ByteArray(12).also(random::nextBytes)
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vaultKey, "HmacSHA256"))
            doFinal("nuvori-sync-payload-key-v1".toByteArray())
        }
        return try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad.toByteArray())
                doFinal(plaintext)
            }
            Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext) to
                Base64.getUrlEncoder().withoutPadding().encodeToString(nonce)
        } finally {
            plaintext.fill(0)
            payloadKey.fill(0)
        }
    }
}
