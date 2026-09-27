package com.privatevault.app.sync

import com.privatevault.app.data.SyncMembershipEntity
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64

internal enum class MembershipAction { GENESIS, ADD, REMOVE, OFFER_TRANSFER, ACCEPT_TRANSFER, CANCEL_TRANSFER, TRANSFER }

internal data class SyncMembershipEvent(
    val formatVersion: Int,
    val vaultId: String,
    val sequence: Long,
    val previousHash: String,
    val action: MembershipAction,
    val issuerDeviceId: String,
    val subjectDeviceId: String,
    val subjectPublicKey: String,
    val keyEpoch: Long,
    val signature: String,
    val hash: String,
) {
    fun signingBytes(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeInt(formatVersion)
            output.writeString(vaultId)
            output.writeLong(sequence)
            output.writeString(previousHash)
            output.writeString(action.name)
            output.writeString(issuerDeviceId)
            output.writeString(subjectDeviceId)
            output.writeString(subjectPublicKey)
            output.writeLong(keyEpoch)
        }
    }.toByteArray()

    fun computedHash(): String = MessageDigest.getInstance("SHA-256").digest(
        signingBytes() + signature.toByteArray(Charsets.US_ASCII))
        .joinToString("") { "%02x".format(it) }

    companion object {
        fun sign(vaultId: String, sequence: Long, previousHash: String, action: MembershipAction,
            issuerDeviceId: String, subjectDeviceId: String, subjectPublicKey: String,
            keyEpoch: Long, signer: (ByteArray) -> ByteArray): SyncMembershipEvent {
            val unsigned = SyncMembershipEvent(1, vaultId, sequence, previousHash, action,
                issuerDeviceId, subjectDeviceId, subjectPublicKey, keyEpoch, "", "")
            val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(signer(unsigned.signingBytes()))
            return unsigned.copy(signature = signature).let { it.copy(hash = it.computedHash()) }
        }
    }
}

internal data class VerifiedMembership(val events: List<SyncMembershipEvent>,
    val members: List<SyncMembershipEntity>, val managerDeviceId: String, val keyEpoch: Long,
    val pendingTransferDeviceId: String?, val transferAccepted: Boolean) {
    val head: String get() = events.last().hash
}

internal object SyncMembershipManager {
    fun verify(events: List<SyncMembershipEvent>): VerifiedMembership {
        require(events.isNotEmpty() && events.size <= 1024) { "Invalid membership history size" }
        val members = linkedMapOf<String, SyncMembershipEntity>()
        var manager = ""
        var epoch = 1L
        var pendingTransfer: String? = null
        var transferAccepted = false
        var previousHash = GENESIS_HASH
        val vaultId = events.first().vaultId
        events.forEachIndexed { index, event ->
            require(event.formatVersion == 1 && event.vaultId == vaultId && vaultId.isNotBlank() &&
                event.sequence == index + 1L && event.previousHash == previousHash &&
                event.hash == event.computedHash()) { "Invalid membership chain" }
            require(event.issuerDeviceId.isNotBlank() && event.subjectDeviceId.isNotBlank())
            val issuerKey = if (index == 0) {
                require(event.action == MembershipAction.GENESIS &&
                    event.issuerDeviceId == event.subjectDeviceId && event.keyEpoch == 1L)
                event.subjectPublicKey
            } else if (event.action == MembershipAction.ACCEPT_TRANSFER) {
                require(event.issuerDeviceId == pendingTransfer && !transferAccepted)
                requireNotNull(members[event.issuerDeviceId]) { "Transfer recipient is missing" }
                    .also { require(it.status == MemberStatus.ACTIVE.name) }.identityPublicKey
            } else {
                require(event.action != MembershipAction.GENESIS && event.issuerDeviceId == manager)
                requireNotNull(members[manager]) { "Managing device is missing" }.identityPublicKey
            }
            val key = Base64.getUrlDecoder().decode(issuerKey)
            require(DeviceIdentityCrypto.verify(key, event.signingBytes(),
                Base64.getUrlDecoder().decode(event.signature))) { "Invalid membership signature" }
            when (event.action) {
                MembershipAction.GENESIS, MembershipAction.ADD -> {
                    require(pendingTransfer == null)
                    require(event.keyEpoch == epoch && event.subjectDeviceId !in members)
                    require(members.values.count { it.status == MemberStatus.ACTIVE.name } < MAX_ACTIVE_SYNC_DEVICES)
                    require(Base64.getUrlDecoder().decode(event.subjectPublicKey).size == DeviceIdentityCrypto.PUBLIC_KEY_BYTES)
                    require(members.values.none { it.identityPublicKey == event.subjectPublicKey }) {
                        "Device identity key was reused"
                    }
                    members[event.subjectDeviceId] = SyncMembershipEntity(vaultId, event.subjectDeviceId,
                        if (index == 0) "This device" else "Android device", event.subjectPublicKey,
                        MemberStatus.ACTIVE.name, event.issuerDeviceId, event.sequence, epoch)
                    if (index == 0) manager = event.subjectDeviceId
                }
                MembershipAction.REMOVE -> {
                    require(pendingTransfer == null)
                    require(event.subjectDeviceId != manager && event.subjectPublicKey.isEmpty() &&
                        event.keyEpoch == epoch + 1L)
                    val subject = requireNotNull(members[event.subjectDeviceId])
                    require(subject.status == MemberStatus.ACTIVE.name)
                    epoch = event.keyEpoch
                    members[event.subjectDeviceId] = subject.copy(status = MemberStatus.REVOKED.name,
                        membershipSequence = event.sequence, keyEpoch = epoch)
                }
                MembershipAction.OFFER_TRANSFER -> {
                    require(event.subjectPublicKey.isEmpty() && event.keyEpoch == epoch &&
                        pendingTransfer == null && event.subjectDeviceId != manager &&
                        members[event.subjectDeviceId]?.status == MemberStatus.ACTIVE.name)
                    pendingTransfer = event.subjectDeviceId
                }
                MembershipAction.ACCEPT_TRANSFER -> {
                    require(event.subjectPublicKey.isEmpty() && event.keyEpoch == epoch &&
                        event.subjectDeviceId == pendingTransfer)
                    transferAccepted = true
                }
                MembershipAction.CANCEL_TRANSFER -> {
                    require(event.subjectPublicKey.isEmpty() && event.keyEpoch == epoch &&
                        event.subjectDeviceId == pendingTransfer)
                    pendingTransfer = null
                    transferAccepted = false
                }
                MembershipAction.TRANSFER -> {
                    require(event.subjectPublicKey.isEmpty() && event.keyEpoch == epoch &&
                        event.subjectDeviceId == pendingTransfer && transferAccepted)
                    manager = event.subjectDeviceId
                    pendingTransfer = null
                    transferAccepted = false
                }
            }
            previousHash = event.hash
        }
        return VerifiedMembership(events, members.values.toList(), manager, epoch,
            pendingTransfer, transferAccepted)
    }
}

private fun DataOutputStream.writeString(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    require(bytes.size <= 4096) { "Membership field is too long" }
    writeInt(bytes.size)
    write(bytes)
}
