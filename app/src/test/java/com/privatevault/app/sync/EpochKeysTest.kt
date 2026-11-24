package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Protocol `epoch-keys-v1`. The vector file (scripts/sync-vectors/epoch_keys.py) is byte-identical in the Windows repository. */
class EpochKeysTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/epoch-keys-v1.json")).readText()).asJsonObject
    private val vault = vectors.get("vaultId").asString
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun text(name: String) = vectors.get(name).asString
    private fun b64(text: String) = Base64.getUrlDecoder().decode(text)
    private fun device(id: String) = vectors.getAsJsonObject("devices").getAsJsonObject(id)

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(EpochKeys.FEATURE, text("feature"))
        assertEquals(2L, vectors.get("epoch").asLong)
    }

    @Test fun deviceKeysAndStatementsMatch() {
        for (id in listOf("dev-pc", "dev-tablet", "dev-removed")) {
            val device = device(id)
            val private = EpochKeys.x25519Private(hex(device.get("ed25519Seed").asString))
            assertEquals(device.get("x25519Private").asString, private.joinToString("") { "%02x".format(it) })
            assertEquals(device.get("x25519").asString,
                Base64.getUrlEncoder().withoutPadding().encodeToString(EpochKeys.x25519Public(private)))
            val statement = KeyStatement(vault, id, device.get("x25519").asString, device.get("signature").asString)
            assertArrayEquals(hex(device.get("statementHex").asString), statement.signingBytes())
            assertTrue(statement.valid(vault, device.get("ed25519Public").asString))
            // Another vault, another signer, or a changed key: not valid.
            assertFalse(statement.valid("other-vault", device.get("ed25519Public").asString))
            assertFalse(statement.valid(vault, device(if (id == "dev-pc") "dev-tablet" else "dev-pc").get("ed25519Public").asString))
            assertFalse(statement.copy(x25519 = device(if (id == "dev-pc") "dev-tablet" else "dev-pc").get("x25519").asString)
                .valid(vault, device.get("ed25519Public").asString))
            // Signing again gives the same statement (Ed25519 is deterministic).
            val seed = hex(device.get("ed25519Seed").asString)
            assertEquals(statement, KeyStatement.create(vault, id, EpochKeys.x25519Public(private)) { DeviceIdentityCrypto.sign(seed, it) })
        }
    }

    @Test fun onlyTheRightSurvivorOpensItsPackage() {
        val secret = hex(text("transportSecretHex"))
        val key = hex(text("packageKeyHex"))
        val packages = vectors.getAsJsonObject("packages")
        listOf("dev-pc", "dev-tablet").forEachIndexed { index, id ->
            val private = hex(device(id).get("x25519Private").asString)
            val sealed = b64(packages.get(id).asString)
            val opened = requireNotNull(EpochKeys.open(vault, 2, id, private, sealed))
            assertArrayEquals(secret, opened.first)
            assertArrayEquals(key, opened.second)
            // The same ephemeral key and nonce seal the same bytes.
            assertArrayEquals(sealed, EpochKeys.sealWith(vault, 2, id, b64(device(id).get("x25519").asString), secret, key,
                ByteArray(32) { (0x40 + index).toByte() }, ByteArray(12) { (0x50 + index).toByte() }))
        }
        val pc = b64(packages.get("dev-pc").asString)
        assertNull("the removed device", EpochKeys.open(vault, 2, "dev-pc", hex(device("dev-removed").get("x25519Private").asString), pc))
        assertNull("another survivor", EpochKeys.open(vault, 2, "dev-tablet", hex(device("dev-tablet").get("x25519Private").asString), pc))
        assertNull("another epoch", EpochKeys.open(vault, 3, "dev-pc", hex(device("dev-pc").get("x25519Private").asString), pc))
        val tampered = vectors.getAsJsonObject("tamperedPackage")
        assertNull("a changed package", EpochKeys.open(vault, 2, "dev-pc", hex(device("dev-pc").get("x25519Private").asString),
            b64(tampered.get("package").asString)))
        // A fresh seal opens.
        val fresh = EpochKeys.seal(vault, 4, "dev-pc", b64(device("dev-pc").get("x25519").asString), secret, key)
        assertArrayEquals(key, EpochKeys.open(vault, 4, "dev-pc", hex(device("dev-pc").get("x25519Private").asString), fresh)?.second)
    }

    /**
     * H3: a package gives S(N) and the package key P(N); the content key needs K(1) as well, which
     * only the open vault holds. So the X25519 key (usable while the phone is locked) never yields K(N).
     */
    @Test fun theContentKeyNeedsThePackageKeyAndTheVaultsEpochOneKey() {
        val first = hex(text("epoch1ContentKeyHex"))
        val packageKey = hex(text("packageKeyHex"))
        assertArrayEquals(hex(text("contentKeyHex")), EpochKeys.contentKey(first, vault, 2, packageKey))
        assertFalse(hex(text("contentKeyHex")).contentEquals(packageKey))
        // Another K(1), vault or epoch gives another key.
        assertFalse(hex(text("contentKeyHex")).contentEquals(EpochKeys.contentKey(ByteArray(32), vault, 2, packageKey)))
        assertFalse(hex(text("contentKeyHex")).contentEquals(EpochKeys.contentKey(first, "other", 2, packageKey)))
        assertFalse(hex(text("contentKeyHex")).contentEquals(EpochKeys.contentKey(first, vault, 3, packageKey)))
    }

    /** L3: only canonical base64url counts; a lenient decoder would read the changed text as the same bytes. */
    @Test fun packagesAndStatementsAreCanonicalBase64UrlOnly() {
        val case = vectors.getAsJsonObject("nonCanonicalPackage")
        val canonical = case.get("canonical").asString
        val lenient = case.get("lenient").asString
        assertArrayEquals(Base64.getUrlDecoder().decode(canonical), Base64.getUrlDecoder().decode(lenient))
        assertArrayEquals(Base64.getUrlDecoder().decode(canonical), EpochKeys.strictDecode(canonical))
        assertNull(EpochKeys.strictDecode(lenient))
        assertNull(EpochKeys.strictDecode("$canonical="))
        val packages = vectors.getAsJsonObject("packages").entrySet().associate { it.key to it.value.asString }
        val set = PackageSet(vault, 2, text("removeEventHash"), packages, text("setSignature"))
        assertTrue(set.valid(vault, text("managerPublic"), text("removeEventHash")))
        assertFalse(set.copy(packages = packages + ("dev-pc" to lenient)).valid(vault, text("managerPublic"), text("removeEventHash")))
        val pc = device("dev-pc")
        val statement = KeyStatement(vault, "dev-pc", pc.get("x25519").asString, pc.get("signature").asString)
        val x = pc.get("x25519").asString
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        val changed = x.dropLast(1) + alphabet[alphabet.indexOf(x.last()) or 1]
        assertFalse(statement.copy(x25519 = changed).valid(vault, pc.get("ed25519Public").asString))
    }

    /** L2: a statement with a low-order point is not valid, so it can't make a REMOVE fail; it only keeps the gate closed. */
    @Test fun aLowOrderStatementKeyIsRefused() {
        val seed = hex(device("dev-pc").get("ed25519Seed").asString)
        for (point in listOf(ByteArray(32), ByteArray(32).also { it[0] = 1 },
            hex("e0eb7a7c3b41b8ae1656e3faf19fc46ada098deb9c32b1fd866205165f49b800"))) {
            assertFalse(EpochKeys.validPoint(point))
            val bad = KeyStatement.create(vault, "dev-pc", point) { DeviceIdentityCrypto.sign(seed, it) }
            assertNull(bad.publicKey())
            assertFalse(bad.valid(vault, device("dev-pc").get("ed25519Public").asString))
        }
        assertTrue(EpochKeys.validPoint(b64(device("dev-pc").get("x25519").asString)))
    }

    /** A set with no packages is the manager's signed "no new keys at this epoch" marker. */
    @Test fun anEmptySetIsASignedMarker() {
        val marker = vectors.getAsJsonObject("markerSet")
        val set = PackageSet(vault, 3, marker.get("removeHash").asString, emptyMap(), marker.get("signature").asString)
        assertArrayEquals(hex(marker.get("setStatementHex").asString), set.signingBytes())
        assertTrue(set.valid(vault, text("managerPublic"), marker.get("removeHash").asString))
        assertFalse(set.valid(vault, device("dev-pc").get("ed25519Public").asString, marker.get("removeHash").asString))
        assertFalse(set.copy(epoch = 4).valid(vault, text("managerPublic"), marker.get("removeHash").asString))
    }

    @Test fun thePackageSetIsSignedByTheManagerAndBoundToTheRemove() {
        val packages = vectors.getAsJsonObject("packages").entrySet().associate { it.key to it.value.asString }
        val set = PackageSet(vault, 2, text("removeEventHash"), packages, text("setSignature"))
        assertArrayEquals(hex(text("setStatementHex")), set.signingBytes())
        assertTrue(set.valid(vault, text("managerPublic"), text("removeEventHash")))
        assertFalse(set.valid(vault, text("managerPublic"), "ab".repeat(32)))
        assertFalse(set.valid(vault, device("dev-pc").get("ed25519Public").asString, text("removeEventHash")))
        assertFalse(set.copy(epoch = 3).valid(vault, text("managerPublic"), text("removeEventHash")))
        assertFalse(set.copy(packages = packages - "dev-tablet").valid(vault, text("managerPublic"), text("removeEventHash")))
        val seed = hex(text("managerSeed"))
        assertEquals(set, PackageSet.sign(vault, 2, text("removeEventHash"), packages.mapValues { b64(it.value) }) {
            DeviceIdentityCrypto.sign(seed, it)
        })
    }

    @Test fun payloadNoncesCarryTheEpoch() {
        vectors.getAsJsonArray("nonces").map { it.asJsonObject }.forEach {
            val epoch = EpochKeys.epochOf(it.get("field").asString)
            if (it.get("valid").asBoolean) assertEquals(it.toString(), it.get("epoch").asLong, epoch)
            else assertNull(it.toString(), epoch)
        }
        vectors.getAsJsonArray("payloads").map { it.asJsonObject }.forEach { payload ->
            val epoch = payload.get("epoch").asLong
            val key = hex(text(if (epoch == 1L) "epoch1ContentKeyHex" else "contentKeyHex"))
            val nonce = EpochKeys.nonceBytes(payload.get("payloadNonce").asString)
            assertEquals(payload.get("payloadNonce").asString, EpochKeys.nonceField(epoch, nonce))
            val aad = EpochKeys.aad(payload.get("entityType").asString, vault, epoch, payload.get("mutationId").asString,
                payload.get("entityId").asString)
            assertEquals(payload.get("aad").asString, String(aad))
            val payloadKey = Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key, "HmacSHA256")); doFinal("nuvori-sync-payload-key-v1".toByteArray()) }
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad)
                doFinal(b64(payload.get("payloadCiphertext").asString))
            }
            assertEquals(payload.get("plaintext").asString, String(plain))
            // Through the shared decrypt path, with a keyring that holds both epochs.
            val keyring = SyncKeyring(mapOf(1L to hex(text("epoch1ContentKeyHex")), 2L to hex(text("contentKeyHex"))))
            val change = SyncChangeRecord(1, vault, "dev-pc", 1, GENESIS_HASH, payload.get("mutationId").asString,
                payload.get("entityType").asString, payload.get("entityId").asString, ChangeKind.UPSERT, 0,
                RecordVersion(mapOf("dev-pc" to 1L)), "2026-11-23T00:00:00Z", payload.get("payloadCiphertext").asString,
                payload.get("payloadNonce").asString, "sig", "hash")
            assertEquals(payload.get("plaintext").asString, String(SyncPayloadCrypto.decrypt(change, keyring)))
            if (epoch == 2L) {
                // Without K(2) the change waits instead of being set aside.
                assertTrue(runCatching { SyncPayloadCrypto.decrypt(change, SyncKeyring(mapOf(1L to hex(text("epoch1ContentKeyHex"))))) }
                    .exceptionOrNull() is MissingEpochKeyException)
                // K(1) does not open it, even as if it were K(2).
                assertTrue(runCatching { SyncPayloadCrypto.decrypt(change, SyncKeyring(mapOf(2L to hex(text("epoch1ContentKeyHex"))))) }
                    .exceptionOrNull() is javax.crypto.AEADBadTagException)
            }
            // A payload sealed by the shared writer opens again.
            val sealed = SyncPayloadCrypto.encrypt(keyring.writeEpoch(epoch), payload.get("entityType").asString, vault,
                "m2", "e2", "hello".toByteArray())
            assertEquals(epoch, EpochKeys.epochOf(sealed.nonce))
        }
    }

    @Test fun transportAndDriveSecretsFollowTheNewestRandomSecret() {
        val root = hex(text("rootTransportSecretHex"))
        val s2 = hex(text("transportSecretHex"))
        fun hexOf(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
        assertEquals(text("epoch2DerivedTransportHex"), hexOf(EpochKeys.transportSecret(root, vault, 2, emptyMap())))
        assertArrayEquals(s2, EpochKeys.transportSecret(root, vault, 2, mapOf(2L to s2)))
        assertEquals(text("epoch3AfterRotationTransportHex"), hexOf(EpochKeys.transportSecret(root, vault, 3, mapOf(2L to s2))))
        assertArrayEquals(root, EpochKeys.transportSecret(root, vault, 1, mapOf(2L to s2)))
        vectors.getAsJsonArray("cloud").map { it.asJsonObject }.forEach { cloud ->
            val epoch = cloud.get("epoch").asLong
            val (_, secret) = EpochKeys.driveSecret(root, epoch, mapOf(2L to s2))
            val keys = requireNotNull(CloudKeys.derive(secret, vault))
            assertEquals(cloud.get("cloudKeyHex").asString, keys.keyHex())
            assertEquals(cloud.get("prefix").asString, keys.prefix)
        }
        val old = requireNotNull(CloudKeys.derive(root, vault))
        val file = vectors.getAsJsonObject("packageFile")
        assertEquals(file.get("name").asString, old.packageName(2, "dev-pc"))
        assertEquals(CloudName.Packages(2, old.deviceTag("dev-pc")), old.parse(file.get("name").asString))
        assertEquals(file.get("sealedHex").asString, hexOf(old.sealWithNonce(file.get("name").asString,
            file.get("bodyJson").asString.toByteArray(), hex(file.get("envelopeNonceHex").asString))))
        // The body is a whole package set, as both apps parse it.
        val parsed = com.google.gson.Gson().fromJson(file.get("bodyJson").asString, PackageSet::class.java)
        assertTrue(parsed.valid(vault, text("managerPublic"), text("removeEventHash")))
        val new = requireNotNull(CloudKeys.derive(s2, vault))
        assertEquals(text("statementFileName"), new.statementName("dev-pc"))
        assertEquals(CloudName.Statement(new.deviceTag("dev-pc")), new.parse(text("statementFileName")))
    }
}
