package com.privatevault.app.sync

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncOperationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.DataInputStream
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The cross-app vector `enrollment-realistic-v1.json` (written on an emulator by
 * `RealisticEnrollmentFixtureTest`, scripts/sync-vectors/enrollment_realistic.ps1): the exact snapshot v4
 * the phone sends to a reset PC after a realistic history. Byte-identical in the Windows repository,
 * where `checkpoint.rs` imports it end to end. Here this app's own validator checks it.
 */
class RealisticEnrollmentVectorTest {
    private val vector: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/enrollment-realistic-v1.json")).readText()).asJsonObject
    private val vaultId = vector.get("vaultId").asString
    private val expected = vector.getAsJsonObject("expected")

    private fun state(): SnapshotSyncState {
        val stream = Base64.getDecoder().decode(vector.get("stream").asString)
        val key = vector.get("pairingKeyHex").asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val data = DataInputStream(stream.inputStream())
        val magic = ByteArray(8).also(data::readFully)
        assertEquals("NUVSYNC4", String(magic, Charsets.US_ASCII))
        val nonce = ByteArray(12).also(data::readFully)
        val sealed = ByteArray(data.readInt()).also(data::readFully)
        val plain = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            updateAAD(magic)
            doFinal(sealed)
        }
        return Gson().fromJson(plain.toString(Charsets.UTF_8), SnapshotSyncState::class.java)
    }

    @Test fun theSnapshotOfARealisticHistoryValidatesHere() {
        val state = state()
        val host = vector.getAsJsonObject("host")
        validateSnapshotState(state, vaultId, vector.getAsJsonObject("joiner").get("deviceId").asString,
            DeviceIdentity(host.get("deviceId").asString, Base64.getUrlDecoder().decode(host.get("publicKey").asString)),
            allowLineage = true)
        assertEquals(expected.get("operations").asInt, state.operations.size)
        assertEquals(expected.get("tombstones").asInt, state.tombstones.size)
        assertEquals(expected.get("openConflicts").asInt, state.conflicts.size)
        assertEquals(listOf("2", "3"), state.contentKeys.orEmpty().keys.sorted())
    }

    /** What the history holds, so a regenerated vector still covers every case this test is for. */
    @Test fun theHistoryCoversTheCasesThatBrokePairing() {
        val state = state()
        val removed = vector.getAsJsonArray("removed").map { it.asString }.toSet()
        val types = state.operations.map { it.entityType }.toSet()
        assertTrue(types.containsAll(setOf("entry", "group", "passkey", "device_name", AppearanceSyncRules.ENTITY_TYPE,
            SiteIconSyncRules.ENTITY_TYPE, SignInMethodSyncRules.ENTITY_TYPE)))
        // Changes of rotated epochs carry `e<N>.` nonces: the form Windows 2.3.1 refused.
        assertEquals(setOf(1L, 2L, 3L), state.operations.mapNotNull { EpochKeys.epochOf(it.payloadNonce) }.toSet())
        // Both removed devices are revoked with a cutoff, and their changes are still in the history.
        assertEquals(removed, state.members.filter { it.status == MemberStatus.REVOKED.name }.map { it.deviceId }.toSet())
        assertEquals(removed, RemovalCutoff.cutoffsOf(state.membershipEvents).keys)
        assertTrue(state.operations.any { it.deviceId in removed })
        assertTrue(state.tombstones.any { it.entityType == "entry" })
        assertTrue(state.tombstones.any { it.entityType == SignInMethodSyncRules.ENTITY_TYPE })
        // Compacted: at least one author's held changes have gaps.
        assertTrue(!SyncSnapshots.fullHistory(state.heads, state.operations.map {
            com.privatevault.app.data.SyncOperationSummary(it.deviceId, it.sequence, it.hash, it.entityType, it.entityId,
                it.kind, it.recordVersionJson, it.occurredAtUtc)
        }))
        // The phone's next change after the join chains on its checkpoint head.
        val next = Gson().fromJson(vector.get("next"), SyncOperationEntity::class.java)
        val head = state.heads.single { it.deviceId == next.deviceId }
        assertEquals(head.sequence + 1, next.sequence)
        assertEquals(head.hash, next.previousHash)
        assertEquals(3L, EpochKeys.epochOf(next.payloadNonce))
    }
}
