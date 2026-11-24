package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.EncryptedPhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * A lost managing device (design item 4): "Mark as lost" freezes the old group on each survivor, a
 * survivor starts a new group, and another survivor joins it keeping its items, matched by ID.
 * Items that differ become conflicts on both devices; nothing is decided by "newest wins".
 */
class LostManagerMergeTest {
    private class TestContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "nobackup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val managerSeed = ByteArray(32) { 21 }
    private val signManager: (ByteArray) -> ByteArray = { DeviceIdentityCrypto.sign(managerSeed, it) }

    /** The old group: the lost phone "manager" added [members], in that order. */
    private suspend fun oldGroup(database: VaultDatabase, members: List<DeviceIdentity>): List<SyncMembershipEvent> {
        val events = mutableListOf(SyncMembershipEvent.sign("old-vault", 1, GENESIS_HASH, MembershipAction.GENESIS,
            "manager", "manager", encoder.encodeToString(DeviceIdentityCrypto.publicKey(managerSeed)), 1, signManager))
        members.forEach { member ->
            events += SyncMembershipEvent.sign("old-vault", events.size + 1L, events.last().hash, MembershipAction.ADD,
                "manager", member.deviceId, member.publicKeyBase64Url, 1, signManager)
        }
        database.dao().saveSettings(VaultSettings(vaultId = "old-vault"))
        database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "old-vault",
            contentKey = encoder.encodeToString(ByteArray(32) { 3 }), transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
        events.forEach { database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
        SyncMembershipManager.verify(events).members.forEach { database.syncDao().upsertMembership(it) }
        return events
    }

    private suspend fun copyChanges(from: VaultDatabase, author: String, to: VaultDatabase, key: ByteArray) {
        val known = to.syncDao().deviceHead(from.dao().settings()!!.vaultId, author)?.sequence ?: 0L
        from.syncDao().operations().filter { it.deviceId == author && it.sequence > known }.sortedBy { it.sequence }
            .forEach { IncomingEntryChangeApplier(to, announceRemoteChanges = false).apply(it, key) }
    }

    @Test
    fun aSurvivorStartsANewGroupAndAnotherJoinsKeepingItsItemsWithoutDuplicates() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "lost-manager-${UUID.randomUUID()}").apply { mkdirs() }
        val tabletContext = TestContext(base, File(root, "tablet"), UUID.randomUUID().toString())
        val phoneContext = TestContext(base, File(root, "phone"), UUID.randomUUID().toString())
        val tablet = Room.inMemoryDatabaseBuilder(tabletContext, VaultDatabase::class.java).build()
        val phone = Room.inMemoryDatabaseBuilder(phoneContext, VaultDatabase::class.java).build()
        val tabletIdentity = AndroidDeviceIdentityStore(tabletContext)
        val phoneIdentity = AndroidDeviceIdentityStore(phoneContext)
        try {
            val key = ByteArray(32) { 9 }
            val pairingKey = ByteArray(32) { 7 }
            val oldTablet = tabletIdentity.getOrCreate()
            val oldPhone = phoneIdentity.getOrCreate()
            oldGroup(tablet, listOf(oldTablet, oldPhone))
            oldGroup(phone, listOf(oldTablet, oldPhone))
            val tabletWriter = LocalEntryChangeWriter(tablet, tabletIdentity)
            val phoneWriter = LocalEntryChangeWriter(phone, phoneIdentity)
            fun note(id: String, title: String) = VaultEntry(id = id, type = EntryType.NOTE, title = title)
            // Shared history: both have every item.
            listOf(note("same", "Same"), note("later", "Later"), note("gone", "Gone"), note("both", "Both"))
                .forEach { tabletWriter.save(it, emptySet(), key) }
            copyChanges(tablet, oldTablet.deviceId, phone, key)
            // Then each goes on alone: the phone edits two items and adds one; the tablet edits one and deletes one.
            phoneWriter.save(note("later", "Later on the phone"), emptySet(), key)
            phoneWriter.save(note("both", "Both on the phone"), emptySet(), key)
            phoneWriter.save(note("only", "Only on the phone"), emptySet(), key)
            tabletWriter.save(note("both", "Both on the tablet"), emptySet(), key)
            tabletWriter.delete(note("gone", "Gone"), key)

            // Each survivor marks the managing phone as lost; the tablet starts the new group.
            tablet.markManagerLost(tabletIdentity)
            phone.markManagerLost(phoneIdentity)
            assertEquals("manager", phone.syncDao().lostManager("old-vault")?.deviceId)
            val newVault = tablet.startGroupAfterLostManager(tabletIdentity)
            assertNull(tablet.syncDao().lostManager("old-vault"))
            assertEquals(setOf("same", "later", "gone", "both"), tablet.syncDao().lineage().map { it.entityId }.toSet())
            assertTrue(tablet.syncDao().lineage().single { it.entityId == "gone" }.deleted)
            val founder = tabletIdentity.getOrCreate()
            assertNotEquals(oldTablet.deviceId, founder.deviceId)

            // The phone pairs with the tablet. Both announce the merge join.
            val joining = SyncMembershipEntity.from(DeviceMembership(newVault, oldPhone.deviceId, "Phone",
                oldPhone.publicKeyBase64Url, MemberStatus.ACTIVE, founder.deviceId, 2, 1))
            val output = ByteArrayOutputStream()
            val admission = SyncSnapshot(tabletContext, tablet, EncryptedPhotoStore(tabletContext)).export(output, key, pairingKey,
                joining, joinerFeatures = setOf(SyncSnapshots.CHECKPOINT_FEATURE, MergeJoinRules.FEATURE))
            val joiner = SyncSnapshot(phoneContext, phone, EncryptedPhotoStore(phoneContext))
            val merged = joiner.prepare(output.toByteArray().inputStream(), key, pairingKey, newVault, oldPhone.deviceId, founder,
                setOf(MergeJoinRules.FEATURE)).use { joiner.commit(it, merge = true) }
            tablet.syncDao().insertMembershipEvent(requireNotNull(admission))
            tablet.syncDao().upsertMembership(joining)

            assertEquals(MergeJoin.Summary(alreadyThere = 1, conflicts = 2, added = 1, dropped = 1), merged?.first)
            val items = phone.dao().allEntries().map { it.entry }
            assertEquals("no duplicates, IDs kept", listOf("both", "later", "only", "same"), items.map { it.id }.sorted())
            assertEquals("Later on the phone", items.single { it.id == "later" }.title)
            assertEquals("Both on the phone", items.single { it.id == "both" }.title)
            assertEquals(listOf("Gone"), phone.dao().trash().map { it.title })
            assertEquals(newVault, phone.dao().settings()?.vaultId)
            assertNull(phone.syncDao().lostManager("old-vault"))
            assertEquals("old-vault", phone.syncDao().lineage().first().fromVaultId)
            // Both versions of each conflict open for review on the phone.
            val phoneReviews = SyncConflictResolver(phone, phoneIdentity).reviews(key)
            assertEquals(setOf("later", "both"), phone.syncDao().conflicts().map { it.entityId }.toSet())
            assertTrue(phoneReviews.any { it.current.description.startsWith("Later on the phone") && it.incoming.description.startsWith("Later") })
            assertTrue(phoneReviews.any { it.incoming.description.startsWith("Both on the tablet") })

            // The tablet receives the phone's changes: the same two conflicts, and the phone's new item.
            copyChanges(phone, oldPhone.deviceId, tablet, key)
            assertEquals(setOf("later", "both"), tablet.syncDao().conflicts().filter { it.resolvedAtUtc.isEmpty() }.map { it.entityId }.toSet())
            assertEquals("Both on the tablet", tablet.dao().entry("both")?.entry?.title)
            assertEquals("Only on the phone", tablet.dao().entry("only")?.entry?.title)
            assertEquals(2, SyncConflictResolver(tablet, tabletIdentity).reviews(key).size)

            // Choosing on one device settles the conflict on the other.
            SyncConflictResolver(phone, phoneIdentity).resolve(phone.syncDao().conflicts().single { it.entityId == "both" }.conflictId,
                useIncoming = true, localKey = key)
            copyChanges(phone, oldPhone.deviceId, tablet, key)
            assertEquals(listOf("later"), tablet.syncDao().conflicts().filter { it.resolvedAtUtc.isEmpty() }.map { it.entityId })
            assertEquals("Both on the tablet", phone.dao().entry("both")?.entry?.title)
        } finally {
            tablet.close(); phone.close()
            tabletIdentity.clear(); phoneIdentity.clear()
            root.deleteRecursively()
        }
    }

    /**
     * M3: a device that left the old group earlier joins through its saved lineage. An item it edited
     * after leaving was never seen by the group, so it becomes a conflict instead of being replaced
     * by the group's copy; an item it did not touch follows the group.
     */
    @Test
    fun anItemEditedAfterLeavingBecomesAConflictInsteadOfBeingReplaced() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "lineage-edit-${UUID.randomUUID()}").apply { mkdirs() }
        val tabletContext = TestContext(base, File(root, "tablet"), UUID.randomUUID().toString())
        val phoneContext = TestContext(base, File(root, "phone"), UUID.randomUUID().toString())
        val tablet = Room.inMemoryDatabaseBuilder(tabletContext, VaultDatabase::class.java).build()
        val phone = Room.inMemoryDatabaseBuilder(phoneContext, VaultDatabase::class.java).build()
        val tabletIdentity = AndroidDeviceIdentityStore(tabletContext)
        val phoneIdentity = AndroidDeviceIdentityStore(phoneContext)
        try {
            val key = ByteArray(32) { 9 }
            val oldTablet = tabletIdentity.getOrCreate()
            val oldPhone = phoneIdentity.getOrCreate()
            oldGroup(tablet, listOf(oldTablet, oldPhone))
            oldGroup(phone, listOf(oldTablet, oldPhone))
            val tabletWriter = LocalEntryChangeWriter(tablet, tabletIdentity)
            listOf(note("edited", "Seen by the group"), note("moved", "Old title")).forEach { tabletWriter.save(it, emptySet(), key) }
            copyChanges(tablet, oldTablet.deviceId, phone, key)
            // The phone leaves; it keeps the lineage (with each item's content digest), then edits one item.
            phone.leaveSyncGroup(phoneIdentity)
            assertTrue(phone.syncDao().lineage().all { it.contentDigest.length == 64 })
            LocalEntryChangeWriter(phone, phoneIdentity).save(note("edited", "Edited after leaving"), emptySet(), key)
            // Meanwhile the tablet renames the other item, then founds the new group from the same old group.
            tabletWriter.save(note("moved", "New title"), emptySet(), key)
            tablet.markManagerLost(tabletIdentity)
            val newVault = tablet.startGroupAfterLostManager(tabletIdentity)
            val founder = tabletIdentity.getOrCreate()

            val joiner = phoneIdentity.getOrCreate()
            val joining = SyncMembershipEntity.from(DeviceMembership(newVault, joiner.deviceId, "Phone",
                joiner.publicKeyBase64Url, MemberStatus.ACTIVE, founder.deviceId, 2, 1))
            val output = ByteArrayOutputStream()
            SyncSnapshot(tabletContext, tablet, EncryptedPhotoStore(tabletContext)).export(output, key, ByteArray(32) { 7 },
                joining, joinerFeatures = setOf(SyncSnapshots.CHECKPOINT_FEATURE, MergeJoinRules.FEATURE))
            val snapshot = SyncSnapshot(phoneContext, phone, EncryptedPhotoStore(phoneContext))
            val merged = snapshot.prepare(output.toByteArray().inputStream(), key, ByteArray(32) { 7 }, newVault, joiner.deviceId, founder,
                setOf(MergeJoinRules.FEATURE)).use { snapshot.commit(it, merge = true) }

            // The untouched item follows the group; the one edited after leaving is a conflict, both copies kept.
            assertEquals("New title", phone.dao().entry("moved")?.entry?.title)
            assertEquals(1, merged?.first?.conflicts)
            assertEquals(listOf("edited"), phone.syncDao().conflicts().map { it.entityId })
            assertEquals("Edited after leaving", phone.dao().entry("edited")?.entry?.title)
        } finally {
            tablet.close(); phone.close()
            tabletIdentity.clear(); phoneIdentity.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun aLostManagerCanNotAddDevicesOrChangesOnWifiOrDrive() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "lost-freeze-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, File(root, "pc"), UUID.randomUUID().toString())
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val identities = AndroidDeviceIdentityStore(context)
        val locked = LockedSyncStore(context, File(root, "transport"))
        try {
            val key = ByteArray(32) { 9 }
            val local = identities.getOrCreate()
            val otherKey = ByteArray(32) { 22 }
            val other = DeviceIdentity("other", DeviceIdentityCrypto.publicKey(otherKey))
            val events = oldGroup(database, listOf(local, other))
            fun managerChange(sequence: Long, previous: String, title: String): SyncOperationEntity {
                val mutation = UUID.randomUUID().toString()
                val payload = org.json.JSONObject().put("entry", note("e1", title).toSyncJson()).put("groupIds", org.json.JSONArray())
                val sealed = SyncPayloadCrypto.encrypt(ByteArray(32) { 3 }, "entry", "old-vault", mutation, "e1",
                    payload.toString().toByteArray())
                val change = SyncChangeRecord.createSigned("old-vault", "manager", sequence, previous, mutation, "entry", "e1",
                    ChangeKind.UPSERT, sequence - 1, RecordVersion(mapOf("manager" to sequence)), "2026-11-23T00:00:00Z",
                    sealed.ciphertext, sealed.nonce, signManager)
                return with(SyncPayloadCrypto) { change.toEntity() }
            }
            val first = managerChange(1, GENESIS_HASH, "Before")
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(database, announceRemoteChanges = false).apply(first, key))
            locked.publish(database)
            database.markManagerLost(identities)
            assertEquals(1L, database.syncDao().lostManager("old-vault")?.headSequence)
            locked.publish(database)
            val mirror = requireNotNull(locked.snapshot())
            assertEquals("manager", mirror.lostManager?.deviceId)

            // Whoever holds the lost phone signs a new device and a change. Wi-Fi and Google Drive
            // both hand them to the transport store: it keeps the old membership and refuses the change.
            val rogue = SyncMembershipEvent.sign("old-vault", events.size + 1L, events.last().hash, MembershipAction.ADD,
                "manager", "rogue", encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 99 })), 1, signManager)
            locked.acceptMembershipEvents(mirror.membershipEvents + SyncMembershipEventEntity.from(rogue))
            assertEquals(events.size, locked.snapshot()?.membershipEvents?.size)
            assertNull(mirrorWithMembership(mirror, mirror.membershipEvents + SyncMembershipEventEntity.from(rogue)))
            val second = managerChange(2, first.hash, "After")
            assertTrue(runCatching { locked.queue(second) }.isFailure)
            assertTrue(runCatching { verifyIncomingOperation(mirror, second) }.isFailure)
            verifyIncomingOperation(mirror, first)
            // A change that arrived before the mark does not apply either.
            assertTrue(runCatching { IncomingEntryChangeApplier(database, announceRemoteChanges = false).apply(second, key) }.isFailure)
            assertEquals("Before", database.dao().entry("e1")?.entry?.title)
            // Only a member that does not manage can mark; the other survivor still syncs.
            assertTrue(LostManagerFreeze.allowsChange(mirror.lostManager, "other", 900, "ff"))

            // "It's back": the same change applies.
            database.clearManagerLost()
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(database, announceRemoteChanges = false).apply(second, key))
            assertEquals("After", database.dao().entry("e1")?.entry?.title)
        } finally {
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun aPopulatedJoinerNeedsAHostThatMerges() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "merge-skew-${UUID.randomUUID()}").apply { mkdirs() }
        val sourceContext = TestContext(base, File(root, "source"), UUID.randomUUID().toString())
        val targetContext = TestContext(base, File(root, "target"), UUID.randomUUID().toString())
        val source = Room.inMemoryDatabaseBuilder(sourceContext, VaultDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(targetContext, VaultDatabase::class.java).build()
        val sourceIdentity = AndroidDeviceIdentityStore(sourceContext)
        val targetIdentity = AndroidDeviceIdentityStore(targetContext)
        try {
            val key = ByteArray(32) { 9 }
            val pairingKey = ByteArray(32) { 7 }
            source.dao().saveSettings(VaultSettings(vaultId = "source-vault"))
            target.dao().saveSettings(VaultSettings(vaultId = "target-vault"))
            val creator = sourceIdentity.getOrCreate()
            val joiner = targetIdentity.getOrCreate()
            source.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("source-vault", creator.deviceId,
                "Source", creator.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 1, 1)))
            source.prepareSyncGroup(sourceIdentity)
            source.syncDao().insertLineage(listOf(SyncLineageEntity("older-vault", "entry", "x", "{\"counters\":{\"a\":1}}", false)))
            LocalEntryChangeWriter(source, sourceIdentity).save(note("host", "Host"), emptySet(), key)
            target.dao().insertEntry(note("mine", "Mine"))
            val joining = SyncMembershipEntity.from(DeviceMembership("source-vault", joiner.deviceId, "Joined",
                joiner.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 2, 1))

            // An older host (no merge join): the joiner keeps the empty-vault rule, and nothing changes.
            val plain = ByteArrayOutputStream()
            SyncSnapshot(sourceContext, source, EncryptedPhotoStore(sourceContext)).export(plain, key, pairingKey, joining,
                joinerFeatures = setOf(SyncSnapshots.CHECKPOINT_FEATURE))
            val snapshot = SyncSnapshot(targetContext, target, EncryptedPhotoStore(targetContext))
            val refused = runCatching {
                snapshot.prepare(plain.toByteArray().inputStream(), key, pairingKey, "source-vault", joiner.deviceId, creator)
                    .use { snapshot.commit(it) }
            }.exceptionOrNull()
            assertEquals("Join from an empty vault", refused?.message)
            assertEquals(listOf("mine"), target.dao().allEntries().map { it.entry.id })
            assertEquals("target-vault", target.dao().settings()?.vaultId)

            // A lineage is read only from a host that announced the merge join.
            val withLineage = ByteArrayOutputStream()
            SyncSnapshot(sourceContext, source, EncryptedPhotoStore(sourceContext)).export(withLineage, key, pairingKey, joining,
                joinerFeatures = setOf(SyncSnapshots.CHECKPOINT_FEATURE, MergeJoinRules.FEATURE))
            assertTrue(runCatching {
                snapshot.prepare(withLineage.toByteArray().inputStream(), key, pairingKey, "source-vault", joiner.deviceId, creator).close()
            }.isFailure)
            snapshot.prepare(withLineage.toByteArray().inputStream(), key, pairingKey, "source-vault", joiner.deviceId, creator,
                setOf(MergeJoinRules.FEATURE)).use { snapshot.commit(it, merge = true) }
            assertEquals(listOf("host", "mine"), target.dao().allEntries().map { it.entry.id }.sorted())
        } finally {
            source.close(); target.close()
            sourceIdentity.clear(); targetIdentity.clear()
            root.deleteRecursively()
        }
    }

    private fun note(id: String, title: String) = VaultEntry(id = id, type = EntryType.NOTE, title = title)
}
