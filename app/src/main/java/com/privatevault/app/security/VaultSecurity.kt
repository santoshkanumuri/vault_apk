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

object PasswordCrypto {
    const val MIN_PASSWORD_LENGTH = 12
    private const val MEMORY_KIB = 65_536
    private const val ITERATIONS = 3
    private const val PARALLELISM = 1

    fun derive(password: CharArray, salt: ByteArray): ByteArray {
        val passwordBytes = password.concatToString().toByteArray(Charsets.UTF_8)
        return try {
            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withSalt(salt)
                .withMemoryAsKB(MEMORY_KIB)
                .withIterations(ITERATIONS)
                .withParallelism(PARALLELISM)
                .build()
            ByteArray(32).also { output ->
                Argon2BytesGenerator().apply { init(params) }
                    .generateBytes(passwordBytes, output)
            }
        } finally {
            passwordBytes.fill(0)
        }
    }

    fun wrap(plain: ByteArray, password: CharArray, salt: ByteArray, random: SecureRandom = SecureRandom()): WrappedSecret {
        val key = derive(password, salt)
        return try {
            val nonce = ByteArray(12).also(random::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            WrappedSecret(nonce, cipher.doFinal(plain))
        } finally {
            key.fill(0)
        }
    }

    fun unwrap(wrapped: WrappedSecret, password: CharArray, salt: ByteArray): ByteArray {
        val key = derive(password, salt)
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

class VaultKeyManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vault_bootstrap", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    val isInitialized: Boolean get() = prefs.contains("salt")

    fun create(password: CharArray): ByteArray {
        require(password.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        check(!isInitialized)
        val vaultKey = ByteArray(32).also(random::nextBytes)
        persistWrappedKey(vaultKey, password)
        return vaultKey
    }

    fun unlock(password: CharArray): ByteArray {
        val salt = decode(prefs.getString("salt", null) ?: error("Vault is not initialized"))
        val wrapped = WrappedSecret(
            decode(prefs.getString("nonce", null) ?: error("Missing nonce")),
            decode(prefs.getString("wrapped_key", null) ?: error("Missing wrapped key"))
        )
        return PasswordCrypto.unwrap(wrapped, password, salt)
    }

    fun changePassword(current: CharArray, replacement: CharArray) {
        require(replacement.size >= PasswordCrypto.MIN_PASSWORD_LENGTH)
        val vaultKey = unlock(current)
        try { persistWrappedKey(vaultKey, replacement) } finally { vaultKey.fill(0) }
    }

    private fun persistWrappedKey(vaultKey: ByteArray, password: CharArray) {
        val salt = ByteArray(16).also(random::nextBytes)
        val wrapped = PasswordCrypto.wrap(vaultKey, password, salt, random)
        prefs.edit(commit = true) {
            putString("salt", encode(salt))
            putString("nonce", encode(wrapped.nonce))
            putString("wrapped_key", encode(wrapped.ciphertext))
            putInt("format", 1)
        }
    }

    private fun encode(value: ByteArray) = android.util.Base64.encodeToString(value, android.util.Base64.NO_WRAP)
    private fun decode(value: String) = android.util.Base64.decode(value, android.util.Base64.NO_WRAP)
}

class BiometricGate(context: Context) {
    private val alias = "private_vault_daily_gate"
    private val prefs = context.getSharedPreferences("vault_biometric_session", Context.MODE_PRIVATE)

    val hasValidDailySession: Boolean
        get() {
            val valid = prefs.getInt("format", 0) == 2 && prefs.contains("wrapped_key") && prefs.getLong("expires_at", 0) > System.currentTimeMillis()
            if (!valid) clearDailySession()
            return valid
        }

    fun dailyEncryptionCipher(): Cipher {
        val key = secretKey()
        return Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
    }

    fun enableDailySession(cipher: Cipher, vaultKey: ByteArray) {
        prefs.edit(commit = true) {
            putString("wrapped_key", android.util.Base64.encodeToString(cipher.doFinal(vaultKey), android.util.Base64.NO_WRAP))
            putString("nonce", android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            putLong("expires_at", System.currentTimeMillis() + DAILY_SESSION_MILLIS)
            putInt("format", 2)
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

    private companion object { const val DAILY_SESSION_MILLIS = 24L * 60 * 60 * 1000 }
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
