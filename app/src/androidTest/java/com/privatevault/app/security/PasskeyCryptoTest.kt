package com.privatevault.app.security

import com.privatevault.app.passkeys.PasskeyCrypto
import com.webauthn4j.converter.AttestationObjectConverter
import com.webauthn4j.converter.util.ObjectConverter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.*
import java.security.spec.X509EncodedKeySpec

class PasskeyCryptoTest {
    companion object {
        const val CREATE = """{"rp":{"id":"example.com","name":"Example"},"user":{"id":"AQIDBA","name":"dummy@example.com","displayName":"Dummy"},"challenge":"AAECAwQFBgcICQoLDA0ODw","pubKeyCredParams":[{"type":"public-key","alg":-7}]}"""
        const val GET = """{"rpId":"example.com","challenge":"EBESExQVFhcYGRobHB0eHw"}"""
    }
    @Test fun registrationAndAssertionAreVerifiableAndBoundToChallenge() {
        val input = PasskeyCrypto.request(CREATE, "https://example.com", true)
        assertEquals("direct", PasskeyCrypto.request(
            JSONObject(CREATE).put("attestation", "direct").toString(), "https://example.com", true
        ).getString("attestation"))
        assertTrue(runCatching { PasskeyCrypto.request(
            JSONObject(CREATE).put("attestation", "enterprise").toString(), "https://example.com", true
        ) }.isFailure)
        val (key, created) = PasskeyCrypto.create(input, "https://example.com", null)
        PasskeyCrypto.validateStored(key)
        val response = JSONObject(created).getJSONObject("response")
        val attestation = AttestationObjectConverter(ObjectConverter()).convert(PasskeyCrypto.decode(response.getString("attestationObject")))!!
        assertTrue(attestation.authenticatorData.isFlagUP)
        assertTrue(attestation.authenticatorData.isFlagUV)
        assertTrue(attestation.authenticatorData.isFlagBE)
        assertFalse(attestation.authenticatorData.isFlagBS)
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest("example.com".toByteArray()), attestation.authenticatorData.rpIdHash)
        assertArrayEquals(PasskeyCrypto.decode(key.id), attestation.authenticatorData.attestedCredentialData!!.credentialId)
        assertEquals(com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier.ES256,
            attestation.authenticatorData.attestedCredentialData!!.coseKey.algorithm)
        val get = PasskeyCrypto.request(GET, "https://example.com", false)
        val hash = ByteArray(32) { it.toByte() }
        val signed = JSONObject(PasskeyCrypto.sign(key, get, "https://example.com", hash)).getJSONObject("response")
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(PasskeyCrypto.decode(key.publicKey)))
        fun verifies(challenge: ByteArray) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(public); update(PasskeyCrypto.decode(signed.getString("authenticatorData"))); update(challenge)
            verify(PasskeyCrypto.decode(signed.getString("signature")))
        }
        assertTrue(verifies(hash))
        assertFalse(verifies(hash.copyOf().apply { this[0] = 99 }))
        assertEquals("{}", String(PasskeyCrypto.decode(signed.getString("clientDataJSON"))))
        val parentRp = JSONObject(GET).put("rpId", "id.me").toString()
        assertEquals("id.me", PasskeyCrypto.request(parentRp, "https://api.id.me", false).getString("rpId"))
        assertTrue(runCatching { PasskeyCrypto.request(GET, "http://example.com", false) }.isFailure)
        assertTrue(runCatching { PasskeyCrypto.request(CREATE, "http://example.com", true) }.isFailure)
        assertTrue(runCatching { PasskeyCrypto.request(CREATE.replace("-7", "-257"), "https://example.com", true) }.isFailure)
        assertTrue(runCatching { PasskeyCrypto.validateStored(key.copy(privateKey = "bad")) }.isFailure)
        assertTrue(runCatching { PasskeyCrypto.sign(key, get, "https://example.com", ByteArray(31)) }.isFailure)
        assertTrue(runCatching { PasskeyCrypto.sign(key, JSONObject(GET).put("rpId", "evil.example"), "https://evil.example", hash) }.isFailure)
        assertFalse(PasskeyCrypto.matches(key, JSONObject(GET).put("allowCredentials", org.json.JSONArray().put(JSONObject().put("type", "public-key").put("id", "different")))))
    }

    @Test fun nativeAppPasskeyUsesSignedAndroidOrigin() {
        val origin = "android:apk-key-hash:" + "A".repeat(43)
        val input = PasskeyCrypto.request(CREATE, origin, true)
        val (key, created) = PasskeyCrypto.create(input, origin, null)
        assertEquals(origin, JSONObject(String(PasskeyCrypto.decode(JSONObject(created).getJSONObject("response").getString("clientDataJSON")))).getString("origin"))
        val signed = JSONObject(PasskeyCrypto.sign(key, PasskeyCrypto.request(GET, origin, false), origin, null)).getJSONObject("response")
        val clientData = PasskeyCrypto.decode(signed.getString("clientDataJSON"))
        assertEquals(origin, JSONObject(String(clientData)).getString("origin"))
        val public = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(PasskeyCrypto.decode(key.publicKey)))
        assertTrue(Signature.getInstance("SHA256withECDSA").run {
            initVerify(public)
            update(PasskeyCrypto.decode(signed.getString("authenticatorData")))
            update(MessageDigest.getInstance("SHA-256").digest(clientData))
            verify(PasskeyCrypto.decode(signed.getString("signature")))
        })
        assertTrue(runCatching { PasskeyCrypto.request(CREATE, "android:apk-key-hash:bad", true) }.isFailure)
    }
}
