package com.privatevault.app.sync

import com.privatevault.app.data.SyncOperationEntity

/**
 * The durable state one authenticated LAN session reads and writes. [LockedSyncStore] is the
 * production implementation; tests use an in-memory one to simulate dropped connections.
 *
 * Contract: [queue] and [PhotoSyncBlobs.receive] return only after their data is durable, because
 * the exchange acknowledges each operation and photo right after they return.
 */
internal interface SyncSessionStore {
    val photoBlobs: PhotoSyncBlobs
    fun progress(): SyncProgress
    fun recordPeerProgress(deviceId: String, progress: SyncProgress)
    /** [relayRemoved]: also changes of removed devices up to their cutoff ([RemovalCutoff]). */
    fun pending(frontier: SyncFrontier, relayRemoved: Boolean = false): List<SyncOperationEntity>
    fun queue(operation: SyncOperationEntity)
    fun missingPhotos(): List<Pair<String, Long>>
    fun receivedPhoto(hash: String)
}
