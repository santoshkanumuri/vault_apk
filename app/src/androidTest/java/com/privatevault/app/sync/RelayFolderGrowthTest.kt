package com.privatevault.app.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The relay folder keeps a change only while a peer or the drive still needs it from this device,
 * and a publish after one edit reads only the new change ([RelayRetention], [LockedSyncStore.publish]).
 */
class RelayFolderGrowthTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun change(vaultId: String, author: String, signer: (ByteArray) -> ByteArray, sequence: Long, previous: String) =
        SyncChangeRecord.createSigned(vaultId, author, sequence, previous, UUID.randomUUID().toString(), "entry",
            "e-$author-$sequence", ChangeKind.UPSERT, 0, RecordVersion(mapOf(author to sequence)),
            "2026-01-01T00:00:00Z", "AAAA", "AAAAAAAAAAAAAAAA", signer).let { change ->
            SyncOperationEntity(change.mutationId, change.formatVersion, change.vaultId, change.deviceId,
                change.sequence, change.previousHash, change.entityType, change.entityId, "upsert", change.baseRevision,
                change.recordVersion.toJson(), change.occurredAtUtc, change.payloadCiphertext, change.payloadNonce,
                change.deviceSignature, change.hash)
        }

    private fun relayFiles(root: File) = File(root, "outgoing").listFiles().orEmpty().count { it.name.length == 64 }

    @Test fun relayFolderHoldsOnlyWhatPeersOrTheDriveStillNeed() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "relay-growth-${UUID.randomUUID()}").apply { mkdirs() }
        val statements = CopyOnWriteArrayList<String>()
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java)
            .setQueryCallback({ sql, _ -> statements += sql }, { it.run() }).build()
        val identity = AndroidDeviceIdentityStore(context)
        val vaultId = "relay-vault"
        val perAuthor = 1_000L
        try {
            identity.clear()
            val phone = identity.getOrCreate()
            val seeds = mapOf("pc" to ByteArray(32) { 21 }, "tablet" to ByteArray(32) { 22 })
            db.dao().saveSettings(VaultSettings(vaultId = vaultId))
            db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = vaultId,
                contentKey = b64.encodeToString(ByteArray(32) { 7 }), transportSecret = b64.encodeToString(ByteArray(32) { 8 })))
            var events = listOf(SyncMembershipEvent.sign(vaultId, 1, GENESIS_HASH, MembershipAction.GENESIS,
                phone.deviceId, phone.deviceId, phone.publicKeyBase64Url, 1, identity::sign))
            seeds.forEach { (device, seed) ->
                events = events + SyncMembershipEvent.sign(vaultId, events.size + 1L, events.last().hash,
                    MembershipAction.ADD, phone.deviceId, device, b64.encodeToString(DeviceIdentityCrypto.publicKey(seed)), 1,
                    identity::sign)
            }
            events.forEach { db.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
            SyncMembershipManager.verify(events).members.forEach { db.syncDao().upsertMembership(it) }
            // 3 authors with 1,000 changes each: the phone's own, and two peers' changes it applied.
            val heads = mutableMapOf<String, SyncChainHead>()
            val signers = seeds.mapValues { (_, seed) -> { bytes: ByteArray -> DeviceIdentityCrypto.sign(seed, bytes) } } +
                (phone.deviceId to identity::sign)
            signers.forEach { (author, signer) ->
                var previous = GENESIS_HASH
                for (sequence in 1..perAuthor) {
                    val operation = change(vaultId, author, signer, sequence, previous)
                    db.syncDao().insertOperation(operation)
                    previous = operation.hash
                }
                db.syncDao().upsertDeviceHead(SyncDeviceHeadEntity(vaultId, author, perAuthor, previous))
                heads[author] = SyncChainHead(perAuthor, previous)
            }
            val store = LockedSyncStore(context, root)
            store.publish(db)
            assertEquals("no peer reported progress, so everything stays", 3 * perAuthor.toInt(), relayFiles(root))

            // Every peer applied everything, and Drive sync is off: nothing needs to stay.
            val everything = SyncProgress(heads, heads)
            store.recordPeerProgress("pc", everything)
            store.recordPeerProgress("tablet", everything)
            store.publish(db)
            assertEquals(0, relayFiles(root))
            assertEquals(emptyList<SyncOperationEntity>(), store.pending(SyncFrontier(emptyMap())))

            // With Drive sync on, what the drive does not hold yet stays, for every author.
            store.updateCloudState { it.copy(enabled = true, vaultId = vaultId,
                covered = mapOf(phone.deviceId to 900L, "pc" to perAuthor, "tablet" to 500L)) }
            store.publish(db, full = true)
            assertEquals(100 + 500, relayFiles(root))
            assertEquals(501L, store.heldOperations("tablet", 500, 10).first().sequence)

            // One more edit: the publish reads only the new change, never the whole log.
            val last = change(vaultId, phone.deviceId, identity::sign, perAuthor + 1, heads.getValue(phone.deviceId).hash)
            db.syncDao().insertOperation(last)
            db.syncDao().upsertDeviceHead(SyncDeviceHeadEntity(vaultId, phone.deviceId, last.sequence, last.hash))
            statements.clear()
            store.publish(db)
            assertEquals(601, relayFiles(root))
            assertTrue(statements.none { it.contains("FROM sync_operations ORDER BY") })
            assertTrue(statements.any { it.contains("sequence > ?") })

            // A cold start reads the sealed index instead of every relay file, and still relays gap-free.
            assertTrue(File(root, "outgoing-index").exists())
            val restarted = LockedSyncStore(context, root)
            assertEquals(listOf(901L, 902L), restarted.pending(SyncFrontier(mapOf(phone.deviceId to 900L,
                "pc" to perAuthor, "tablet" to perAuthor)), relayRemoved = false).take(2).map { it.sequence })
            assertEquals(emptyList<SyncOperationEntity>(), restarted.pending(SyncFrontier(mapOf(phone.deviceId to 1001L,
                "pc" to perAuthor, "tablet" to 100L))))
        } finally {
            db.close()
            identity.clear()
            root.deleteRecursively()
        }
    }
}
