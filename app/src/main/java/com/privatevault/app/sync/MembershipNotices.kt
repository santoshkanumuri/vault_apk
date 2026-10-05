package com.privatevault.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncMembershipEventEntity
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Transport secret per membership key epoch.
 *
 * Epoch 1 is the random group transport secret stored in `sync_vault_state.transportSecret`
 * (created in SyncGroupInitializer / SyncGroupReset and copied to the locked transport mirror), so
 * groups that never removed a device keep exactly today's secret. Each REMOVE event raises the
 * signed key epoch by one; the secret for epoch N > 1 is
 * `base64url(HMAC-SHA256(key = decode(epoch-1 secret), "nuvori-transport-epoch-v1:<vaultId>:<N>"))`.
 *
 * The transport secret only gates the LAN probe and the transport J-PAKE. Authorization is the
 * signed membership chain plus the per-session identity proof, so a removed device that still knows
 * the epoch-1 secret can never pass the active-member check. Retained epochs are derived on demand,
 * never stored separately, and disappear with the epoch-1 secret on group reset or leave.
 */
internal object TransportEpochs {
    const val RETAINED_PREVIOUS_EPOCHS = 4
    const val RETENTION_MILLIS = 90L * 24 * 60 * 60 * 1000
    /** A lagging server accepts a client that already moved this many epochs ahead. */
    const val FUTURE_EPOCHS = 4

    fun secret(rootSecret: String, vaultId: String, epoch: Long): String {
        require(epoch >= 1) { "Invalid key epoch" }
        if (epoch == 1L) return rootSecret
        val root = Base64.getUrlDecoder().decode(rootSecret)
        require(root.size == 32) { "Invalid transport credential" }
        return try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(root, "HmacSHA256"))
                doFinal("nuvori-transport-epoch-v1:$vaultId:$epoch".toByteArray(Charsets.UTF_8))
            })
        } finally { root.fill(0) }
    }

    /**
     * Previous epochs still answered with a removal notice. [retiredAt] maps an epoch to the time this
     * device first saw the next epoch; an epoch with no recorded time counts as retired now.
     */
    fun retained(currentEpoch: Long, retiredAt: Map<Long, Long>, now: Long): List<Long> =
        ((currentEpoch - RETAINED_PREVIOUS_EPOCHS).coerceAtLeast(1) until currentEpoch).filter { epoch ->
            now - (retiredAt[epoch] ?: now) <= RETENTION_MILLIS
        }.sortedDescending()

    fun probeProof(secret: String, challenge: ByteArray): ByteArray {
        val key = Base64.getUrlDecoder().decode(secret)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                update("nuvori-lan-probe-v1".toByteArray(Charsets.US_ASCII))
                doFinal(challenge)
            }
        } finally { key.fill(0) }
    }
}

/** Fields of a signed leave request. The JSON keeps this key order; readers accept any order. */
internal data class LeaveRequest(val vaultId: String, val deviceId: String, val membershipHead: String,
    val keyEpoch: Long, val createdAt: Long) {
    fun signingBytes(): ByteArray =
        "nuvori-leave-v1\n$vaultId\n$deviceId\n$membershipHead\n$keyEpoch\n$createdAt".toByteArray(Charsets.UTF_8)

    fun toJson(): String = JsonObject().apply {
        addProperty("vaultId", vaultId); addProperty("deviceId", deviceId)
        addProperty("membershipHead", membershipHead); addProperty("keyEpoch", keyEpoch)
        addProperty("createdAt", createdAt)
    }.toString()

    fun validate() {
        require(vaultId.isNotBlank() && vaultId.length <= 128 && deviceId.isNotBlank() && deviceId.length <= 128 &&
            membershipHead.matches(Regex("[a-f0-9]{64}")) && keyEpoch >= 1 && createdAt > 0) { "Invalid leave request" }
    }

    companion object {
        fun parseJson(json: String): LeaveRequest {
            val o = JsonParser.parseString(json).asJsonObject
            require(o.keySet() == setOf("vaultId", "deviceId", "membershipHead", "keyEpoch", "createdAt"))
            fun number(name: String) = o.getAsJsonPrimitive(name).also { require(it.isNumber) }
                .asBigDecimal.longValueExact()
            fun text(name: String) = o.getAsJsonPrimitive(name).also { require(it.isString) }.asString
            return LeaveRequest(text("vaultId"), text("deviceId"), text("membershipHead"),
                number("keyEpoch"), number("createdAt")).also { it.validate() }
        }
    }
}

/** The additive transport-step messages (SPEC3). All control frames are ASCII and at most 4096 bytes. */
internal object MembershipWire {
    const val OK = "ok"
    const val REMOVED = "removed1"
    const val LEFT = "left1"
    const val NOT_MANAGER = "notmanager1"
    const val BUSY = "busy1"
    const val LEAVE_PREFIX = "leave1."
    const val CONTROL_FRAME = 4096
    const val HISTORY_FRAME = 1_000_000
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val gson = Gson()

    data class LeaveFrame(val proof: ByteArray, val request: LeaveRequest, val signature: ByteArray)

    fun leaveFrame(proof: ByteArray, request: LeaveRequest, signature: ByteArray): String =
        LEAVE_PREFIX + encoder.encodeToString(proof) + "." +
            encoder.encodeToString(request.toJson().toByteArray(Charsets.UTF_8)) + "." + encoder.encodeToString(signature)

    /** Returns null for an ordinary proof frame; throws for a malformed leave frame. */
    fun parseLeaveFrame(frame: ByteArray): LeaveFrame? {
        require(frame.size in 1..CONTROL_FRAME)
        val text = frame.toString(Charsets.US_ASCII)
        if (!text.startsWith(LEAVE_PREFIX)) return null
        val parts = text.removePrefix(LEAVE_PREFIX).split('.')
        require(parts.size == 3 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+")) }) { "Invalid leave request" }
        val decoder = Base64.getUrlDecoder()
        val proof = decoder.decode(parts[0])
        val signature = decoder.decode(parts[2])
        require(proof.size == 32 && signature.size == DeviceIdentityCrypto.SIGNATURE_BYTES) { "Invalid leave request" }
        return LeaveFrame(proof, LeaveRequest.parseJson(decoder.decode(parts[1]).toString(Charsets.UTF_8)), signature)
    }

    /** Same JSON shape as the in-channel membership exchange in [LanSyncExchange]. */
    fun encodeHistory(events: List<SyncMembershipEventEntity>): ByteArray =
        gson.toJson(events).toByteArray(Charsets.UTF_8).also { require(it.size in 1..HISTORY_FRAME) }

    fun parseHistory(bytes: ByteArray): List<SyncMembershipEventEntity> {
        require(bytes.size in 1..HISTORY_FRAME) { "Membership history too large" }
        return requireNotNull(gson.fromJson(bytes.toString(Charsets.UTF_8),
            Array<SyncMembershipEventEntity>::class.java)).toList()
    }

    /**
     * The history sent with `removed1` to a device that proved [provedEpoch]: everything up to and
     * including the event that ended that epoch (the REMOVE that rotated it). A device removed there
     * sees its own REMOVE; an active device that only missed the rotation adopts it and reconnects,
     * one epoch at a time. Devices added after that point are never shown to an old-secret holder.
     */
    fun historyForEpoch(events: List<SyncMembershipEventEntity>, provedEpoch: Long): List<SyncMembershipEventEntity> {
        val end = events.indexOfFirst { it.keyEpoch > provedEpoch }
        return if (end < 0) events else events.take(end + 1)
    }

    fun proofMatches(supplied: ByteArray, secret: String, challenge: ByteArray): Boolean =
        MessageDigest.isEqual(supplied, TransportEpochs.probeProof(secret, challenge))
}

/** What a client learns from a verified `removed1` history. */
internal sealed interface RemovalNotice {
    /** A signed REMOVE names this device. [issuerDeviceId] is the manager that signed it. */
    data class Removed(val issuerDeviceId: String, val removeSequence: Long,
        val history: List<SyncMembershipEventEntity>) : RemovalNotice
    /** A valid newer history that still lists this device: adopt it, then reconnect with the new epoch. */
    data class CatchUp(val history: List<SyncMembershipEventEntity>) : RemovalNotice
}

internal object RemovalNoticeVerifier {
    /**
     * Accepts [remote] only when it verifies, starts with this device's own stored history (same
     * genesis and vault, this device's head at the same sequence) and is longer than it. Anything
     * else throws, and the caller treats it as an ordinary failed connection.
     */
    fun verify(local: List<SyncMembershipEventEntity>, localDeviceId: String,
        remote: List<SyncMembershipEventEntity>): RemovalNotice {
        require(local.isNotEmpty() && remote.size > local.size) { "Membership history does not extend this device's" }
        val localEvents = local.map { it.toEvent() }
        val remoteEvents = remote.map { it.toEvent() }
        SyncMembershipManager.verify(localEvents)
        val verified = SyncMembershipManager.verify(remoteEvents)
        require(localEvents.indices.all { remoteEvents[it].hash == localEvents[it].hash }) {
            "Membership history does not extend this device's"
        }
        require(verified.events.first().vaultId == localEvents.first().vaultId)
        val removal = remoteEvents.drop(localEvents.size).firstOrNull {
            it.action == MembershipAction.REMOVE && it.subjectDeviceId == localDeviceId
        }
        return if (removal != null) RemovalNotice.Removed(removal.issuerDeviceId, removal.sequence, remote)
        else {
            require(verified.members.any { it.deviceId == localDeviceId && it.status == MemberStatus.ACTIVE.name }) {
                "Membership history does not list this device"
            }
            RemovalNotice.CatchUp(remote)
        }
    }
}

/** The manager-side and member-side answer to a leave request. */
internal sealed interface LeaveDecision {
    /** Close without detail. */
    data object Reject : LeaveDecision
    /** Reply `left1`. [append] holds the signed events to persist first (empty when already removed). */
    data class Left(val append: List<SyncMembershipEvent>) : LeaveDecision
    data object NotManager : LeaveDecision
    data object Busy : LeaveDecision
}

internal object LeaveRequestHandler {
    const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    const val MAX_CLOCK_SKEW_MILLIS = 5L * 60 * 1000

    /**
     * Validates [frame] against this server's verified [history] and decides the answer.
     *
     * The proof must match this session's [challenge] under the secret of the request's key epoch,
     * and that epoch must be the current one or a retained previous one ([acceptedEpochs]); the
     * challenge binding is what stops a captured frame from being replayed in another session.
     * The signature must verify against the device's identity key in the signed membership.
     *
     * A pending transfer offered to the leaving device is cancelled (signed CANCEL_TRANSFER) before
     * the REMOVE, because the chain forbids REMOVE while a transfer is pending. A pending transfer to
     * another device answers `busy1`. The managing device itself cannot leave this way.
     */
    fun handle(frame: MembershipWire.LeaveFrame, challenge: ByteArray, rootSecret: String,
        history: List<SyncMembershipEvent>, acceptedEpochs: Set<Long>, localDeviceId: String, now: Long,
        signer: (ByteArray) -> ByteArray): LeaveDecision {
        val verified = runCatching { SyncMembershipManager.verify(history) }.getOrNull() ?: return LeaveDecision.Reject
        val request = frame.request
        val vaultId = verified.events.first().vaultId
        if (request.vaultId != vaultId || request.keyEpoch !in acceptedEpochs) return LeaveDecision.Reject
        if (!MembershipWire.proofMatches(frame.proof, TransportEpochs.secret(rootSecret, vaultId, request.keyEpoch),
                challenge)) return LeaveDecision.Reject
        if (request.createdAt > now + MAX_CLOCK_SKEW_MILLIS || request.createdAt < now - MAX_AGE_MILLIS)
            return LeaveDecision.Reject
        val headIndex = verified.events.indexOfFirst { it.hash == request.membershipHead }
        if (headIndex < 0) return LeaveDecision.Reject
        val epochAtHead = SyncMembershipManager.verify(verified.events.take(headIndex + 1)).keyEpoch
        if (epochAtHead != request.keyEpoch) return LeaveDecision.Reject
        val member = verified.members.firstOrNull { it.deviceId == request.deviceId } ?: return LeaveDecision.Reject
        val publicKey = runCatching { Base64.getUrlDecoder().decode(member.identityPublicKey) }.getOrNull()
            ?: return LeaveDecision.Reject
        if (!DeviceIdentityCrypto.verify(publicKey, request.signingBytes(), frame.signature)) return LeaveDecision.Reject
        if (member.status == MemberStatus.REVOKED.name) return LeaveDecision.Left(emptyList())
        if (request.deviceId == verified.managerDeviceId) return LeaveDecision.Reject
        if (verified.managerDeviceId != localDeviceId) return LeaveDecision.NotManager
        val pending = verified.pendingTransferDeviceId
        if (pending != null && pending != request.deviceId) return LeaveDecision.Busy
        val events = history.toMutableList()
        if (pending != null) events += SyncMembershipEvent.sign(vaultId, events.size + 1L, events.last().hash,
            MembershipAction.CANCEL_TRANSFER, localDeviceId, pending, "", verified.keyEpoch, signer)
        events += SyncMembershipEvent.sign(vaultId, events.size + 1L, events.last().hash,
            MembershipAction.REMOVE, localDeviceId, request.deviceId, "", verified.keyEpoch + 1, signer)
        SyncMembershipManager.verify(events)
        return LeaveDecision.Left(events.drop(history.size))
    }
}

/** At most [limit] removal notices per [windowMillis] for one server. */
internal class NoticeRateLimiter(private val limit: Int = 10, private val windowMillis: Long = 60_000) {
    private val sent = ArrayDeque<Long>()
    @Synchronized fun allow(now: Long): Boolean {
        while (sent.isNotEmpty() && now - sent.first() >= windowMillis) sent.removeFirst()
        if (sent.size >= limit) return false
        sent.addLast(now)
        return true
    }
}
