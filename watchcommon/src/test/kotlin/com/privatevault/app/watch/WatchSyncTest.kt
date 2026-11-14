package com.privatevault.app.watch

import com.privatevault.app.security.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchSyncTest {
    private val account = WatchAccount("id-1", "Example", "me@example.com", "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", "SHA1", 8, 30)
    private val key = WatchSync.keyFromVault(ByteArray(32) { it.toByte() })

    @Test fun encryptedSnapshotRoundTripsAndGeneratesOfflineCode() {
        val original = WatchSnapshot("vault-1", listOf(account))
        val plaintext = WatchSync.encode(original)
        val payload = WatchSync.encrypt(key, plaintext)
        assertFalse(payload.toString(Charsets.UTF_8).contains(account.secret))
        assertEquals(original, WatchSync.decode(WatchSync.decrypt(key, payload)))
        assertEquals("94287082", Totp.code(account.secret, account.algorithm, account.digits, account.period, 59))
    }

    @Test fun appearancePathAndParser() {
        assertEquals("/nuvori/watch-appearance/v1", WatchSync.APPEARANCE_PATH)
        assertEquals("rose", parseWatchAppearance("""{"accent":"rose"}""".toByteArray()))
        assertEquals("deep-blue2", parseWatchAppearance(encodeWatchAppearance("deep-blue2")))
        assertEquals(null, parseWatchAppearance("""{"accent":"Rose"}""".toByteArray()))
        assertEquals(null, parseWatchAppearance("""{"accent":"1red"}""".toByteArray()))
        assertEquals(null, parseWatchAppearance("""{"accent":"${"a".repeat(33)}"}""".toByteArray()))
        assertEquals(null, parseWatchAppearance("""{"other":"rose"}""".toByteArray()))
        assertEquals(null, parseWatchAppearance("""{"accent":5}""".toByteArray()))
        assertEquals(null, parseWatchAppearance("not json".toByteArray()))
        assertEquals(null, parseWatchAppearance(ByteArray(0)))
    }

    @Test fun tamperingOrWrongVaultKeyFails() {
        val payload = WatchSync.encrypt(key, WatchSync.encode(WatchSnapshot("vault-1", listOf(account))))
        payload[payload.lastIndex] = (payload.last().toInt() xor 1).toByte()
        assertTrue(runCatching { WatchSync.decrypt(key, payload) }.isFailure)
        val clean = WatchSync.encrypt(key, WatchSync.encode(WatchSnapshot("vault-1", listOf(account))))
        assertTrue(runCatching { WatchSync.decrypt(WatchSync.keyFromVault(ByteArray(32)), clean) }.isFailure)
    }

    @Test fun randomPairingKeyIsIndependentOfTheLegacyKey() {
        val vaultKey = ByteArray(32) { it.toByte() }
        val pairingKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        val payload = WatchSync.encrypt(pairingKey, WatchSync.encode(WatchSnapshot("vault-1", listOf(account))))
        assertEquals("vault-1", WatchSync.decode(WatchSync.decrypt(pairingKey, payload)).vaultId)
        assertTrue(runCatching { WatchSync.decrypt(WatchSync.keyFromVault(vaultKey), payload) }.isFailure)
    }

    @Test fun fullReplacementCanRemoveEveryAccount() {
        val removed = WatchSnapshot("vault-1", emptyList())
        assertEquals(removed, WatchSync.decode(WatchSync.encode(removed)))
        assertTrue(runCatching { WatchSync.encode(WatchSnapshot("vault-1", listOf(account, account))) }.isFailure)
    }
}
