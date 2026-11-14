package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.appearance.Appearance
import com.privatevault.app.appearance.StoredAppearance
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Protocol `appearance-v1`. The vector file is byte-identical in the Windows repository. */
class AppearanceSyncTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/appearance-v1.json")).readText()).asJsonObject
    private val contentKey = vectors.get("contentKeyHex").asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val vaultId = vectors.get("vaultId").asString
    private val mutationId = vectors.get("mutationId").asString
    private val decoder = Base64.getUrlDecoder()

    private fun change(ciphertext: String, nonce: String) = SyncChangeRecord(SYNC_FORMAT_VERSION, vaultId, "device-a", 1,
        GENESIS_HASH, mutationId, AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID, ChangeKind.UPSERT, 0,
        RecordVersion(mapOf("device-a" to 1L)), "2026-11-13T10:00:00Z", ciphertext, nonce, "signature", "hash")

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(SyncFeatures.APPEARANCE, vectors.get("feature").asString)
        assertEquals(AppearanceSyncRules.ENTITY_TYPE, vectors.get("entityType").asString)
        assertEquals(AppearanceSyncRules.ENTITY_ID, vectors.get("entityId").asString)
        assertEquals(vectors.get("aad").asString, AppearanceSyncRules.aad(vaultId, mutationId))
        assertTrue(SyncFeatures.APPEARANCE in SyncFeatures.ANNOUNCED)
        assertTrue(RemovalCutoff.FEATURE in SyncFeatures.ANNOUNCED)
        assertEquals(SyncFeatures.ANNOUNCED, RemovalCutoff.FEATURES)
    }

    @Test fun everyVectorCaseDecryptsAndParsesOrIsRejected() {
        val cases = vectors.getAsJsonArray("cases")
        assertEquals(3, cases.size())
        cases.map { it.asJsonObject }.forEach { case ->
            val name = case.get("name").asString
            val ciphertext = case.get("payloadCiphertext").asString
            val nonce = case.get("payloadNonce").asString
            val plaintext = case.get("plaintext").asString
            // The generic applier path decrypts it (the AAD comes from the entity type).
            val decrypted = IncomingEntryChangeApplier.decryptBytes(change(ciphertext, nonce), contentKey)
            assertEquals(name, plaintext, decrypted.toString(Charsets.UTF_8))
            // The writer's encryption produces the same bytes for the same nonce.
            val encrypted = AppearanceSyncRules.encrypt(contentKey, vectors.get("aad").asString, decoder.decode(nonce),
                plaintext.toByteArray(Charsets.UTF_8))
            assertArrayEquals(name, decoder.decode(ciphertext), encrypted)
            val expected = case.get("expected")
            if (expected.isJsonNull) {
                assertThrows(name, IllegalArgumentException::class.java) { AppearanceSyncRules.parsePayload(plaintext) }
            } else {
                val ids = expected.asJsonObject
                assertEquals(name, Appearance(ids.get("accent").asString, ids.get("surface").asString),
                    AppearanceSyncRules.parsePayload(plaintext))
            }
        }
    }

    @Test fun theWriterPayloadIsTheVectorPlaintext() {
        val known = vectors.getAsJsonArray("cases")[0].asJsonObject
        assertEquals(known.get("plaintext").asString,
            AppearanceSyncRules.encodePayload(Appearance("blue", "slate")).toString(Charsets.UTF_8))
    }

    @Test fun aWrongAadDoesNotDecrypt() {
        val known = vectors.getAsJsonArray("cases")[0].asJsonObject
        val other = change(known.get("payloadCiphertext").asString, known.get("payloadNonce").asString)
            .copy(entityId = "other")
        assertThrows(javax.crypto.AEADBadTagException::class.java) { IncomingEntryChangeApplier.decryptBytes(other, contentKey) }
    }

    @Test fun malformedPayloadsAreRejected() {
        listOf("", "[]", "{}", "{\"appearance\":1}", "{\"appearance\":{\"accent\":\"blue\"}}",
            "{\"appearance\":{\"accent\":1,\"surface\":\"slate\"}}", "{\"appearance\":{\"accent\":\"blue\",\"surface\":\"\"}}",
            "{\"appearance\":{\"accent\":\"9blue\",\"surface\":\"slate\"}}",
            "{\"appearance\":{\"accent\":\"${"a".repeat(33)}\",\"surface\":\"slate\"}}").forEach { json ->
            assertThrows(json, IllegalArgumentException::class.java) { AppearanceSyncRules.parsePayload(json) }
        }
        assertEquals(Appearance("a${"b".repeat(31)}", "slate"),
            AppearanceSyncRules.parsePayload("{\"appearance\":{\"accent\":\"a${"b".repeat(31)}\",\"surface\":\"slate\"}}"))
    }

    @Test fun everyLastWriterWinsVectorHolds() {
        val cases = vectors.getAsJsonArray("lastWriterWins")
        assertEquals(6, cases.size())
        cases.map { it.asJsonObject }.forEach { case ->
            val current = case.get("current").takeIf { !it.isJsonNull }?.asJsonObject
            val incoming = case.getAsJsonObject("incoming")
            assertEquals(case.toString(), case.get("incomingWins").asBoolean, AppearanceSyncRules.incomingWins(
                current?.get("at")?.asString, current?.get("device")?.asString,
                incoming.get("at").asString, incoming.get("device").asString))
        }
    }

    @Test fun missingOrUnreadableValuesLose() {
        assertTrue(AppearanceSyncRules.incomingWins("2026-11-13T10:00:00Z", null, "2026-11-13T10:00:00Z", "a"))
        assertTrue(AppearanceSyncRules.incomingWins("not a time", "z", "2026-11-13T10:00:00Z", "a"))
        assertFalse(AppearanceSyncRules.incomingWins("2026-11-13T10:00:00Z", "a", "not a time", "z"))
        // An offset is the same instant as its UTC form.
        assertTrue(AppearanceSyncRules.incomingWins("2026-11-13T10:00:00Z", "a", "2026-11-13T11:00:01+01:00", "a"))
    }

    private fun member(id: String, active: Boolean = true) = SyncMembershipEntity("vault", id, "Device", "key",
        if (active) MemberStatus.ACTIVE.name else MemberStatus.REVOKED.name, "me", 1, 1)

    @Test fun theGateOpensOnlyWhenEveryOtherActiveMemberAnnouncedTheFeature() {
        val members = listOf(member("me"), member("phone"), member("pc"), member("old", active = false))
        val both = listOf(RemovalCutoff.FEATURE, SyncFeatures.APPEARANCE)
        assertTrue(AppearanceSyncRules.gateOpen("me", members, mapOf("phone" to both, "pc" to listOf(SyncFeatures.APPEARANCE))))
        assertFalse("one active member without the feature",
            AppearanceSyncRules.gateOpen("me", members, mapOf("phone" to both, "pc" to listOf(RemovalCutoff.FEATURE))))
        assertFalse("one active member never seen over Wi-Fi", AppearanceSyncRules.gateOpen("me", members, mapOf("phone" to both)))
        // A removed member does not count, with or without features.
        assertTrue(AppearanceSyncRules.gateOpen("me", members, mapOf("phone" to both, "pc" to both, "old" to emptyList())))
        assertFalse("not enrolled: no other member", AppearanceSyncRules.gateOpen("me", listOf(member("me")), emptyMap()))
        assertFalse("removed itself", AppearanceSyncRules.gateOpen("me",
            listOf(member("me", active = false), member("phone")), mapOf("phone" to both)))
        assertFalse("not a member", AppearanceSyncRules.gateOpen("me", listOf(member("phone")), mapOf("phone" to both)))
    }

    private fun stored(at: String?, device: String?, pending: Boolean) =
        StoredAppearance(Appearance("blue", "slate"), at, device, pending)

    @Test fun incomingChangesFollowVersionsThenLastWriterWins() {
        val early = "2026-11-13T10:00:00Z"
        val late = "2026-11-13T11:00:00Z"
        fun decide(relation: VersionRelation, value: StoredAppearance, at: String, local: String? = "me") =
            AppearanceSyncRules.decide(relation, value, local, at, "peer")
        // A change that includes everything seen here applies, even with an older clock.
        assertEquals(AppearanceOutcome.APPLY, decide(VersionRelation.AFTER, stored(late, "x", false), early))
        assertEquals(AppearanceOutcome.OBSOLETE, decide(VersionRelation.EQUAL, stored(early, "x", false), late))
        assertEquals(AppearanceOutcome.OBSOLETE, decide(VersionRelation.BEFORE, stored(early, "x", false), late))
        assertEquals(AppearanceOutcome.APPLY, decide(VersionRelation.CONCURRENT, stored(early, "x", false), late))
        assertEquals(AppearanceOutcome.KEEP_LOCAL, decide(VersionRelation.CONCURRENT, stored(late, "x", false), early))
        // A never-written local choice competes by time, even against a dominating change.
        assertEquals(AppearanceOutcome.KEEP_LOCAL, decide(VersionRelation.AFTER, stored(late, null, true), early))
        assertEquals(AppearanceOutcome.APPLY, decide(VersionRelation.AFTER, stored(early, null, true), late))
        // At the same instant the pending value carries this device's ID ("me" < "peer", "zz" > "peer").
        assertEquals(AppearanceOutcome.APPLY, decide(VersionRelation.AFTER, stored(early, null, true), early))
        assertEquals(AppearanceOutcome.KEEP_LOCAL, decide(VersionRelation.AFTER, stored(early, null, true), early, "zz"))
        // A value never set (defaults) loses to anything.
        assertEquals(AppearanceOutcome.APPLY, decide(VersionRelation.CONCURRENT, stored(null, null, false), early))
    }

    @Test fun mergedVersionsDominateBothSides() {
        val local = RecordVersion(mapOf("a" to 3L, "b" to 1L))
        val incoming = RecordVersion(mapOf("b" to 2L, "c" to 1L))
        val merged = AppearanceSyncRules.merge(local, incoming)
        assertEquals(RecordVersion(mapOf("a" to 3L, "b" to 2L, "c" to 1L)), merged)
        assertEquals(VersionRelation.AFTER, merged.relationTo(local))
        assertEquals(VersionRelation.AFTER, merged.relationTo(incoming))
        assertEquals(incoming, AppearanceSyncRules.merge(RecordVersion(emptyMap()), incoming))
    }

    private fun operation(device: String, sequence: Long, version: Map<String, Long>, at: String,
        type: String = AppearanceSyncRules.ENTITY_TYPE) = SyncOperationEntity("m-$device-$sequence", 1, "vault", device,
        sequence, GENESIS_HASH, type, if (type == AppearanceSyncRules.ENTITY_TYPE) AppearanceSyncRules.ENTITY_ID else "x",
        "upsert", 0, RecordVersion(version).toJson(), at, "c", "n", "s", "h-$device-$sequence")

    @Test fun aSnapshotSettlesOnTheLastWriterAmongUndominatedChanges() {
        val a1 = operation("a", 1, mapOf("a" to 1L), "2026-11-13T12:00:00Z")
        val b1 = operation("b", 1, mapOf("b" to 1L), "2026-11-13T10:00:00Z")
        val entry = operation("c", 1, mapOf("c" to 1L), "2026-11-13T13:00:00Z", type = "entry")
        assertEquals(a1, AppearanceSyncRules.current(listOf(b1, a1, entry)))
        // b2 includes a1, so a1's later clock no longer matters.
        val b2 = operation("b", 2, mapOf("a" to 1L, "b" to 2L), "2026-11-13T11:00:00Z")
        assertEquals(b2, AppearanceSyncRules.current(listOf(a1, b1, b2)))
        assertEquals(b2, AppearanceSyncRules.current(listOf(b2, b1, a1)))
        assertNull(AppearanceSyncRules.current(listOf(entry)))
    }
}
