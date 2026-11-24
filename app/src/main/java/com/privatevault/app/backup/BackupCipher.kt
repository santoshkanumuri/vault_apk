package com.privatevault.app.backup

import com.google.crypto.tink.BinaryKeysetReader
import com.google.crypto.tink.BinaryKeysetWriter
import com.google.crypto.tink.CleartextKeysetHandle
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.StreamingAead
import com.google.crypto.tink.config.TinkConfig
import com.privatevault.app.security.KdfParams
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
 * The `.pvault` envelope. Format `PVLT0002` (written since the timed Argon2id settings):
 *
 * ```
 * "PVLT0002" | memory KiB, iterations, parallelism (3 x int32) | salt (16) | nonce (12)
 *            | wrapped length (int32) | wrapped keyset | Tink streaming AEAD body
 * ```
 *
 * Format `PVLT0001` (2.3.1 and earlier) has no settings after the magic and always used
 * [KdfParams.LEGACY]. Both formats open.
 *
 * The wrapped keyset is a Tink `AES128_GCM_HKDF_1MB` keyset, sealed with AES-256-GCM under
 * Argon2id(password, salt). The associated data is everything before the salt: the magic, and in
 * format 2 the settings too, so a changed setting cannot go unnoticed. The body is encrypted with
 * that keyset and the associated data `private-vault-backup-v1`.
 *
 * A [Key] holds one header and its keyset. A manual export makes a new key each time. Google Drive
 * backups make one key when they are turned on and reuse it, so a scheduled backup needs no
 * password: every Tink stream picks its own random salt and nonce prefix, so reusing the keyset is
 * safe, and every file still opens with the password and [decrypt].
 */
internal object BackupCipher {
    private val MAGIC_V1 = "PVLT0001".toByteArray(Charsets.US_ASCII)
    private val MAGIC = "PVLT0002".toByteArray(Charsets.US_ASCII)
    private const val SETTINGS_BYTES = 12
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

    /**
     * Makes a new keyset and wraps it with [password] under [kdf]: the vault's own timed settings,
     * so the file costs an attacker as much per guess as the vault. Slow on purpose (Argon2id);
     * call off the main thread.
     */
    fun create(password: CharArray, kdf: KdfParams, random: SecureRandom = SecureRandom()): Key {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH) { "The password is too short" }
        val keyset = KeysetHandle.generateNew(KeyTemplates.get(KEY_TEMPLATE))
        val serialized = ByteArrayOutputStream().also {
            CleartextKeysetHandle.write(keyset, BinaryKeysetWriter.withOutputStream(it))
        }.toByteArray()
        return Key(wrapKeyset(serialized, password, kdf, random), serialized)
    }

    /**
     * The same keyset under new settings: a backup key from 2.3.1 or earlier moves to [kdf]. The
     * files already made with [key] keep opening, because each file carries its own header.
     */
    fun rewrap(key: Key, password: CharArray, kdf: KdfParams, random: SecureRandom = SecureRandom()): Key {
        val keyset = key.keyset().copyOf()
        return Key(wrapKeyset(keyset, password, kdf, random), keyset)
    }

    /** The settings that [key] was wrapped with. */
    fun settings(key: Key): KdfParams = readHeader(DataInputStream(key.header.inputStream())).kdf

    /** True when [password] opens the header of [key]. Slow (Argon2id). */
    fun opens(key: Key, password: CharArray): Boolean =
        runCatching { unwrapKeyset(readHeader(DataInputStream(key.header.inputStream())), password).fill(0) }.isSuccess

    private fun wrapKeyset(serialized: ByteArray, password: CharArray, kdf: KdfParams, random: SecureRandom): ByteArray {
        require(kdf.valid) { "Invalid key settings" }
        val prefix = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                write(MAGIC); writeInt(kdf.memoryKib); writeInt(kdf.iterations); writeInt(kdf.parallelism); flush()
            }
        }.toByteArray()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val wrappingKey = PasswordCrypto.derive(password, salt, kdf)
        val wrapped = try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(prefix)
                doFinal(serialized)
            }
        } catch (failure: Exception) {
            serialized.fill(0)
            throw failure
        } finally {
            wrappingKey.fill(0)
        }
        return ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).apply {
                write(prefix); write(salt); write(nonce); writeInt(wrapped.size); write(wrapped); flush()
            }
        }.toByteArray()
    }

    private class Header(val prefix: ByteArray, val kdf: KdfParams, val salt: ByteArray, val nonce: ByteArray, val wrapped: ByteArray)

    /** Reads either header format. Settings outside the limits are refused before any key derivation. */
    private fun readHeader(data: DataInputStream): Header {
        val magic = ByteArray(MAGIC.size).also(data::readFully)
        val (prefix, kdf) = when {
            magic.contentEquals(MAGIC_V1) -> magic to KdfParams.LEGACY
            magic.contentEquals(MAGIC) -> {
                val settings = ByteArray(SETTINGS_BYTES).also(data::readFully)
                val values = ByteBuffer.wrap(settings)
                val kdf = KdfParams(values.int, values.int, values.int)
                require(kdf.valid) { "Invalid backup header" }
                (magic + settings) to kdf
            }
            else -> throw IllegalArgumentException("Unsupported backup format. Update Nuvori to open it.")
        }
        val salt = ByteArray(SALT_BYTES).also(data::readFully)
        val nonce = ByteArray(NONCE_BYTES).also(data::readFully)
        val wrappedSize = data.readInt()
        require(wrappedSize in 1..MAX_WRAPPED_KEY) { "Invalid backup header" }
        val wrapped = ByteArray(wrappedSize).also(data::readFully)
        return Header(prefix, kdf, salt, nonce, wrapped)
    }

    private fun unwrapKeyset(header: Header, password: CharArray): ByteArray {
        val wrappingKey = PasswordCrypto.derive(password, header.salt, header.kdf)
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(wrappingKey, "AES"), GCMParameterSpec(128, header.nonce))
                updateAAD(header.prefix)
                doFinal(header.wrapped)
            }
        } finally { wrappingKey.fill(0) }
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
        val serialized = unwrapKeyset(readHeader(data), password)
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
        require(headerSize in MAGIC.size + SALT_BYTES + NONCE_BYTES + 5..MAX_WRAPPED_KEY + 64 + SETTINGS_BYTES &&
            headerSize < bytes.size - 4) { "Invalid backup key" }
        val header = ByteArray(headerSize).also(buffer::get)
        val magic = header.copyOfRange(0, MAGIC.size)
        require(magic.contentEquals(MAGIC) || magic.contentEquals(MAGIC_V1)) { "Invalid backup key" }
        val keyset = ByteArray(buffer.remaining()).also(buffer::get)
        return Key(header, keyset)
    }
}
