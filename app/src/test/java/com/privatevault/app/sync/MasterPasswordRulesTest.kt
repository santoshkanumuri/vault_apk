package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Protocol `master-password-v1`. The vector file (scripts/sync-vectors/master_password.py) is byte-identical in the Windows repository. */
class MasterPasswordRulesTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("sync-vectors/master-password-v1.json")).readText()).asJsonObject

    private fun JsonObject.text(key: String) = get(key).asString
    private fun JsonObject.longOrNull(key: String) = get(key)?.takeUnless { it.isJsonNull }?.asLong

    @Test fun theVectorFileDescribesThisProtocol() {
        assertEquals(MasterPasswordRules.FEATURE, vectors.text("feature"))
        assertEquals(SyncFeatures.MASTER_PASSWORD, MasterPasswordRules.FEATURE)
        assertEquals(MasterPasswordRules.ENTITY_TYPE, vectors.text("entityType"))
        assertEquals(MasterPasswordRules.LABEL, vectors.text("label"))
        assertEquals(MasterPasswordRules.MEMORY_KIB, vectors.get("memoryKib").asInt)
        assertEquals(MasterPasswordRules.MIN_ITERATIONS, vectors.get("minIterations").asInt)
        assertEquals(MasterPasswordRules.MAX_ITERATIONS, vectors.get("maxIterations").asInt)
        assertTrue(SyncFeatures.MASTER_PASSWORD in SyncFeatures.ANNOUNCED)
    }

    @Test fun aCheckMatchesOnlyItsPasswordVaultAndEpoch() {
        vectors.getAsJsonArray("checks").map { it.asJsonObject }.forEach { case ->
            val name = case.text("name")
            val check = requireNotNull(MasterPasswordRules.parseCheck(case.getAsJsonObject("payload").toString())) { name }
            assertEquals(name, case.text("checkHex"), check.checkHex())
            case.getAsJsonArray("variants").map { it.asJsonObject }.forEach { variant ->
                val tested = check.withEpoch(variant.get("epoch").asLong)
                assertEquals("$name: $variant", variant.get("matches").asBoolean,
                    tested.matches(variant.text("vaultId"), variant.text("password").toCharArray()))
            }
        }
    }

    @Test fun aCheckThisPhoneMakesIsTheOneInTheVectors() {
        vectors.getAsJsonArray("checks").map { it.asJsonObject }.forEach { case ->
            val payload = case.getAsJsonObject("payload")
            val parsed = requireNotNull(MasterPasswordRules.parseCheck(payload.toString()))
            val salt = java.util.Base64.getDecoder().decode(payload.text("salt"))
            val made = MasterPasswordRules.create(case.text("vaultId"), case.text("password").toCharArray(), parsed.epoch,
                parsed.membershipSequence, salt, parsed.iterations)
            assertEquals(case.text("name"), parsed, made)
            assertEquals(case.text("name"), payload, made.toPayload())
        }
    }

    @Test fun payloadsOutsideTheProtocolAreRefused() {
        val payloads = vectors.getAsJsonObject("payloads")
        val check = payloads.getAsJsonObject("check")
        check.getAsJsonArray("valid").forEach {
            val parsed = MasterPasswordRules.parseCheck(it.toString())
            assertNotNull(it.toString(), parsed)
            assertEquals(it, requireNotNull(parsed).toPayload())
        }
        check.getAsJsonArray("invalid").forEach { assertNull(it.toString(), MasterPasswordRules.parseCheck(it.toString())) }
        val switched = payloads.getAsJsonObject("switched")
        switched.getAsJsonArray("valid").forEach {
            val epoch = requireNotNull(MasterPasswordRules.parseSwitched(it.toString())) { it.toString() }
            assertEquals(it, MasterPasswordRules.switchedPayload(epoch))
        }
        switched.getAsJsonArray("invalid").forEach { assertNull(it.toString(), MasterPasswordRules.parseSwitched(it.toString())) }
    }

    @Test fun aChangeIsACheckUnderTheVaultIdAndASwitchUnderItsOwnDeviceId() {
        vectors.getAsJsonArray("records").map { it.asJsonObject }.forEach { case ->
            val record = MasterPasswordRules.read(case.text("vaultId"), case.text("author"), case.text("entityId"),
                case.text("kind") == "upsert", case.getAsJsonObject("payload").toString())
            val kind = when (record) {
                is MasterPasswordRules.Record.CheckRecord -> "check"
                is MasterPasswordRules.Record.Switched -> "switched"
                null -> "invalid"
            }
            assertEquals(case.text("name"), case.text("expected"), kind)
        }
    }

    private fun candidate(value: JsonObject) = MasterPasswordRules.Candidate(value.text("author"),
        value.get("hash")?.asString ?: "hash", MasterPasswordRules.Check(value.get("epoch").asLong,
            value.get("membershipSequence").asLong, ByteArray(16), MasterPasswordRules.MIN_ITERATIONS, ByteArray(32)))

    @Test fun theCurrentCheckComesFromTheManagerOfItsTime() {
        vectors.getAsJsonArray("current").map { it.asJsonObject }.forEach { case ->
            val managers = case.getAsJsonArray("managers").map { it.asString }
            val candidates = case.getAsJsonArray("candidates").map { it.asJsonObject }
                // The checked constructor refuses sequence 0, as parsing does: such a check never exists here.
                .filter { it.get("membershipSequence").asLong >= 1 }.map(::candidate)
            val chosen = MasterPasswordRules.current(candidates, case.get("head").asLong) { managers.getOrNull(it.toInt() - 1) }
            val expected = case.get("expected").takeUnless { it.isJsonNull }?.asString
            assertEquals(case.text("name"), expected, chosen?.hash)
        }
    }

    @Test fun aDeviceHoldsThePasswordOnceItSwitchedOrJoinedAfter() {
        vectors.getAsJsonArray("satisfied").map { it.asJsonObject }.forEach { case ->
            assertEquals(case.text("name"), case.get("expected").asBoolean, MasterPasswordRules.satisfied(case.text("device"),
                candidate(case.getAsJsonObject("current")), case.longOrNull("switchedEpoch"), case.longOrNull("addedAt")))
        }
    }

    @Test fun theTextOfACheckLeavesOutTheSaltAndTheTag() {
        val check = MasterPasswordRules.Check(2, 3, ByteArray(16) { 0x5A }, 3, ByteArray(32) { 0x6B })
        assertEquals("Check(epoch=2, membershipSequence=3)", check.toString())
        assertFalse(check.toString().contains("90"))
    }

    @Test fun onlyStrictJsonIsRead() {
        assertEquals(2L, MasterPasswordRules.parseSwitched("""{"switchedEpoch":2}"""))
        listOf("""{switchedEpoch:2}""", """{'switchedEpoch':2}""", """{"switchedEpoch":2} {}""",
            """{"switchedEpoch":2,}""", """/* x */{"switchedEpoch":2}""", """{"switchedEpoch":02}""",
            """{"switchedEpoch":2e0}""", "[2]", "").forEach { assertNull(it, MasterPasswordRules.parseSwitched(it)) }
    }

    @Test fun theManagerMovesOnlyWithGenesisAndTransfer() {
        val events = listOf("GENESIS" to "phone-a", "ADD" to "pc", "ADD" to "phone-b", "OFFER_TRANSFER" to "phone-b",
            "ACCEPT_TRANSFER" to "phone-b", "TRANSFER" to "phone-b", "REMOVE" to "pc", "ADD" to "pc")
        assertEquals(listOf("phone-a", "phone-a", "phone-a", "phone-a", "phone-a", "phone-b", "phone-b", "phone-b"),
            MasterPasswordRules.managersAfter(events))
        assertEquals(1L, MasterPasswordRules.addedAt(events, "phone-a"))
        assertEquals(8L, MasterPasswordRules.addedAt(events, "pc"))
        // A transfer does not add its subject.
        assertEquals(3L, MasterPasswordRules.addedAt(events, "phone-b"))
        assertNull(MasterPasswordRules.addedAt(events, "tablet"))
        assertEquals(emptyList<String>(), MasterPasswordRules.managersAfter(emptyList()))
    }

    @Test fun theGateNamesEveryDeviceThatDidNotAnnounceTheFeature() {
        val features = mapOf("pc" to listOf(SyncFeatures.APPEARANCE, MasterPasswordRules.FEATURE), "tablet" to listOf(SyncFeatures.APPEARANCE))
        assertEquals(listOf("tablet", "watch"), MasterPasswordRules.devicesWithoutFeature(listOf("pc", "tablet", "watch"), features))
        assertEquals(emptyList<String>(), MasterPasswordRules.devicesWithoutFeature(listOf("pc"), features))
    }
}
