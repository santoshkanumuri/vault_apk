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
import java.util.concurrent.TimeUnit

class WatchSyncPublisher(private val context: Context) {
    private val mutex = Mutex()

    suspend fun pair(vaultId: String, vaultKey: ByteArray, entries: List<VaultEntry>) = mutex.withLock {
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
            val key = WatchSync.keyFromVault(vaultKey)
            try {
                val message = ByteArrayOutputStream().also { stream ->
                    DataOutputStream(stream).use { it.writeUTF(vaultId); it.write(key) }
                }.toByteArray()
                try {
                    val response = Tasks.await(messages.sendRequest(ready.single().id, WatchSync.PAIR_PATH, message), 30, TimeUnit.SECONDS)
                    require(response.contentEquals(byteArrayOf(1))) { "The watch could not save the pairing. Check its screen lock, then try again." }
                } finally { message.fill(0) }
                val payload = publishLocked(vaultId, key, entries)
                runCatching { Tasks.await(messages.sendRequest(ready.single().id, WatchSync.SNAPSHOT_PATH, payload),
                    15, TimeUnit.SECONDS).contentEquals(byteArrayOf(1)) }.getOrDefault(false)
            } finally { key.fill(0) }
        }
    }

    suspend fun publish(vaultId: String, vaultKey: ByteArray, entries: List<VaultEntry>) = mutex.withLock {
        withContext(Dispatchers.IO) {
            val key = WatchSync.keyFromVault(vaultKey)
            try { publishLocked(vaultId, key, entries) } finally { key.fill(0) }
        }
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
