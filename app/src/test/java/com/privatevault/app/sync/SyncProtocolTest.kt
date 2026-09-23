package com.privatevault.app.sync

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class SyncProtocolTest {
    @Test
    fun comparesConcurrentVersions() {
        val left = RecordVersion(mapOf("device-a" to 2, "device-b" to 1))
        val right = RecordVersion(mapOf("device-a" to 1, "device-b" to 2))

        assertEquals(VersionRelation.CONCURRENT, left.relationTo(right))
    }

    @Test
    fun comparesOrderedVersions() {
        val earlier = RecordVersion(mapOf("device-a" to 1))
        val later = RecordVersion(mapOf("device-a" to 2, "device-b" to 1))

        assertEquals(VersionRelation.BEFORE, earlier.relationTo(later))
        assertEquals(VersionRelation.AFTER, later.relationTo(earlier))
    }

    @Test
    fun rejectsEmptyRecordVersion() {
        assertThrows(IllegalArgumentException::class.java) {
            RecordVersion(emptyMap()).validate()
        }
    }

    @Test
    fun readsSharedFoundationFixture() {
        val json = checkNotNull(
            javaClass.classLoader?.getResourceAsStream("protocol/sync-foundation-v1.json"),
        ).bufferedReader().use { it.readText() }
        val fixture = Gson().fromJson(json, FoundationFixture::class.java)

        assertEquals(SYNC_FORMAT_VERSION, fixture.formatVersion)
        assertEquals(PAIRING_CODE_DIGITS, fixture.pairingCodeDigits)
        assertEquals(RECOVERY_SECRET_BITS, fixture.recoverySecretBits)
        fixture.membership.validate()
        fixture.keyEnvelope.validate()
        fixture.frontier.validate()
        fixture.change.recordVersion.validate()
        assertEquals(fixture.expectedChangeHash, fixture.change.computeHash())
        assertEquals(fixture.expectedChangeHash, fixture.change.hash)
    }

    @Test
    fun signsCanonicalOperationBytes() {
        val privateSeed = ByteArray(DeviceIdentityCrypto.PRIVATE_SEED_BYTES) { it.toByte() }
        val publicKey = DeviceIdentityCrypto.publicKey(privateSeed)
        val change = SyncChangeRecord.createSigned(
            vaultId = "vault-a",
            deviceId = "device-a",
            sequence = 1,
            previousHash = GENESIS_HASH,
            mutationId = "mutation-a",
            entityType = "entry",
            entityId = "entry-a",
            kind = ChangeKind.UPSERT,
            baseRevision = 0,
            recordVersion = RecordVersion(mapOf("device-a" to 1)),
            occurredAtUtc = "2026-09-22T12:00:00Z",
            payloadCiphertext = "ciphertext",
            payloadNonce = "nonce",
            signer = { DeviceIdentityCrypto.sign(privateSeed, it) },
        )
        val signature = java.util.Base64.getUrlDecoder().decode(change.deviceSignature)

        change.validate()
        assertEquals(
            "op50N3zIarrPOcIDnVStIIufMEMpoDsSvhyVvoBmhm3Xt6MKstl08Ka4o1ETreGaWl3cbeTD8iVREtgvyoqlBg",
            change.deviceSignature,
        )
        assertEquals("e01a1d9910027109f4a8745691c9fcf691a1a786c6823dadf0d9f9588184aac8", change.hash)
        assertTrue(DeviceIdentityCrypto.verify(publicKey, change.signingBytes(), signature))
        assertTrue(!DeviceIdentityCrypto.verify(publicKey, change.copy(entityId = "changed").signingBytes(), signature))
    }
}

private data class FoundationFixture(
    val formatVersion: Int,
    val pairingCodeDigits: Int,
    val recoverySecretBits: Int,
    val membership: DeviceMembership,
    val keyEnvelope: VaultKeyEnvelope,
    val frontier: SyncFrontier,
    val change: SyncChangeRecord,
    val expectedChangeHash: String,
)
