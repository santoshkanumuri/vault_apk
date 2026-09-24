package com.privatevault.app.sync

import org.bouncycastle.crypto.agreement.jpake.JPAKEParticipant
import org.bouncycastle.crypto.agreement.jpake.JPAKERound1Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound2Payload
import org.bouncycastle.crypto.agreement.jpake.JPAKERound3Payload
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class PairingTranscript(
    val sessionId: String,
    val vaultId: String,
    val creatorDeviceId: String,
    val creatorPublicKey: String,
    val joinerDeviceId: String,
    val joinerPublicKey: String,
) {
    fun bytes(): ByteArray = ByteArrayOutputStream().apply {
        listOf("nuvori-pairing-v1", sessionId, vaultId, creatorDeviceId, creatorPublicKey,
            joinerDeviceId, joinerPublicKey).forEach { value ->
            require(value.isNotBlank() && value.length <= 256) { "Invalid pairing transcript" }
            val encoded = value.toByteArray(Charsets.UTF_8)
            write(ByteBuffer.allocate(4).putInt(encoded.size).array())
            write(encoded)
        }
    }.toByteArray()
}

/** One-time J-PAKE exchange. The 24-digit code never becomes a stored device key. */
class PairingCrypto private constructor(private val participant: JPAKEParticipant) {
    constructor(participantId: String, code: CharArray) : this(createParticipant(participantId, code, false))
    private var keyingMaterial: BigInteger? = null

    companion object {
        internal fun transport(participantId: String, secret: CharArray) =
            PairingCrypto(createParticipant(participantId, secret, true))

        private fun createParticipant(id: String, secret: CharArray, transport: Boolean): JPAKEParticipant = try {
            require(id.isNotBlank())
            if (transport) require(secret.size == 43 && java.util.Base64.getUrlDecoder().decode(secret.concatToString()).size == 32)
            else require(secret.size == PAIRING_CODE_DIGITS && secret.all { it in '0'..'9' })
            JPAKEParticipant(id, secret)
        } finally { secret.fill('\u0000') }
    }

    fun round1(): JPAKERound1Payload = participant.createRound1PayloadToSend()
    fun acceptRound1(peer: JPAKERound1Payload) = participant.validateRound1PayloadReceived(peer)
    fun round2(): JPAKERound2Payload = participant.createRound2PayloadToSend()
    fun acceptRound2(peer: JPAKERound2Payload) = participant.validateRound2PayloadReceived(peer)
    fun round3(): JPAKERound3Payload {
        val material = participant.calculateKeyingMaterial()
        keyingMaterial = material
        return participant.createRound3PayloadToSend(material)
    }

    fun finish(peer: JPAKERound3Payload, transcript: PairingTranscript): ByteArray {
        val material = requireNotNull(keyingMaterial) { "Pairing rounds are incomplete" }
        val raw = material.toByteArray()
        return try {
            participant.validateRound3PayloadReceived(peer, material)
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(raw, "HmacSHA256"))
                doFinal(transcript.bytes())
            }
        } finally {
            raw.fill(0)
            keyingMaterial = null
        }
    }
}
