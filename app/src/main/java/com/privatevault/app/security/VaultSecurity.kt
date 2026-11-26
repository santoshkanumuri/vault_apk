package com.privatevault.app.security

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.content.edit
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Argon2id settings that turn the master password into the key that wraps the vault key.
 *
 * Memory stays at 64 MiB on every device. The number of passes is timed on the device so that
 * one unlock takes about [TARGET_MILLIS], as KeePassXC does, and it never drops below the 2.3.1
 * setting ([LEGACY]). Nuvori for Windows uses the same rules (`KdfParams` in `vault.rs`).
 */
data class KdfParams(val memoryKib: Int, val iterations: Int, val parallelism: Int) {
    /** Stored settings stay within these limits, so a damaged file cannot freeze the unlock or weaken it. */
    val valid: Boolean
        get() = memoryKib == LEGACY.memoryKib && iterations in LEGACY.iterations..MAX_ITERATIONS &&
            parallelism == LEGACY.parallelism

    companion object {
        /** The fixed setting of 2.3.1 and earlier, and the lowest setting Nuvori saves. */
        val LEGACY = KdfParams(memoryKib = 65_536, iterations = 3, parallelism = 1)
        const val MAX_ITERATIONS = 64
        const val TARGET_MILLIS = 1_000L

        /** Whole passes for [targetNanos] when one pass takes [perPassNanos], between the legacy minimum and [MAX_ITERATIONS]. */
        fun iterationsFor(perPassNanos: Double, targetNanos: Double): Int {
            val wanted = if (perPassNanos > 0.0) Math.round(targetNanos / perPassNanos).toDouble() else MAX_ITERATIONS.toDouble()
            return wanted.coerceIn(LEGACY.iterations.toDouble(), MAX_ITERATIONS.toDouble()).toInt()
        }

        /** Passes that fill [targetMillis], given that [iterations] passes took [elapsedNanos] on this device. */
        fun forMeasured(elapsedNanos: Long, iterations: Int, targetMillis: Long = TARGET_MILLIS): KdfParams =
            LEGACY.copy(iterations = iterationsFor(elapsedNanos.toDouble() / iterations.coerceAtLeast(1), targetMillis * 1_000_000.0))
    }
}

object PasswordCrypto {
    const val MIN_PASSWORD_LENGTH = 12

    /**
     * Derives a 32-byte key. Encrypted backups keep [KdfParams.LEGACY], because Nuvori on another
     * device must open them with the same settings.
     */
    fun derive(password: CharArray, salt: ByteArray, kdf: KdfParams = KdfParams.LEGACY): ByteArray {
        val passwordBytes = utf8(password)
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(kdf.memoryKib)
                .withIterations(kdf.iterations)
                .withParallelism(kdf.parallelism)
                .build()
            ByteArray(32).also { output ->
                Argon2BytesGenerator().apply { init(params) }
                    .generateBytes(passwordBytes, output)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    /**
     * UTF-8 bytes of [password], the same bytes `String(password).toByteArray(UTF_8)` gives (a lone
     * surrogate becomes '?'), without making an immutable String that can't be wiped (security
     * review L-11). Wipe the result after use.
     */
    internal fun utf8(password: CharArray): ByteArray {
        val encoder = Charsets.UTF_8.newEncoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
        val buffer = encoder.encode(java.nio.CharBuffer.wrap(password))
        return try { ByteArray(buffer.remaining()).also(buffer::get) }
        finally { if (buffer.hasArray()) buffer.array().fill(0) }
    }

    /** Times [KdfParams.LEGACY] (3 passes) here, the same measurement as an unlock of a 2.3.1 vault. */
    fun calibrated(targetMillis: Long = KdfParams.TARGET_MILLIS): KdfParams {
        if (targetMillis <= 0) return KdfParams.LEGACY
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val started = System.nanoTime()
        derive("nuvori-kdf-calibration".toCharArray(), salt, KdfParams.LEGACY).fill(0)
        return KdfParams.forMeasured(System.nanoTime() - started, KdfParams.LEGACY.iterations, targetMillis)
    }

    fun wrap(plain: ByteArray, password: CharArray, salt: ByteArray, random: SecureRandom = SecureRandom(),
             kdf: KdfParams = KdfParams.LEGACY): WrappedSecret {
        val key = derive(password, salt, kdf)
        return try {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            WrappedSecret(nonce, cipher.doFinal(plain))
        } finally {
            key.fill(0)
        }
    }

    fun unwrap(wrapped: WrappedSecret, password: CharArray, salt: ByteArray, kdf: KdfParams = KdfParams.LEGACY): ByteArray {
        val key = derive(password, salt, kdf)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, wrapped.nonce))
            cipher.doFinal(wrapped.ciphertext)
        } finally {
            key.fill(0)
        }
    }
}

data class WrappedSecret(val nonce: ByteArray, val ciphertext: ByteArray)

/**
 * The Argon2id settings of this phone's vault key. Encrypted backups use them too, so a backup
 * file costs an attacker as much per guess as the vault.
 */
internal fun vaultKdf(context: Context): KdfParams =
    runCatching { VaultKeyManager(context).kdf }.getOrDefault(KdfParams.LEGACY)

/** A vault key file from a newer Nuvori. Never shown as a wrong password. */
class UnsupportedVaultFormatException(format: Int) :
    IllegalStateException("A newer version of Nuvori saved this vault. Update Nuvori to open it. (Format $format)")

/**
 * The phone's secure hardware could not open the vault key file (security review M-04). Never
 * shown or counted as a wrong password: retrying the password cannot help.
 */
class VaultKeyUnavailableException(cause: Throwable? = null) : IllegalStateException(
    "This phone's secure hardware could not open your vault. Restart the phone and try again. " +
        "If it keeps happening, restore your vault from a backup.", cause)

/** Bytes encrypted by [VaultKeyHardware], with the IV the hardware chose. */
class SealedBytes(val iv: ByteArray, val ciphertext: ByteArray)

/**
 * A key that never leaves this phone's secure hardware (security review M-04). The password-wrapped
 * vault key is encrypted again with it, so a copy of the app's files cannot be used to guess the
 * master password on another computer.
 */
internal interface VaultKeyHardware {
    /** Encrypts with the hardware key, creating the key first if there is none. Throws when no hardware key can be used. */
    fun seal(plain: ByteArray, aad: ByteArray): SealedBytes
    /** Decrypts with the existing hardware key. Never creates a key. */
    fun open(sealed: SealedBytes, aad: ByteArray): ByteArray
}

/**
 * An AES-256-GCM key in Android Keystore, in StrongBox where the phone has one and otherwise in the
 * trusted execution environment. It needs no user authentication and no unlocked screen, so a
 * change of screen lock or fingerprints never makes it unusable: losing it would lose the vault.
 */
internal class KeystoreVaultKeyHardware(private val alias: String = ALIAS) : VaultKeyHardware {
    override fun seal(plain: ByteArray, aad: ByteArray): SealedBytes {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, existingKey() ?: generate())
        cipher.updateAAD(aad)
        val ciphertext = cipher.doFinal(plain)
        return SealedBytes(cipher.iv, ciphertext)
    }

    override fun open(sealed: SealedBytes, aad: ByteArray): ByteArray {
        val key = existingKey() ?: throw VaultKeyUnavailableException()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.iv))
        cipher.updateAAD(aad)
        return cipher.doFinal(sealed.ciphertext)
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(alias, null) as? SecretKey

    private fun generate(): SecretKey {
        fun spec(strongBox: Boolean) = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .setIsStrongBoxBacked(strongBox)
            .build()
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        return try {
            generator.init(spec(strongBox = true))
            generator.generateKey()
        } catch (_: Exception) {
            // No StrongBox (StrongBoxUnavailableException) or no AES-256 there: use the TEE.
            runCatching { keyStore().deleteEntry(alias) }
            generator.init(spec(strongBox = false))
            generator.generateKey()
        }
    }

    companion object {
        const val ALIAS = "nuvori_vault_key_wrap_v1"
    }
}

/** Where the vault key file lives. [commit] writes every change at once, or none. */
internal interface VaultKeyRecords {
    fun string(name: String): String?
    fun int(name: String, default: Int): Int
    fun has(name: String): Boolean
    fun commit(strings: Map<String, String>, ints: Map<String, Int>, remove: Set<String>)
}

internal class PreferenceVaultKeyRecords(private val prefs: SharedPreferences) : VaultKeyRecords {
    override fun string(name: String): String? = prefs.getString(name, null)
    override fun int(name: String, default: Int): Int = prefs.getInt(name, default)
    override fun has(name: String): Boolean = prefs.contains(name)
    override fun commit(strings: Map<String, String>, ints: Map<String, Int>, remove: Set<String>) {
        val saved = prefs.edit().apply {
            remove.forEach(::remove)
            strings.forEach(::putString)
            ints.forEach(::putInt)
        }.commit()
        check(saved) { "Could not save the vault key" }
    }
}

/**
 * The vault key file: the vault key, wrapped with the master password.
 *
 * - Format 1 (2.3.1 and earlier) has no Argon2id settings and always used [KdfParams.LEGACY].
 * - Format 2 stores the settings.
 * - Format 3 (security review M-04) also encrypts the password-wrapped key with a key in the
 *   phone's secure hardware ([VaultKeyHardware]); `hw_iv` holds that layer's IV.
 *
 * The next unlock of an older vault saves it again in the newest format this phone supports. A
 * phone whose hardware key cannot be made keeps format 2. The hardware layer is checked by opening
 * it again before anything is saved, so a faulty Keystore can never leave an unopenable vault.
 * Encrypted backups keep a password-only wrap, so they still open on another device.
 */
internal class VaultKeyFile(
    private val records: VaultKeyRecords,
    private val hardware: VaultKeyHardware?,
    private val targetMillis: Long,
    private val random: SecureRandom = SecureRandom(),
) {
    val isInitialized: Boolean get() = records.has("salt")

    /** 1 for a 2.3.1 vault, 2 with timed settings, 3 with the hardware layer. */
    val format: Int get() = records.int("format", 1)

    /** The stored Argon2id settings. */
    val kdf: KdfParams
        get() = when (val format = format) {
            1 -> KdfParams.LEGACY
            TIMED, HARDWARE -> KdfParams(records.int("kdf_memory_kib", 0), records.int("kdf_iterations", 0), records.int("kdf_parallelism", 0))
                .also { check(it.valid) { "The vault key settings are damaged" } }
            else -> throw UnsupportedVaultFormatException(format)
        }

    fun create(password: CharArray): ByteArray {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        check(!isInitialized)
        val vaultKey = ByteArray(32).also(random::nextBytes)
        persistWrappedKey(vaultKey, password, PasswordCrypto.calibrated(targetMillis))
        return vaultKey
    }

    fun unlock(password: CharArray): ByteArray {
        val format = format
        val kdf = kdf
        val salt = decode(records.string("salt") ?: error("Vault is not initialized"))
        val nonce = decode(records.string("nonce") ?: error("Missing nonce"))
        val stored = decode(records.string("wrapped_key") ?: error("Missing wrapped key"))
        val inner = if (format == HARDWARE) openHardwareLayer(stored) else stored
        val started = System.nanoTime()
        // AEADBadTagException here, and only here, means a wrong password.
        val vaultKey = PasswordCrypto.unwrap(WrappedSecret(nonce, inner), password, salt, kdf)
        when (format) {
            // AES-GCM proved the password. Save the key again with settings timed by the run above,
            // so no extra benchmark runs. A failure keeps the old values, and the unlock still works.
            1 -> runCatching { persistWrappedKey(vaultKey, password, KdfParams.forMeasured(System.nanoTime() - started, kdf.iterations, targetMillis)) }
            // The same password wrap gains the hardware layer; no extra Argon2id run.
            TIMED -> runCatching { sealedInHardware(inner)?.let { saveHardwareLayer(it) } }
        }
        return vaultKey
    }

    fun changePassword(current: CharArray, replacement: CharArray) {
        require(replacement.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        val vaultKey = unlock(current)
        try { persistWrappedKey(vaultKey, replacement, PasswordCrypto.calibrated(targetMillis)) } finally { vaultKey.fill(0) }
    }

    /**
     * Saves [vaultKey] under [replacement] without the current password: the managing device of this phone's sync
     * group changed the master password, and [replacement] passed its password check (`MasterPasswordRules`).
     * [vaultKey] is the key of the open vault, from this session's unlock. The new wrap is opened again in memory
     * before anything is saved, so a wrong key or a fault can't leave an unopenable vault.
     */
    fun adoptPassword(vaultKey: ByteArray, replacement: CharArray) {
        require(vaultKey.size == 32 && replacement.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        check(isInitialized)
        // Keeps the timed settings (no benchmark); a 2.3.1 vault gets timed settings now.
        val settings = if (format == 1) PasswordCrypto.calibrated(targetMillis) else this.kdf
        persistWrappedKey(vaultKey, replacement, settings, verify = true)
    }

    private fun openHardwareLayer(stored: ByteArray): ByteArray {
        val iv = decode(records.string("hw_iv") ?: throw VaultKeyUnavailableException())
        val layer = hardware ?: throw VaultKeyUnavailableException()
        return try { layer.open(SealedBytes(iv, stored), HARDWARE_AAD) }
        catch (unavailable: VaultKeyUnavailableException) { throw unavailable }
        catch (failure: Exception) { throw VaultKeyUnavailableException(failure) }
    }

    /** The hardware layer over [inner], proven to open again, or null when this phone can't make one. */
    private fun sealedInHardware(inner: ByteArray): SealedBytes? {
        val layer = hardware ?: return null
        return runCatching {
            val sealed = layer.seal(inner, HARDWARE_AAD)
            val reopened = layer.open(SealedBytes(sealed.iv.copyOf(), sealed.ciphertext.copyOf()), HARDWARE_AAD)
            sealed.takeIf { java.security.MessageDigest.isEqual(reopened, inner) }
        }.getOrNull()
    }

    private fun saveHardwareLayer(sealed: SealedBytes) {
        records.commit(
            strings = mapOf("wrapped_key" to encode(sealed.ciphertext), "hw_iv" to encode(sealed.iv)),
            ints = mapOf("format" to HARDWARE),
            remove = emptySet(),
        )
    }

    private fun persistWrappedKey(vaultKey: ByteArray, password: CharArray, kdf: KdfParams, verify: Boolean = false) {
        check(kdf.valid)
        val salt = ByteArray(16).also(random::nextBytes)
        val wrapped = PasswordCrypto.wrap(vaultKey, password, salt, random, kdf)
        if (verify) {
            val reopened = PasswordCrypto.unwrap(WrappedSecret(wrapped.nonce.copyOf(), wrapped.ciphertext.copyOf()), password, salt, kdf)
            try { check(java.security.MessageDigest.isEqual(reopened, vaultKey)) { "The new password wrap did not open" } }
            finally { reopened.fill(0) }
        }
        val sealed = sealedInHardware(wrapped.ciphertext)
        val strings = mutableMapOf(
            "salt" to encode(salt),
            "nonce" to encode(wrapped.nonce),
            "wrapped_key" to encode(sealed?.ciphertext ?: wrapped.ciphertext),
        )
        sealed?.let { strings["hw_iv"] = encode(it.iv) }
        records.commit(
            strings = strings,
            ints = mapOf(
                "kdf_memory_kib" to kdf.memoryKib,
                "kdf_iterations" to kdf.iterations,
                "kdf_parallelism" to kdf.parallelism,
                "format" to if (sealed != null) HARDWARE else TIMED,
            ),
            remove = if (sealed != null) emptySet() else setOf("hw_iv"),
        )
    }

    private fun encode(value: ByteArray) = java.util.Base64.getEncoder().encodeToString(value)
    private fun decode(value: String) = java.util.Base64.getDecoder().decode(value)

    companion object {
        const val TIMED = 2
        const val HARDWARE = 3
        val HARDWARE_AAD = "nuvori-vault-key-v3".toByteArray(Charsets.US_ASCII)
    }
}

/** The vault key file in private preferences, with the hardware layer in Android Keystore. */
class VaultKeyManager internal constructor(
    context: Context,
    targetMillis: Long,
    preferences: String,
    hardware: VaultKeyHardware? = KeystoreVaultKeyHardware(),
) {
    constructor(context: Context) : this(context, KdfParams.TARGET_MILLIS, "vault_bootstrap")

    private val file = VaultKeyFile(
        PreferenceVaultKeyRecords(context.getSharedPreferences(preferences, Context.MODE_PRIVATE)), hardware, targetMillis)

    val isInitialized: Boolean get() = file.isInitialized

    /** 1 for a 2.3.1 vault, 2 with timed settings, 3 with the hardware layer. */
    internal val format: Int get() = file.format

    /** The stored Argon2id settings. */
    internal val kdf: KdfParams get() = file.kdf

    /** Slow on purpose (Argon2id, about one second): call off the main thread. */
    fun create(password: CharArray): ByteArray = file.create(password)

    /** Slow on purpose (Argon2id, about one second): call off the main thread. */
    fun unlock(password: CharArray): ByteArray = file.unlock(password)

    fun changePassword(current: CharArray, replacement: CharArray) = file.changePassword(current, replacement)

    /** Slow on purpose (two Argon2id runs): call off the main thread. See [VaultKeyFile.adoptPassword]. */
    fun adoptPassword(vaultKey: ByteArray, replacement: CharArray) = file.adoptPassword(vaultKey, replacement)
}

/**
 * Fingerprint unlock for a limited time. Security review L-04: each session gets its own Keystore
 * key, which Keystore itself refuses to use after the session's end and while the screen is locked,
 * so editing the app's files cannot stretch a session. The app's own checks (reboot, clock moved
 * back) stay as a second layer.
 */
class BiometricGate(context: Context) {
    private val alias = "nuvori_biometric_session_v2"
    private val legacyAlias = "private_vault_daily_gate"
    private val prefs = context.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private fun bootCount(): Int = android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    val hasValidDailySession: Boolean
        get() {
            val nowWall = System.currentTimeMillis()
            val valid = prefs.getInt("format", 0) == FORMAT && prefs.contains("wrapped_key") && biometricSessionValid(
                nowWall, android.os.SystemClock.elapsedRealtime(), bootCount(),
                prefs.getLong("created_wall", 0), prefs.getLong("created_elapsed", -1), prefs.getInt("boot_count", -1),
                prefs.getLong("last_seen_wall", 0), prefs.getLong("duration", 0)
            )
            if (!valid) clearDailySession()
            else prefs.edit(commit = true) { putLong("last_seen_wall", nowWall) }
            return valid
        }

    /** A new session key that Keystore lets work for [duration] from now, ready for the fingerprint prompt. */
    fun dailyEncryptionCipher(duration: Long): Cipher {
        require(duration in masterPasswordIntervals && duration > 0)
        val key = newSessionKey(System.currentTimeMillis() + duration)
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun enableDailySession(cipher: Cipher, vaultKey: ByteArray, duration: Long) {
        require(duration in masterPasswordIntervals && duration > 0)
        val boot = bootCount()
        check(boot >= 0) { "Cannot verify device reboot" }
        val nowWall = System.currentTimeMillis()
        prefs.edit(commit = true) {
            putString("wrapped_key", android.util.Base64.encodeToString(cipher.doFinal(vaultKey), android.util.Base64.NO_WRAP))
            putString("nonce", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            putLong("created_wall", nowWall)
            putLong("last_seen_wall", nowWall)
            putLong("created_elapsed", android.os.SystemClock.elapsedRealtime())
            putInt("boot_count", boot)
            putLong("duration", duration)
            putInt("format", FORMAT)
        }
    }

    fun dailyDecryptionCipher(): Cipher? {
        if (!hasValidDailySession) return null
        return runCatching {
            val nonce = android.util.Base64.decode(prefs.getString("nonce", null) ?: error("Missing biometric nonce"), android.util.Base64.NO_WRAP)
            val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as? SecretKey
                ?: error("Biometric session key missing")
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)) }
        }.onFailure { clearDailySession() }.getOrNull()
    }

    fun finishDailyUnlock(cipher: Cipher): ByteArray {
        check(hasValidDailySession) { "Biometric session expired" }
        val wrapped = android.util.Base64.decode(prefs.getString("wrapped_key", null) ?: error("Biometric session expired"), android.util.Base64.NO_WRAP)
        return cipher.doFinal(wrapped).also { require(it.size == 32) { "Invalid vault key" } }
    }

    fun clearDailySession() {
        prefs.edit(commit = true) { clear() }
        runCatching {
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.apply { deleteEntry(alias); deleteEntry(legacyAlias) }
        }
    }

    /** Replaces the session key. Keystore enforces [validUntilWall], fingerprint per use, and an unlocked screen. */
    private fun newSessionKey(validUntilWall: Long): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val end = java.util.Date(validUntilWall)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeyValidityForOriginationEnd(end)
                .setKeyValidityForConsumptionEnd(end)
                .setUnlockedDeviceRequired(true)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        /** 3 was 2.3.x (one long-lived key); 4 has a Keystore key per session. */
        const val FORMAT = 4
    }
}

class BiometricActionGate {
    private val alias = "private_vault_action_gate"

    fun encryptionCipher(): Cipher {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = (keyStore.getKey(alias, null) as? SecretKey) ?: generateKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setUserAuthenticationRequired(true)
                .apply {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                        setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                    } else {
                        @Suppress("DEPRECATION")
                        setUserAuthenticationValidityDurationSeconds(-1)
                    }
                }
                .setInvalidatedByBiometricEnrollment(true)
                .build()
        )
        return generator.generateKey()
    }
}
