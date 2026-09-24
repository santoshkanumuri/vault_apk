package com.privatevault.wear

import android.content.Intent
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import com.privatevault.app.watch.WatchSync
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
        val key = ByteArray(32)
        val accepted = try {
            val stream = DataInputStream(ByteArrayInputStream(request))
            val vaultId = stream.readUTF()
            stream.readFully(key)
            require(stream.available() == 0)
            WatchStore.get(this).pair(vaultId, key)
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
        }
    }

    companion object { const val ACTION_UPDATED = "com.privatevault.wear.CODES_UPDATED" }
}
