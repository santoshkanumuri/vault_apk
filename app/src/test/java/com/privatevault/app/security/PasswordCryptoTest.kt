package com.privatevault.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class PasswordCryptoTest {
    @Test
    fun wrappedVaultKeyRoundTrips() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val password = "a long memorable test phrase".toCharArray()

        val wrapped = PasswordCrypto.wrap(secret, password, salt)
        val restored = PasswordCrypto.unwrap(wrapped, password, salt)

        assertArrayEquals(secret, restored)
        assertFalse(secret.contentEquals(wrapped.ciphertext))
    }

    @Test(expected = Exception::class)
    fun wrongPasswordCannotUnwrapVaultKey() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val wrapped = PasswordCrypto.wrap(secret, "correct horse battery staple".toCharArray(), salt)

        PasswordCrypto.unwrap(wrapped, "wrong password entirely".toCharArray(), salt)
    }

    @Test
    fun passesFillAboutOneSecondWithinTheLimits() {
        val second = 1_000_000_000.0
        // Same cases as `passes_fill_about_one_second_within_the_limits` in Nuvori for Windows.
        assertEquals(23, KdfParams.iterationsFor(44_000_000.0, second))
        assertEquals(3, KdfParams.iterationsFor(600_000_000.0, second))
        assertEquals(KdfParams.MAX_ITERATIONS, KdfParams.iterationsFor(1_000_000.0, second))
        assertEquals(KdfParams.MAX_ITERATIONS, KdfParams.iterationsFor(0.0, second))
        assertEquals(11, KdfParams.forMeasured(264_000_000L, 3).iterations)
        assertTrue(KdfParams.forMeasured(264_000_000L, 3).valid)
    }

    @Test
    fun storedSettingsOutsideTheLimitsAreInvalid() {
        assertTrue(KdfParams.LEGACY.valid)
        assertFalse(KdfParams.LEGACY.copy(memoryKib = 4_194_304).valid)
        assertFalse(KdfParams.LEGACY.copy(iterations = 1_000).valid)
        assertFalse(KdfParams.LEGACY.copy(iterations = 2).valid)
        assertFalse(KdfParams.LEGACY.copy(parallelism = 8).valid)
        assertFalse(KdfParams(0, 0, 0).valid)
    }

    @Test
    fun aKeyWrappedWithMorePassesOpensOnlyWithThoseSettings() {
        val secret = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val password = "a long memorable test phrase".toCharArray()
        val stronger = KdfParams.LEGACY.copy(iterations = 5)
        val wrapped = PasswordCrypto.wrap(secret, password, salt, kdf = stronger)
        assertArrayEquals(secret, PasswordCrypto.unwrap(wrapped, password, salt, stronger))
        assertThrows(Exception::class.java) { PasswordCrypto.unwrap(wrapped, password, salt, KdfParams.LEGACY) }
    }

    /** Real Argon2id runs on this machine: the timed setting must take about the target time. */
    @Test
    fun calibrationOnThisMachineMeetsTheTarget() {
        val target = 400L
        val kdf = PasswordCrypto.calibrated(target)
        assertTrue(kdf.toString(), kdf.valid)
        val started = System.nanoTime()
        PasswordCrypto.derive("synthetic-test-password".toCharArray(), ByteArray(16), kdf).fill(0)
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        println("calibrated $kdf: one derivation took $tookMillis ms for a $target ms target")
        if (kdf.iterations in (KdfParams.LEGACY.iterations + 1) until KdfParams.MAX_ITERATIONS) {
            assertTrue("$tookMillis ms for $target ms", tookMillis in (target / 3)..(target * 3))
        }
    }
}
