package com.privatevault.wear

import android.app.KeyguardManager
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.privatevault.app.watch.WatchDisplay
import com.privatevault.app.watch.WatchSnapshot
import com.privatevault.app.watch.WatchSync
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal class WatchStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("watch_pair", Context.MODE_PRIVATE)
    private val snapshotFile = AtomicFile(File(context.filesDir, "watch_codes"))
    private val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun isSecure(): Boolean = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure

    @Synchronized fun pair(vaultId: String, key: ByteArray) {
        require(isSecure() && vaultId.length in 1..100 && key.size == 32)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrappingKey())
        val wrapped = cipher.doFinal(key)
        val previous = prefs.getString("vault_id", null)
        require(prefs.edit().putString("vault_id", vaultId)
            .putString("wrapped_key", android.util.Base64.encodeToString(cipher.iv + wrapped, android.util.Base64.NO_WRAP)).commit())
        if (previous != vaultId) {
            snapshotFile.delete()
            prefs.edit().remove(SYNCED_AT).remove(RECENT_IDS).commit()
        }
    }

    @Synchronized fun apply(payload: ByteArray): Boolean {
        if (!isSecure()) { clear(); return false }
        val key = readKey() ?: return false
        try {
            val plaintext = WatchSync.decrypt(key, payload)
            try {
                val snapshot = WatchSync.decode(plaintext)
                if (snapshot.vaultId != prefs.getString("vault_id", null)) return false
                // The same data item is offered again each time the app opens; only a new copy is written and dated.
                val stored = runCatching { snapshotFile.openRead().use { it.readBytes() } }.getOrNull()
                if (stored != null && stored.contentEquals(payload)) return true
                val output = snapshotFile.startWrite()
                try { output.write(payload); snapshotFile.finishWrite(output) }
                catch (failure: Exception) { snapshotFile.failWrite(output); throw failure }
                prefs.edit().putLong(SYNCED_AT, System.currentTimeMillis()).apply()
                return true
            } finally { plaintext.fill(0) }
        } finally { key.fill(0) }
    }

    @Synchronized fun read(): WatchSnapshot? {
        if (!isSecure()) { clear(); return null }
        val key = readKey() ?: return null
        return try {
            val payload = runCatching { snapshotFile.openRead().use { it.readBytes() } }.getOrNull() ?: return null
            val plaintext = WatchSync.decrypt(key, payload)
            try { WatchSync.decode(plaintext).takeIf { it.vaultId == prefs.getString("vault_id", null) } }
            finally { plaintext.fill(0) }
        } catch (_: Exception) { null }
        finally { key.fill(0) }
    }

    /** When the phone last delivered a new copy, or 0. Wall-clock time of this watch. */
    fun syncedAt(): Long = prefs.getLong(SYNCED_AT, 0L)

    /** Account ids opened recently on this watch, newest first. Ids only: names and secrets stay in the encrypted copy. */
    fun recentIds(): List<String> = prefs.getString(RECENT_IDS, null).orEmpty().split('\n').filter(String::isNotBlank)

    @Synchronized fun markUsed(id: String) {
        if (id.isBlank() || '\n' in id) return
        prefs.edit().putString(RECENT_IDS, WatchDisplay.recordRecent(recentIds(), id).joinToString("\n")).apply()
    }

    @Synchronized fun clear() {
        snapshotFile.delete()
        prefs.edit().clear().commit()
        keyStore.deleteEntry("nuvori_watch_wrap")
    }

    private fun readKey(): ByteArray? {
        val encoded = prefs.getString("wrapped_key", null) ?: return null
        return runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
            require(bytes.size >= 29)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, wrappingKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            cipher.doFinal(bytes, 12, bytes.size - 12)
        }.getOrNull()
    }

    private fun wrappingKey(): SecretKey {
        (keyStore.getKey("nuvori_watch_wrap", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nuvori_watch_wrap", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
        }.generateKey()
    }

    companion object {
        private const val SYNCED_AT = "synced_at"
        private const val RECENT_IDS = "recent_ids"
        @Volatile private var instance: WatchStore? = null
        fun get(context: Context): WatchStore = instance ?: synchronized(this) {
            instance ?: WatchStore(context.applicationContext).also { instance = it }
        }
    }
}
