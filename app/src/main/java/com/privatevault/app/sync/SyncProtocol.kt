package com.privatevault.app.sync

import com.google.gson.annotations.SerializedName
import java.nio.ByteBuffer
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64

const val SYNC_FORMAT_VERSION = 1
const val KEY_ENVELOPE_FORMAT_VERSION = 1
const val PAIRING_CODE_DIGITS = 24
const val RECOVERY_SECRET_BITS = 256
const val GENESIS_HASH = "0000000000000000000000000000000000000000000000000000000000000000"

enum class MemberStatus {
    @SerializedName("active")
    ACTIVE,

    @SerializedName("revoked")
    REVOKED,
}

data class DeviceMembership(
    val vaultId: String,
    val deviceId: String,
    val displayName: String,
    val identityPublicKey: String,
    val status: MemberStatus,
    val addedByDeviceId: String,
    val membershipSequence: Long,
    val keyEpoch: Long,
) {
    fun validate() {
        requireText("vaultId", vaultId)
        requireText("deviceId", deviceId)
        requireText("displayName", displayName)
        requireText("identityPublicKey", identityPublicKey)
        requireText("addedByDeviceId", addedByDeviceId)
        require(membershipSequence > 0) { "membershipSequence must be greater than zero" }
        require(keyEpoch > 0) { "keyEpoch must be greater than zero" }
    }
}

enum class EnvelopeRecipientType {
    @SerializedName("password")
    PASSWORD,

    @SerializedName("device")
    DEVICE,

    @SerializedName("recoveryCard")
    RECOVERY_CARD,

    @SerializedName("backup")
    BACKUP,
}

data class VaultKeyEnvelope(
    val formatVersion: Int,
    val vaultId: String,
    val keyEpoch: Long,
    val recipientType: EnvelopeRecipientType,
    val recipientId: String,
    val algorithm: String,
    val nonce: String,
    val wrappedVaultKey: String,
) {
    fun validate() {
        require(formatVersion == KEY_ENVELOPE_FORMAT_VERSION) {
            "unsupported key envelope format $formatVersion"
        }
        requireText("vaultId", vaultId)
        requireText("recipientId", recipientId)
        requireText("algorithm", algorithm)
        requireText("nonce", nonce)
        requireText("wrappedVaultKey", wrappedVaultKey)
        require(keyEpoch > 0) { "keyEpoch must be greater than zero" }
    }
}

data class SyncFrontier(val counters: Map<String, Long>) {
    fun validate() {
        counters.keys.forEach { requireText("frontierDeviceId", it) }
        counters.values.forEach { require(it >= 0) { "frontier counter must not be negative" } }
    }
}

data class RecordVersion(val counters: Map<String, Long>) {
    fun relationTo(other: RecordVersion): VersionRelation {
        var less = false
        var greater = false

        (counters.keys + other.counters.keys).forEach { deviceId ->
            val left = counters[deviceId] ?: 0
            val right = other.counters[deviceId] ?: 0
            less = less || left < right
            greater = greater || left > right
        }

        return when {
            !less && !greater -> VersionRelation.EQUAL
            less && !greater -> VersionRelation.BEFORE
            !less && greater -> VersionRelation.AFTER
            else -> VersionRelation.CONCURRENT
        }
    }

    fun validate() {
        require(counters.isNotEmpty()) { "record version must contain at least one device" }
        counters.forEach { (deviceId, counter) ->
            requireText("recordVersionDeviceId", deviceId)
            require(counter > 0) { "recordVersionCounter must be greater than zero" }
        }
    }
}

enum class VersionRelation {
    BEFORE,
    AFTER,
    EQUAL,
    CONCURRENT,
}

enum class ChangeKind {
    @SerializedName("upsert")
    UPSERT,

    @SerializedName("delete")
    DELETE,
}

data class SyncChangeRecord(
    val formatVersion: Int,
    val vaultId: String,
    val deviceId: String,
    val sequence: Long,
    val previousHash: String,
    val mutationId: String,
    val entityType: String,
    val entityId: String,
    val kind: ChangeKind,
    val baseRevision: Long,
    val recordVersion: RecordVersion,
    val occurredAtUtc: String,
    val payloadCiphertext: String,
    val payloadNonce: String,
    val deviceSignature: String,
    val hash: String,
) {
    fun signingBytes(): ByteArray = canonicalBytes(includeSignature = false)

    fun computeHash(): String {
        return MessageDigest.getInstance("SHA-256").digest(canonicalBytes(includeSignature = true))
            .joinToString("") { "%02x".format(it) }
    }

    fun validate() {
        require(formatVersion == SYNC_FORMAT_VERSION) { "unsupported sync format $formatVersion" }
        requireText("vaultId", vaultId)
        requireText("deviceId", deviceId)
        require(sequence > 0) { "sequence must be greater than zero" }
        requireText("previousHash", previousHash)
        requireText("mutationId", mutationId)
        requireText("entityType", entityType)
        requireText("entityId", entityId)
        recordVersion.validate()
        requireText("occurredAtUtc", occurredAtUtc)
        requireText("payloadCiphertext", payloadCiphertext)
        requireText("payloadNonce", payloadNonce)
        requireText("deviceSignature", deviceSignature)
        require(hash == computeHash()) { "record contents do not match its hash" }
    }

    private fun canonicalBytes(includeSignature: Boolean): ByteArray = ByteArrayOutputStream().apply {
        addField(formatVersion.toU16Bytes())
        addField(vaultId.toByteArray(Charsets.UTF_8))
        addField(deviceId.toByteArray(Charsets.UTF_8))
        addField(sequence.toU64Bytes())
        addField(previousHash.toByteArray(Charsets.UTF_8))
        addField(mutationId.toByteArray(Charsets.UTF_8))
        addField(entityType.toByteArray(Charsets.UTF_8))
        addField(entityId.toByteArray(Charsets.UTF_8))
        addField(kind.name.lowercase().toByteArray(Charsets.UTF_8))
        addField(baseRevision.toU64Bytes())
        addField(recordVersion.counters.size.toLong().toU64Bytes())
        recordVersion.counters.toSortedMap().forEach { (versionDeviceId, counter) ->
            addField(versionDeviceId.toByteArray(Charsets.UTF_8))
            addField(counter.toU64Bytes())
        }
        addField(occurredAtUtc.toByteArray(Charsets.UTF_8))
        addField(payloadCiphertext.toByteArray(Charsets.UTF_8))
        addField(payloadNonce.toByteArray(Charsets.UTF_8))
        if (includeSignature) addField(deviceSignature.toByteArray(Charsets.UTF_8))
    }.toByteArray()

    companion object {
        fun createSigned(
            vaultId: String,
            deviceId: String,
            sequence: Long,
            previousHash: String,
            mutationId: String,
            entityType: String,
            entityId: String,
            kind: ChangeKind,
            baseRevision: Long,
            recordVersion: RecordVersion,
            occurredAtUtc: String,
            payloadCiphertext: String,
            payloadNonce: String,
            signer: (ByteArray) -> ByteArray,
        ): SyncChangeRecord {
            val unsigned = SyncChangeRecord(
                SYNC_FORMAT_VERSION, vaultId, deviceId, sequence, previousHash, mutationId,
                entityType, entityId, kind, baseRevision, recordVersion, occurredAtUtc,
                payloadCiphertext, payloadNonce, "", "",
            )
            val signature = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(signer(unsigned.signingBytes()))
            val signed = unsigned.copy(deviceSignature = signature)
            return signed.copy(hash = signed.computeHash()).also { it.validate() }
        }
    }
}

private fun requireText(field: String, value: String) {
    require(value.isNotBlank()) { "$field must not be blank" }
}

private fun ByteArrayOutputStream.addField(bytes: ByteArray) {
    write(bytes.size.toLong().toU64Bytes())
    write(bytes)
}

private fun Int.toU16Bytes(): ByteArray = ByteBuffer.allocate(2).putShort(toShort()).array()

private fun Long.toU64Bytes(): ByteArray = ByteBuffer.allocate(8).putLong(this).array()
