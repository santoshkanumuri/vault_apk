package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol `merge-join-v1`. The vector file (scripts/sync-vectors/lineage_merge.py) is byte-identical in the Windows repository. */
class MergeJoinRulesTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/merge-join-v1.json")).readText()).asJsonObject

    private fun version(value: com.google.gson.JsonElement?): RecordVersion? =
        if (value == null || value.isJsonNull) null else RecordVersion(value.asJsonObject.entrySet().associate { it.key to it.value.asLong })

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(MergeJoinRules.FEATURE, vectors.get("feature").asString)
        assertEquals(SyncFeatures.MERGE_JOIN, MergeJoinRules.FEATURE)
        assertTrue(SyncFeatures.MERGE_JOIN in SyncFeatures.ANNOUNCED)
        assertEquals(vectors.getAsJsonArray("entryFields").map { it.asString }, MergeJoinRules.ENTRY_FIELDS)
        assertEquals(vectors.getAsJsonArray("groupFields").map { it.asString }, MergeJoinRules.GROUP_FIELDS)
        assertEquals(vectors.getAsJsonArray("passkeyFields").map { it.asString }, MergeJoinRules.PASSKEY_FIELDS)
    }

    @Test fun everyCaseDecidesLikeTheVectors() {
        val cases = vectors.getAsJsonArray("cases")
        assertEquals(23, cases.size())
        cases.map { it.asJsonObject }.forEach { case ->
            val type = case.get("entityType").asString
            val joiner = case.getAsJsonObject("joiner")
            val host = case.getAsJsonObject("host")
            val lineage = case.getAsJsonObject("lineage")
            val hostContent = host.get("content").takeUnless { it.isJsonNull }?.asJsonObject
            val state = MergeJoinRules.HostState.valueOf(host.get("state").asString.uppercase())
            val same = hostContent != null && MergeJoinRules.sameContent(type, joiner.getAsJsonObject("content"), hostContent)
            val outcome = MergeJoinRules.decide(same, state, lineage.get("shared").asBoolean,
                version(joiner.get("oldVersion")), version(lineage.get("version")),
                joinerUnchanged = !joiner.get("changedSinceLineage").asBoolean)
            val expected = when (case.get("expected").asString) {
                "same" -> MergeJoinRules.Outcome.SAME
                "keepHost" -> MergeJoinRules.Outcome.KEEP_HOST
                "drop" -> MergeJoinRules.Outcome.DROP
                "add" -> MergeJoinRules.Outcome.ADD
                else -> MergeJoinRules.Outcome.CONFLICT
            }
            assertEquals(case.get("name").asString, expected, outcome)
        }
    }

    @Test fun theFreezeKeepsTheOldGroupAsItWas() {
        val freeze = vectors.getAsJsonObject("freeze")
        val rule = LostManagerFreeze.Freeze(freeze.get("deviceId").asString, freeze.get("membershipCount").asLong,
            freeze.get("headSequence").asLong, freeze.get("headHash").asString)
        freeze.getAsJsonArray("changes").map { it.asJsonObject }.forEach {
            assertEquals(it.toString(), it.get("allowed").asBoolean, LostManagerFreeze.allowsChange(rule,
                it.get("deviceId").asString, it.get("sequence").asLong, it.get("hash").asString))
        }
        freeze.getAsJsonArray("events").map { it.asJsonObject }.forEach {
            assertEquals(it.get("kept").asInt, LostManagerFreeze.keptEvents(rule, List(it.get("offered").asInt) { index -> index }).size)
        }
        // Without a freeze nothing changes.
        assertTrue(LostManagerFreeze.allowsChange(null, "dev-phone", 99, "ab"))
        assertEquals(5, LostManagerFreeze.keptEvents(null, List(5) { it }).size)
        assertNull(LostManagerFreeze.of(null))
    }

    @Test fun aMergeSummaryIsShortAndPlain() {
        assertNull(MergeJoin.Summary().describe())
        assertEquals("2 items were already in the group. 1 item from this device was added. 1 item differs from the group's copy. Review it under Sync conflicts.",
            MergeJoin.Summary(alreadyThere = 2, added = 1, conflicts = 1).describe())
        assertEquals("3 items were deleted in the group. They are in Recently deleted.", MergeJoin.Summary(dropped = 3).describe())
    }

    @Test fun aLineageIsCheckedBeforeUse() {
        val gson = com.google.gson.Gson()
        val good = SnapshotLineage("old-vault", listOf(LineageRecord("entry", "e1", "{\"counters\":{\"dev\":2}}")), emptyList())
        assertEquals(good, SnapshotLineage.parse(gson.toJsonTree(good)))
        assertNull(SnapshotLineage.parse(null))
        assertNull(SnapshotLineage.parse(com.google.gson.JsonNull.INSTANCE))
        val blank = gson.toJsonTree(good.copy(fromVaultId = ""))
        assertFalse(runCatching { SnapshotLineage.parse(blank) }.isSuccess)
        val badVersion = gson.toJsonTree(good.copy(records = listOf(LineageRecord("entry", "e1", "{\"counters\":{\"dev\":0}}"))))
        assertFalse(runCatching { SnapshotLineage.parse(badVersion) }.isSuccess)
        val rows = listOf(com.privatevault.app.data.SyncLineageEntity("old", "entry", "e1", "{\"counters\":{\"a\":1}}", false),
            com.privatevault.app.data.SyncLineageEntity("old", "entry", "e2", "{\"counters\":{\"a\":2}}", true))
        val lineage = requireNotNull(SnapshotLineage.of(rows))
        assertEquals(listOf("e1"), lineage.records.map { it.entityId })
        assertEquals(listOf("e2"), lineage.tombstones.map { it.entityId })
    }
}
