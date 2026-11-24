package com.privatevault.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** History compaction on the device. The vector file (scripts/sync-vectors/compaction.py) is byte-identical in the Windows repository. */
class HistoryCompactionTest {
    private fun resource(name: String): JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/$name")).readText()).asJsonObject
    private val vectors = resource("compaction-v1.json")
    private val history = resource("checkpoint-v1.json").getAsJsonObject("keepSetInput")
    private val gson = Gson()
    private inline fun <reified T> list(name: String): List<T> = history.getAsJsonArray(name).map { gson.fromJson(it, T::class.java) }
    private fun longs(json: JsonObject?): Map<String, Long> = json?.entrySet()?.associate { it.key to it.value.asLong }.orEmpty()

    private val members = vectors.getAsJsonArray("members").map {
        val member = it.asJsonObject
        SyncMembershipEntity("v", member.get("deviceId").asString, member.get("deviceId").asString, "k",
            member.get("status").asString, "dev-phone", 1, 1)
    }

    @Test fun floorsAndDeletionsMatchTheSharedVectors() {
        val changes = list<SyncOperationEntity>("operations").map(KeepSet::of)
        val keep = KeepSet.compute(changes, list<SyncRecordStateEntity>("records"), list<SyncTombstoneEntity>("tombstones"),
            list<SyncConflictEntity>("conflicts"))
        val local = vectors.get("localDeviceId").asString
        val localApplied = longs(vectors.getAsJsonObject("localApplied"))
        val scenarios = vectors.getAsJsonArray("scenarios")
        assertEquals(5, scenarios.size())
        scenarios.forEach { item ->
            val scenario = item.asJsonObject
            val name = scenario.get("name").asString
            val applied = scenario.getAsJsonObject("applied").entrySet().associate { it.key to longs(it.value.asJsonObject) }
            val drive = scenario.get("driveCovered").takeIf { !it.isJsonNull }?.asJsonObject?.let(::longs)
            val floors = HistoryCompaction.floors(local, members, localApplied, applied, drive)
            assertEquals(name, longs(scenario.getAsJsonObject("floors")), floors)
            val deleted = HistoryCompaction.deletable(changes, keep, floors, localApplied)
            assertEquals(name, scenario.getAsJsonArray("deleted").map { it.asString }.toSet(), deleted.toSet())
            // Heads, witnesses, tombstones and open conflicts always stay.
            assertTrue(name, deleted.none { it in keep })
        }
    }

    @Test fun theRecordRoundTripsAndAnUnreadableOneMeansNeverRan() {
        val record = HistoryCompaction.Record(1795000000000, mapOf("dev-pc" to HistoryCompaction.Floor(12, "a".repeat(64))))
        assertEquals(record, HistoryCompaction.parse(HistoryCompaction.encode(record)))
        assertEquals(HistoryCompaction.Record(0, emptyMap()), HistoryCompaction.parse(""))
        assertEquals(HistoryCompaction.Record(0, emptyMap()), HistoryCompaction.parse("{}"))
    }

    @Test fun resolvedConflictsGoAfterThirtyDaysOpenOnesNever() {
        fun conflict(id: String, resolved: String) = SyncConflictEntity(id, "entry", "e", "a", "b", "{}", "{}", "", resolved)
        val now = java.time.Instant.parse("2026-11-22T00:00:00Z").toEpochMilli()
        assertEquals(listOf("old"), HistoryCompaction.expiredConflicts(listOf(conflict("old", "2026-10-01T00:00:00Z"),
            conflict("recent", "2026-11-10T00:00:00Z"), conflict("open", ""), conflict("odd", "not a time")), now))
    }

    @Test fun aMemberSilentForMonthsOnlyGetsASuggestion() {
        val day = 24L * 60 * 60 * 1000
        val now = 400 * day
        val named = members.map { if (it.deviceId == "dev-laptop") it.copy(displayName = "Tablet") else it }
        val heads = mapOf("dev-phone" to 6_000L, "dev-pc" to 10L)
        val applied = mapOf("dev-pc" to heads, "dev-laptop" to mapOf("dev-phone" to 100L, "dev-pc" to 10L))
        assertEquals("Tablet last synced 4 months ago. Nuvori keeps older sync history until it syncs. Remove it if you no longer use it.",
            HistoryCompaction.blockingHint("dev-phone", named, heads, applied,
                mapOf("dev-pc" to now, "dev-laptop" to now - 125 * day), now))
        // Devices & sync links the suggestion to removing that device.
        assertEquals("dev-laptop", HistoryCompaction.blockingSuggestion("dev-phone", named, heads, applied,
            mapOf("dev-pc" to now, "dev-laptop" to now - 125 * day), now)?.first)
        // Recently seen, or holding back too little: no suggestion.
        assertNull(HistoryCompaction.blockingHint("dev-phone", named, heads, applied,
            mapOf("dev-pc" to now, "dev-laptop" to now - 60 * day), now))
        assertNull(HistoryCompaction.blockingHint("dev-phone", named, heads,
            applied + ("dev-laptop" to mapOf("dev-phone" to 2_000L, "dev-pc" to 10L)),
            mapOf("dev-pc" to now, "dev-laptop" to now - 125 * day), now))
    }
}
