package com.privatevault.app.sync

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncProgressTest {
    @Test fun matchesWindowsProgressEncoding() {
        val progress = SyncProgress(
            mapOf("phone" to SyncChainHead(2, "a".repeat(64)),
                "windows" to SyncChainHead(0, GENESIS_HASH)),
            mapOf("phone" to SyncChainHead(1, "b".repeat(64))),
        )
        val expected = """{"received":{"phone":[2,"${"a".repeat(64)}"],"windows":[0,"$GENESIS_HASH"]},"applied":{"phone":[1,"${"b".repeat(64)}"]}}"""
        assertEquals(expected, progress.encode().toString(Charsets.UTF_8))
        assertEquals(progress, SyncProgress.parse(expected.toByteArray()))
    }

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

    @Test fun readsProgressSavedByMinifiedRelease() {
        val gson = Gson()
        val head = SyncChainHead(3, "a".repeat(64))
        val mirror = TransportMirror("vault", "self", "public", "secret", 1,
            emptyList(), emptyList(), emptyList(), emptyList(),
            mapOf("peer" to SyncProgress(mapOf("self" to head), mapOf("self" to head))))
        val json = JsonParser.parseString(gson.toJson(mirror)).asJsonObject
        val oldProgress = """{"peer":{"a":{"self":{"a":3,"b":"${"a".repeat(64)}"}},"b":{"self":{"a":3,"b":"${"a".repeat(64)}"}}}}"""
        json.add("peerProgress", JsonParser.parseString(oldProgress))
        val restored = gson.fromJson(json, TransportMirror::class.java)
        assertEquals(head, restored.peerProgress.getValue("peer").received.getValue("self"))
        assertEquals(head, gson.fromJson(gson.toJson(mirror), TransportMirror::class.java)
            .peerProgress.getValue("peer").applied.getValue("self"))
    }
}
