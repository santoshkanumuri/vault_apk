package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The `nv1` names and envelope against the vectors shared with Windows (`fixtures/cloud-sync-v1.json`). */
class CloudSyncFormatTest {
    private val vectors: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.classLoader?.getResource("cloud-sync-v1.json")).readText()).asJsonObject

    private fun text(name: String) = vectors.get(name).asString
    private fun unhex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test fun namesAndEnvelopeMatchTheSharedVectors() {
        val keys = requireNotNull(CloudKeys.derive(unhex(text("transportSecretHex")), text("vaultId")))
        assertEquals(text("cloudKeyHex"), keys.keyHex())
        assertEquals(text("prefix"), keys.prefix)
        val device = text("deviceId")
        assertEquals(text("deviceTag"), keys.deviceTag(device))
        assertEquals(text("membershipName"), keys.membershipName(3, "ab".repeat(32)))
        assertEquals(text("operationsName"), keys.operationsName(device, 7, 42))
        assertEquals(text("progressName"), keys.progressName(device))
        assertEquals(text("blobName"), keys.blobName("cd".repeat(32)))
        val name = text("operationsName")
        val plain = text("plaintext").toByteArray(Charsets.UTF_8)
        val sealed = keys.sealWithNonce(name, plain, unhex(text("nonceHex")))
        assertEquals(text("sealedHex"), hex(sealed))
        assertArrayEquals(plain, keys.open(name, sealed))
        assertNull("the name is bound to the content", keys.open(text("progressName"), sealed))
        assertNull("another group cannot open it", CloudKeys.derive(ByteArray(32) { 9 }, "vault-a")!!.open(name, sealed))
        assertNull("a random nonce still opens; a cut file does not", keys.open(name, sealed.copyOf(sealed.size - 1)))
        assertArrayEquals(plain, keys.open(name, keys.seal(name, plain)))
        assertNull(CloudKeys.derive(ByteArray(31), "vault-a"))
    }

    @Test fun namesParseBackAndForeignNamesAreIgnored() {
        val keys = requireNotNull(CloudKeys.derive(ByteArray(32) { 5 }, "vault-a"))
        val tag = keys.deviceTag("phone")
        assertEquals(CloudName.Operations(tag, 1, 256), keys.parse(keys.operationsName("phone", 1, 256)))
        assertEquals(CloudName.Membership(4, "ab".repeat(8)), keys.parse(keys.membershipName(4, "ab".repeat(32))))
        assertEquals(CloudName.Progress(tag), keys.parse(keys.progressName("phone")))
        assertEquals(CloudName.Blob("cd".repeat(32)), keys.parse(keys.blobName("cd".repeat(32))))
        val prefix = keys.prefix
        for (name in listOf(
            "notes.txt",
            "nv1.${"0".repeat(16)}.o.$tag.000000000001.000000000002",
            "${prefix}o.$tag.000000000005.000000000002",
            "${prefix}o.$tag.000000000000.000000000002",
            "${prefix}o.$tag.1.2",
            "${prefix}o.${tag.uppercase()}.000000000001.000000000002",
            "${prefix}o.$tag.00000000000١.000000000002",
            "${prefix}m.0000000000.${"ab".repeat(8)}",
            "${prefix}m.0000000001.${"ab".repeat(8)}.extra",
            "${prefix}b.${"CD".repeat(32)}",
            "${prefix}p.$tag.x",
            "${prefix}x.$tag",
        )) assertNull(name, keys.parse(name))
        assertEquals(20L, cloudCoverage(listOf(1L to 10L, 11L to 20L, 5L to 12L)))
        assertEquals("a gap stops the coverage", 10L, cloudCoverage(listOf(1L to 10L, 12L to 20L)))
        assertEquals(0L, cloudCoverage(listOf(2L to 10L)))
    }

    @Test fun driveRequestsAndAnswersMatchWindows() {
        val body = String(GoogleDrive.multipartBody("b1", "nv1.tag.p.device", "sealed".toByteArray()), Charsets.UTF_8)
        assertEquals("--b1\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"nv1.tag.p.device\",\"parents\":[\"appDataFolder\"]}\r\n--b1\r\nContent-Type: application/octet-stream\r\n\r\nsealed\r\n--b1--", body)
        val file = GoogleDrive.parseFile(JsonParser.parseString(
            """{"id":"f1","name":"n","size":"1234","modifiedTime":"2026-11-12T10:00:00.000Z"}""").asJsonObject)
        assertEquals(CloudFile("f1", "n", 1234, "2026-11-12T10:00:00.000Z"), file)
        assertNull(GoogleDrive.parseFile(JsonParser.parseString("""{"name":"n"}""").asJsonObject))
        assertEquals(CloudError.AUTH, GoogleDrive.classify(401, ""))
        assertEquals(CloudError.AUTH, GoogleDrive.classify(400, """{"error":"invalid_grant"}"""))
        assertEquals(CloudError.QUOTA, GoogleDrive.classify(403, """{"error":{"errors":[{"reason":"storageQuotaExceeded"}]}}"""))
        assertEquals(CloudError.BUSY, GoogleDrive.classify(403, """{"error":{"errors":[{"reason":"userRateLimitExceeded"}]}}"""))
        assertEquals(CloudError.NOT_FOUND, GoogleDrive.classify(404, ""))
        assertEquals(CloudError.BUSY, GoogleDrive.classify(429, ""))
        assertEquals(CloudError.BUSY, GoogleDrive.classify(503, ""))
        assertEquals(CloudError.OTHER, GoogleDrive.classify(400, "bad request"))
        assertEquals("2026-11-12T09:50:00Z", CloudSyncPass.relistFrom("2026-11-12T10:00:00.000Z"))
        assertNull(CloudSyncPass.relistFrom(""))
        assertNotNull(CloudSyncState().forGroup("v").normalized())
    }
}
