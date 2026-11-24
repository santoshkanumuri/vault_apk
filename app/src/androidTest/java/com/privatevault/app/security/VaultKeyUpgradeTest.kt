package com.privatevault.app.security

import android.content.Context
import androidx.core.content.edit
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.SecureRandom

/**
 * The vault key file on a real device: a 2.3.1 vault (format 1) opens with its password and is
 * saved again with timed Argon2id settings and the Keystore layer (format 3). Uses its own
 * preferences file and Keystore alias, never the app's vault.
 */
class VaultKeyUpgradeTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "vault_bootstrap_upgrade_test"
    private val password = "synthetic-test-password".toCharArray()
    private val hardware = KeystoreVaultKeyHardware("nuvori_vault_key_wrap_test")
    private fun manager(targetMillis: Long = 300) = VaultKeyManager(context, targetMillis, name, hardware)
    private val prefs get() = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before @After fun clear() {
        prefs.edit(commit = true) { clear() }
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("nuvori_vault_key_wrap_test")
    }

    /** Writes the vault key exactly as 2.3.1 and earlier did: format 1, no Argon2id settings. */
    private fun writeLegacyVault(): ByteArray {
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val wrapped = PasswordCrypto.wrap(key, password, salt)
        val encode = { bytes: ByteArray -> android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) }
        prefs.edit(commit = true) {
            putString("salt", encode(salt))
            putString("nonce", encode(wrapped.nonce))
            putString("wrapped_key", encode(wrapped.ciphertext))
            putInt("format", 1)
        }
        return key
    }

    @Test fun aFormat1VaultOpensAndIsSavedAgainWithTimedSettings() {
        val key = writeLegacyVault()
        val oldSalt = prefs.getString("salt", null)

        assertThrows(Exception::class.java) { manager().unlock("wrong-test-password".toCharArray()) }
        assertEquals("a wrong password changes nothing", 1, manager().format)

        assertArrayEquals(key, manager().unlock(password))
        assertEquals(3, manager().format)
        val kdf = manager().kdf
        assertTrue(kdf.toString(), kdf.valid)
        assertNotEquals(oldSalt, prefs.getString("salt", null))
        // The same vault key is inside, so the database, backups and biometric sessions keep working.
        assertArrayEquals(key, manager().unlock(password))
        android.util.Log.i("VaultKeyUpgradeTest", "upgraded to $kdf")
    }

    @Test fun aNewVaultTimesItsSettingsOnThisDevice() {
        val key = manager().create(password)
        val kdf = manager().kdf
        assertTrue(kdf.toString(), kdf.valid)
        val started = System.nanoTime()
        assertArrayEquals(key, manager().unlock(password))
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        android.util.Log.i("VaultKeyUpgradeTest", "new vault $kdf: unlock took $tookMillis ms for a 300 ms target")
        if (kdf.iterations in (KdfParams.LEGACY.iterations + 1) until KdfParams.MAX_ITERATIONS) {
            assertTrue("$tookMillis ms", tookMillis in 100L..900L)
        }
    }

    @Test fun aChangedPasswordKeepsTheKeyAndTimesNewSettings() {
        val key = manager().create(password)
        val replacement = "another-synthetic-password".toCharArray()
        manager().changePassword(password, replacement)
        assertArrayEquals(key, manager().unlock(replacement))
        assertThrows(Exception::class.java) { manager().unlock(password) }
        assertEquals(3, manager().format)
    }

    @Test fun aFormat2VaultGainsTheKeystoreLayerOnItsNextUnlock() {
        val key = manager().create(password)
        // Strip the Keystore layer, as a 2.3.3 phone saved it.
        val noHardware = VaultKeyManager(context, 300, name, hardware = null)
        noHardware.changePassword(password, password)
        assertEquals(2, noHardware.format)
        val salt = prefs.getString("salt", null)

        assertArrayEquals(key, manager().unlock(password))
        assertEquals(3, manager().format)
        assertEquals("no extra Argon2id run", salt, prefs.getString("salt", null))
        // The stored bytes alone no longer open with the password.
        assertThrows(VaultKeyUnavailableException::class.java) { noHardware.unlock(password) }
        assertArrayEquals(key, manager().unlock(password))
    }

    @Test fun aLostKeystoreKeyIsNeverReportedAsAWrongPassword() {
        manager().create(password)
        assertEquals(3, manager().format)
        java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry("nuvori_vault_key_wrap_test")
        assertThrows(VaultKeyUnavailableException::class.java) { manager().unlock(password) }
        assertEquals("nothing is rewritten", 3, manager().format)
    }

    @Test fun damagedOrNewerSettingsAreRefusedBeforeAnyKeyDerivation() {
        manager().create(password)
        prefs.edit(commit = true) { putInt("kdf_memory_kib", 4_194_304) }
        assertThrows(IllegalStateException::class.java) { manager().unlock(password) }
        prefs.edit(commit = true) { putInt("kdf_memory_kib", 65_536); putInt("kdf_iterations", 1) }
        assertThrows(IllegalStateException::class.java) { manager().unlock(password) }
        prefs.edit(commit = true) { putInt("format", 4) }
        assertThrows(UnsupportedVaultFormatException::class.java) { manager().unlock(password) }
    }
}
