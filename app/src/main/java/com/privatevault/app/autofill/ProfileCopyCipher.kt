package com.privatevault.app.autofill

import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The file format of the device copy: a 12-byte IV, then AES-256-GCM. Unchanged since it was added. */
internal object ProfileCopyCipher {
    fun seal(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        return cipher.iv + cipher.doFinal(plain)
    }

    /** Opens [encrypted] with the first of [keys] that exists and fits. Throws when none does. */
    fun open(encrypted: ByteArray, keys: List<() -> SecretKey?>): ByteArray {
        var failure: Exception? = null
        for (candidate in keys) {
            val key = runCatching(candidate).getOrNull() ?: continue
            try {
                return Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, encrypted, 0, 12))
                    doFinal(encrypted, 12, encrypted.size - 12)
                }
            } catch (error: Exception) { failure = error }
        }
        throw failure ?: IllegalStateException("No key for the device copy")
    }
}
