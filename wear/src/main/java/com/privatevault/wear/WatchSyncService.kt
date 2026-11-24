package com.privatevault.wear

import android.content.Intent
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.privatevault.app.watch.WatchSync
import com.privatevault.app.watch.parseWatchAppearance
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.concurrent.TimeUnit

class WatchSyncService : WearableListenerService() {
    override fun onRequest(nodeId: String, path: String, request: ByteArray): com.google.android.gms.tasks.Task<ByteArray>? {
        if (path == WatchSync.PROBE_PATH) return Tasks.forResult(byteArrayOf((if (request.isEmpty() && WatchStore.get(this).isSecure()) 1 else 0).toByte()))
        if (path == WatchSync.SNAPSHOT_PATH) {
            val applied = runCatching { WatchStore.get(this).apply(request) }.getOrDefault(false)
            if (applied) sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
            return Tasks.forResult(byteArrayOf((if (applied) 1 else 0).toByte()))
        }
        if (path != WatchSync.PAIR_PATH) return null
        val store = WatchStore.get(this)
        // M-02: only the phone that paired this watch may replace the pairing while it is connected.
        val pinned = store.pairedNode()
        if (pinned != null && pinned != nodeId) {
            val connected = runCatching {
                Tasks.await(Wearable.getNodeClient(this).connectedNodes, 10, TimeUnit.SECONDS).map { it.id }.toSet()
            }.getOrNull()
            // When the watch can't tell which phones are connected, it keeps its pairing.
            if (connected == null || !WatchSync.mayPair(pinned, nodeId, connected)) {
                return Tasks.forResult(byteArrayOf(WatchSync.PAIR_OTHER_PHONE))
            }
        }
        val key = ByteArray(32)
        val accepted = try {
            val stream = DataInputStream(ByteArrayInputStream(request))
            val vaultId = stream.readUTF()
            stream.readFully(key)
            require(stream.available() == 0)
            store.pair(vaultId, key, nodeId)
            // A queued DataItem can arrive before the pairing message.
            runCatching {
                val items = Tasks.await(Wearable.getDataClient(this).dataItems, 10, TimeUnit.SECONDS)
                try { items.firstOrNull { it.uri.path == WatchSync.SNAPSHOT_PATH }?.data?.let { WatchStore.get(this).apply(it) } }
                finally { items.release() }
            }
            sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
            true
        } catch (_: Exception) {
            false
        } finally { key.fill(0) }
        return Tasks.forResult(byteArrayOf((if (accepted) 1 else 0).toByte()))
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val store = WatchStore.get(this)
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WatchSync.SNAPSHOT_PATH) {
                runCatching { event.dataItem.data?.let(store::apply) }.onSuccess { applied ->
                    if (applied == true) sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
                }
            }
            if (event.dataItem.uri.path == WatchSync.APPEARANCE_PATH) {
                // Only the accent id is read. Other paths stay ignored, so older phones and watches keep working.
                val accent = if (event.type == DataEvent.TYPE_DELETED) null
                    else event.dataItem.data?.let(::parseWatchAppearance) ?: return@forEach
                if (store.setAccent(accent)) sendBroadcast(Intent(ACTION_UPDATED).setPackage(packageName))
            }
        }
    }

    companion object { const val ACTION_UPDATED = "com.privatevault.wear.CODES_UPDATED" }
}
