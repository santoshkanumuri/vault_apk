package com.privatevault.app.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Security review L-10: photos use a key derived from the vault key, and 2.3.x photos still open. */
class PhotoCipherTest {
    private val vaultKey = ByteArray(32).also(SecureRandom()::nextBytes)
    private val photo = ByteArray(200_000).also(SecureRandom()::nextBytes)

    private fun save(plain: ByteArray, key: ByteArray = vaultKey): ByteArray =
        ByteArrayOutputStream().also { out -> PhotoCipher.encrypting(out, key).use { it.write(plain) } }.toByteArray()

    private fun open(file: ByteArray, key: ByteArray = vaultKey): ByteArray =
        PhotoCipher.decrypting(ByteArrayInputStream(file), key).use { it.readBytes() }

    @Test fun aNewPhotoRoundTripsAndStartsWithTheVersion2Header() {
        val file = save(photo)
        assertTrue(PhotoCipher.isCurrent(file))
        assertArrayEquals(photo, open(file))
    }

    @Test fun aNewPhotoIsNotEncryptedWithTheVaultKeyItself() {
        val file = save(photo)
        val nonce = file.copyOfRange(8, 20)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, nonce))
        cipher.updateAAD(PhotoCipher.MAGIC)
        assertTrue(runCatching { cipher.doFinal(file.copyOfRange(20, file.size)) }.isFailure)
        assertFalse(PhotoCipher.photoKey(vaultKey).contentEquals(vaultKey))
    }

    @Test fun aVersion1PhotoFrom233StillOpens() {
        val legacy = PhotoCipher.legacyForTest(photo, vaultKey)
        assertFalse(PhotoCipher.isCurrent(legacy))
        assertArrayEquals(photo, open(legacy))
    }

    @Test fun theHeaderIsAuthenticated() {
        val file = save(photo)
        // A version 2 body under a stripped header must not open as version 1.
        val stripped = file.copyOfRange(8, file.size)
        assertTrue(runCatching { open(stripped) }.isFailure)
        val tampered = file.copyOf().also { it[file.size - 1] = (it.last().toInt() xor 1).toByte() }
        assertTrue(runCatching { open(tampered) }.isFailure)
    }

    @Test fun anotherVaultKeyCannotOpenIt() {
        val other = ByteArray(32).also(SecureRandom()::nextBytes)
        try { open(save(photo), other); fail("opened with another key") } catch (_: Exception) { }
    }

    @Test fun thePhotoKeyIsStableSoSavedPhotosKeepOpening() {
        assertArrayEquals(PhotoCipher.photoKey(vaultKey), PhotoCipher.photoKey(vaultKey.copyOf()))
    }
}
