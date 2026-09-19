package com.privatevault.app.passkeys

import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.security.httpsOrigin
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.AuthenticatorDataConverter
import com.webauthn4j.converter.util.ObjectConverter
import com.webauthn4j.data.attestation.AttestationObject
import com.webauthn4j.data.attestation.authenticator.*
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.security.*
import java.security.interfaces.ECPublicKey
import java.security.spec.*
import java.util.Base64

/** ES256 resident credentials. WebAuthn4J encodes authenticator/attestation data; JCA generates and signs keys. */
internal object PasskeyCrypto {
    private val converter = ObjectConverter()
    fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    fun decode(value: String): ByteArray {
        require(value.length <= 8192 && value.matches(Regex("[A-Za-z0-9_-]+")))
        return Base64.getUrlDecoder().decode(value).also { require(encode(it) == value) }
    }
    fun request(json: String, origin: String, create: Boolean): JSONObject {
        require(json.length <= 65536 && httpsOrigin(origin) == origin)
        val input = JSONObject(json)
        require(decode(input.getString("challenge")).size in 16..1024)
        val rp = if (create) input.getJSONObject("rp").getString("id") else input.getString("rpId")
        // Offline first release: exact host only. Related-origin and parent-domain requests are unsupported.
        require(rp == URI(origin).host && rp.contains('.') && !rp.endsWith('.')) { "Unsupported passkey website" }
        if (create) {
            val algorithms = input.getJSONArray("pubKeyCredParams")
            require((0 until algorithms.length()).any { algorithms.getJSONObject(it).let { p -> p.optString("type") == "public-key" && p.optInt("alg") == -7 } })
            require(decode(input.getJSONObject("user").getString("id")).size in 1..64)
            require(input.getJSONObject("user").getString("name").length in 1..1024)
            require(input.optString("attestation", "none") == "none") { "Only anonymous attestation is supported" }
        }
        return input
    }
    fun matches(key: VaultPasskey, input: JSONObject): Boolean {
        if (key.rpId != input.getString("rpId")) return false
        val allowed = input.optJSONArray("allowCredentials") ?: return true
        return allowed.length() == 0 || (0 until allowed.length()).any {
            allowed.getJSONObject(it).let { item -> item.optString("type") == "public-key" && item.optString("id") == key.id }
        }
    }
    fun excluded(key: VaultPasskey, input: JSONObject): Boolean {
        val excluded = input.optJSONArray("excludeCredentials") ?: return false
        return key.rpId == input.getJSONObject("rp").getString("id") && (0 until excluded.length()).any {
            excluded.getJSONObject(it).optString("id") == key.id
        }
    }
    private fun clientData(input: JSONObject, origin: String, create: Boolean) = JSONObject()
        .put("type", if (create) "webauthn.create" else "webauthn.get").put("challenge", input.getString("challenge"))
        .put("origin", origin).put("crossOrigin", false).toString().toByteArray()
    fun create(input: JSONObject, origin: String, clientHash: ByteArray?): Pair<VaultPasskey, String> {
        request(input.toString(), origin, true)
        require(clientHash == null || clientHash.size == 32)
        val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val id = ByteArray(32).also(SecureRandom()::nextBytes)
        val user = input.getJSONObject("user")
        val key = VaultPasskey(encode(id), input.getJSONObject("rp").getString("id"), user.getString("id"), user.getString("name"),
            user.optString("displayName", user.getString("name")).take(1024), encode(pair.private.encoded), encode(pair.public.encoded))
        val attested = AttestedCredentialData(AAGUID.ZERO, id, EC2COSEKey.create(pair.public as ECPublicKey,
            com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.ES256))
        // UP + UV + backup eligible + attested credential. No signature counter, safe across restored copies.
        val data = AuthenticatorData<RegistrationExtensionAuthenticatorOutput>(sha256(key.rpId.toByteArray()), 0x4d.toByte(), 0, attested)
        val bytes = AttestationObjectConverter(converter).convertToBytes(AttestationObject(data, NoneAttestationStatement()))
        val response = JSONObject().put("clientDataJSON", if (clientHash == null) encode(clientData(input, origin, true)) else encode("{}".toByteArray()))
            .put("attestationObject", encode(bytes)).put("transports", JSONArray().put("internal"))
            .put("publicKeyAlgorithm", -7).put("publicKey", key.publicKey)
            .put("authenticatorData", encode(AuthenticatorDataConverter(converter).convert(data)))
        val extensions = JSONObject()
        if (input.optJSONObject("extensions")?.optBoolean("credProps") == true) extensions.put("credProps", JSONObject().put("rk", true))
        return key to credential(key.id, response).put("clientExtensionResults", extensions).toString()
    }
    fun sign(key: VaultPasskey, input: JSONObject, origin: String, clientHash: ByteArray?): String {
        request(input.toString(), origin, false)
        require(matches(key, input) && (clientHash == null || clientHash.size == 32))
        val client = clientData(input, origin, false)
        val data = AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(sha256(key.rpId.toByteArray()), 0x0d.toByte(), 0)
        val auth = AuthenticatorDataConverter(converter).convert(data)
        val privateBytes = decode(key.privateKey)
        val signature = try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(privateBytes)))
                update(auth); update(clientHash ?: sha256(client)); sign()
            }
        } finally { privateBytes.fill(0) }
        return credential(key.id, JSONObject().put("clientDataJSON", if (clientHash == null) encode(client) else encode("{}".toByteArray()))
            .put("authenticatorData", encode(auth)).put("signature", encode(signature)).put("userHandle", key.userHandle)).toString()
    }
    fun validateStored(key: VaultPasskey) {
        require(decode(key.id).size == 32 && decode(key.userHandle).size in 1..64)
        require(httpsOrigin("https://${key.rpId}") == "https://${key.rpId}" && URI("https://${key.rpId}").host == key.rpId)
        require(key.username.length in 1..1024 && key.displayName.length <= 1024)
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(decode(key.publicKey))) as ECPublicKey
        val expected = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        require(public.params.curve == expected.curve && public.params.generator == expected.generator &&
            public.params.order == expected.order && public.params.cofactor == expected.cofactor)
        val secret = decode(key.privateKey)
        try {
            val private = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(secret))
            val proof = Signature.getInstance("SHA256withECDSA").run { initSign(private); update(byteArrayOf(1, 2, 3)); sign() }
            require(Signature.getInstance("SHA256withECDSA").run { initVerify(public); update(byteArrayOf(1, 2, 3)); verify(proof) })
        } finally { secret.fill(0) }
    }
    private fun credential(id: String, response: JSONObject) = JSONObject().put("id", id).put("rawId", id)
        .put("type", "public-key").put("authenticatorAttachment", "platform").put("response", response).put("clientExtensionResults", JSONObject())
    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
}
