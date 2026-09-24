package com.privatevault.app.sync

import com.google.gson.Gson
import com.privatevault.app.data.SyncMembershipEventEntity
import com.privatevault.app.data.SyncOperationEntity
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** The random probe identifies a paired peer without advertising vault or device IDs. */
internal object LanSyncExchange {
    private data class PhotoRequest(val hash: String, val offset: Long)
    private val gson = Gson()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun run(frames: SyncFrames, store: LockedSyncStore, server: Boolean) {
        val mirror = requireNotNull(store.snapshot())
        require(mirror.members.count { it.status == MemberStatus.ACTIVE.name } in 2..MAX_ACTIVE_SYNC_DEVICES)
        val secret = mirror.transportSecret
        require(Base64.getUrlDecoder().decode(secret).size == 32) { "Invalid transport credential" }
        val challenge: ByteArray
        if (server) {
            challenge = ByteArray(32).also(SecureRandom()::nextBytes)
            frames.send(challenge, 4096)
            val supplied = Base64.getUrlDecoder().decode(frames.receive(4096).toString(Charsets.US_ASCII))
            require(MessageDigest.isEqual(supplied, proof(secret, challenge))) { "Device is not a group member" }
            frames.send("ok".toByteArray(Charsets.US_ASCII), 4096)
        } else {
            challenge = frames.receive(4096)
            require(challenge.size == 32)
            frames.send(encoder.encodeToString(proof(secret, challenge)).toByteArray(Charsets.US_ASCII), 4096)
            require(frames.receive(4096).contentEquals("ok".toByteArray(Charsets.US_ASCII)))
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
                require(encoded.size <= 1_000_000)
                channel.send(encoded)
                val received = channel.receive()
                require(received.size <= 1_000_000)
                val remoteEvents = gson.fromJson(received.toString(Charsets.UTF_8),
                    Array<SyncMembershipEventEntity>::class.java).toList()
                store.acceptMembershipEvents(remoteEvents)
                val accepted = requireNotNull(store.snapshot())
                require(accepted.members.any { it.deviceId == identity.deviceId &&
                    it.identityPublicKey == identity.publicKeyBase64Url &&
                    it.status == MemberStatus.ACTIVE.name }) { "This device was removed from the sync group" }
                val member = requireNotNull(accepted.members.firstOrNull {
                    it.deviceId == session.peer.deviceId && it.status == MemberStatus.ACTIVE.name &&
                        it.identityPublicKey == session.peer.publicKeyBase64Url
                }) { "Peer is not an active signed member" }
                require(member.keyEpoch == accepted.keyEpoch) { "Peer key epoch is unsupported" }
                val localProof = identityProof(session.key, accepted.vaultId,
                    identity.deviceId, session.peer.deviceId)
                channel.send(store.signIdentity(localProof))
                val remoteSignature = channel.receive()
                require(DeviceIdentityCrypto.verify(session.peer.publicKey,
                    identityProof(session.key, accepted.vaultId, session.peer.deviceId, identity.deviceId),
                    remoteSignature)) { "Peer identity proof failed" }
                val membershipHead = accepted.membershipEvents.last().hash
                // Alternate bounded batches so neither socket waits for the other to drain a large write.
                var finished = false
                repeat(32) {
                    if (finished) return@repeat
                    require(store.snapshot()?.membershipEvents?.last()?.hash == membershipHead) {
                        "Membership changed during sync"
                    }
                    val sent: Int
                    val received: Int
                    if (server) { sent = sendBatch(channel, store, member.deviceId); received = receiveBatch(channel, store) }
                    else { received = receiveBatch(channel, store); sent = sendBatch(channel, store, member.deviceId) }
                    if (sent == 0 && received == 0) finished = true
                }
                if (server) {
                    servePhotos(channel, store)
                    requestPhotos(channel, store)
                } else {
                    requestPhotos(channel, store)
                    servePhotos(channel, store)
                }
            }
        } finally { session.key.fill(0) }
    }

    private fun sendBatch(channel: EncryptedSyncChannel, store: LockedSyncStore, peerDeviceId: String): Int {
        val progress = SyncProgress.parse(channel.receive())
        store.recordPeerProgress(peerDeviceId, progress)
        val frontier = SyncFrontier(progress.received.mapValues { it.value.sequence })
        val pending = store.pending(frontier)
        channel.send(pending.size.toString().toByteArray())
        pending.forEach { operation ->
            channel.send(gson.toJson(operation).toByteArray(Charsets.UTF_8))
            require(channel.receive().contentEquals(operation.hash.toByteArray(Charsets.US_ASCII)))
        }
        return pending.size
    }

    private fun receiveBatch(channel: EncryptedSyncChannel, store: LockedSyncStore): Int {
        channel.send(store.progress().encode())
        val count = channel.receive().toString(Charsets.US_ASCII).toInt()
        require(count in 0..100)
        repeat(count) {
            val operation = gson.fromJson(channel.receive().toString(Charsets.UTF_8), SyncOperationEntity::class.java)
            store.queue(operation)
            // Acknowledge only after the authenticated ciphertext reaches durable storage.
            channel.send(operation.hash.toByteArray(Charsets.US_ASCII))
        }
        return count
    }

    private fun requestPhotos(channel: EncryptedSyncChannel, store: LockedSyncStore) {
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

    private fun servePhotos(channel: EncryptedSyncChannel, store: LockedSyncStore) {
        val requests = gson.fromJson(channel.receive().toString(Charsets.UTF_8),
            Array<PhotoRequest>::class.java).toList()
        require(requests.size <= 32)
        requests.forEach { request ->
            require(request.hash.matches(Regex("[a-f0-9]{64}")) && request.offset >= 0)
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

    private fun proof(secret: String, challenge: ByteArray): ByteArray {
        val key = Base64.getUrlDecoder().decode(secret)
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                update("nuvori-lan-probe-v1".toByteArray(Charsets.US_ASCII))
                doFinal(challenge)
            }
        } finally { key.fill(0) }
    }

    private fun identityProof(key: ByteArray, vaultId: String, author: String, recipient: String) =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal("nuvori-lan-identity-v2:$vaultId:$author:$recipient".toByteArray(Charsets.UTF_8))
        }
}
