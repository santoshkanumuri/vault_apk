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
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

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
        var locked = true
        val encrypted = try {
            runCatching { lockedKey()?.let { ProfileCopyCipher.seal(bytes, it) } }.getOrNull()
                ?: ProfileCopyCipher.seal(bytes, legacyKey()).also { locked = false }
        } finally { bytes.fill(0) }
        val stream = file.startWrite()
        try { stream.write(encrypted); file.finishWrite(stream) }
        catch (failure: Exception) { file.failWrite(stream); throw failure }
        // The copy is now under the newer key, so the older one has nothing left to open.
        if (locked) runCatching { keystore().deleteEntry(LEGACY_ALIAS) }
    }

    @Synchronized fun read(): List<Pair<String, AutofillProfile>> {
        if (!enabled() || !file.baseFile.exists()) return emptyList()
        return runCatching {
            val encrypted = file.openRead().use { it.readBytes() }
            require(encrypted.size in 29..(128 * 1024 + 28))
            val plain = ProfileCopyCipher.open(encrypted, listOf({ existingKey(KEY_ALIAS) }, { existingKey(LEGACY_ALIAS) }))
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

    private fun keystore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun existingKey(alias: String): SecretKey? = keystore().getKey(alias, null) as? SecretKey

    /**
     * Security review L-05: the device copy is written under a key that Android only lets the app use while the
     * phone itself is unlocked ([KeyGenParameterSpec.Builder.setUnlockedDeviceRequired]). Autofill only runs while
     * someone is using the unlocked phone, so this takes nothing away, but a copy of the app's files read from a
     * locked phone stays sealed. A phone that cannot make or use such a key keeps the key it had before, and
     * a copy written by 2.3.4 or earlier still opens until the next unlock writes it again.
     */
    private fun lockedKey(): SecretKey? = existingKey(KEY_ALIAS)
        ?: runCatching { generate(KEY_ALIAS, unlockedDeviceRequired = true) }.getOrNull()

    private fun legacyKey(): SecretKey = existingKey(LEGACY_ALIAS) ?: generate(LEGACY_ALIAS, unlockedDeviceRequired = false)

    private fun generate(alias: String, unlockedDeviceRequired: Boolean): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .apply { if (unlockedDeviceRequired) setUnlockedDeviceRequired(true) }.build())
            generateKey()
        }

    private companion object {
        /** Made by 2.3.4 and earlier, usable while the phone is locked. Read only, and deleted once replaced. */
        const val LEGACY_ALIAS = "nuvori_unlocked_profiles_v1"
        const val KEY_ALIAS = "nuvori_unlocked_profiles_v2"
    }
}
