package com.privatevault.app.sync

import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The random probe identifies a paired peer without advertising vault or device IDs. */
internal object LanSyncExchange {
    class PeerBusy : Exception("The device is finishing another sync")
    class BatchLimitReached : Exception("More changes remain after this sync pass")
    /** A verified, signed REMOVE of this device arrived. The caller leaves the group locally. */
    class RemovedFromGroup(val notice: RemovalNotice.Removed) : Exception("This device was removed from the sync group")
    /** A newer signed history (a later key epoch) was adopted; reconnect to use it. */
    class MembershipCaughtUp : Exception("Sync group changed; reconnecting")
    /** This server answered a leave request or sent a removal notice; no sync happened. */
    class MembershipMessageServed(val reply: String) : Exception("Membership message answered")
    /** Kept for R8: Windows reads `hash` and `offset`. 2.1.x release phones sent `a` and `b`. */
    @Keep private data class PhotoRequest(@SerializedName(value = "hash", alternate = ["a"]) val hash: String,
        @SerializedName(value = "offset", alternate = ["b"]) val offset: Long)
    /** Wire limits shared with Windows (`lan_sync.rs` MAX_ROUNDS / MAX_BATCH_OPERATIONS). */
    internal const val MAX_ROUNDS = 32
    internal const val MAX_BATCH = 100
    private const val MAX_PHOTO_REQUESTS = 32
    private val HASH = Regex("[a-f0-9]{64}")
    private val gson = Gson()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val removalNotices = NoticeRateLimiter()

    /** [onAuthenticated] receives the peer's device ID and its announced platform ("android" or "windows"). */
    fun run(frames: SyncFrames, store: LockedSyncStore, server: Boolean,
        onAuthenticated: (String, String) -> Unit = { _, _ -> }) {
        val mirror = requireNotNull(store.snapshot())
        val currentSecret = TransportEpochs.secret(mirror.transportSecret, mirror.vaultId, mirror.keyEpoch)
        require(Base64.getUrlDecoder().decode(currentSecret).size == 32) { "Invalid transport credential" }
        val challenge: ByteArray
        val secret: String
        if (server) {
            challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            frames.send(challenge, MembershipWire.CONTROL_FRAME)
            val frame = frames.receive(MembershipWire.CONTROL_FRAME)
            MembershipWire.parseLeaveFrame(frame)?.let { leave ->
                throw MembershipMessageServed(answerLeave(frames, store, leave, challenge))
            }
            val supplied = Base64.getUrlDecoder().decode(frame.toString(Charsets.US_ASCII))
            secret = transportSecretFor(supplied, challenge, store, mirror) ?: run {
                // A device on a retained earlier epoch was removed (or missed a removal): show it
                // the signed history. Nothing else is sent before authentication.
                val previous = store.retainedEpochs().firstOrNull { epoch ->
                    MembershipWire.proofMatches(supplied, store.transportSecret(epoch), challenge)
                }
                require(previous != null && removalNotices.allow(System.currentTimeMillis())) {
                    "Device is not a group member"
                }
                frames.send(MembershipWire.REMOVED.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME)
                frames.send(MembershipWire.encodeHistory(MembershipWire.historyForEpoch(mirror.membershipEvents, previous)),
                    MembershipWire.HISTORY_FRAME)
                throw MembershipMessageServed(MembershipWire.REMOVED)
            }
            require(mirror.members.count { it.status == MemberStatus.ACTIVE.name } in 2..MAX_ACTIVE_SYNC_DEVICES)
            frames.send(MembershipWire.OK.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME)
        } else {
            require(mirror.members.count { it.status == MemberStatus.ACTIVE.name } in 2..MAX_ACTIVE_SYNC_DEVICES)
            secret = currentSecret
            challenge = frames.receive(MembershipWire.CONTROL_FRAME)
            if (challenge.contentEquals(MembershipWire.BUSY.toByteArray(Charsets.US_ASCII))) throw PeerBusy()
            require(challenge.size == 32)
            frames.send(encoder.encodeToString(TransportEpochs.probeProof(secret, challenge))
                .toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME)
            val reply = frames.receive(MembershipWire.CONTROL_FRAME).toString(Charsets.US_ASCII)
            if (reply == MembershipWire.REMOVED) receiveRemovalNotice(frames, store, mirror)
            require(reply == MembershipWire.OK) { "Device is not a group member" }
        }
        val identity = DeviceIdentity(mirror.localDeviceId, Base64.getUrlDecoder().decode(mirror.publicKey))
        val password = secret.toCharArray()
        val session = try { pairingHandshake(frames, identity, password,
            encoder.encodeToString(challenge), mirror.vaultId, server, transport = true) }
        finally { password.fill('\u0000') }
        try {
            EncryptedSyncChannel(frames, session.key, server).use { channel ->
                val events = mirror.membershipEvents
                val encoded = gson.toJson(events).toByteArray(Charsets.UTF_8)
                require(encoded.size <= MembershipWire.HISTORY_FRAME)
                channel.send(encoded)
                val received = channel.receive()
                require(received.size <= MembershipWire.HISTORY_FRAME)
                val remoteEvents = gson.fromJson(received.toString(Charsets.UTF_8),
                    Array<SyncMembershipEventEntity>::class.java).toList()
                store.acceptMembershipEvents(remoteEvents)
                val accepted = requireNotNull(store.snapshot())
                if (accepted.members.none { it.deviceId == identity.deviceId &&
                        it.identityPublicKey == identity.publicKeyBase64Url &&
                        it.status == MemberStatus.ACTIVE.name }) {
                    // Learned in an authenticated session from a peer on the new epoch.
                    accepted.membershipEvents.map { it.toEvent() }.lastOrNull {
                        it.action == MembershipAction.REMOVE && it.subjectDeviceId == identity.deviceId
                    }?.let { throw RemovedFromGroup(RemovalNotice.Removed(it.issuerDeviceId, it.sequence,
                        accepted.membershipEvents)) }
                    throw IllegalStateException("This device was removed from the sync group")
                }
                val member = requireNotNull(accepted.members.firstOrNull {
                    it.deviceId == session.peer.deviceId && it.status == MemberStatus.ACTIVE.name &&
                        it.identityPublicKey == session.peer.publicKeyBase64Url && it.keyEpoch <= accepted.keyEpoch
                }) { "Peer is not an active signed member" }
                val localProof = identityProof(session.key, accepted.vaultId,
                    identity.deviceId, session.peer.deviceId)
                channel.send(store.signIdentity(localProof))
                val remoteSignature = channel.receive()
                require(DeviceIdentityCrypto.verify(session.peer.publicKey,
                    identityProof(session.key, accepted.vaultId, session.peer.deviceId, identity.deviceId),
                    remoteSignature)) { "Peer identity proof failed" }
                onAuthenticated(member.deviceId, session.peerPlatform)
                val membershipHead = accepted.membershipEvents.last().hash
                val finished = exchangeChanges(channel, store, server, member.deviceId) {
                    require(store.snapshot()?.membershipEvents?.last()?.hash == membershipHead) {
                        "Membership changed during sync"
                    }
                }
                exchangePhotos(channel, store, server)
                if (!finished) throw BatchLimitReached()
            }
        } finally { session.key.fill(0) }
    }

    /**
     * The secret the client proved: the current epoch, or a later one when this server has not yet
     * seen a removal (the session's signed membership exchange then brings it up to date).
     */
    private fun transportSecretFor(supplied: ByteArray, challenge: ByteArray, store: LockedSyncStore,
        mirror: TransportMirror): String? =
        (mirror.keyEpoch..mirror.keyEpoch + TransportEpochs.FUTURE_EPOCHS).asSequence()
            .map { store.transportSecret(it) }
            .firstOrNull { MembershipWire.proofMatches(supplied, it, challenge) }

    /** Client side of `removed1`: act only on a signed history that extends this device's own. */
    private fun receiveRemovalNotice(frames: SyncFrames, store: LockedSyncStore, mirror: TransportMirror): Nothing {
        val history = MembershipWire.parseHistory(frames.receive(MembershipWire.HISTORY_FRAME))
        when (val notice = RemovalNoticeVerifier.verify(mirror.membershipEvents, mirror.localDeviceId, history)) {
            is RemovalNotice.Removed -> {
                store.acceptMembershipEvents(notice.history)
                throw RemovedFromGroup(notice)
            }
            is RemovalNotice.CatchUp -> {
                store.acceptMembershipEvents(notice.history)
                throw MembershipCaughtUp()
            }
        }
    }

    /** Server side of `leave1`. Invalid requests close without detail. */
    private fun answerLeave(frames: SyncFrames, store: LockedSyncStore, leave: MembershipWire.LeaveFrame,
        challenge: ByteArray): String {
        val mirror = requireNotNull(store.snapshot())
        val decision = LeaveRequestHandler.handle(leave, challenge, mirror.transportSecret,
            mirror.membershipEvents.map { it.toEvent() }, setOf(mirror.keyEpoch) + store.retainedEpochs(),
            mirror.localDeviceId, System.currentTimeMillis(), store::signIdentity)
        val reply = when (decision) {
            LeaveDecision.Reject -> throw IllegalArgumentException("Invalid leave request")
            is LeaveDecision.Left -> {
                store.appendMembershipEvents(decision.append.map { SyncMembershipEventEntity.from(it) })
                MembershipWire.LEFT
            }
            LeaveDecision.NotManager -> MembershipWire.NOT_MANAGER
            LeaveDecision.Busy -> MembershipWire.BUSY
        }
        frames.send(reply.toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME)
        return reply
    }

    /**
     * Client side of `leave1`: proves the old epoch's secret for this session's challenge and sends
     * the stored signed request. Returns the server's reply; a closed connection throws.
     */
    fun sendLeave(frames: SyncFrames, notice: PendingLeaveNotice): String {
        val challenge = frames.receive(MembershipWire.CONTROL_FRAME)
        require(challenge.size == 32)
        val proof = TransportEpochs.probeProof(notice.transportSecret, challenge)
        frames.send(MembershipWire.leaveFrame(proof, notice.request(), Base64.getUrlDecoder().decode(notice.signature))
            .toByteArray(Charsets.US_ASCII), MembershipWire.CONTROL_FRAME)
        return frames.receive(MembershipWire.CONTROL_FRAME).toString(Charsets.US_ASCII)
    }

    /**
     * Alternates bounded batches so neither socket waits for the other to drain a large write. Both
     * peers stop after a round that moved nothing, or after [MAX_ROUNDS] (Windows uses the same limit).
     * Every received operation is durable before it is acknowledged, so a dropped connection keeps
     * what arrived and the next session resumes from the reported frontier. Returns false when the
     * round limit ended the session with changes left.
     */
    internal fun exchangeChanges(channel: EncryptedSyncChannel, store: SyncSessionStore, server: Boolean,
        peerDeviceId: String, beforeRound: () -> Unit = {}): Boolean {
        repeat(MAX_ROUNDS) {
            beforeRound()
            val sent: Int
            val received: Int
            if (server) { sent = sendBatch(channel, store, peerDeviceId); received = receiveBatch(channel, store) }
            else { received = receiveBatch(channel, store); sent = sendBatch(channel, store, peerDeviceId) }
            if (sent == 0 && received == 0) return true
        }
        return false
    }

    /** The phone that accepted the connection serves first; each side then asks for what it lacks. */
    internal fun exchangePhotos(channel: EncryptedSyncChannel, store: SyncSessionStore, server: Boolean) {
        if (server) {
            servePhotos(channel, store)
            requestPhotos(channel, store)
        } else {
            requestPhotos(channel, store)
            servePhotos(channel, store)
        }
    }

    private fun sendBatch(channel: EncryptedSyncChannel, store: SyncSessionStore, peerDeviceId: String): Int {
        val progress = SyncProgress.parse(channel.receive())
        store.recordPeerProgress(peerDeviceId, progress)
        val frontier = SyncFrontier(progress.received.mapValues { it.value.sequence })
        val pending = store.pending(frontier).take(MAX_BATCH)
        channel.send(pending.size.toString().toByteArray())
        pending.forEach { operation ->
            channel.send(gson.toJson(operation).toByteArray(Charsets.UTF_8))
            require(channel.receive().contentEquals(operation.hash.toByteArray(Charsets.US_ASCII)))
        }
        return pending.size
    }

    private fun receiveBatch(channel: EncryptedSyncChannel, store: SyncSessionStore): Int {
        channel.send(store.progress().encode())
        val count = channel.receive().toString(Charsets.US_ASCII).toInt()
        require(count in 0..MAX_BATCH)
        repeat(count) {
            val operation = requireNotNull(gson.fromJson(channel.receive().toString(Charsets.UTF_8),
                SyncOperationEntity::class.java)) { "Invalid sync change" }
            require(operation.hash.matches(HASH)) { "Invalid sync change" }
            store.queue(operation)
            // Acknowledge only after the authenticated ciphertext reaches durable storage.
            channel.send(operation.hash.toByteArray(Charsets.US_ASCII))
        }
        return count
    }

    private fun requestPhotos(channel: EncryptedSyncChannel, store: SyncSessionStore) {
        val requests = store.missingPhotos().map { PhotoRequest(it.first, it.second) }
        channel.send(gson.toJson(requests).toByteArray(Charsets.UTF_8))
        requests.forEach { request ->
            val size = channel.receive().toString(Charsets.US_ASCII).toLong()
            if (size == -2L) {
                store.photoBlobs.discardPartial(request.hash)
                return@forEach
            }
            if (size < 0) return@forEach
            val ref = PhotoBlobRef(request.hash, size)
            store.photoBlobs.receive(channel, ref, request.offset)
            store.receivedPhoto(request.hash)
            channel.send(request.hash.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun servePhotos(channel: EncryptedSyncChannel, store: SyncSessionStore) {
        val requests = requireNotNull(gson.fromJson(channel.receive().toString(Charsets.UTF_8),
            Array<PhotoRequest?>::class.java)) { "Invalid photo request" }.toList()
        require(requests.size <= MAX_PHOTO_REQUESTS)
        requests.forEach { request ->
            @Suppress("SENSELESS_COMPARISON")
            require(request != null && request.hash != null && request.hash.matches(HASH) && request.offset >= 0) {
                "Invalid photo request"
            }
            val available = store.photoBlobs.file(request.hash)
            val file = available?.takeIf { request.offset <= it.length() }
            val size = if (available != null && file == null) -2L else file?.length() ?: -1L
            channel.send(size.toString().toByteArray(Charsets.US_ASCII))
            if (file != null) {
                store.photoBlobs.send(channel, file, request.offset)
                require(channel.receive().contentEquals(request.hash.toByteArray(Charsets.US_ASCII))) {
                    "Photo transfer was not acknowledged"
                }
            }
        }
    }

    private fun identityProof(key: ByteArray, vaultId: String, author: String, recipient: String) =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("nuvori-lan-identity-v2:$vaultId:$author:$recipient".toByteArray(Charsets.UTF_8))
        }
}
