package com.privatevault.app.autofill

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.privatevault.app.data.VaultEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Opt-in device copy of ordinary profile fields. It never contains passwords or TOTP secrets. */
internal class UnlockedProfileStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "autofill-profiles"))

    @Synchronized fun publish(vaultId: String, entries: List<VaultEntry>) {
        if (!enabled()) { clear(); return }
        val bytes = JSONObject().put("vaultId", vaultId).put("profiles", JSONArray().apply {
            entries.filter { it.type == com.privatevault.app.data.EntryType.AUTOFILL }.forEach { entry ->
                entry.autofillProfile()?.takeIf { it.hasValue }?.let { profile ->
                    put(JSONObject().put("title", entry.title).put("details", profile.encode()))
                }
            }
        }).toString().toByteArray(Charsets.UTF_8)
        require(bytes.size <= 128 * 1024) { "Too many Autofill details" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val encrypted = cipher.iv + cipher.doFinal(bytes)
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        finally { bytes.fill(0) }
    }

    @Synchronized fun read(): List<Pair<String, AutofillProfile>> {
        if (!enabled() || !file.baseFile.exists()) return emptyList()
        return runCatching {
            val encrypted = file.openRead().use { it.readBytes() }
            require(encrypted.size in 29..(128 * 1024 + 28))
            val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, encrypted, 0, 12))
                doFinal(encrypted, 12, encrypted.size - 12)
            }
            try {
                val json = JSONObject(plain.toString(Charsets.UTF_8))
                val profiles = json.getJSONArray("profiles")
                (0 until profiles.length()).mapNotNull { index ->
                    val item = profiles.getJSONObject(index)
                    AutofillProfile.decode(item.getString("details"))?.let { item.getString("title") to it }
                }
            } finally { plain.fill(0) }
        }.getOrDefault(emptyList())
    }

    @Synchronized fun clear() { file.delete() }

    fun enabled(): Boolean = context.getSharedPreferences("vault_preferences", Context.MODE_PRIVATE)
        .getBoolean("autofill_unlocked_profiles", false)

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            generateKey()
        }
    }

    private companion object { const val KEY_ALIAS = "nuvori_unlocked_profiles_v1" }
}
