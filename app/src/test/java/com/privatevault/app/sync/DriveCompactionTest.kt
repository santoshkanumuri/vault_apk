package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drive compaction (`history-compaction-v1`) against `compaction-v1.json` `drive` (scripts/sync-vectors/compaction.py). */
class DriveCompactionTest {
    private val drive: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/compaction-v1.json")).readText()).asJsonObject
        .getAsJsonObject("drive")
    private fun ranges(value: com.google.gson.JsonElement) = value.asJsonArray.map { it.asJsonArray[0].asLong to it.asJsonArray[1].asLong }
    private fun hex(text: String) = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun theFeatureIsAnnounced() {
        assertEquals(DriveCompaction.FEATURE, drive.get("feature").asString)
        assertTrue(DriveCompaction.FEATURE in SyncFeatures.ANNOUNCED)
    }

    @Test fun coverageStartsAfterACountedMarker() {
        drive.getAsJsonArray("coverage").map { it.asJsonObject }.forEach { case ->
            val marker = case.get("marker").takeUnless { it.isJsonNull }?.asLong
            val counted = DriveCompaction.countedFloor(marker, case.get("readerFloor").asLong)
            assertEquals(case.get("name").asString, case.get("coverage").asLong, DriveCompaction.coverage(ranges(case.get("ranges")), counted))
        }
    }

    @Test fun cleanupDeletesOnlyBelowTheFloorAndMovesTheMarkerFirst() {
        drive.getAsJsonArray("cleanup").map { it.asJsonObject }.forEach { case ->
            val (marker, gone) = DriveCompaction.cleanup(ranges(case.get("ranges")), case.get("marker").asLong, case.get("floor").asLong)
            assertEquals(case.get("name").asString, case.get("newMarker").asLong, marker)
            assertEquals(case.get("name").asString, ranges(case.get("deleted")), gone)
        }
    }

    @Test fun theMarkerFileMatchesWindows() {
        val marker = drive.getAsJsonObject("marker")
        val keys = requireNotNull(CloudKeys.derive(hex(marker.get("transportSecretHex").asString), marker.get("vaultId").asString))
        assertEquals(marker.get("name").asString, keys.floorName("dev-pc"))
        assertEquals(CloudName.Floor(keys.deviceTag("dev-pc")), keys.parse(marker.get("name").asString))
        assertEquals(marker.get("sealedHex").asString, keys.sealWithNonce(marker.get("name").asString,
            marker.get("bodyJson").asString.toByteArray(), hex(marker.get("nonceHex").asString)).joinToString("") { "%02x".format(it) })
        val parsed = com.google.gson.Gson().fromJson(marker.get("bodyJson").asString, DriveCompaction.Marker::class.java)
        assertEquals(256L, parsed.floor)
    }
}
