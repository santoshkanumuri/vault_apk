package com.privatevault.app.sync

import com.privatevault.app.data.SyncOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Drops connections mid-batch and mid-photo and checks that the next session resumes cleanly. */
class SyncSessionResumeTest {
    @get:Rule val folder = TemporaryFolder()
    private val key = ByteArray(32) { 7 }

    @Test fun dropAfterADurableChangeKeepsItAndTheNextSessionResumesWithoutDuplicates() {
        val sender = MemoryStore(File(folder.root, "a")).apply { own(chain("phone", 150)) }
        val receiver = MemoryStore(File(folder.root, "b"))
        receiver.onQueued = { count -> if (count == 30) throw IOException("Connection dropped before acknowledging") }
        val first = session(sender, receiver)
        assertTrue(first.serverFailed && first.clientFailed)
        assertEquals(30, receiver.held.size)

        receiver.onQueued = {}
        sender.frontiersSeen.clear()
        val second = session(sender, receiver)
        assertFalse(second.serverFailed || second.clientFailed)
        assertEquals(150, receiver.held.size)
        assertEquals(0, receiver.duplicates)
        // The resumed session started right after the last durable change.
        assertEquals(30L, sender.frontiersSeen.first())
        assertEquals((1L..150L).toList(), receiver.held.values.map { it.sequence }.sorted())
    }

    @Test fun dropWhileTheSenderIsMidBatchKeepsEverythingAcknowledged() {
        val sender = MemoryStore(File(folder.root, "a")).apply { own(chain("phone", 80)) }
        val receiver = MemoryStore(File(folder.root, "b"))
        val first = session(sender, receiver, serverOutputLimit = 20_000)
        assertTrue(first.serverFailed)
        val kept = receiver.held.size
        assertTrue(kept in 1 until 80)
        session(sender, receiver)
        assertEquals(80, receiver.held.size)
        assertEquals(0, receiver.duplicates)
    }

    @Test fun roundLimitEndsTheSessionAndAnotherOneFinishesTheTransfer() {
        val sender = MemoryStore(File(folder.root, "a")).apply { own(chain("phone", 3_250)) }
        val receiver = MemoryStore(File(folder.root, "b"))
        val first = session(sender, receiver)
        assertEquals(false, first.serverFinished)
        assertEquals(false, first.clientFinished)
        assertEquals(LanSyncExchange.MAX_ROUNDS * LanSyncExchange.MAX_BATCH, receiver.held.size)
        val second = session(sender, receiver)
        assertEquals(true, second.serverFinished)
        assertEquals(3_250, receiver.held.size)
        assertEquals(0, receiver.duplicates)
    }

    @Test fun changesFlowBothWaysInOneSession() {
        val phone = MemoryStore(File(folder.root, "a")).apply { own(chain("phone", 40)) }
        val pc = MemoryStore(File(folder.root, "b")).apply { own(chain("pc", 25)) }
        val result = session(phone, pc)
        assertEquals(true, result.serverFinished)
        assertEquals(65, phone.held.size)
        assertEquals(65, pc.held.size)
    }

    @Test fun photoTransferResumesFromThePartialFileAfterADrop() {
        val owner = MemoryStore(File(folder.root, "a"))
        val requester = MemoryStore(File(folder.root, "b"))
        val bytes = ByteArray(300_000) { (it * 31 + 7).toByte() }
        val hash = sha256(bytes)
        File(owner.blobDirectory, hash).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        requester.wanted += hash

        val first = session(owner, requester, photos = true, serverOutputLimit = 150_000)
        assertTrue(first.serverFailed)
        val partial = requester.photoBlobs.partialSize(hash)
        assertTrue("partial $partial", partial in 1 until bytes.size)
        assertEquals(null, requester.photoBlobs.file(hash))

        val second = session(owner, requester, photos = true)
        assertFalse(second.serverFailed || second.clientFailed)
        assertNotNull(requester.photoBlobs.file(hash))
        assertTrue(hash !in requester.wanted)
        // Only the missing tail travelled again (plus framing).
        assertTrue(second.serverBytes < bytes.size - partial + 20_000)
    }

    @Test fun peerWithANewerPartialThanTheSourceStartsOver() {
        val owner = MemoryStore(File(folder.root, "a"))
        val requester = MemoryStore(File(folder.root, "b"))
        val bytes = ByteArray(1_000) { it.toByte() }
        val hash = sha256(bytes)
        File(owner.blobDirectory, hash).apply { parentFile!!.mkdirs(); writeBytes(bytes) }
        File(requester.blobDirectory, "$hash.part").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(2_000)) }
        requester.wanted += hash
        session(owner, requester, photos = true)
        assertEquals(0L, requester.photoBlobs.partialSize(hash))
        session(owner, requester, photos = true)
        assertNotNull(requester.photoBlobs.file(hash))
    }

    private data class SessionResult(val serverFinished: Boolean?, val clientFinished: Boolean?,
        val serverFailed: Boolean, val clientFailed: Boolean, val serverBytes: Long)

    /** [server] accepts; [client] connects. Optionally the server's socket dies after [serverOutputLimit] bytes. */
    private fun session(server: MemoryStore, client: MemoryStore, photos: Boolean = false,
        serverOutputLimit: Long = Long.MAX_VALUE): SessionResult {
        val executor = Executors.newSingleThreadExecutor()
        try {
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                var serverBytes = 0L
                val serverFuture = executor.submit<Boolean?> {
                    listener.accept().use { socket ->
                        socket.soTimeout = 20_000
                        val output = LimitedOutput(socket.getOutputStream(), serverOutputLimit) { socket.close() }
                        try {
                            EncryptedSyncChannel(SyncFrames(socket.getInputStream(), output), key, true).use { channel ->
                                if (photos) { LanSyncExchange.exchangePhotos(channel, server, true); true }
                                else LanSyncExchange.exchangeChanges(channel, server, true, "client")
                            }
                        } finally { serverBytes = output.written }
                    }
                }
                val clientResult = runCatching {
                    Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                        socket.soTimeout = 20_000
                        EncryptedSyncChannel(SyncFrames(socket.getInputStream(), socket.getOutputStream()), key, false)
                            .use { channel ->
                                if (photos) { LanSyncExchange.exchangePhotos(channel, client, false); true }
                                else LanSyncExchange.exchangeChanges(channel, client, false, "server")
                            }
                    }
                }
                val serverResult = runCatching { serverFuture.get(60, TimeUnit.SECONDS) }
                return SessionResult(serverResult.getOrNull(), clientResult.getOrNull(),
                    serverResult.isFailure, clientResult.isFailure, serverBytes)
            }
        } finally { executor.shutdownNow() }
    }

    private class LimitedOutput(private val target: OutputStream, private val limit: Long,
        private val onLimit: () -> Unit) : OutputStream() {
        var written = 0L
        override fun write(value: Int) = write(byteArrayOf(value.toByte()), 0, 1)
        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            if (written + length > limit) {
                val allowed = (limit - written).toInt().coerceAtLeast(0)
                target.write(bytes, offset, allowed); target.flush()
                written += allowed
                onLimit()
                throw IOException("Connection dropped")
            }
            target.write(bytes, offset, length)
            written += length
        }
        override fun flush() = target.flush()
    }

    /** In-memory [SyncSessionStore] that follows the production chain rules. */
    private class MemoryStore(val blobDirectory: File) : SyncSessionStore {
        val held = LinkedHashMap<String, SyncOperationEntity>()
        val wanted = sortedSetOf<String>()
        var duplicates = 0
        var onQueued: (Int) -> Unit = {}
        val frontiersSeen = mutableListOf<Long>()
        override val photoBlobs = PhotoSyncBlobs(blobDirectory)

        fun own(operations: List<SyncOperationEntity>) = operations.forEach { held[it.hash] = it }

        override fun progress(): SyncProgress = SyncProgress(IncomingChain.received(emptyMap(), emptyList(),
            held.values.map { ChainLink(it.deviceId, it.sequence, it.hash, it.previousHash) }), emptyMap())

        override fun recordPeerProgress(deviceId: String, progress: SyncProgress) { progress.validate() }

        override fun pending(frontier: SyncFrontier): List<SyncOperationEntity> {
            frontiersSeen += frontier.counters.values.maxOrNull() ?: 0L
            return held.values.filter { it.sequence > (frontier.counters[it.deviceId] ?: 0L) }
                .sortedWith(compareBy({ it.deviceId }, { it.sequence })).take(100)
        }

        override fun queue(operation: SyncOperationEntity) {
            if (operation.hash in held) { duplicates++; return }
            IncomingChain.admit(ChainLink(operation.deviceId, operation.sequence, operation.hash,
                operation.previousHash), progress().received, emptySet())
            held[operation.hash] = operation
            onQueued(held.size)
        }

        override fun missingPhotos(): List<Pair<String, Long>> =
            wanted.filter { photoBlobs.file(it) == null }.take(32).map { it to photoBlobs.partialSize(it) }

        override fun receivedPhoto(hash: String) { wanted -= hash }
    }

    companion object {
        fun chain(device: String, count: Int): List<SyncOperationEntity> {
            var previous = GENESIS_HASH
            return (1..count).map { sequence ->
                val hash = sha256("$device-$sequence".toByteArray())
                SyncOperationEntity("$device-m$sequence", 1, "vault", device, sequence.toLong(), previous, "entry",
                    "entry-$sequence", "upsert", 0, "{\"counters\":{\"$device\":$sequence}}", "2026-01-01T00:00:00Z",
                    "cipher", "nonce", "signature", hash).also { previous = hash }
            }
        }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
