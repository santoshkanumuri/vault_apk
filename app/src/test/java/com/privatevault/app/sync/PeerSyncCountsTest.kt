package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class PeerSyncCountsTest {
    private fun head(sequence: Long) = SyncChainHead(sequence, "a".repeat(64))

    @Test fun countsSeparateDeliveryFromApplication() {
        val local = SyncProgress(mapOf("me" to head(5), "peer" to head(4)),
            mapOf("me" to head(5), "peer" to head(2)))
        val peer = SyncProgress(mapOf("me" to head(3), "peer" to head(6)),
            mapOf("me" to head(1), "peer" to head(6)))

        assertEquals(PeerSyncCounts(2, 2, 2, 2), peerSyncCounts("me", "peer", local, peer))
    }

    @Test fun noContactDoesNotClaimACompletedCheck() {
        val local = SyncProgress(mapOf("me" to head(1)), mapOf("me" to head(1)))
        assertEquals(PeerSyncCounts(0, 0, 0, 0), peerSyncCounts("me", "peer", local, null))
    }
}
