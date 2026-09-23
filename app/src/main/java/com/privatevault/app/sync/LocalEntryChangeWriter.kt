package com.privatevault.app.sync

import androidx.room.withTransaction
import com.google.gson.Gson
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import org.json.JSONArray
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

class LocalEntryChangeWriter(
    private val database: VaultDatabase,
    private val identityStore: AndroidDeviceIdentityStore,
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    suspend fun save(entry: VaultEntry, groupIds: Set<String>, vaultKey: ByteArray) {
        require(vaultKey.size == 32) { "Vault key must be 32 bytes" }
        val identity = identityStore.getOrCreate()
        val savedEntry = entry.copy(updatedAt = clock.millis())

        database.withTransaction {
            val settings = database.dao().settings() ?: error("Vault settings are missing")
            require(settings.vaultId.isNotBlank()) { "Vault ID is missing" }
            val syncDao = database.syncDao()
            val head = syncDao.deviceHead(settings.vaultId, identity.deviceId)
            val state = syncDao.recordState(ENTITY_TYPE, savedEntry.id)
            val currentVersion = state?.let {
                Gson().fromJson(it.recordVersionJson, RecordVersion::class.java)
            } ?: RecordVersion(emptyMap())
            val sequence = (head?.sequence ?: 0L) + 1L
            val counters = currentVersion.counters.toMutableMap().apply {
                this[identity.deviceId] = (this[identity.deviceId] ?: 0L) + 1L
            }
            val recordVersion = RecordVersion(counters)
            val mutationId = UUID.randomUUID().toString()
            val encrypted = encryptPayload(
                savedEntry,
                groupIds,
                vaultKey,
                settings.vaultId,
                mutationId,
            )
            val change = SyncChangeRecord.createSigned(
                vaultId = settings.vaultId,
                deviceId = identity.deviceId,
                sequence = sequence,
                previousHash = head?.hash ?: GENESIS_HASH,
                mutationId = mutationId,
                entityType = ENTITY_TYPE,
                entityId = savedEntry.id,
                kind = ChangeKind.UPSERT,
                baseRevision = state?.revision ?: 0L,
                recordVersion = recordVersion,
                occurredAtUtc = Instant.now(clock).toString(),
                payloadCiphertext = encrypted.ciphertext,
                payloadNonce = encrypted.nonce,
                signer = identityStore::sign,
            )

            database.dao().saveEntryExact(savedEntry, groupIds)
            syncDao.insertOperation(change.toEntity())
            syncDao.upsertDeviceHead(
                SyncDeviceHeadEntity(change.vaultId, change.deviceId, change.sequence, change.hash),
            )
            syncDao.upsertRecordState(
                SyncRecordStateEntity(
                    ENTITY_TYPE,
                    savedEntry.id,
                    change.baseRevision + 1L,
                    Gson().toJson(recordVersion),
                ),
            )
        }
    }

    private fun encryptPayload(
        entry: VaultEntry,
        groupIds: Set<String>,
        vaultKey: ByteArray,
        vaultId: String,
        mutationId: String,
    ): EncryptedPayload {
        val plaintext = JSONObject()
            .put("entry", entry.toSyncJson())
            .put("groupIds", JSONArray(groupIds.sorted()))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(12).also(random::nextBytes)
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(vaultKey, "HmacSHA256"))
            doFinal(PAYLOAD_KEY_LABEL.toByteArray(Charsets.UTF_8))
        }
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad(vaultId, mutationId, entry.id))
            }
            EncryptedPayload(encode(cipher.doFinal(plaintext)), encode(nonce))
        } finally {
            plaintext.fill(0)
            payloadKey.fill(0)
        }
    }

    private fun VaultEntry.toSyncJson() = JSONObject()
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

    private fun SyncChangeRecord.toEntity() = SyncOperationEntity(
        mutationId, formatVersion, vaultId, deviceId, sequence, previousHash,
        entityType, entityId, kind.name.lowercase(), baseRevision, Gson().toJson(recordVersion),
        occurredAtUtc, payloadCiphertext, payloadNonce, deviceSignature, hash,
    )

    private fun aad(vaultId: String, mutationId: String, entityId: String) =
        "nuvori-sync-entry-v1:$vaultId:$mutationId:$entityId".toByteArray(Charsets.UTF_8)

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private data class EncryptedPayload(val ciphertext: String, val nonce: String)

    private companion object {
        const val ENTITY_TYPE = "entry"
        const val PAYLOAD_KEY_LABEL = "nuvori-sync-payload-key-v1"
    }
}
