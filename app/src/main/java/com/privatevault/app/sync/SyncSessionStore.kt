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
    /**
     * Progress a peer reports in a live Wi-Fi session. A head lower than its last report, on the same
     * chain, means the peer lost ground (an older build drops a change it set aside from what it
     * reports): it is taken and logged, so the session goes on and the changes go again (H2). A
     * different change at a known sequence still ends the session. Drive reports, which can be old
     * copies, keep refusing a lower head ([recordPeerProgress]).
     */
    fun recordLivePeerProgress(deviceId: String, progress: SyncProgress) = recordPeerProgress(deviceId, progress)
    /** [relayRemoved]: also changes of removed devices up to their cutoff ([RemovalCutoff]). */
    fun pending(frontier: SyncFrontier, relayRemoved: Boolean = false): List<SyncOperationEntity>
    fun queue(operation: SyncOperationEntity)
    fun missingPhotos(): List<Pair<String, Long>>
    fun receivedPhoto(hash: String)
}
