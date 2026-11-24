package com.privatevault.app.sync

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** History compaction against a real Room database: what goes, what stays, and that sync goes on with gaps. */
class HistoryCompactionDatabaseTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    @Test fun compactionKeepsWitnessesWaitsForOfflineMembersAndRelaysAroundGaps() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val identity = AndroidDeviceIdentityStore(context)
        val vaultId = "compaction-vault"
        val key = ByteArray(32) { 7 }
        try {
            identity.clear()
            val phone = identity.getOrCreate()
            val peers = listOf("pc", "tablet")
            db.dao().saveSettings(VaultSettings(vaultId = vaultId))
            db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = vaultId,
                contentKey = b64.encodeToString(ByteArray(32) { 3 }), transportSecret = b64.encodeToString(ByteArray(32) { 8 })))
            var events = listOf(SyncMembershipEvent.sign(vaultId, 1, GENESIS_HASH, MembershipAction.GENESIS,
                phone.deviceId, phone.deviceId, phone.publicKeyBase64Url, 1, identity::sign))
            peers.forEachIndexed { index, peer ->
                events = events + SyncMembershipEvent.sign(vaultId, events.size + 1L, events.last().hash, MembershipAction.ADD,
                    phone.deviceId, peer, b64.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { (40 + index).toByte() })),
                    1, identity::sign)
            }
            events.forEach { db.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
            SyncMembershipManager.verify(events).members.forEach { db.syncDao().upsertMembership(it) }
            val writer = LocalEntryChangeWriter(db, identity)
            repeat(30) { writer.save(VaultEntry(id = "a", type = EntryType.NOTE, title = "A $it"), emptySet(), key) }
            repeat(20) { writer.save(VaultEntry(id = "b", type = EntryType.NOTE, title = "B $it"), emptySet(), key) }
            val head = requireNotNull(db.syncDao().deviceHead(vaultId, phone.deviceId))
            assertEquals(50L, head.sequence)
            val applied = SyncProgress(mapOf(phone.deviceId to SyncChainHead(head.sequence, head.hash)),
                mapOf(phone.deviceId to SyncChainHead(head.sequence, head.hash)))
            fun mirror(progress: Map<String, SyncProgress>) = TransportMirror(vaultId, phone.deviceId, phone.publicKeyBase64Url,
                b64.encodeToString(ByteArray(32) { 8 }), 1, emptyList(), emptyList(), emptyList(), emptyList(), peerProgress = progress)

            // The tablet never reported: nothing goes.
            assertEquals(0, HistoryCompaction.run(db, mirror(mapOf("pc" to applied)), CloudSyncState(), force = true))
            assertEquals(50, db.syncDao().operations().size)
            // With Drive sync on, what the drive lacks stays even after every peer applied it.
            assertEquals(29, HistoryCompaction.run(db, mirror(mapOf("pc" to applied, "tablet" to applied)),
                CloudSyncState(enabled = true, vaultId = vaultId, covered = mapOf(phone.deviceId to 30L)), force = true))
            // Every member applied everything: only each item's witness (30 and the head 50) stays.
            assertEquals(19, HistoryCompaction.run(db, mirror(mapOf("pc" to applied, "tablet" to applied)), CloudSyncState(), force = true))
            assertEquals(listOf(30L, 50L), db.syncDao().operations().map { it.sequence })
            val record = HistoryCompaction.parse(requireNotNull(db.syncDao().checkpoint(vaultId)).floorJson)
            assertEquals(49L, record.floor.getValue(phone.deviceId).sequence)
            // Paced: a second run the same day does nothing.
            assertEquals(0, HistoryCompaction.run(db, mirror(mapOf("pc" to applied, "tablet" to applied)), CloudSyncState()))

            // Sync goes on: the next change chains onto the head, a peer at the head gets it, and a
            // peer below the gap gets nothing of this author from here.
            writer.save(VaultEntry(id = "a", type = EntryType.NOTE, title = "After"), emptySet(), key)
            val planner = SyncTransferPlanner(db)
            assertEquals(listOf(51L), planner.pendingFor("pc", SyncFrontier(mapOf(phone.deviceId to 50L))).map { it.sequence })
            assertTrue(planner.pendingFor("pc", SyncFrontier(mapOf(phone.deviceId to 10L))).isEmpty())
            // An older joiner can no longer get a full-history snapshot from this device.
            assertFalse(SyncSnapshots.fullHistory(db.syncDao().deviceHeads(vaultId), db.syncDao().operationSummaries(vaultId)))
        } finally {
            db.close()
            identity.clear()
        }
    }
}
