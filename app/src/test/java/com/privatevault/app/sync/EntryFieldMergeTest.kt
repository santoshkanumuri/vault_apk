package com.privatevault.app.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** M2, rule `entry-merge-v1`. The vector file is byte-identical in the Windows repository. */
class EntryFieldMergeTest {
    private val vectors = JSONObject(requireNotNull(javaClass.classLoader?.getResource("sync-vectors/entry-merge-v1.json")).readText())

    private fun payload(json: JSONObject, key: String): JSONObject? = json.optJSONObject(key)

    private fun version(json: JSONObject): RecordVersion =
        RecordVersion(json.keys().asSequence().associateWith { json.getLong(it) })

    @Test fun everyCaseMergesAsTheSharedRuleSays() {
        val cases = vectors.getJSONArray("cases")
        assertTrue(cases.length() >= 15)
        for (index in 0 until cases.length()) {
            val case = cases.getJSONObject(index)
            val name = case.getString("name")
            val type = case.getString("entityType")
            val base = payload(case, "base")
            val local = case.getJSONObject("local")
            val remote = case.getJSONObject("remote")
            val expected = payload(case, "expected")
            // Both directions: every device computes the same result, whichever side is its own.
            for ((first, second) in listOf(local to remote, remote to local)) {
                val merged = EntryFieldMerge.merge(type, base?.let { JSONObject(it.toString()) },
                    JSONObject(first.toString()), JSONObject(second.toString()))
                if (expected == null) assertNull(name, merged)
                else assertTrue("$name: $merged", EntryFieldMerge.same(expected, merged))
            }
        }
    }

    @Test fun theMergeVersionIncludesBothSidesWithNoExtraCount() {
        val versions = vectors.getJSONArray("versions")
        for (index in 0 until versions.length()) {
            val case = versions.getJSONObject(index)
            val local = version(case.getJSONObject("local"))
            val remote = version(case.getJSONObject("remote"))
            assertEquals(version(case.getJSONObject("meet")), ConflictVersions.meet(local, remote))
            assertEquals(version(case.getJSONObject("join")), ConflictVersions.join(local, remote))
            assertEquals(ConflictVersions.join(local, remote), ConflictVersions.join(remote, local))
            // The merge settles the conflict on every device, older versions included.
            assertTrue(ConflictVersions.settles(ConflictVersions.join(local, remote), local, remote))
        }
    }

    @Test fun twoDevicesMergingTheSamePairSeeEachOthersMergeAsEqual() {
        val phone = RecordVersion(mapOf("phone" to 2L))
        val pc = RecordVersion(mapOf("phone" to 1L, "pc" to 1L))
        val fromPhone = ConflictVersions.join(phone, pc)
        val fromPc = ConflictVersions.join(pc, phone)
        assertEquals(VersionRelation.EQUAL, fromPhone.relationTo(fromPc))
        assertEquals(VersionRelation.AFTER, fromPhone.relationTo(phone))
        assertEquals(VersionRelation.AFTER, fromPhone.relationTo(pc))
    }

    @Test fun numbersCompareByValue() {
        assertTrue(EntryFieldMerge.same(30, 30L))
        assertTrue(EntryFieldMerge.same(30, java.math.BigDecimal("30.0")))
        assertTrue(EntryFieldMerge.same(null, JSONObject.NULL))
        assertTrue(!EntryFieldMerge.same("30", 30))
        assertTrue(!EntryFieldMerge.same(true, 1))
    }
}
