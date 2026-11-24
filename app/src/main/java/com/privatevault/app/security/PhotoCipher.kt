package com.privatevault.app.security

import com.google.crypto.tink.subtle.Hkdf
import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The file format of a photo saved on this phone (security review L-10).
 *
 * - Version 1 (2.3.x and earlier): a 12-byte nonce, then AES-256-GCM under the vault key itself,
 *   the same key that opens the SQLCipher database.
 * - Version 2: the 8 bytes [MAGIC], a 12-byte nonce, then AES-256-GCM under a photo key derived from
 *   the vault key with HKDF-SHA256, with [MAGIC] as associated data. A photo key can never open the
 *   database, and the database key is never used to encrypt attacker-chosen bytes.
 *
 * New photos are always saved as version 2. Version 1 files still open, and [EncryptedPhotoStore]
 * saves them again as version 2 after an unlock. Photos never leave the phone in this format: sync
 * and backups decrypt them first, so other devices and older backups are not affected.
 */
internal object PhotoCipher {
    val MAGIC: ByteArray = "NUVPHOT2".toByteArray(Charsets.US_ASCII)
    private const val NONCE_BYTES = 12
    private const val INFO = "nuvori-photo-key-v2"

    /** The version 2 photo key. Wipe it after use. */
    fun photoKey(vaultKey: ByteArray): ByteArray {
        require(vaultKey.size == 32) { "Invalid vault key" }
        return Hkdf.computeHkdf("HMACSHA256", vaultKey, ByteArray(0), INFO.toByteArray(Charsets.US_ASCII), 32)
    }

    /** Starts a version 2 file on [output]. Closing the returned stream finishes it. */
    fun encrypting(output: OutputStream, vaultKey: ByteArray, random: SecureRandom = SecureRandom()): OutputStream {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val key = photoKey(vaultKey)
        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
            }
        } finally { key.fill(0) }
        output.write(MAGIC)
        output.write(nonce)
        return CipherOutputStream(output, cipher)
    }

    /** Opens a version 1 or version 2 file. The caller closes the returned stream, which closes [input]. */
    fun decrypting(input: InputStream, vaultKey: ByteArray): InputStream {
        val data = DataInputStream(input)
        val head = ByteArray(MAGIC.size)
        data.readFully(head)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        if (head.contentEquals(MAGIC)) {
            val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
            val key = photoKey(vaultKey)
            try { cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)) } finally { key.fill(0) }
            cipher.updateAAD(MAGIC)
        } else {
            // Version 1: the 8 bytes already read are the start of the nonce.
            val nonce = head.copyOf(NONCE_BYTES)
            data.readFully(nonce, MAGIC.size, NONCE_BYTES - MAGIC.size)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, nonce))
        }
        return CipherInputStream(data, cipher)
    }

    /** True when [head] (the first bytes of a file) is already version 2. */
    fun isCurrent(head: ByteArray): Boolean = head.size >= MAGIC.size && head.copyOf(MAGIC.size).contentEquals(MAGIC)

    /**
     * Encrypts [plain] as version 1, as 2.3.x did. Only for tests of the upgrade path: Nuvori never
     * writes version 1 again.
     */
    internal fun legacyForTest(plain: ByteArray, vaultKey: ByteArray): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(vaultKey, "AES"), GCMParameterSpec(128, nonce))
        }
        return nonce + cipher.doFinal(plain)
    }
}
