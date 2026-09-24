package com.privatevault.app.sync

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncWireTest {
    @Test
    fun peersAuthenticateAndExchangeEncryptedFrames() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val creator = DeviceIdentity("creator", DeviceIdentityCrypto.publicKey(ByteArray(32) { 1 }))
            val joiner = DeviceIdentity("joiner", DeviceIdentityCrypto.publicKey(ByteArray(32) { 2 }))
            val future = executor.submit<String> {
                server.accept().use { socket ->
                    socket.soTimeout = 10_000
                    val frames = SyncFrames(socket.getInputStream(), socket.getOutputStream())
                    val result = pairingHandshake(frames, creator, "123456789012345678901234".toCharArray(),
                        "session", "vault", true)
                    EncryptedSyncChannel(frames, result.key, true).use { channel ->
                        result.key.fill(0)
                        channel.send("encrypted message".toByteArray())
                        assertEquals("acknowledged", channel.receive().toString(Charsets.UTF_8))
                    }
                    result.confirmation
                }
            }
            try {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                    socket.soTimeout = 10_000
                    val frames = SyncFrames(socket.getInputStream(), socket.getOutputStream())
                    val result = pairingHandshake(frames, joiner, "123456789012345678901234".toCharArray(),
                        "session", "vault", false)
                    EncryptedSyncChannel(frames, result.key, false).use { channel ->
                        result.key.fill(0)
                        assertEquals("encrypted message", channel.receive().toString(Charsets.UTF_8))
                        channel.send("acknowledged".toByteArray())
                    }
                    assertEquals(result.confirmation, future.get(15, TimeUnit.SECONDS))
                }
            } finally { executor.shutdownNow() }
        }
    }

    @Test
    fun frameReaderRejectsOversizeBeforeAllocatingPayload() {
        val bytes = ByteArrayOutputStream().also { DataOutputStream(it).writeInt(Int.MAX_VALUE) }.toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            SyncFrames(ByteArrayInputStream(bytes), ByteArrayOutputStream()).receive()
        }
    }

    @Test
    fun encryptedChannelRejectsReplayAndWrongDirection() {
        val output = ByteArrayOutputStream()
        val key = ByteArray(32) { 1 }
        EncryptedSyncChannel(SyncFrames(ByteArrayInputStream(byteArrayOf()), output), key, true).use {
            it.send("message".toByteArray())
        }
        val frame = output.toByteArray()
        EncryptedSyncChannel(SyncFrames(ByteArrayInputStream(frame + frame), ByteArrayOutputStream()), key, false).use {
            assertEquals("message", it.receive().toString(Charsets.UTF_8))
            assertThrows(javax.crypto.AEADBadTagException::class.java) { it.receive() }
        }
        EncryptedSyncChannel(SyncFrames(ByteArrayInputStream(frame), ByteArrayOutputStream()), key, true).use {
            assertThrows(javax.crypto.AEADBadTagException::class.java) { it.receive() }
        }
    }

    @Test
    fun snapshotStreamEndsBeforeTheNextControlMessage() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            val future = executor.submit {
                server.accept().use { socket ->
                    socket.soTimeout = 5_000
                    EncryptedSyncChannel(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                        ByteArray(32) { 7 }, true).use { channel ->
                        SyncChannelOutput(channel).use { output ->
                            output.write(ByteArray(70_000) { (it % 251).toByte() })
                        }
                        assertEquals("ready", channel.receive().toString(Charsets.US_ASCII))
                    }
                }
            }
            try {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { socket ->
                    socket.soTimeout = 5_000
                    EncryptedSyncChannel(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                        ByteArray(32) { 7 }, false).use { channel ->
                        val bytes = SyncChannelInput(channel).use { it.readBytes() }
                        assertArrayEquals(ByteArray(70_000) { (it % 251).toByte() }, bytes)
                        channel.send("ready".toByteArray(Charsets.US_ASCII))
                    }
                }
                future.get(10, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
