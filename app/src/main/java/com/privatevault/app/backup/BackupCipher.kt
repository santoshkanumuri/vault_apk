package com.privatevault.app.backup

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.config.TinkConfig
import com.privatevault.app.security.PasswordCrypto
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The `.pvault` envelope (format `PVLT0001`):
 *
 * ```
 * "PVLT0001" | salt (16) | nonce (12) | wrapped length (int32) | wrapped keyset | Tink streaming AEAD body
 * ```
 *
 * The wrapped keyset is a Tink `AES128_GCM_HKDF_1MB` keyset, sealed with AES-256-GCM under
 * Argon2id(password, salt) with the magic as associated data. The body is encrypted with that
 * keyset and the associated data `private-vault-backup-v1`.
 *
 * A [Key] holds one header and its keyset. A manual export makes a new key each time. Google Drive
 * backups make one key when they are turned on and reuse it, so a scheduled backup needs no
 * password: every Tink stream picks its own random salt and nonce prefix, so reusing the keyset is
 * safe, and every file still opens with the password and [decrypt].
 */
internal object BackupCipher {
    private val MAGIC = "PVLT0001".toByteArray(Charsets.US_ASCII)
    private val ASSOCIATED_DATA = "private-vault-backup-v1".toByteArray()
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val MAX_WRAPPED_KEY = 128 * 1024
    private const val KEY_TEMPLATE = "AES128_GCM_HKDF_1MB"

    init { TinkConfig.register() }

    /** One backup key: the file header and the plain serialized keyset. [close] clears the keyset. */
    class Key internal constructor(val header: ByteArray, keyset: ByteArray) : AutoCloseable {
        private val secret = keyset
        internal fun keyset(): ByteArray = secret
        override fun close() { secret.fill(0) }
    }

    /** Makes a new keyset and wraps it with [password]. Slow on purpose (Argon2id); call off the main thread. */
    fun create(password: CharArray, random: SecureRandom = SecureRandom()): Key {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH) { "The password is too short" }
        val keyset = KeysetHandle.generateNew(KeyTemplates.get(KEY_TEMPLATE))
        val serialized = ByteArrayOutputStream().also {
            CleartextKeysetHandle.write(keyset, BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val wrappingKey = PasswordCrypto.derive(password, salt)
        val wrapped = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
                doFinal(serialized)
            }
        } catch (failure: Exception) {
            serialized.fill(0)
            throw failure
        } finally {
            wrappingKey.fill(0)
        }
        val header = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                write(MAGIC); write(salt); write(nonce); writeInt(wrapped.size); write(wrapped); flush()
            }
        }.toByteArray()
        return Key(header, serialized)
    }

    /**
     * Writes the header of [key] to [output] and returns the stream for the backup body. Closing
     * the returned stream finishes the last authenticated segment and closes [output].
     */
    fun encrypt(output: OutputStream, key: Key): OutputStream {
        output.write(key.header)
        val handle = CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(key.keyset()))
        return handle.getPrimitive(StreamingAead::class.java).newEncryptingStream(output, ASSOCIATED_DATA)
    }

    /**
     * Reads the header from [input], opens the keyset with [password], and returns the decrypted
     * body. A wrong password or a changed header fails here; a changed body fails while reading.
     */
    fun decrypt(input: InputStream, password: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        require(magic.contentEquals(MAGIC)) { "Unsupported backup format" }
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
        val wrappedSize = data.readInt()
        require(wrappedSize in 1..MAX_WRAPPED_KEY) { "Invalid backup header" }
        val wrapped = ByteArray(wrappedSize).also(data::readFully)
        val wrappingKey = PasswordCrypto.derive(password, salt)
        val serialized = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(MAGIC)
                doFinal(wrapped)
            }
        } finally { wrappingKey.fill(0) }
        val keyset = try {
            CleartextKeysetHandle.read(BinaryKeysetReader.withBytes(serialized))
        } finally { serialized.fill(0) }
        return keyset.getPrimitive(StreamingAead::class.java).newDecryptingStream(data, ASSOCIATED_DATA)
    }

    /** `header length (int32) | header | keyset`, for [DriveBackupKeyFile]. The caller encrypts it. */
    fun encode(key: Key): ByteArray {
        val keyset = key.keyset()
        return ByteBuffer.allocate(4 + key.header.size + keyset.size)
            .putInt(key.header.size).put(key.header).put(keyset).array()
    }

    fun decode(bytes: ByteArray): Key {
        val buffer = ByteBuffer.wrap(bytes)
        val headerSize = buffer.int
        require(headerSize in MAGIC.size + SALT_BYTES + NONCE_BYTES + 5..MAX_WRAPPED_KEY + 64 &&
            headerSize < bytes.size - 4) { "Invalid backup key" }
        val header = ByteArray(headerSize).also(buffer::get)
        require(header.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "Invalid backup key" }
        val keyset = ByteArray(buffer.remaining()).also(buffer::get)
        return Key(header, keyset)
    }
}
