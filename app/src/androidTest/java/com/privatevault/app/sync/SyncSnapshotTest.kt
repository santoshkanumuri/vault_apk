package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.EncryptedPhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SyncSnapshotTest {
    private class TestContext(base: Context, private val root: File, private val prefix: String) : ContextWrapper(base) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            super.getSharedPreferences("$prefix-$name", mode)
    }

    @Test
    fun confirmedSnapshotCompletesAcrossEncryptedSocket() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-socket-${UUID.randomUUID()}").apply { mkdirs() }
        val sourceContext = TestContext(base, File(root, "source"), UUID.randomUUID().toString())
        val targetContext = TestContext(base, File(root, "target"), UUID.randomUUID().toString())
        val source = Room.inMemoryDatabaseBuilder(sourceContext, VaultDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(targetContext, VaultDatabase::class.java).build()
        val sourceIdentity = AndroidDeviceIdentityStore(sourceContext)
        val targetIdentity = AndroidDeviceIdentityStore(targetContext)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val creator = sourceIdentity.getOrCreate()
            val joiner = targetIdentity.getOrCreate()
            val pairingKey = ByteArray(32) { 7 }
            val sourceKey = ByteArray(32) { 3 }
            val targetKey = ByteArray(32) { 9 }
            source.dao().saveSettings(VaultSettings(vaultId = "socket-vault"))
            target.dao().saveSettings(VaultSettings(vaultId = "empty-vault"))
            source.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("socket-vault",
                creator.deviceId, "Source", creator.publicKeyBase64Url, MemberStatus.ACTIVE,
                creator.deviceId, 1, 1)))
            source.prepareSyncGroup(sourceIdentity)
            LocalEntryChangeWriter(source, sourceIdentity).save(VaultEntry(id = "socket-entry",
                type = EntryType.NOTE, title = "Socket copy"), emptySet(), sourceKey)
            val joining = SyncMembershipEntity.from(DeviceMembership("socket-vault", joiner.deviceId,
                "Joining", joiner.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 2, 1))
            ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
                val host = pool.submit {
                    listener.accept().use { socket ->
                        socket.soTimeout = 15_000
                        EncryptedSyncChannel(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                            pairingKey, true).use { channel ->
                            val admission = SyncChannelOutput(channel).use { output ->
                                runBlocking { SyncSnapshot(sourceContext, source, EncryptedPhotoStore(sourceContext))
                                    .export(output, sourceKey, pairingKey, joining) }
                            }
                            check(channel.receive().contentEquals("ready".toByteArray()))
                            runBlocking {
                                source.syncDao().insertMembershipEvent(requireNotNull(admission))
                                source.syncDao().upsertMembership(joining)
                            }
                            channel.send("commit".toByteArray())
                            check(channel.receive().contentEquals("committed".toByteArray()))
                        }
                    }
                }
                Socket(InetAddress.getLoopbackAddress(), listener.localPort).use { socket ->
                    socket.soTimeout = 15_000
                    EncryptedSyncChannel(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                        pairingKey, false).use { channel ->
                        SyncSnapshot(targetContext, target, EncryptedPhotoStore(targetContext))
                            .prepare(SyncChannelInput(channel), targetKey, pairingKey, "socket-vault",
                                joiner.deviceId, creator).use { prepared ->
                                channel.send("ready".toByteArray())
                                assertArrayEquals("commit".toByteArray(), channel.receive())
                                SyncSnapshot(targetContext, target, EncryptedPhotoStore(targetContext)).commit(prepared)
                                channel.send("committed".toByteArray())
                            }
                    }
                }
                host.get(20, TimeUnit.SECONDS)
            }
            assertEquals("Socket copy", target.dao().entry("socket-entry")?.entry?.title)
        } finally {
            pool.shutdownNow()
            source.close(); target.close(); sourceIdentity.clear(); targetIdentity.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun photoTransformKeepsTheVersionReferencedByAnOlderSnapshot() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-photo-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, root, UUID.randomUUID().toString())
        val photos = EncryptedPhotoStore(context)
        val key = ByteArray(32) { 4 }
        val bitmap = Bitmap.createBitmap(8, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        try {
            photos.encrypt(bitmap, "old.vaultphoto", key)
            val original = photos.decryptedBytes("old.vaultphoto", key)
            photos.transform("old.vaultphoto", "new.vaultphoto", "new.vaultthumb", key, rotateDegrees = 90f)
            assertArrayEquals(original, photos.decryptedBytes("old.vaultphoto", key))
            assertTrue(photos.encryptedFile("new.vaultphoto").exists())
            assertTrue(photos.encryptedFile("new.vaultthumb").exists())
        } finally {
            bitmap.recycle()
            root.deleteRecursively()
        }
    }

    @Test
    fun slowSnapshotOutputDoesNotHoldVaultTransaction() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-output-${UUID.randomUUID()}").apply { mkdirs() }
        val context = TestContext(base, root, UUID.randomUUID().toString())
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val identities = AndroidDeviceIdentityStore(context)
        val release = CountDownLatch(1)
        try {
            val creator = identities.getOrCreate()
            database.dao().saveSettings(VaultSettings(vaultId = "vault"))
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault", creator.deviceId,
                "Source", creator.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 1, 1)))
            database.prepareSyncGroup(identities)
            val joining = SyncMembershipEntity.from(DeviceMembership("vault", "joining-device", "Joining",
                java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    DeviceIdentityCrypto.publicKey(ByteArray(32) { 9 })),
                MemberStatus.ACTIVE, creator.deviceId, 2, 1))
            val writing = CountDownLatch(1)
            val output = object : OutputStream() {
                override fun write(value: Int) {
                    writing.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
                override fun write(bytes: ByteArray, offset: Int, length: Int) {
                    writing.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                }
            }
            val key = ByteArray(32) { 3 }
            val export = async(Dispatchers.IO) {
                SyncSnapshot(context, database, EncryptedPhotoStore(context))
                    .export(output, key, ByteArray(32) { 7 }, joining)
            }
            assertTrue(writing.await(10, TimeUnit.SECONDS))
            withTimeout(5_000) {
                LocalEntryChangeWriter(database, identities).save(
                    VaultEntry(type = EntryType.NOTE, title = "Saved while sending"), emptySet(), key)
            }
            release.countDown()
            export.await()
            assertTrue(context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("sync-enrollment-") })
        } finally {
            release.countDown()
            database.close()
            identities.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun membershipAllowsEightActiveDevicesAndReusesRevokedSlot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val sync = database.syncDao()
            suspend fun member(number: Int, status: MemberStatus = MemberStatus.ACTIVE) =
                sync.upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault", "device-$number",
                    "Device $number", "public-$number", status, "device-1", number.toLong(), 1)))
            (1..MAX_ACTIVE_SYNC_DEVICES).forEach { member(it) }
            assertEquals(MAX_ACTIVE_SYNC_DEVICES, sync.activeMembershipCount("vault"))
            val extra = MAX_ACTIVE_SYNC_DEVICES + 1
            assertTrue(runCatching { member(extra) }.isFailure)
            assertNull(sync.membership("vault", "device-$extra"))
            member(2, MemberStatus.REVOKED)
            member(extra)
            assertEquals(MAX_ACTIVE_SYNC_DEVICES, sync.activeMembershipCount("vault"))
        } finally { database.close() }
    }

    @Test
    fun enrollmentPreservesLocalPasswordKeyAndSupportsChangesInBothDirections() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-snapshot-${UUID.randomUUID()}").apply { mkdirs() }
        val sourceContext = TestContext(base, File(root, "source"), UUID.randomUUID().toString())
        val targetContext = TestContext(base, File(root, "target"), UUID.randomUUID().toString())
        val source = Room.inMemoryDatabaseBuilder(sourceContext, VaultDatabase::class.java).build()
        val target = Room.inMemoryDatabaseBuilder(targetContext, VaultDatabase::class.java).build()
        val sourceIdentity = AndroidDeviceIdentityStore(sourceContext)
        val targetIdentity = AndroidDeviceIdentityStore(targetContext)
        try {
            val sourceKey = ByteArray(32) { 3 }
            val targetKey = ByteArray(32) { 9 }
            val pairingKey = ByteArray(32) { 7 }
            source.dao().saveSettings(VaultSettings(vaultId = "source-vault"))
            target.dao().saveSettings(VaultSettings(vaultId = "empty-vault", inactivityTimeoutMs = 300_000))
            val creator = sourceIdentity.getOrCreate()
            val joiner = targetIdentity.getOrCreate()
            val creatorMember = SyncMembershipEntity.from(DeviceMembership("source-vault", creator.deviceId,
                "Source", creator.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 1, 1))
            val joiningMember = SyncMembershipEntity.from(DeviceMembership("source-vault", joiner.deviceId,
                "Joined", joiner.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 2, 1))
            source.syncDao().upsertMembership(creatorMember)
            val entry = VaultEntry(id = "entry", type = EntryType.NOTE, title = "First copy")
            LocalEntryChangeWriter(source, sourceIdentity).save(entry, emptySet(), sourceKey)
            val localHistory = source.syncDao().operations().single()
            source.prepareSyncGroup(sourceIdentity)
            assertTrue(source.syncDao().operations().isEmpty())
            assertEquals(localHistory.mutationId, source.syncDao().archivedOperations().single().mutationId)
            assertFalse(source.syncContentKey(sourceKey).contentEquals(sourceKey))
            val sourceSnapshot = SyncSnapshot(sourceContext, source, EncryptedPhotoStore(sourceContext))
            val targetSnapshot = SyncSnapshot(targetContext, target, EncryptedPhotoStore(targetContext))
            val output = ByteArrayOutputStream()
            val admission = sourceSnapshot.export(output, sourceKey, pairingKey, joiningMember)
            val bytes = output.toByteArray()

            assertTrue(runCatching {
                targetSnapshot.prepare(bytes.inputStream(), targetKey, ByteArray(32), "source-vault", joiner.deviceId, creator)
            }.isFailure)
            assertTrue(target.dao().allEntries().isEmpty())
            targetSnapshot.prepare(bytes.inputStream(), targetKey, pairingKey, "source-vault", joiner.deviceId, creator).use {
                assertTrue(target.dao().allEntries().isEmpty())
                targetSnapshot.commit(it)
            }
            source.syncDao().upsertMembership(joiningMember)
            source.syncDao().insertMembershipEvent(requireNotNull(admission))
            val retryOutput = ByteArrayOutputStream()
            assertNull(sourceSnapshot.export(retryOutput, sourceKey, pairingKey, joiningMember))
            val recovered = Room.inMemoryDatabaseBuilder(targetContext, VaultDatabase::class.java).build()
            try {
                recovered.dao().saveSettings(VaultSettings(vaultId = "empty-vault"))
                val retrySnapshot = SyncSnapshot(targetContext, recovered, EncryptedPhotoStore(targetContext))
                retrySnapshot.prepare(retryOutput.toByteArray().inputStream(), targetKey, pairingKey,
                    "source-vault", joiner.deviceId, creator).use { retrySnapshot.commit(it) }
                assertEquals("First copy", recovered.dao().entry(entry.id)?.entry?.title)
                assertEquals(2, recovered.syncDao().membershipEvents("source-vault").size)
            } finally { recovered.close() }
            assertEquals("First copy", target.dao().entry(entry.id)?.entry?.title)
            assertEquals(300_000L, target.dao().settings()?.inactivityTimeoutMs)
            assertEquals("source-vault", target.dao().settings()?.vaultId)

            LocalEntryChangeWriter(target, targetIdentity).save(entry.copy(title = "From second phone"), emptySet(), targetKey)
            val changed = target.syncDao().operations().single { it.deviceId == joiner.deviceId }
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(source).apply(changed, sourceKey))
            assertEquals("From second phone", source.dao().entry(entry.id)?.entry?.title)
        } finally {
            source.close(); target.close()
            sourceIdentity.clear(); targetIdentity.clear()
            root.deleteRecursively()
        }
    }

    @Test
    fun thirdDeviceCanJoinAfterFirstPairExchangesChanges() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-third-${UUID.randomUUID()}").apply { mkdirs() }
        val contexts = (1..3).map { TestContext(base, File(root, "device-$it"), UUID.randomUUID().toString()) }
        val databases = contexts.map { Room.inMemoryDatabaseBuilder(it, VaultDatabase::class.java).build() }
        val identities = contexts.map(::AndroidDeviceIdentityStore)
        val keys = (1..3).map { ByteArray(32) { byte -> (it + byte).toByte() } }
        val pairingKey = ByteArray(32) { 7 }
        try {
            val devices = identities.map { it.getOrCreate() }
            databases[0].dao().saveSettings(VaultSettings(vaultId = "three-device-vault"))
            databases[1].dao().saveSettings(VaultSettings(vaultId = "empty-b"))
            databases[2].dao().saveSettings(VaultSettings(vaultId = "empty-c"))
            databases[0].syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership(
                "three-device-vault", devices[0].deviceId, "Manager", devices[0].publicKeyBase64Url,
                MemberStatus.ACTIVE, devices[0].deviceId, 1, 1)))
            databases[0].prepareSyncGroup(identities[0])
            suspend fun enroll(index: Int) {
                val member = SyncMembershipEntity.from(DeviceMembership("three-device-vault",
                    devices[index].deviceId, "Joining", devices[index].publicKeyBase64Url,
                    MemberStatus.ACTIVE, devices[0].deviceId,
                    databases[0].syncDao().membershipEvents("three-device-vault").size + 1L, 1))
                val bytes = ByteArrayOutputStream()
                val admission = SyncSnapshot(contexts[0], databases[0], EncryptedPhotoStore(contexts[0]))
                    .export(bytes, keys[0], pairingKey, member)
                SyncSnapshot(contexts[index], databases[index], EncryptedPhotoStore(contexts[index]))
                    .also { snapshot ->
                        snapshot.prepare(bytes.toByteArray().inputStream(), keys[index], pairingKey,
                            "three-device-vault", devices[index].deviceId, devices[0]).use { snapshot.commit(it) }
                    }
                databases[0].syncDao().insertMembershipEvent(requireNotNull(admission))
                databases[0].syncDao().upsertMembership(member)
            }
            enroll(1)
            LocalEntryChangeWriter(databases[1], identities[1]).save(VaultEntry(id = "from-b",
                type = EntryType.NOTE, title = "Edit after first pairing"), emptySet(), keys[1])
            val change = databases[1].syncDao().operations().single()
            assertEquals(IncomingResult.APPLIED,
                IncomingEntryChangeApplier(databases[0]).apply(change, keys[0]))
            val history = databases[0].syncDao().membershipEvents("three-device-vault")
                .map { it.toEvent() }.toMutableList()
            suspend fun append(action: MembershipAction, issuer: Int, subject: Int) {
                val event = SyncMembershipEvent.sign("three-device-vault", history.size + 1L,
                    history.last().hash, action, devices[issuer].deviceId, devices[subject].deviceId,
                    "", 1, identities[issuer]::sign)
                databases[0].syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(event))
                history += event
            }
            append(MembershipAction.OFFER_TRANSFER, 0, 1)
            append(MembershipAction.ACCEPT_TRANSFER, 1, 1)
            append(MembershipAction.TRANSFER, 0, 1)
            append(MembershipAction.OFFER_TRANSFER, 1, 0)
            append(MembershipAction.ACCEPT_TRANSFER, 0, 0)
            append(MembershipAction.TRANSFER, 1, 0)
            assertEquals(devices[0].deviceId, SyncMembershipManager.verify(history).managerDeviceId)
            assertEquals(2L, databases[0].syncDao().memberships("three-device-vault")
                .maxOf { it.membershipSequence })
            enroll(2)
            assertEquals("Edit after first pairing", databases[2].dao().entry("from-b")?.entry?.title)
            assertEquals(9, databases[2].syncDao().membershipEvents("three-device-vault").size)
        } finally {
            databases.forEach { it.close() }
            identities.forEach { it.clear() }
            root.deleteRecursively()
        }
    }
}
