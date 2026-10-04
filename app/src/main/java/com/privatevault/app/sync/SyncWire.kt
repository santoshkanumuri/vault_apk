package com.privatevault.app.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class SyncFrames(input: InputStream, output: OutputStream) {
    private val input = DataInputStream(input)
    private val output = DataOutputStream(output)

    fun send(bytes: ByteArray, maximum: Int = MAX_FRAME) {
        require(bytes.size in 1..maximum) { "Invalid sync frame size" }
        output.writeInt(bytes.size)
        output.write(bytes)
        output.flush()
    }

    fun receive(maximum: Int = MAX_FRAME): ByteArray {
        val size = input.readInt()
        require(size in 1..maximum) { "Invalid sync frame size" }
        return ByteArray(size).also(input::readFully)
    }

    fun exchange(json: JsonObject): JsonObject {
        send(json.toString().toByteArray(Charsets.UTF_8), HANDSHAKE_FRAME)
        return JsonParser.parseString(receive(HANDSHAKE_FRAME).toString(Charsets.UTF_8)).asJsonObject
    }

    companion object {
        const val MAX_FRAME = 2 * 1024 * 1024
        const val HANDSHAKE_FRAME = 16 * 1024
    }
}

internal data class HandshakeResult(val peer: DeviceIdentity, val key: ByteArray, val confirmation: String,
    val peerPlatform: String)

/** J-PAKE authenticates the shared secret; its result binds both public device identities. */
internal fun pairingHandshake(frames: SyncFrames, local: DeviceIdentity, code: CharArray,
    sessionId: String, vaultId: String, creator: Boolean, expectedPeer: DeviceIdentity? = null,
    transport: Boolean = false, masterPassword: Boolean = false): HandshakeResult {
    val crypto = when {
        transport -> PairingCrypto.transport(local.deviceId, code)
        masterPassword -> PairingCrypto.masterPassword(local.deviceId, code)
        else -> PairingCrypto(local.deviceId, code)
    }
    val hello = frames.exchange(JsonObject().apply {
        addProperty("protocol", SYNC_WIRE_VERSION)
        addProperty("session", sessionId)
        addProperty("vault", vaultId)
        addProperty("creator", creator)
        addProperty("device", local.deviceId)
        addProperty("key", local.publicKeyBase64Url)
        addProperty("platform", "android")
    })
    require(hello.get("protocol").asInt == SYNC_WIRE_VERSION && hello.get("session").asString == sessionId &&
        hello.get("vault").asString == vaultId && hello.get("creator").asBoolean != creator) { "Pairing session mismatch" }
    val peerId = hello.get("device").asString
    val peerPlatform = hello.get("platform")?.asString ?: "android"
    require(peerPlatform == "android" || peerPlatform == "windows") { "Unsupported pairing platform" }
    require(peerId.isNotBlank() && peerId.length <= 128 && peerId != local.deviceId)
    val peer = DeviceIdentity(peerId, Base64.getUrlDecoder().decode(hello.get("key").asString))
    require(peer.publicKey.size == DeviceIdentityCrypto.PUBLIC_KEY_BYTES)
    if (expectedPeer != null) require(peer.deviceId == expectedPeer.deviceId &&
        peer.publicKey.contentEquals(expectedPeer.publicKey)) { "Paired identity changed" }
    val first = crypto.round1()
    val firstPeer = frames.exchange(JsonObject().apply {
        addProperty("id", first.participantId)
        addProperty("gx1", first.gx1.toString(16)); addProperty("gx2", first.gx2.toString(16))
        add("p1", proof(first.knowledgeProofForX1)); add("p2", proof(first.knowledgeProofForX2))
    })
    require(firstPeer.get("id").asString == peer.deviceId)
    crypto.acceptRound1(JPAKERound1Payload(peer.deviceId, integer(firstPeer, "gx1"), integer(firstPeer, "gx2"),
        readProof(firstPeer, "p1"), readProof(firstPeer, "p2")))
    val second = crypto.round2()
    val secondPeer = frames.exchange(JsonObject().apply {
        addProperty("id", second.participantId); addProperty("a", second.a.toString(16))
        add("proof", proof(second.knowledgeProofForX2s))
    })
    require(secondPeer.get("id").asString == peer.deviceId)
    crypto.acceptRound2(JPAKERound2Payload(peer.deviceId, integer(secondPeer, "a"), readProof(secondPeer, "proof")))
    val third = crypto.round3()
    val thirdPeer = frames.exchange(JsonObject().apply {
        addProperty("id", third.participantId); addProperty("mac", third.macTag.toString(16))
    })
    require(thirdPeer.get("id").asString == peer.deviceId)
    val source = if (creator) local else peer
    val target = if (creator) peer else local
    val key = crypto.finish(JPAKERound3Payload(peer.deviceId, integer(thirdPeer, "mac", signed = true)),
        PairingTranscript(sessionId, vaultId, source.deviceId, source.publicKeyBase64Url,
            target.deviceId, target.publicKeyBase64Url))
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    val confirmation = digest.take(4).joinToString("") { "%02X".format(it) }.chunked(4).joinToString(" ")
    return HandshakeResult(peer, key, confirmation, peerPlatform)
}

private fun proof(values: Array<BigInteger>) = JsonArray().apply { values.forEach { add(it.toString(16)) } }
private fun readProof(json: JsonObject, key: String): Array<BigInteger> {
    val values = json.getAsJsonArray(key)
    require(values.size() == 2)
    return Array(2) { index -> parseInteger(values[index].asString, false) }
}
private fun integer(json: JsonObject, key: String, signed: Boolean = false) = parseInteger(json.get(key).asString, signed)
private fun parseInteger(value: String, signed: Boolean): BigInteger {
    require(value.length in 1..1025 && value.matches(if (signed) Regex("-?[0-9a-f]+") else Regex("[0-9a-f]+")))
    return BigInteger(value, 16)
}

/** Separate direction keys and sequence-bound AEAD frames reject cross-direction replay. */
internal class EncryptedSyncChannel(private val frames: SyncFrames, sessionKey: ByteArray,
    creator: Boolean) : AutoCloseable {
    private val sendKey = derive(sessionKey, if (creator) "creator-send" else "joiner-send")
    private val receiveKey = derive(sessionKey, if (creator) "joiner-send" else "creator-send")
    private var sendSequence = 0L
    private var receiveSequence = 0L
    private var closed = false

    fun send(bytes: ByteArray) {
        check(!closed && sendSequence < Long.MAX_VALUE)
        require(bytes.size <= SyncFrames.MAX_FRAME - 16)
        val cipher = cipher(Cipher.ENCRYPT_MODE, sendKey, sendSequence++)
        frames.send(cipher.doFinal(bytes))
    }

    fun receive(): ByteArray {
        check(!closed && receiveSequence < Long.MAX_VALUE)
        return cipher(Cipher.DECRYPT_MODE, receiveKey, receiveSequence++).doFinal(frames.receive())
    }

    override fun close() {
        closed = true
        sendKey.fill(0); receiveKey.fill(0)
    }

    private fun cipher(mode: Int, key: ByteArray, sequence: Long): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            val nonce = ByteBuffer.allocate(12).putInt(1).putLong(sequence).array()
            init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD("nuvori-sync-frame-v1".toByteArray(Charsets.US_ASCII))
        }

    private fun derive(key: ByteArray, label: String): ByteArray = Mac.getInstance("HmacSHA256").run {
        require(key.size == 32)
        init(SecretKeySpec(key, "HmacSHA256"))
        doFinal("nuvori-sync-channel-v1:$label".toByteArray(Charsets.US_ASCII))
    }
}
