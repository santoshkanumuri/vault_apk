package com.privatevault.app.passkeys

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class CredentialTransferTest {
    @Test fun importsCxfP256PasskeyAndDerivesItsPublicKey() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val credentialId = PasskeyCrypto.encode(ByteArray(48) { it.toByte() })
        val result = readCxfPasskeys(payload(credentialId, PasskeyCrypto.encode(pair.private.encoded)))

        assertEquals("Synthetic provider", result.exporterDisplayName)
        assertEquals(0, result.unsupportedPasskeys)
        val imported = result.passkeys.single()
        assertEquals(credentialId, imported.id)
        assertEquals(PasskeyCrypto.encode(pair.public.encoded), imported.publicKey)
        PasskeyCrypto.validateStored(imported)
    }

    @Test fun skipsUnsupportedExtensionsAndNeverEchoesKeyMaterialInErrors() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val privateKey = PasskeyCrypto.encode(pair.private.encoded)
        val extended = JSONObject(payload(PasskeyCrypto.encode(ByteArray(32) { 3 }), privateKey))
        extended.getJSONArray("accounts").getJSONObject(0).getJSONArray("items").getJSONObject(0)
            .getJSONArray("credentials").getJSONObject(0)
            .put("fido2Extensions", JSONObject().put("hmacSecret", JSONObject()))
        val skipped = readCxfPasskeys(extended.toString())
        assertEquals(1, skipped.unsupportedPasskeys)
        assertTrue(skipped.passkeys.isEmpty())

        val malformed = payload("not base64!", privateKey)
        val error = runCatching { readCxfPasskeys(malformed) }.exceptionOrNull()!!
        assertTrue(error.message!!.contains("No passkeys were saved"))
        assertTrue(!error.message!!.contains(privateKey))
    }

    @Test fun rejectsDuplicateCredentialIds() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val root = JSONObject(payload(PasskeyCrypto.encode(ByteArray(32) { 5 }), PasskeyCrypto.encode(pair.private.encoded)))
        val items = root.getJSONArray("accounts").getJSONObject(0).getJSONArray("items")
        items.put(JSONObject(items.getJSONObject(0).toString()).put("id", UUID.randomUUID().toString()))
        assertTrue(runCatching { readCxfPasskeys(root.toString()) }.isFailure)
    }

    private fun payload(credentialId: String, privateKey: String): String {
        val credential = JSONObject()
            .put("type", "passkey")
            .put("credentialId", credentialId)
            .put("rpId", "example.com")
            .put("username", "user@example.com")
            .put("userDisplayName", "Example user")
            .put("userHandle", PasskeyCrypto.encode(ByteArray(32) { 7 }))
            .put("key", privateKey)
        val item = JSONObject().put("id", "item-id").put("creationAt", 1_700_000_000)
            .put("title", "Example").put("credentials", JSONArray().put(credential))
        val account = JSONObject().put("id", "account-id").put("username", "owner")
            .put("email", "owner@example.com").put("collections", JSONArray())
            .put("items", JSONArray().put(item))
        return JSONObject().put("version", JSONObject().put("major", 1).put("minor", 0))
            .put("exporterRpId", "provider.example").put("exporterDisplayName", "Synthetic provider")
            .put("timestamp", 1_700_000_000).put("accounts", JSONArray().put(account)).toString()
    }
}
