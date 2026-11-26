package com.privatevault.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Security review M-04: the vault key file and its hardware layer, with a software stand-in for Keystore. */
class VaultKeyFileTest {
    private val password = "synthetic-test-password".toCharArray()

    private class MemoryRecords : VaultKeyRecords {
        val strings = mutableMapOf<String, String>()
        val ints = mutableMapOf<String, Int>()
        var commits = 0
        override fun string(name: String) = strings[name]
        override fun int(name: String, default: Int) = ints[name] ?: default
        override fun has(name: String) = name in strings || name in ints
        override fun commit(strings: Map<String, String>, ints: Map<String, Int>, remove: Set<String>) {
            commits++
            remove.forEach { this.strings.remove(it); this.ints.remove(it) }
            this.strings.putAll(strings)
            this.ints.putAll(ints)
        }
        fun snapshot() = strings.toMap() to ints.toMap()
    }

    /** AES-GCM with a key held in memory, like a Keystore key that can be lost or fail. */
    private class SoftwareHardware : VaultKeyHardware {
        var key: ByteArray? = null
        var failSeal = false
        var corruptSeal = false
        var openCalls = 0
        override fun seal(plain: ByteArray, aad: ByteArray): SealedBytes {
            check(!failSeal) { "no hardware" }
            val key = key ?: ByteArray(32).also(SecureRandom()::nextBytes).also { key = it }
            val iv = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            cipher.updateAAD(aad)
            val out = cipher.doFinal(plain)
            if (corruptSeal) out[0] = (out[0].toInt() xor 1).toByte()
            return SealedBytes(iv, out)
        }
        override fun open(sealed: SealedBytes, aad: ByteArray): ByteArray {
            openCalls++
            val key = key ?: throw VaultKeyUnavailableException()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, sealed.iv))
            cipher.updateAAD(aad)
            return cipher.doFinal(sealed.ciphertext)
        }
    }

    private fun file(records: VaultKeyRecords, hardware: VaultKeyHardware?) = VaultKeyFile(records, hardware, targetMillis = 0)

    private fun decode(value: String?) = java.util.Base64.getDecoder().decode(requireNotNull(value))

    /** What an attacker with a copy of the app's files can try offline: the password wrap alone. */
    private fun opensWithPasswordAlone(records: MemoryRecords): Boolean = runCatching {
        PasswordCrypto.unwrap(WrappedSecret(decode(records.strings["nonce"]), decode(records.strings["wrapped_key"])),
            password, decode(records.strings["salt"]), file(records, null).kdf)
    }.isSuccess

    /** A format 2 vault, exactly as 2.3.3 saved it. */
    private fun format2Vault(records: MemoryRecords): ByteArray = file(records, null).create(password)

    @Test fun aNewVaultIsSealedInHardwareAndTheFilesAloneCannotBeGuessedAgainst() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        val key = file(records, hardware).create(password)

        assertEquals(3, file(records, hardware).format)
        assertNotNull(records.strings["hw_iv"])
        assertFalse("the password wrap must be hidden behind the hardware key", opensWithPasswordAlone(records))
        assertArrayEquals(key, file(records, hardware).unlock(password))
    }

    @Test fun aWrongPasswordOnFormat3IsAnAuthenticationFailureNotAHardwareFailure() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        file(records, hardware).create(password)
        assertThrows(AEADBadTagException::class.java) { file(records, hardware).unlock("wrong-test-password".toCharArray()) }
    }

    @Test fun aFormat2VaultGainsTheHardwareLayerOnItsNextUnlockWithoutAnotherArgon2Run() {
        val records = MemoryRecords()
        val key = format2Vault(records)
        assertEquals(2, file(records, null).format)
        assertTrue(opensWithPasswordAlone(records))
        val salt = records.strings["salt"]
        val nonce = records.strings["nonce"]
        val kdf = file(records, null).kdf

        val hardware = SoftwareHardware()
        assertThrows(AEADBadTagException::class.java) { file(records, hardware).unlock("wrong-test-password".toCharArray()) }
        assertEquals("a wrong password changes nothing", 2, file(records, hardware).format)

        assertArrayEquals(key, file(records, hardware).unlock(password))
        assertEquals(3, file(records, hardware).format)
        assertEquals(salt, records.strings["salt"])
        assertEquals(nonce, records.strings["nonce"])
        assertEquals(kdf, file(records, hardware).kdf)
        assertFalse(opensWithPasswordAlone(records))
        assertArrayEquals("the same vault key, so the database and backups keep working", key, file(records, hardware).unlock(password))
    }

    @Test fun aFormat1VaultMovesStraightToFormat3() {
        val records = MemoryRecords()
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val wrapped = PasswordCrypto.wrap(key, password, salt)
        val encode = { bytes: ByteArray -> java.util.Base64.getEncoder().encodeToString(bytes) }
        records.commit(mapOf("salt" to encode(salt), "nonce" to encode(wrapped.nonce), "wrapped_key" to encode(wrapped.ciphertext)),
            mapOf("format" to 1), emptySet())

        val hardware = SoftwareHardware()
        assertArrayEquals(key, file(records, hardware).unlock(password))
        assertEquals(3, file(records, hardware).format)
        assertTrue(file(records, hardware).kdf.valid)
        assertArrayEquals(key, file(records, hardware).unlock(password))
    }

    @Test fun aPhoneWithoutUsableHardwareKeepsFormat2AndUnlocksAsBefore() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware().apply { failSeal = true }
        val key = file(records, hardware).create(password)
        assertEquals(2, file(records, hardware).format)
        assertNull(records.strings["hw_iv"])
        val commits = records.commits
        assertArrayEquals(key, file(records, hardware).unlock(password))
        assertEquals("a failed upgrade writes nothing", commits, records.commits)
        assertEquals(2, file(records, hardware).format)

        hardware.failSeal = false
        assertArrayEquals(key, file(records, hardware).unlock(password))
        assertEquals("the hardware layer is added once it works", 3, file(records, hardware).format)
    }

    @Test fun aHardwareLayerThatDoesNotOpenAgainIsNeverSaved() {
        val records = MemoryRecords()
        val key = format2Vault(records)
        val before = records.snapshot()
        val hardware = SoftwareHardware().apply { corruptSeal = true }
        assertArrayEquals(key, file(records, hardware).unlock(password))
        assertEquals(before, records.snapshot())
        assertEquals(2, file(records, hardware).format)
    }

    @Test fun aLostHardwareKeyIsReportedAsUnavailableAndNothingIsRewritten() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        file(records, hardware).create(password)
        val before = records.snapshot()
        hardware.key = null
        assertThrows(VaultKeyUnavailableException::class.java) { file(records, hardware).unlock(password) }
        assertThrows(VaultKeyUnavailableException::class.java) { file(records, null).unlock(password) }
        assertEquals(before, records.snapshot())
    }

    @Test fun aDamagedHardwareLayerIsReportedAsUnavailableNotAsAWrongPassword() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        file(records, hardware).create(password)
        val stored = decode(records.strings["wrapped_key"])
        stored[stored.size - 1] = (stored.last().toInt() xor 1).toByte()
        records.strings["wrapped_key"] = java.util.Base64.getEncoder().encodeToString(stored)
        assertThrows(VaultKeyUnavailableException::class.java) { file(records, hardware).unlock(password) }
        records.strings.remove("hw_iv")
        assertThrows(VaultKeyUnavailableException::class.java) { file(records, hardware).unlock(password) }
    }

    @Test fun aChangedPasswordKeepsTheKeyAndTheHardwareLayer() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        val key = file(records, hardware).create(password)
        val replacement = "another-synthetic-password".toCharArray()
        file(records, hardware).changePassword(password, replacement)
        assertEquals(3, file(records, hardware).format)
        assertArrayEquals(key, file(records, hardware).unlock(replacement))
        assertThrows(AEADBadTagException::class.java) { file(records, hardware).unlock(password) }
    }

    @Test fun aChangedPasswordWithoutHardwareDropsAStaleHardwareIv() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        val key = file(records, hardware).create(password)
        hardware.failSeal = true
        val replacement = "another-synthetic-password".toCharArray()
        file(records, hardware).changePassword(password, replacement)
        assertEquals(2, file(records, hardware).format)
        assertNull(records.strings["hw_iv"])
        assertArrayEquals(key, file(records, null).unlock(replacement))
    }

    @Test fun anAdoptedPasswordOpensTheSameKeyWithoutTheOldPassword() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        val key = file(records, hardware).create(password)
        val kdf = file(records, hardware).kdf
        val replacement = "the-managing-device-password".toCharArray()
        file(records, hardware).adoptPassword(key.copyOf(), replacement)
        assertEquals(3, file(records, hardware).format)
        assertEquals(kdf, file(records, hardware).kdf)
        assertArrayEquals(key, file(records, hardware).unlock(replacement))
        assertThrows(AEADBadTagException::class.java) { file(records, hardware).unlock(password) }
    }

    @Test fun aPasswordIsNeverAdoptedForAnotherKeyOrWhenItsWrapFails() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        val key = file(records, hardware).create(password)
        val before = records.snapshot()
        val replacement = "the-managing-device-password".toCharArray()
        assertThrows(IllegalArgumentException::class.java) { file(records, hardware).adoptPassword(ByteArray(16), replacement) }
        assertThrows(IllegalArgumentException::class.java) { file(records, hardware).adoptPassword(key, "short".toCharArray()) }
        assertThrows(IllegalStateException::class.java) { file(MemoryRecords(), hardware).adoptPassword(key, replacement) }
        assertEquals(before, records.snapshot())
        assertArrayEquals(key, file(records, hardware).unlock(password))
    }

    @Test fun aNewerFormatIsRefusedBeforeAnyKeyDerivation() {
        val records = MemoryRecords()
        val hardware = SoftwareHardware()
        file(records, hardware).create(password)
        records.ints["format"] = 4
        val opened = hardware.openCalls
        assertThrows(UnsupportedVaultFormatException::class.java) { file(records, hardware).unlock(password) }
        assertEquals(opened, hardware.openCalls)
    }
}
