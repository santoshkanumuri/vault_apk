package com.privatevault.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncConflictEntity
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncOperationSummary
import com.privatevault.app.data.SyncRecordStateEntity
import com.privatevault.app.data.SyncTombstoneEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Protocol `checkpoint-enroll-v1`. The vector file (scripts/sync-vectors/checkpoint.py) is byte-identical in the Windows repository. */
class SyncCheckpointTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/checkpoint-v1.json")).readText()).asJsonObject
    private val gson = Gson()
    private val vaultId = vectors.get("vaultId").asString
    private val devices = vectors.getAsJsonArray("devices").associate { item ->
        val device = item.asJsonObject
        device.get("deviceId").asString to device
    }

    private fun identity(deviceId: String) = DeviceIdentity(deviceId,
        Base64.getUrlDecoder().decode(devices.getValue(deviceId).get("publicKey").asString))

    private fun seed(deviceId: String): ByteArray = devices.getValue(deviceId).get("seedHex").asString
        .chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun state(json: JsonObject): SnapshotSyncState = gson.fromJson(json, SnapshotSyncState::class.java)

    private inline fun <reified T> list(json: JsonObject, name: String): List<T> =
        json.getAsJsonArray(name).map { gson.fromJson(it, T::class.java) }

    private fun validate(snapshot: SnapshotSyncState) =
        validateSnapshotState(snapshot, vaultId, vectors.get("joiner").asString, identity(vectors.get("manager").asString))

    @Test fun keepSetMatchesTheSharedVectors() {
        val input = vectors.getAsJsonObject("keepSetInput")
        val operations = list<SyncOperationEntity>(input, "operations")
        assertEquals(40, operations.size)
        val kept = KeepSet.compute(operations.map(KeepSet::of), list<SyncRecordStateEntity>(input, "records"),
            list<SyncTombstoneEntity>(input, "tombstones"), list<SyncConflictEntity>(input, "conflicts"))
        assertEquals(vectors.getAsJsonArray("keepSet").map { it.asString }.toSet(), kept)
    }

    @Test fun digestsSigningBytesAndSignatureMatchTheSharedVectors() {
        val snapshot = state(vectors.getAsJsonObject("snapshot"))
        val digests = vectors.getAsJsonObject("digests")
        assertEquals(SyncCheckpoints.Digests(digests.get("operations").asString, digests.get("records").asString,
            digests.get("tombstones").asString, digests.get("conflicts").asString),
            SyncCheckpoints.digests(snapshot.operations.map { it.hash }, snapshot.records, snapshot.tombstones, snapshot.conflicts))
        val checkpoint = requireNotNull(snapshot.checkpoint)
        assertEquals(vectors.get("signingBytesText").asString, checkpoint.signingBytes().toString(Charsets.UTF_8))
        assertEquals(vectors.get("signingBytesHex").asString, checkpoint.signingBytes().joinToString("") { "%02x".format(it) })
        // Ed25519 is deterministic, so signing again gives the vector's signature.
        val manager = vectors.get("manager").asString
        val again = SyncCheckpoints.sign(vaultId, manager, snapshot.membershipEvents,
            checkpoint.heads.reversed(), SyncCheckpoints.digests(snapshot.operations.map { it.hash }, snapshot.records,
                snapshot.tombstones, snapshot.conflicts), checkpoint.createdAt) { DeviceIdentityCrypto.sign(seed(manager), it) }
        assertEquals(checkpoint, again)
        assertArrayEquals(checkpoint.signingBytes(), again.signingBytes())
    }

    @Test fun aValidCheckpointSnapshotVerifies() {
        val snapshot = state(vectors.getAsJsonObject("snapshot"))
        assertEquals(4, snapshot.version)
        validate(snapshot)
        // Each present item has its witness; a deleted one has none to show.
        val present = setOf("entry" to "e1", "entry" to "e2", "group" to "g1")
        assertEquals(emptyList<SyncRecordStateEntity>(), SyncCheckpoints.unwitnessed(snapshot.records, snapshot.operations, present))
        assertEquals(listOf("e3"), SyncCheckpoints.unwitnessed(snapshot.records, snapshot.operations,
            present + ("entry" to "e3")).map { it.entityId })
    }

    @Test fun everyNegativeCaseIsRefused() {
        val negatives = vectors.getAsJsonArray("negative")
        assertEquals(9, negatives.size())
        negatives.forEach { item ->
            val case = item.asJsonObject
            val name = case.get("name").asString
            val snapshot = state(case.getAsJsonObject("snapshot"))
            val refused = runCatching {
                validate(snapshot)
                if (name == "present item without a witness") require(SyncCheckpoints.unwitnessed(snapshot.records,
                    snapshot.operations, setOf("entry" to "e1", "entry" to "e2", "group" to "g1")).isEmpty())
            }.exceptionOrNull()
            assertTrue("$name must be refused", refused is IllegalArgumentException)
        }
    }

    @Test fun aVersionThreeSnapshotMustNotCarryACheckpoint() {
        val json = vectors.getAsJsonObject("snapshot").deepCopy().apply { addProperty("version", 3) }
        assertThrows(IllegalArgumentException::class.java) { validate(state(json)) }
    }

    @Test fun anOlderJoinerGetsVersionThreeOnlyWithTheFullHistory() {
        fun summary(author: String, sequence: Long) = SyncOperationSummary(author, sequence, "h", "entry", "e", "upsert",
            "{\"counters\":{\"$author\":$sequence}}", "2026-01-01T00:00:00Z")
        fun head(author: String, sequence: Long) = SyncDeviceHeadEntity("v", author, sequence, "h")
        val full = (1L..3L).map { summary("a", it) } + (1L..2L).map { summary("b", it) }
        assertTrue(SyncSnapshots.fullHistory(listOf(head("a", 3), head("b", 2)), full))
        // A checkpoint base or a compacted floor leaves a gap: v3 can no longer describe it.
        assertFalse(SyncSnapshots.fullHistory(listOf(head("a", 3), head("b", 2)), full.filterNot { it.deviceId == "a" && it.sequence == 2L }))
        assertFalse(SyncSnapshots.fullHistory(listOf(head("a", 4), head("b", 2)), full))
        assertEquals("Update Nuvori on the new PC to add it. Your sync history is too large for this version.",
            JoinerUpdateNeeded("the new PC").message)
        assertEquals(JoinerUpdateNeeded("the new PC").message, pairingFailure(ExportFailure(JoinerUpdateNeeded("the new PC")), "transfer"))
        assertEquals(JoinerUpdateNeeded("the new PC").message, exportFailureReason(JoinerUpdateNeeded("the new PC")))
    }

    @Test fun thisBuildAnnouncesCheckpointEnrollment() {
        assertTrue(SyncSnapshots.CHECKPOINT_FEATURE in SyncFeatures.ANNOUNCED)
        assertEquals(vectors.get("feature").asString, SyncSnapshots.CHECKPOINT_FEATURE)
    }
}
