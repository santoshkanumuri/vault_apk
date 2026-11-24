package com.privatevault.app.sync

import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
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

/** A content key and its epoch ([EpochKeys]). */
internal class EpochKey(val epoch: Long, val key: ByteArray)

/** The change needs the content key of an epoch this device does not hold yet: it waits, it is not set aside. */
internal class MissingEpochKeyException(val epoch: Long) : Exception("Waiting for new sync keys")

/**
 * The content keys this device holds, by epoch: epoch 1 is the group's content key, later ones came
 * in key packages ([EpochKeys]). Writers use the newest. Wipe it after use.
 */
internal class SyncKeyring(keys: Map<Long, ByteArray>) {
    private val keys = keys.toSortedMap()

    fun key(epoch: Long): ByteArray? = keys[epoch]
    fun epochs(): Set<Long> = keys.keys
    /** The newest key: what this device writes with. */
    fun writing(): EpochKey = keys.lastKey().let { EpochKey(it, keys.getValue(it)) }
    fun writeEpoch(epoch: Long): EpochKey = EpochKey(epoch, requireNotNull(keys[epoch]))
    fun wipe() = keys.values.forEach { it.fill(0) }
}

/**
 * The payload encryption every local change writer uses: AES-256-GCM under
 * HMAC-SHA256(content key, `nuvori-sync-payload-key-v1`), AAD
 * `nuvori-sync-<type>-v1:<vaultId>:<mutationId>:<entityId>`, a random 12-byte nonce. A content key of
 * epoch N >= 2 ([EpochKeys]) marks the nonce field `e<N>.` and uses AAD v2.
 */
internal object SyncPayloadCrypto {
    data class Encrypted(val ciphertext: String, val nonce: String)

    /**
     * Encrypts with [key]: epoch 1 as before; epoch N >= 2 with nonce field `e<N>.<nonce>` and AAD v2
     * (`nuvori-sync-<type>-v2:<vaultId>:<N>:<mutationId>:<entityId>`).
     */
    fun encrypt(key: EpochKey, entityType: String, vaultId: String, mutationId: String, entityId: String,
        plaintext: ByteArray, random: SecureRandom = SecureRandom()): Encrypted {
        require(key.key.size == 32)
        if (key.epoch == 1L) return encrypt(key.key, entityType, vaultId, mutationId, entityId, plaintext, random)
        val nonce = ByteArray(12).also(random::nextBytes)
        val payloadKey = payloadKey(key.key)
        return try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(EpochKeys.aad(entityType, vaultId, key.epoch, mutationId, entityId))
                doFinal(plaintext)
            }
            Encrypted(Base64.getUrlEncoder().withoutPadding().encodeToString(ciphertext), EpochKeys.nonceField(key.epoch, nonce))
        } finally { payloadKey.fill(0) }
    }

    /** Encrypts with this device's newest content key, read from the vault (the writers' one path). */
    suspend fun sealFor(database: VaultDatabase, vaultKey: ByteArray, entityType: String, vaultId: String,
        mutationId: String, entityId: String, plaintext: ByteArray): Encrypted {
        val keyring = database.syncKeyring(vaultKey)
        return try { encrypt(keyring.writing(), entityType, vaultId, mutationId, entityId, plaintext) }
        finally { keyring.wipe() }
    }

    /** The payload of [change] with the key of its epoch; [MissingEpochKeyException] when it is not held. */
    fun decrypt(change: SyncChangeRecord, keyring: SyncKeyring): ByteArray {
        require(change.payloadCiphertext.length <= 1_500_000) { "Sync payload is too large" }
        val epoch = requireNotNull(EpochKeys.epochOf(change.payloadNonce)) { "Invalid sync payload nonce" }
        val key = keyring.key(epoch) ?: throw MissingEpochKeyException(epoch)
        val ciphertext = Base64.getUrlDecoder().decode(change.payloadCiphertext)
        require(ciphertext.size <= 1_048_592) { "Invalid sync payload size" }
        val payloadKey = payloadKey(key)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, EpochKeys.nonceBytes(change.payloadNonce)))
                updateAAD(EpochKeys.aad(change.entityType, change.vaultId, epoch, change.mutationId, change.entityId))
                doFinal(ciphertext)
            }
        } finally { payloadKey.fill(0) }
    }

    private fun payloadKey(contentKey: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(contentKey, "HmacSHA256"))
        doFinal("nuvori-sync-payload-key-v1".toByteArray(Charsets.UTF_8))
    }

    fun encrypt(contentKey: ByteArray, entityType: String, vaultId: String, mutationId: String, entityId: String,
        plaintext: ByteArray, random: SecureRandom = SecureRandom()): Encrypted {
        require(contentKey.size == 32)
        val nonce = ByteArray(12).also(random::nextBytes)
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(contentKey, "HmacSHA256"))
            doFinal("nuvori-sync-payload-key-v1".toByteArray(Charsets.UTF_8))
        }
        return try {
            val ciphertext = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("nuvori-sync-$entityType-v1:$vaultId:$mutationId:$entityId".toByteArray(Charsets.UTF_8))
                doFinal(plaintext)
            }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            Encrypted(encoder.encodeToString(ciphertext), encoder.encodeToString(nonce))
        } finally { payloadKey.fill(0) }
    }

    /**
     * Signs [payload] as this device's next change of its chain in [vaultId] and stores the change and
     * the new head. The caller sets the record state (and tombstone) in the same transaction.
     */
    suspend fun signAndStore(database: VaultDatabase, identityStore: AndroidDeviceIdentityStore, vaultId: String,
        contentKey: EpochKey, entityType: String, entityId: String, kind: ChangeKind, payload: JSONObject,
        version: RecordVersion, baseRevision: Long): SyncChangeRecord {
        val identity = identityStore.getOrCreate()
        val dao = database.syncDao()
        val head = dao.deviceHead(vaultId, identity.deviceId)
        val mutation = UUID.randomUUID().toString()
        val plaintext = payload.toString().toByteArray(Charsets.UTF_8)
        val encrypted = try { encrypt(contentKey, entityType, vaultId, mutation, entityId, plaintext) }
            finally { plaintext.fill(0) }
        val change = SyncChangeRecord.createSigned(vaultId, identity.deviceId, Math.addExact(head?.sequence ?: 0L, 1L),
            head?.hash ?: GENESIS_HASH, mutation, entityType, entityId, kind, baseRevision, version,
            Instant.now().toString(), encrypted.ciphertext, encrypted.nonce, identityStore::sign)
        dao.insertOperation(change.toEntity())
        dao.upsertDeviceHead(SyncDeviceHeadEntity(vaultId, identity.deviceId, change.sequence, change.hash))
        return change
    }

    fun SyncChangeRecord.toEntity() = SyncOperationEntity(mutationId, formatVersion, vaultId, deviceId, sequence,
        previousHash, entityType, entityId, kind.name.lowercase(), baseRevision, recordVersion.toJson(), occurredAtUtc,
        payloadCiphertext, payloadNonce, deviceSignature, hash)
}
