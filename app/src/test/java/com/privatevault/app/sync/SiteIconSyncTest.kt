package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncedSiteIcon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Protocol `site-icons-v1`. The vector file (scripts/sync-vectors/site_icons.py) is byte-identical in the Windows repository. */
class SiteIconSyncTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/site-icons-v1.json")).readText()).asJsonObject
    private val contentKey = vectors.get("contentKeyHex").asString.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private val vaultId = vectors.get("vaultId").asString
    private val mutationId = vectors.get("mutationId").asString

    private fun change(entityId: String, ciphertext: String, nonce: String) = SyncChangeRecord(SYNC_FORMAT_VERSION, vaultId,
        "device-a", 1, GENESIS_HASH, mutationId, SiteIconSyncRules.ENTITY_TYPE, entityId, ChangeKind.UPSERT, 0,
        RecordVersion(mapOf("device-a" to 1L)), "2026-11-21T10:00:00Z", ciphertext, nonce, "signature", "hash")

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(SyncFeatures.SITE_ICONS, vectors.get("feature").asString)
        assertEquals(SiteIconSyncRules.ENTITY_TYPE, vectors.get("entityType").asString)
        assertEquals(SiteIconSyncRules.ID_PREFIX, vectors.get("idPrefix").asString)
        assertEquals(SiteIconSyncRules.MAX_PNG_BYTES, vectors.get("maxPngBytes").asInt)
        assertEquals(SiteIconSyncRules.MIN_SIDE, vectors.get("minSide").asInt)
        assertEquals(SiteIconSyncRules.MAX_SIDE, vectors.get("maxSide").asInt)
        assertTrue(SyncFeatures.SITE_ICONS in SyncFeatures.ANNOUNCED)
    }

    @Test fun entityIdsHideTheSiteAndMatchTheVectors() {
        vectors.getAsJsonArray("entityIds").map { it.asJsonObject }.forEach {
            val site = it.get("site").asString
            val id = SiteIconSyncRules.entityId(contentKey, site)
            assertEquals(site, it.get("entityId").asString, id)
            assertTrue(Regex("[0-9a-f]{32}").matches(id))
            assertFalse(id.contains(site.substringBefore('.')))
        }
        // Another group (another content key) gives another id for the same site.
        assertTrue(SiteIconSyncRules.entityId(ByteArray(32), "github.com") != SiteIconSyncRules.entityId(contentKey, "github.com"))
    }

    @Test fun sitesFollowTheSharedRules() {
        val sites = vectors.getAsJsonObject("sites")
        sites.getAsJsonArray("valid").forEach { assertTrue(it.asString, SiteIconSyncRules.validSite(it.asString)) }
        sites.getAsJsonArray("invalid").forEach { assertFalse(it.asString, SiteIconSyncRules.validSite(it.asString)) }
    }

    @Test fun pngHeadersFollowTheSharedRules() {
        val pngs = vectors.getAsJsonArray("pngs").map { it.asJsonObject }
        assertEquals(11, pngs.size)
        pngs.forEach {
            val bytes = Base64.getDecoder().decode(it.get("base64").asString)
            assertEquals(it.get("name").asString, it.get("valid").asBoolean, SiteIconSyncRules.validPng(bytes))
        }
    }

    @Test fun everyPayloadCaseDecryptsAndParsesOrIsRejected() {
        val cases = vectors.getAsJsonArray("cases").map { it.asJsonObject }
        assertEquals(9, cases.size)
        cases.forEach { case ->
            val name = case.get("name").asString
            val entityId = case.get("entityId").asString
            val plaintext = case.get("plaintext").asString
            // The generic applier path decrypts it (the AAD comes from the entity type and id).
            val decrypted = IncomingEntryChangeApplier.decryptBytes(
                change(entityId, case.get("payloadCiphertext").asString, case.get("payloadNonce").asString), contentKey)
            assertEquals(name, plaintext, decrypted.toString(Charsets.UTF_8))
            if (case.get("valid").asBoolean) {
                val icon = SiteIconSyncRules.parsePayload(plaintext, entityId, contentKey)
                val expected = case.getAsJsonObject("expected")
                assertEquals(name, expected.get("site").asString, icon.site)
                assertEquals(name, expected.get("pngSha256").asString, sha256(icon.png))
            } else {
                assertThrows(name, IllegalArgumentException::class.java) {
                    SiteIconSyncRules.parsePayload(plaintext, entityId, contentKey)
                }
            }
        }
    }

    @Test fun concurrentChangesFollowLastWriterWins() {
        vectors.getAsJsonArray("lastWriterWins").map { it.asJsonObject }.forEach {
            val stored = SyncedSiteIcon("github.com", ByteArray(1), it.get("currentAt").asString, 0, it.get("currentDevice").asString)
            val outcome = SiteIconSyncRules.decide(VersionRelation.CONCURRENT, stored, it.get("incomingAt").asString,
                it.get("incomingDevice").asString)
            assertEquals(it.toString(), if (it.get("incomingWins").asBoolean) AppearanceOutcome.APPLY else AppearanceOutcome.KEEP_LOCAL, outcome)
        }
    }

    @Test fun causalOrderDecidesBeforeTime() {
        val stored = SyncedSiteIcon("github.com", ByteArray(1), "2030-01-01T00:00:00Z", 0, "z")
        // A change that includes the stored one applies, even with an earlier clock.
        assertEquals(AppearanceOutcome.APPLY, SiteIconSyncRules.decide(VersionRelation.AFTER, stored, "2026-01-01T00:00:00Z", "a"))
        assertEquals(AppearanceOutcome.OBSOLETE, SiteIconSyncRules.decide(VersionRelation.BEFORE, stored, "2031-01-01T00:00:00Z", "z"))
        assertEquals(AppearanceOutcome.OBSOLETE, SiteIconSyncRules.decide(VersionRelation.EQUAL, stored, "2031-01-01T00:00:00Z", "z"))
        // The first icon for a site always applies.
        assertEquals(AppearanceOutcome.APPLY, SiteIconSyncRules.decide(VersionRelation.CONCURRENT, null, "2026-01-01T00:00:00Z", "a"))
    }

    @Test fun aJoiningDeviceTakesTheLatestChangeOfEachSite() {
        fun op(entity: String, device: String, version: Map<String, Long>, at: String, sequence: Long) = SyncOperationEntity(
            "$entity-$device-$sequence", SYNC_FORMAT_VERSION, vaultId, device, sequence, GENESIS_HASH, SiteIconSyncRules.ENTITY_TYPE, entity, "upsert",
            0, RecordVersion(version).toJson(), at, "c", "n", "s", "h-$entity-$device-$sequence")
        val first = op("a", "pc", mapOf("pc" to 1L), "2026-11-21T10:00:00Z", 1)
        val replaced = op("a", "pc", mapOf("pc" to 2L), "2026-11-20T10:00:00Z", 2)
        val concurrentEarly = op("b", "pc", mapOf("pc" to 3L), "2026-11-21T10:00:00Z", 3)
        val concurrentLate = op("b", "laptop", mapOf("laptop" to 1L), "2026-11-21T11:00:00Z", 1)
        val other = op("c", "pc", mapOf("pc" to 4L), "2026-11-21T10:00:00Z", 4).copy(entityType = "entry")
        val chosen = SiteIconSyncRules.current(listOf(first, replaced, concurrentEarly, concurrentLate, other)).associateBy { it.entityId }
        assertEquals(setOf("a", "b"), chosen.keys)
        // A change that includes another wins even when its clock is earlier.
        assertEquals(replaced, chosen["a"])
        assertEquals(concurrentLate, chosen["b"])
    }

    @Test fun driveProgressFeaturesAreReadOnlyWhenPresentAndWellFormed() {
        fun body(json: String) = JsonParser.parseString(json).asJsonObject
        assertNull(peerFeatures(body("""{"deviceId":"a","updatedAt":1,"progress":{}}""")))
        assertEquals(setOf("appearance-v1", "site-icons-v1"),
            peerFeatures(body("""{"features":["site-icons-v1","appearance-v1"]}""")))
        assertEquals(emptySet<String>(), peerFeatures(body("""{"features":[]}""")))
        assertThrows(IllegalArgumentException::class.java) { peerFeatures(body("""{"features":"site-icons-v1"}""")) }
        assertThrows(IllegalArgumentException::class.java) { peerFeatures(body("""{"features":[1]}""")) }
        assertThrows(IllegalArgumentException::class.java) { peerFeatures(body("""{"features":[""]}""")) }
        assertThrows(IllegalArgumentException::class.java) {
            peerFeatures(body("""{"features":[${(1..33).joinToString(",") { "\"f$it\"" }}]}"""))
        }
    }
}
