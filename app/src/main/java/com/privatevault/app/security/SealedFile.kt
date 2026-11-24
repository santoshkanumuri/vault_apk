package com.privatevault.app.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A small file in `noBackupFilesDir`, sealed with AES-GCM under its own Keystore key [keyAlias]. For device-only
 * records that must be readable while the vault is locked (usage counts, the passkey request history). It never
 * enters a backup, and clearing app data removes it. At most [maxBytes] of plaintext.
 */
internal class SealedFile(context: Context, name: String, private val keyAlias: String, private val maxBytes: Int) {
    private val file = AtomicFile(File(context.applicationContext.noBackupFilesDir, name))

    /** The plaintext, or null when there is no file. Throws when the file is damaged or the key is gone. */
    @Synchronized fun read(): ByteArray? {
        if (!file.baseFile.exists()) return null
        val sealed = file.openRead().use { it.readBytes() }
        require(sealed.size in 29..(maxBytes + 28)) { "Sealed file has the wrong size" }
        return Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            doFinal(sealed, 12, sealed.size - 12)
        }
    }

    @Synchronized fun write(plaintext: ByteArray) {
        require(plaintext.size <= maxBytes) { "Too large to seal" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val sealed = cipher.iv + cipher.doFinal(plaintext)
        val stream = file.startWrite()
        try { stream.write(sealed); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
    }

    @Synchronized fun delete() = file.delete()

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(keyAlias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }
}
