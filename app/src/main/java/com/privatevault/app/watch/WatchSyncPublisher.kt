package com.privatevault.app.watch

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * What the phone knows about its watch: names Wear OS can reach now (null when unavailable), the last send time,
 * and whether the watch still uses the key derived from the vault key (paired before random watch keys).
 */
data class WatchConnection(val names: List<String>?, val lastSentAt: Long, val legacyKey: Boolean = false)

class WatchSyncPublisher(private val context: Context) {
    private val mutex = Mutex()
    private val pairing = WatchPairingStore(File(context.noBackupFilesDir, "watch-pairing"))

    /** True when no random watch key is stored yet. Reads the record type only; no vault key is needed. */
    suspend fun usesLegacyKey(): Boolean = withContext(Dispatchers.IO) {
        runCatching { pairing.state() == WatchPairingStore.State.NONE }.getOrDefault(false)
    }

    /** Watches the Wear OS companion reaches right now. Asks the Data Layer only; nothing is sent to the watch. */
    suspend fun connectedWatches(): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            Tasks.await(Wearable.getNodeClient(context).connectedNodes, 5, TimeUnit.SECONDS).map { it.displayName }.sorted()
        }.getOrNull()
    }

    suspend fun pair(vaultId: String, vaultKey: ByteArray, entries: List<VaultEntry>, appearanceAfterPair: String? = null) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes, 10, TimeUnit.SECONDS)
            require(nodes.isNotEmpty()) { "No Wear OS watch is connected through Google Play services. Check the phone's watch companion app, then try again." }
            val messages = Wearable.getMessageClient(context)
            var needsScreenLock = false
            val ready = nodes.mapNotNull { node ->
                val response = runCatching { Tasks.await(messages.sendRequest(node.id, WatchSync.PROBE_PATH, byteArrayOf()), 20, TimeUnit.SECONDS) }.getOrNull()
                if (response?.contentEquals(byteArrayOf(0)) == true) needsScreenLock = true
                if (response?.contentEquals(byteArrayOf(1)) == true) node else null
            }
            require(ready.isNotEmpty()) {
                if (needsScreenLock) "Set a PIN or pattern on the watch, unlock it, then try again."
                else "Nuvori did not respond. Use Play Store builds on both devices, or matching signed APKs. Open Nuvori on the watch and retry."
            }
            require(ready.size == 1) { "More than one Nuvori watch is connected. Disconnect the other watch, then try again." }
            // A new random key for each pairing. The PAIR message format is unchanged, so released watch builds accept it.
            val key = ByteArray(32).also(SecureRandom()::nextBytes)
            try {
                val message = ByteArrayOutputStream().also { stream ->
                    DataOutputStream(stream).use { it.writeUTF(vaultId); it.write(key) }
                }.toByteArray()
                try {
                    val response = Tasks.await(messages.sendRequest(ready.single().id, WatchSync.PAIR_PATH, message), 30, TimeUnit.SECONDS)
                    require(response.contentEquals(byteArrayOf(1))) { "The watch could not save the pairing. Check its screen lock, then try again." }
                } finally { message.fill(0) }
                // Stored only after the watch accepted it, so a failed attempt keeps the previous pairing working.
                pairing.savePaired(vaultId, vaultKey, key)
                val payload = publishLocked(vaultId, key, entries)
                appearanceAfterPair?.let { accent -> runCatching {
                    Tasks.await(Wearable.getDataClient(context).putDataItem(
                        PutDataRequest.create(WatchSync.APPEARANCE_PATH).setData(encodeWatchAppearance(accent)).setUrgent()), 15, TimeUnit.SECONDS)
                } }
                runCatching { Tasks.await(messages.sendRequest(ready.single().id, WatchSync.SNAPSHOT_PATH, payload),
                    15, TimeUnit.SECONDS).contentEquals(byteArrayOf(1)) }.getOrDefault(false)
            } finally { key.fill(0) }
        }
    }

    /** Sends a new snapshot with the stored watch key. Returns false and sends nothing after the codes were removed. */
    suspend fun publish(vaultId: String, vaultKey: ByteArray, entries: List<VaultEntry>): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = currentKey(vaultId, vaultKey) ?: return@withContext false
            try { publishLocked(vaultId, key, entries); true } finally { key.fill(0) }
        }
    }

    /**
     * Sends the vault accent id (plain JSON, urgent) so the watch can use it. Sends nothing after the watch was removed.
     * The caller checks that watch sync is on. Returns true when the DataItem was put.
     */
    suspend fun publishAppearance(accent: String): Boolean = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (pairing.state() == WatchPairingStore.State.REMOVED) return@withContext false
            val request = PutDataRequest.create(WatchSync.APPEARANCE_PATH).setData(encodeWatchAppearance(accent)).setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), 15, TimeUnit.SECONDS)
            true
        }
    }

    /**
     * Sends an empty snapshot with the current key, then replaces the stored key with the removal marker.
     * Later publish calls send nothing until the watch is connected again with a new key.
     */
    suspend fun remove(vaultId: String, vaultKey: ByteArray) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = currentKey(vaultId, vaultKey) ?: return@withContext
            try { publishLocked(vaultId, key, emptyList()) } finally { key.fill(0) }
            pairing.markRemoved()
            // The watch returns to the default green. A failed delete must not undo the removal.
            runCatching { Tasks.await(Wearable.getDataClient(context).deleteDataItems(
                android.net.Uri.Builder().scheme("wear").path(WatchSync.APPEARANCE_PATH).build()), 15, TimeUnit.SECONDS) }
        }
    }

    private fun currentKey(vaultId: String, vaultKey: ByteArray): ByteArray? = when (val stored = pairing.load(vaultId, vaultKey)) {
        is WatchPairing.Paired -> stored.key
        // Watches paired before random keys still hold the derived key. The next Connect replaces it.
        WatchPairing.Legacy -> WatchSync.keyFromVault(vaultKey)
        WatchPairing.Removed -> null
    }

    private fun publishLocked(vaultId: String, key: ByteArray, entries: List<VaultEntry>): ByteArray {
        val snapshot = WatchSnapshot(vaultId, entries.filter { it.type == EntryType.AUTHENTICATOR }
            .sortedWith(compareBy({ it.title.lowercase() }, { it.primaryValue.lowercase() }, { it.id }))
            .map { WatchAccount(it.id, it.title, it.primaryValue, it.secondaryValue, it.totpAlgorithm, it.totpDigits, it.totpPeriod) })
        val plaintext = WatchSync.encode(snapshot)
        try {
            val payload = WatchSync.encrypt(key, plaintext)
            require(payload.size <= 100_000)
            val request = PutDataRequest.create(WatchSync.SNAPSHOT_PATH).setData(payload).setUrgent()
            Tasks.await(Wearable.getDataClient(context).putDataItem(request), 15, TimeUnit.SECONDS)
            return payload
        } finally { plaintext.fill(0) }
    }
}
