package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {
    @Test fun roundTripsExactCounters() {
        val head = SyncChainHead(9_007_199_254_740_993L, "a".repeat(64))
        val progress = SyncProgress(mapOf("phone" to head), mapOf("phone" to head))
        assertEquals(progress, SyncProgress.parse(progress.encode()))
    }

    @Test fun rejectsAppliedAheadOfReceived() {
        val received = SyncChainHead(1, "a".repeat(64))
        val applied = SyncChainHead(2, "b".repeat(64))
        assertTrue(runCatching { SyncProgress(mapOf("phone" to received),
            mapOf("phone" to applied)).validate() }.isFailure)
    }

    @Test fun rejectsOverflowedCounter() {
        val json = """{"received":{"phone":[9223372036854775808,"${"a".repeat(64)}"]},"applied":{}}"""
        assertTrue(runCatching { SyncProgress.parse(json.toByteArray()) }.isFailure)
    }
}
