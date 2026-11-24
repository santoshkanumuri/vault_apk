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
        val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
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
 * The vault key, wrapped with the master password, in private preferences.
 *
 * Format 1 (2.3.1 and earlier) has no Argon2id settings and always used [KdfParams.LEGACY].
 * Format 2 stores the settings. The next unlock of a format 1 vault saves it again in format 2.
 */
class VaultKeyManager internal constructor(
    context: Context,
    private val targetMillis: Long,
    preferences: String,
) {
    constructor(context: Context) : this(context, KdfParams.TARGET_MILLIS, "vault_bootstrap")

    private val prefs: SharedPreferences = context.getSharedPreferences(preferences, Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val isInitialized: Boolean get() = prefs.contains("salt")

    /** 1 for a 2.3.1 vault, 2 after the upgrade. */
    internal val format: Int get() = prefs.getInt("format", 1)

    /** The stored Argon2id settings. */
    internal val kdf: KdfParams
        get() = when (val format = format) {
            1 -> KdfParams.LEGACY
            FORMAT -> KdfParams(prefs.getInt("kdf_memory_kib", 0), prefs.getInt("kdf_iterations", 0), prefs.getInt("kdf_parallelism", 0))
                .also { check(it.valid) { "The vault key settings are damaged" } }
            else -> throw UnsupportedVaultFormatException(format)
        }

    /** Slow on purpose (Argon2id, about one second): call off the main thread. */
    fun create(password: CharArray): ByteArray {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        check(!isInitialized)
        val vaultKey = ByteArray(32).also(random::nextBytes)
        persistWrappedKey(vaultKey, password, PasswordCrypto.calibrated(targetMillis))
        return vaultKey
    }

    /** Slow on purpose (Argon2id, about one second): call off the main thread. */
    fun unlock(password: CharArray): ByteArray {
        val format = format
        val kdf = kdf
        val salt = decode(prefs.getString("salt", null) ?: error("Vault is not initialized"))
        val wrapped = WrappedSecret(
            decode(prefs.getString("nonce", null) ?: error("Missing nonce")),
            decode(prefs.getString("wrapped_key", null) ?: error("Missing wrapped key"))
        )
        val started = System.nanoTime()
        val vaultKey = PasswordCrypto.unwrap(wrapped, password, salt, kdf)
        if (format == 1) {
            // AES-GCM proved the password. Save the key again with settings timed by the run above,
            // so no extra benchmark runs. A failure keeps the old values, and the unlock still works.
            runCatching { persistWrappedKey(vaultKey, password, KdfParams.forMeasured(System.nanoTime() - started, kdf.iterations, targetMillis)) }
        }
        return vaultKey
    }

    fun changePassword(current: CharArray, replacement: CharArray) {
        require(replacement.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        val vaultKey = unlock(current)
        try { persistWrappedKey(vaultKey, replacement, PasswordCrypto.calibrated(targetMillis)) } finally { vaultKey.fill(0) }
    }

    private fun persistWrappedKey(vaultKey: ByteArray, password: CharArray, kdf: KdfParams) {
        check(kdf.valid)
        val salt = ByteArray(16).also(random::nextBytes)
        val wrapped = PasswordCrypto.wrap(vaultKey, password, salt, random, kdf)
        prefs.edit(commit = true) {
            putString("salt", encode(salt))
            putString("nonce", encode(wrapped.nonce))
            putString("wrapped_key", encode(wrapped.ciphertext))
            putInt("kdf_memory_kib", kdf.memoryKib)
            putInt("kdf_iterations", kdf.iterations)
            putInt("kdf_parallelism", kdf.parallelism)
            putInt("format", FORMAT)
        }
    }

    private fun encode(value: ByteArray) = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    private fun decode(value: String) = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)

    private companion object {
        const val FORMAT = 2
    }
}

class BiometricGate(context: Context) {
    private val alias = "private_vault_daily_gate"
    private val prefs = context.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE)
    private val resolver = context.contentResolver
    private fun bootCount(): Int = android.provider.Settings.Global.getInt(resolver, android.provider.Settings.Global.BOOT_COUNT, -1)

    val hasValidDailySession: Boolean
        get() {
            val nowWall = System.currentTimeMillis()
            val valid = prefs.getInt("format", 0) == 3 && prefs.contains("wrapped_key") && biometricSessionValid(
                nowWall, android.os.SystemClock.elapsedRealtime(), bootCount(),
                prefs.getLong("created_wall", 0), prefs.getLong("created_elapsed", -1), prefs.getInt("boot_count", -1),
                prefs.getLong("last_seen_wall", 0), prefs.getLong("duration", 0)
            )
            if (!valid) clearDailySession()
            else prefs.edit(commit = true) { putLong("last_seen_wall", nowWall) }
            return valid
        }

    fun dailyEncryptionCipher(): Cipher {
        val key = secretKey()
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
            putInt("format", 3)
        }
    }

    fun dailyDecryptionCipher(): Cipher? {
        if (!hasValidDailySession) return null
        return runCatching {
            val nonce = android.util.Base64.decode(prefs.getString("nonce", null) ?: error("Missing biometric nonce"), android.util.Base64.NO_WRAP)
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, nonce)) }
        }.onFailure { clearDailySession() }.getOrNull()
    }

    fun finishDailyUnlock(cipher: Cipher): ByteArray {
        check(hasValidDailySession) { "Biometric session expired" }
        val wrapped = android.util.Base64.decode(prefs.getString("wrapped_key", null) ?: error("Biometric session expired"), android.util.Base64.NO_WRAP)
        return cipher.doFinal(wrapped).also { require(it.size == 32) { "Invalid vault key" } }
    }

    fun clearDailySession() {
        prefs.edit(commit = true) { clear() }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
        if (keyStore.containsAlias(alias)) keyStore.deleteEntry(alias)
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
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
