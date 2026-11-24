package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncTombstoneEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Protocol `sign-in-methods-v1`. The vector file (scripts/sync-vectors/sign_in_methods.py) is byte-identical in the Windows repository. */
class SignInMethodSyncTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/sign-in-methods-v1.json")).readText()).asJsonObject
    private val contentKey = vectors.get("contentKeyHex").asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val vaultId = vectors.get("vaultId").asString
    private val mutationId = vectors.get("mutationId").asString

    private fun method(json: JsonObject) = SignInMethodSyncRules.Method(json.get("site").asString, json.get("provider").asString,
        json.get("account").asString, json.get("firstUsedAt").asLong, json.get("lastUsedAt").asLong, json.get("useCount").asLong)

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(SyncFeatures.SIGN_IN_METHODS, vectors.get("feature").asString)
        assertEquals(SignInMethodSyncRules.ENTITY_TYPE, vectors.get("entityType").asString)
        assertEquals(SignInMethodSyncRules.ID_PREFIX, vectors.get("idPrefix").asString)
        assertEquals(SignInMethodSyncRules.MAX_ACCOUNT_CHARS, vectors.get("maxAccountChars").asInt)
        assertEquals(SignInMethodSyncRules.MAX_USE_COUNT, vectors.get("maxUseCount").asLong)
        assertEquals(SignInMethodSyncRules.PROVIDERS, vectors.getAsJsonArray("providers").map { it.asString })
        assertTrue(SyncFeatures.SIGN_IN_METHODS in SyncFeatures.ANNOUNCED)
    }

    @Test fun entityIdsAndAccountKeysMatchTheVectors() {
        vectors.getAsJsonArray("entityIds").map { it.asJsonObject }.forEach {
            val account = it.get("account").asString
            assertEquals(account, it.get("accountKey").asString, SignInMethodSyncRules.accountKey(account))
            assertEquals(account, it.get("entityId").asString,
                SignInMethodSyncRules.entityId(contentKey, it.get("site").asString, it.get("provider").asString, account))
        }
        // Another group (another content key) gives another id for the same method.
        assertTrue(SignInMethodSyncRules.entityId(ByteArray(32), "example.com", "github", "octocat") !=
            SignInMethodSyncRules.entityId(contentKey, "example.com", "github", "octocat"))
    }

    @Test fun sitesAndAccountsFollowTheSharedRules() {
        val sites = vectors.getAsJsonObject("sites")
        sites.getAsJsonArray("valid").forEach { assertTrue(it.asString, SiteIconSyncRules.validSite(it.asString)) }
        sites.getAsJsonArray("invalid").forEach { assertFalse(it.asString, SiteIconSyncRules.validSite(it.asString)) }
        val accounts = vectors.getAsJsonObject("accounts")
        accounts.getAsJsonArray("valid").forEach { assertTrue(it.asString, SignInMethodSyncRules.validAccount(it.asString)) }
        accounts.getAsJsonArray("invalid").forEach { assertFalse(it.asString, SignInMethodSyncRules.validAccount(it.asString)) }
    }

    @Test fun everyPayloadCaseDecryptsAndParsesOrIsRefused() {
        val cases = vectors.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(18, cases.size)
        cases.forEach { case ->
            val name = case.get("name").asString
            val entityId = case.get("entityId").asString
            val delete = case.get("kind").asString == "delete"
            val change = SyncChangeRecord(SYNC_FORMAT_VERSION, vaultId, "device-a", 1, GENESIS_HASH, mutationId,
                SignInMethodSyncRules.ENTITY_TYPE, entityId, if (delete) ChangeKind.DELETE else ChangeKind.UPSERT, 0,
                RecordVersion(mapOf("device-a" to 1L)), "2026-11-21T10:00:00Z", case.get("payloadCiphertext").asString,
                case.get("payloadNonce").asString, "signature", "hash")
            val plaintext = IncomingEntryChangeApplier.decryptBytes(change, contentKey).toString(Charsets.UTF_8)
            assertEquals(name, case.get("plaintext").asString, plaintext)
            val valid = case.get("valid").asBoolean
            if (delete) assertEquals(name, valid, SignInMethodSyncRules.isDelete(plaintext))
            else if (valid) assertEquals(name, method(case.getAsJsonObject("expected")),
                SignInMethodSyncRules.parsePayload(plaintext, entityId, contentKey))
            else assertThrows(name, IllegalArgumentException::class.java) {
                SignInMethodSyncRules.parsePayload(plaintext, entityId, contentKey)
            }
        }
    }

    @Test fun concurrentUpsertsMergeOnBothSidesAndDeletesFollowTheLastWriter() {
        vectors.getAsJsonArray("concurrentUpserts").map { it.asJsonObject }.forEach { case ->
            val current = case.getAsJsonObject("current")
            val incoming = case.getAsJsonObject("incoming")
            val merged = SignInMethodSyncRules.merge(method(current.getAsJsonObject("method")), current.get("at").asString,
                current.get("device").asString, method(incoming.getAsJsonObject("method")), incoming.get("at").asString,
                incoming.get("device").asString)
            assertEquals(case.get("name").asString, method(case.getAsJsonObject("merged")), merged)
            // The applier gives the same value for a concurrent change against a live row.
            val outcome = SignInMethodSyncRules.decide(VersionRelation.CONCURRENT, SignInMethodSyncRules.Local.Live(
                method(current.getAsJsonObject("method")), current.get("at").asString, current.get("device").asString),
                method(incoming.getAsJsonObject("method")), incoming.get("at").asString, incoming.get("device").asString)
            assertEquals(merged, (outcome as SignInMethodSyncRules.Outcome.Store).method)
        }
        vectors.getAsJsonArray("upsertVersusDelete").map { it.asJsonObject }.forEach { case ->
            val upsertWins = case.get("result").asString == "upsert"
            val (deleteAt, deleteDevice) = case.get("deleteAt").asString to case.get("deleteDevice").asString
            val (upsertAt, upsertDevice) = case.get("upsertAt").asString to case.get("upsertDevice").asString
            assertEquals(case.toString(), upsertWins, SignInMethodSyncRules.upsertBeatsDelete(deleteAt, deleteDevice, upsertAt, upsertDevice))
            val octo = SignInMethodSyncRules.Method("example.com", "github", "octocat", 1, 2, 1)
            // An upsert arriving at a tombstone, and a delete arriving at a live row: the same order decides.
            assertEquals(case.toString(), if (upsertWins) SignInMethodSyncRules.Outcome.Store(octo, upsertAt, upsertDevice)
                else SignInMethodSyncRules.Outcome.KeepLocal, SignInMethodSyncRules.decide(VersionRelation.CONCURRENT,
                SignInMethodSyncRules.Local.Deleted(deleteAt, deleteDevice), octo, upsertAt, upsertDevice))
            assertEquals(case.toString(), if (upsertWins) SignInMethodSyncRules.Outcome.KeepLocal else SignInMethodSyncRules.Outcome.Delete,
                SignInMethodSyncRules.decide(VersionRelation.CONCURRENT, SignInMethodSyncRules.Local.Live(octo, upsertAt, upsertDevice),
                    null, deleteAt, deleteDevice))
        }
    }

    @Test fun versionsDecideFirstAndAWaitingDeleteCompetes() {
        val octo = SignInMethodSyncRules.Method("example.com", "github", "octocat", 1, 2, 1)
        val live = SignInMethodSyncRules.Local.Live(octo, "2030-01-01T00:00:00Z", "z")
        val newer = octo.copy(lastUsedAt = 9, useCount = 4)
        // A change that includes the stored one applies even with an earlier clock; older or equal is obsolete.
        assertEquals(SignInMethodSyncRules.Outcome.Store(newer, "2026-01-01T00:00:00Z", "a"),
            SignInMethodSyncRules.decide(VersionRelation.AFTER, live, newer, "2026-01-01T00:00:00Z", "a"))
        assertEquals(SignInMethodSyncRules.Outcome.Obsolete, SignInMethodSyncRules.decide(VersionRelation.BEFORE, live, newer, "2031-01-01T00:00:00Z", "z"))
        assertEquals(SignInMethodSyncRules.Outcome.Obsolete, SignInMethodSyncRules.decide(VersionRelation.EQUAL, live, null, "2031-01-01T00:00:00Z", "z"))
        assertEquals(SignInMethodSyncRules.Outcome.Delete, SignInMethodSyncRules.decide(VersionRelation.AFTER, live, null, "2026-01-01T00:00:00Z", "a"))
        // A later use elsewhere brings back a method this phone is still waiting to delete; an earlier one does not.
        val waiting = SignInMethodSyncRules.Local.PendingDelete(octo, "2026-06-01T00:00:00Z", "phone")
        assertEquals(SignInMethodSyncRules.Outcome.Store(newer, "2026-07-01T00:00:00Z", "pc"),
            SignInMethodSyncRules.decide(VersionRelation.AFTER, waiting, newer, "2026-07-01T00:00:00Z", "pc"))
        assertEquals(SignInMethodSyncRules.Outcome.KeepLocal,
            SignInMethodSyncRules.decide(VersionRelation.AFTER, waiting, newer, "2026-05-01T00:00:00Z", "pc"))
        // The first change of a method always applies.
        assertEquals(SignInMethodSyncRules.Outcome.Store(octo, "2026-01-01T00:00:00Z", "a"),
            SignInMethodSyncRules.decide(VersionRelation.AFTER, SignInMethodSyncRules.Local.None, octo, "2026-01-01T00:00:00Z", "a"))
    }

    private fun op(entity: String, device: String, sequence: Long, version: Map<String, Long>, at: String,
        method: SignInMethodSyncRules.Method?): SyncOperationEntity {
        val mutation = "m-$device-$sequence"
        val plaintext = if (method == null) SignInMethodSyncRules.deletePayload() else JsonObject().apply {
            add("signInMethod", JsonObject().apply {
                addProperty("site", method.site); addProperty("provider", method.provider); addProperty("account", method.account)
                addProperty("firstUsedAt", method.firstUsedAt); addProperty("lastUsedAt", method.lastUsedAt)
                addProperty("useCount", method.useCount)
            })
        }.toString().toByteArray()
        val nonce = ByteArray(12) { sequence.toByte() }
        val ciphertext = AppearanceSyncRules.encrypt(contentKey, SignInMethodSyncRules.aad(vaultId, mutation, entity), nonce, plaintext)
        val encoder = Base64.getUrlEncoder().withoutPadding()
        return SyncOperationEntity(mutation, SYNC_FORMAT_VERSION, vaultId, device, sequence, GENESIS_HASH, SignInMethodSyncRules.ENTITY_TYPE,
            entity, if (method == null) "delete" else "upsert", 0, RecordVersion(version).toJson(), at,
            encoder.encodeToString(ciphertext), encoder.encodeToString(nonce), "s", "h-$device-$sequence")
    }

    private fun read(operation: SyncOperationEntity): SignInMethodSyncRules.Method? {
        val change = SyncChangeRecord(operation.formatVersion, operation.vaultId, operation.deviceId, operation.sequence,
            operation.previousHash, operation.mutationId, operation.entityType, operation.entityId,
            if (operation.kind == "upsert") ChangeKind.UPSERT else ChangeKind.DELETE, operation.baseRevision,
            RecordVersion.parse(operation.recordVersionJson), operation.occurredAtUtc, operation.payloadCiphertext,
            operation.payloadNonce, operation.deviceSignature, operation.hash)
        val json = IncomingEntryChangeApplier.decryptBytes(change, contentKey).toString(Charsets.UTF_8)
        return if (operation.kind == "upsert") SignInMethodSyncRules.parsePayload(json, operation.entityId, contentKey)
            else { require(SignInMethodSyncRules.isDelete(json)); null }
    }

    @Test fun aJoiningDeviceMergesTheLatestChangesAndHonoursALaterDelete() {
        val octo = SignInMethodSyncRules.Method("example.com", "github", "octocat", 100, 500, 7)
        val id = SignInMethodSyncRules.entityId(contentKey, "example.com", "github", "octocat")
        val old = op(id, "pc", 1, mapOf("pc" to 1L), "2026-11-20T09:00:00Z", octo.copy(useCount = 1))
        val pc = op(id, "pc", 2, mapOf("pc" to 2L), "2026-11-20T10:00:00Z", octo)
        val laptop = op(id, "laptop", 1, mapOf("pc" to 1L, "laptop" to 1L), "2026-11-20T10:00:05Z",
            octo.copy(account = "OctoCat", firstUsedAt = 50, lastUsedAt = 400, useCount = 9))
        val google = SignInMethodSyncRules.Method("example.com", "google", "me@example.com", 1, 2, 1)
        val googleId = SignInMethodSyncRules.entityId(contentKey, "example.com", "google", "me@example.com")
        val kept = op(googleId, "pc", 3, mapOf("pc" to 3L), "2026-11-20T10:00:00Z", google)
        val removed = op(googleId, "phone", 1, mapOf("phone" to 1L), "2026-11-20T11:00:00Z", null)
        val forged = op(SignInMethodSyncRules.entityId(contentKey, "example.com", "x", "a"), "pc", 4, mapOf("pc" to 4L),
            "2026-11-20T10:00:00Z", google)
        val chosen = SignInMethodSyncRules.current(listOf(old, pc, laptop, kept, removed, forged), ::read).associateBy { it.entityId }
        assertEquals(setOf(id, googleId), chosen.keys)
        assertEquals(octo.copy(account = "OctoCat", firstUsedAt = 50, lastUsedAt = 500, useCount = 9), chosen.getValue(id).method)
        assertEquals(laptop, chosen.getValue(id).operation)
        assertNull(chosen.getValue(googleId).method)
    }

    @Test fun theKeepSetKeepsEveryChangeNoOtherIncludesAndTombstones() {
        fun change(device: String, sequence: Long, entity: String, kind: String, version: Map<String, Long>) =
            KeepSet.Change(device, sequence, "$device-$sequence", SignInMethodSyncRules.ENTITY_TYPE, entity, kind,
                RecordVersion(version).toJson(), "2026-11-20T10:00:00Z")
        val changes = listOf(
            change("pc", 1, "a", "upsert", mapOf("pc" to 1L)),
            change("pc", 2, "a", "upsert", mapOf("pc" to 2L)),
            change("phone", 1, "a", "upsert", mapOf("pc" to 1L, "phone" to 1L)),
            change("pc", 3, "b", "upsert", mapOf("pc" to 3L)),
            change("phone", 2, "b", "delete", mapOf("pc" to 3L, "phone" to 2L)),
            change("pc", 4, "c", "upsert", mapOf("pc" to 4L)),
        )
        val tombstones = listOf(SyncTombstoneEntity("t", SignInMethodSyncRules.ENTITY_TYPE, "b", "phone", 2,
            RecordVersion(mapOf("pc" to 3L, "phone" to 2L)).toJson(), "2026-11-20T10:00:00Z", "phone-2"))
        // The same answer as Windows `checkpoint::keep_set` (sign_in_methods_keep_every_change_no_other_change_includes).
        assertEquals(setOf("pc-2", "pc-4", "phone-1", "phone-2"), KeepSet.compute(changes, emptyList(), tombstones, emptyList()))
    }

    @Test fun theWriteGateNeedsEveryOtherActiveMemberToAnnounceTheFeature() {
        fun member(id: String, status: MemberStatus = MemberStatus.ACTIVE) =
            SyncMembershipEntity("v", id, id, "key", status.name, "me", 1, 1)
        val members = listOf(member("me"), member("pc"), member("old", MemberStatus.REVOKED))
        val all = listOf(SyncFeatures.APPEARANCE, SyncFeatures.SIGN_IN_METHODS)
        assertTrue(SignInMethodSyncRules.gateOpen("me", members, mapOf("pc" to all)))
        assertFalse("a 2.3.x PC", SignInMethodSyncRules.gateOpen("me", members, mapOf("pc" to listOf(SyncFeatures.APPEARANCE))))
        assertFalse("a lone device never writes", SignInMethodSyncRules.gateOpen("me", listOf(member("me")), emptyMap()))
        assertFalse("not a member", SignInMethodSyncRules.gateOpen("gone", members, mapOf("pc" to all)))
    }
}
