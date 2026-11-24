package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Key rotation at a REMOVE (epoch-keys-v1): the gate on signed statements, one transaction with the
 * REMOVE, a survivor that was offline through the removal, and changes that wait for their key.
 */
class EpochKeyRotationTest {
    private class TestContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "nobackup").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }

    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val removedKey = encoder.encodeToString(DeviceIdentityCrypto.publicKey(ByteArray(32) { 44 }))

    private class Device(val context: Context, val database: VaultDatabase, val identity: AndroidDeviceIdentityStore,
        val store: LockedSyncStore)

    private suspend fun group(device: Device, events: List<SyncMembershipEvent>) {
        device.database.dao().saveSettings(VaultSettings(vaultId = "rotating"))
        device.database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "rotating",
            contentKey = encoder.encodeToString(ByteArray(32) { 3 }), transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
        events.forEach { device.database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(it)) }
        SyncMembershipManager.verify(events).members.forEach { device.database.syncDao().upsertMembership(it) }
    }

    private fun withDevices(block: suspend (Device, Device) -> Unit) = withThree { manager, survivor, _ -> block(manager, survivor) }

    private fun withThree(block: suspend (Device, Device, Device) -> Unit) = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "rotation-${UUID.randomUUID()}").apply { mkdirs() }
        val devices = listOf("manager", "survivor", "leaver").map { name ->
            val context = TestContext(base, File(root, name), UUID.randomUUID().toString())
            Device(context, Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build(),
                AndroidDeviceIdentityStore(context), LockedSyncStore(context, File(root, "$name-transport")))
        }
        try { block(devices[0], devices[1], devices[2]) }
        finally { devices.forEach { it.database.close(); it.identity.clear() }; root.deleteRecursively() }
    }

    private fun history(manager: AndroidDeviceIdentityStore, survivor: DeviceIdentity): List<SyncMembershipEvent> {
        val owner = manager.getOrCreate()
        val genesis = SyncMembershipEvent.sign("rotating", 1, GENESIS_HASH, MembershipAction.GENESIS, owner.deviceId,
            owner.deviceId, owner.publicKeyBase64Url, 1, manager::sign)
        val addSurvivor = SyncMembershipEvent.sign("rotating", 2, genesis.hash, MembershipAction.ADD, owner.deviceId,
            survivor.deviceId, survivor.publicKeyBase64Url, 1, manager::sign)
        val addRemoved = SyncMembershipEvent.sign("rotating", 3, addSurvivor.hash, MembershipAction.ADD, owner.deviceId,
            "removed", removedKey, 1, manager::sign)
        return listOf(genesis, addSurvivor, addRemoved)
    }

    @Test
    fun aRemovalWithEveryStatementRotatesAndAnOfflineSurvivorCatchesUp() = withDevices { manager, survivor ->
        val key = ByteArray(32) { 9 }
        val events = history(manager.identity, survivor.identity.getOrCreate())
        group(manager, events); group(survivor, events)
        manager.store.publish(manager.database); survivor.store.publish(survivor.database)
        val statement = survivor.store.ownKeyStatement()
        assertTrue(manager.store.acceptKeyStatement(statement))
        assertEquals(emptyList<String>(), EpochKeyRotation.missingStatements(
            SyncMembershipManager.verify(events), "removed", manager.identity.getOrCreate().deviceId, manager.store.keyStatements()))

        val added = manager.database.removeSyncMember(manager.identity, "removed", statements = manager.store.keyStatements())
        manager.store.publish(manager.database)
        val epoch2 = manager.database.syncDao().epochKeys().single()
        assertEquals(2L, epoch2.epoch)
        val set = manager.store.keyPackages().single()
        assertEquals(setOf(survivor.identity.getOrCreate().deviceId), set.packages.keys)
        assertEquals(added.last().hash, set.removeHash)
        // The new transport secret is random: not the HMAC of the root the removed device knows.
        val derived = TransportEpochs.secret(encoder.encodeToString(ByteArray(32) { 4 }), "rotating", 2)
        assertNotEquals(derived, manager.store.transportSecret(2))
        assertEquals(epoch2.transportSecret, manager.store.transportSecret(2))

        // The manager writes under K(2); the survivor (offline through all of it) holds no K(2) yet.
        LocalEntryChangeWriter(manager.database, manager.identity).save(
            VaultEntry(id = "after", type = EntryType.NOTE, title = "After the removal"), emptySet(), key)
        val change = manager.database.syncDao().operations().single { it.entityId == "after" }
        assertEquals(2L, EpochKeys.epochOf(change.payloadNonce))
        survivor.store.acceptMembershipEvents((events.map { SyncMembershipEventEntity.from(it) } + added))
        assertTrue(runCatching { IncomingEntryChangeApplier(survivor.database, announceRemoteChanges = false).apply(change, key) }
            .exceptionOrNull() is MissingEpochKeyException)
        // Locked: the package gives the transport secret only. Unlocked: the content key too.
        assertTrue(survivor.store.acceptKeyPackages(listOf(set)))
        assertEquals(manager.store.transportSecret(2), survivor.store.transportSecret(2))
        assertTrue(survivor.database.syncDao().epochKeys().isEmpty())
        survivor.store.applyQueued(survivor.database, key)
        assertEquals(epoch2.contentKey, survivor.database.syncDao().epochKeys().single().contentKey)
        assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(survivor.database, announceRemoteChanges = false).apply(change, key))
        assertEquals("After the removal", survivor.database.dao().entry("after")?.entry?.title)
        // A set signed for another REMOVE is refused.
        assertFalse(survivor.store.acceptKeyPackages(listOf(set.copy(removeHash = "ab".repeat(32)))))
    }

    /**
     * H3: a locked phone opens its package with an X25519 key it can use without the master password,
     * so the package must not hold the content key. It holds S(2) and P(2); K(2) = HKDF(P(2), K(1)) and
     * K(1) is only in SQLCipher. The transport store (Keystore only) never holds anything K(2) comes from alone.
     */
    @Test
    fun aLockedSurvivorGetsTheTransportSecretButNeverTheContentKey() = withDevices { manager, survivor ->
        val events = history(manager.identity, survivor.identity.getOrCreate())
        group(manager, events); group(survivor, events)
        manager.store.publish(manager.database); survivor.store.publish(survivor.database)
        assertTrue(manager.store.acceptKeyStatement(survivor.store.ownKeyStatement()))
        val added = manager.database.removeSyncMember(manager.identity, "removed", statements = manager.store.keyStatements())
        manager.store.publish(manager.database)
        val epoch2 = manager.database.syncDao().epochKeys().single()
        survivor.store.acceptMembershipEvents(events.map { SyncMembershipEventEntity.from(it) } + added)
        assertTrue(survivor.store.acceptKeyPackages(manager.store.keyPackages()))
        // Everything the locked phone can reach: its X25519 key and the package give S(2) and P(2) only.
        val private = survivor.identity.x25519Private()
        val sealed = Base64.getUrlDecoder().decode(manager.store.keyPackages().single().packages.getValue(survivor.identity.getOrCreate().deviceId))
        val (secret, packageKey) = requireNotNull(EpochKeys.open("rotating", 2, survivor.identity.getOrCreate().deviceId, private, sealed))
        assertEquals(epoch2.transportSecret, encoder.encodeToString(secret))
        assertNotEquals(epoch2.contentKey, encoder.encodeToString(packageKey))
        assertFalse(survivor.store.snapshot().toString().contains(epoch2.contentKey))
        // With the vault's K(1) (unlocked) it is the manager's K(2).
        assertEquals(epoch2.contentKey, encoder.encodeToString(EpochKeys.contentKey(ByteArray(32) { 3 }, "rotating", 2, packageKey)))
    }

    /**
     * M2: a REMOVE answered from a leave request is signed while the manager may be locked, so it
     * can't rotate then. The manager holds what it sends until it unlocks; at unlock it rotates, and
     * the leaver can't read what the survivors add next.
     */
    @Test
    fun aRemovalFromALeaveRequestRotatesWhenTheManagerUnlocks() = withThree { manager, survivor, leaver ->
        val key = ByteArray(32) { 9 }
        val owner = manager.identity.getOrCreate()
        val genesis = SyncMembershipEvent.sign("rotating", 1, GENESIS_HASH, MembershipAction.GENESIS, owner.deviceId,
            owner.deviceId, owner.publicKeyBase64Url, 1, manager.identity::sign)
        val addSurvivor = SyncMembershipEvent.sign("rotating", 2, genesis.hash, MembershipAction.ADD, owner.deviceId,
            survivor.identity.getOrCreate().deviceId, survivor.identity.getOrCreate().publicKeyBase64Url, 1, manager.identity::sign)
        val addLeaver = SyncMembershipEvent.sign("rotating", 3, addSurvivor.hash, MembershipAction.ADD, owner.deviceId,
            leaver.identity.getOrCreate().deviceId, leaver.identity.getOrCreate().publicKeyBase64Url, 1, manager.identity::sign)
        val events = listOf(genesis, addSurvivor, addLeaver)
        group(manager, events); group(survivor, events)
        manager.store.publish(manager.database); survivor.store.publish(survivor.database)
        assertTrue(manager.store.acceptKeyStatement(survivor.store.ownKeyStatement()))
        manager.store.ownKeyStatement()
        // The leave request arrives while the manager is locked (Wi-Fi or Drive): it appends the REMOVE.
        val request = LeaveRequest("rotating", leaver.identity.getOrCreate().deviceId, addLeaver.hash, 1, System.currentTimeMillis())
        assertTrue(manager.store.answerDriveLeave(request, leaver.identity.sign(request.signingBytes())))
        assertEquals(4, manager.store.snapshot()?.membershipEvents?.size)
        assertTrue(manager.store.stranded())
        assertTrue(manager.store.pending(SyncFrontier(emptyMap()), false).isEmpty())
        // Unlock: the REMOVE goes into the vault and gets new keys.
        manager.store.applyQueued(manager.database, key)
        val epoch2 = manager.database.syncDao().epochKeys().single()
        assertEquals(2L, epoch2.epoch)
        val set = manager.store.keyPackages().single()
        assertEquals(setOf(survivor.identity.getOrCreate().deviceId), set.packages.keys)
        assertFalse(manager.store.stranded())
        // A second unlock makes no new keys (the epoch is covered).
        manager.store.applyQueued(manager.database, key)
        assertEquals(epoch2, manager.database.syncDao().epochKeys().single())
        // The survivor gets K(2); the leaver's derivation of S(2) is not the new secret.
        survivor.store.acceptMembershipEvents(manager.store.snapshot()!!.membershipEvents)
        assertTrue(survivor.store.acceptKeyPackages(listOf(set)))
        survivor.store.applyQueued(survivor.database, key)
        assertEquals(epoch2.contentKey, survivor.database.syncDao().epochKeys().single().contentKey)
        assertNotEquals(TransportEpochs.secret(encoder.encodeToString(ByteArray(32) { 4 }), "rotating", 2), epoch2.transportSecret)
    }

    /**
     * H1 over a real loopback socket: a survivor that learned the REMOVE but not its package sends
     * nothing (its own new change stays here). It proves the secret derived for epoch 2; the rotated
     * manager answers with the history and the sealed packages (`removed1`), the survivor opens its
     * package, reconnects with S(2), and both exchange `keys1` frames and changes in a normal session.
     */
    @Test
    fun aStrandedSurvivorSendsNothingAndGetsItsPackageOverWiFi() = withDevices { manager, survivor ->
        val key = ByteArray(32) { 9 }
        val events = history(manager.identity, survivor.identity.getOrCreate())
        group(manager, events); group(survivor, events)
        manager.store.publish(manager.database); survivor.store.publish(survivor.database)
        assertTrue(manager.store.acceptKeyStatement(survivor.store.ownKeyStatement()))
        assertTrue(survivor.store.acceptKeyStatement(manager.store.ownKeyStatement()))
        val added = manager.database.removeSyncMember(manager.identity, "removed", statements = manager.store.keyStatements())
        manager.store.publish(manager.database)
        // The survivor hears of the REMOVE (no package): it holds what it sends.
        survivor.store.acceptMembershipEvents(events.map { SyncMembershipEventEntity.from(it) } + added)
        survivor.store.applyQueued(survivor.database, key)
        LocalEntryChangeWriter(survivor.database, survivor.identity).save(
            VaultEntry(id = "held", type = EntryType.NOTE, title = "Written while waiting"), emptySet(), key)
        survivor.store.publish(survivor.database)
        assertTrue(survivor.store.stranded())
        assertTrue(survivor.store.waitingForKeys())
        assertTrue(survivor.store.pending(SyncFrontier(emptyMap()), false).isEmpty())
        LocalEntryChangeWriter(manager.database, manager.identity).save(
            VaultEntry(id = "after", type = EntryType.NOTE, title = "After the removal"), emptySet(), key)
        manager.store.publish(manager.database)

        val pool = Executors.newSingleThreadExecutor()
        fun exchange(): Pair<Throwable?, Throwable?> = ServerSocket(0).use { listener ->
            val server = pool.submit<Throwable?> {
                runCatching { listener.accept().use { socket ->
                    socket.soTimeout = 15_000
                    LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), manager.store, true)
                } }.exceptionOrNull()
            }
            val client = runCatching { Socket("127.0.0.1", listener.localPort).use { socket ->
                socket.soTimeout = 15_000
                LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), survivor.store, false)
            } }.exceptionOrNull()
            client to server.get(20, TimeUnit.SECONDS)
        }
        try {
            val (client, server) = exchange()
            assertTrue("$client", client is LanSyncExchange.MembershipCaughtUp)
            assertTrue("$server", server is LanSyncExchange.MembershipMessageServed)
            assertFalse(survivor.store.stranded())
            assertEquals(manager.store.transportSecret(2), survivor.store.transportSecret(2))
            // Now a normal session under S(2): keys1 both ways, then the changes.
            val (again, served) = exchange()
            assertNull("$again", again)
            assertNull("$served", served)
            survivor.store.applyQueued(survivor.database, key)
            manager.store.applyQueued(manager.database, key)
            assertEquals("After the removal", survivor.database.dao().entry("after")?.entry?.title)
            assertEquals("Written while waiting", manager.database.dao().entry("held")?.entry?.title)
            assertEquals(manager.database.syncDao().epochKeys().single().contentKey, survivor.database.syncDao().epochKeys().single().contentKey)
        } finally { pool.shutdownNow() }
    }

    @Test
    fun anOlderSurvivorKeepsTheOldDerivationAndACrashLeavesNothing() = withDevices { manager, survivor ->
        val events = history(manager.identity, survivor.identity.getOrCreate())
        group(manager, events)
        manager.store.publish(manager.database)
        // A crash inside the REMOVE transaction: neither the REMOVE nor any key is stored.
        val survivorStatement = KeyStatement.of(survivor.identity, "rotating")
        val crash = runCatching {
            manager.database.withTransaction {
                manager.database.removeSyncMember(manager.identity, "removed",
                    statements = mapOf(survivorStatement.deviceId to survivorStatement))
                error("power loss")
            }
        }
        assertTrue(crash.isFailure)
        assertEquals(3, manager.database.syncDao().membershipEvents("rotating").size)
        assertTrue(manager.database.syncDao().epochKeys().isEmpty())
        assertTrue(manager.database.syncDao().keyPackages("rotating").isEmpty())
        // Version skew: the survivor never sent a statement (an older build). The REMOVE goes ahead
        // without new keys, and the epoch is derived as before.
        manager.database.removeSyncMember(manager.identity, "removed", statements = emptyMap())
        manager.store.publish(manager.database)
        assertTrue(manager.database.syncDao().epochKeys().isEmpty())
        assertEquals(TransportEpochs.secret(encoder.encodeToString(ByteArray(32) { 4 }), "rotating", 2), manager.store.transportSecret(2))
        // A forged statement (signed by the wrong key) does not open the gate either.
        val forged = survivorStatement.copy(signature = KeyStatement.of(manager.identity, "rotating").signature)
        assertEquals(listOf(survivorStatement.deviceId), EpochKeyRotation.missingStatements(SyncMembershipManager.verify(events),
            "removed", manager.identity.getOrCreate().deviceId, mapOf(forged.deviceId to forged)))
    }
}
