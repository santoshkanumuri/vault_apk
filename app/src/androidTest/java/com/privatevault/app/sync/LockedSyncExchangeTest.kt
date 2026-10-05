package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.EncryptedPhotoStore
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

class LockedSyncExchangeTest {
    @Test fun concurrentGroupNamesResolveToOneSignedVersion() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val prefix = UUID.randomUUID().toString()
        fun context(label: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("$prefix-$label-$name", mode)
        }
        val a = context("a"); val b = context("b")
        val dbA = Room.inMemoryDatabaseBuilder(a, VaultDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(b, VaultDatabase::class.java).build()
        val idA = AndroidDeviceIdentityStore(a); val idB = AndroidDeviceIdentityStore(b)
        try {
            val first = idA.getOrCreate(); val second = idB.getOrCreate()
            val genesis = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 1,
                GENESIS_HASH, MembershipAction.GENESIS, first.deviceId, first.deviceId,
                first.publicKeyBase64Url, 1, idA::sign))
            val admission = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 2,
                genesis.hash, MembershipAction.ADD, first.deviceId, second.deviceId,
                second.publicKeyBase64Url, 1, idA::sign))
            val group = VaultGroup(id = "shared-group", name = "Shared")
            listOf(dbA, dbB).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 }),
                    transportSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 })))
                db.syncDao().insertMembershipEvent(genesis); db.syncDao().insertMembershipEvent(admission)
                listOf(first, second).forEachIndexed { index, member ->
                    db.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault",
                        member.deviceId, "Phone", member.publicKeyBase64Url, MemberStatus.ACTIVE,
                        first.deviceId, index + 1L, 1)))
                }
                db.dao().insertGroup(group)
            }
            val vaultKey = ByteArray(32) { 9 }
            LocalGroupChangeWriter(dbA, idA).save(group.copy(name = "From A"), vaultKey)
            LocalGroupChangeWriter(dbB, idB).save(group.copy(name = "From B"), vaultKey)
            val aChange = dbA.syncDao().operations().single()
            val bChange = dbB.syncDao().operations().single()
            assertEquals(IncomingResult.CONFLICT, IncomingEntryChangeApplier(dbA).apply(bChange, vaultKey))
            val review = SyncConflictResolver(dbA, idA).reviews(vaultKey).single()
            assertTrue(review.current.description.contains("Different: Name"))
            assertTrue(review.incoming.description.contains("Different: Name"))
            SyncConflictResolver(dbA, idA).resolve(review.id, true, vaultKey, review.currentVersion)
            assertEquals("From B", dbA.dao().allGroupsWithEntries().single().group.name)
            assertEquals(IncomingResult.CONFLICT, IncomingEntryChangeApplier(dbB).apply(aChange, vaultKey))
            val resolution = dbA.syncDao().operations().first { it.deviceId == first.deviceId && it.sequence == 2L }
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(dbB).apply(resolution, vaultKey))
            assertEquals("From B", dbB.dao().allGroupsWithEntries().single().group.name)
            assertTrue(SyncConflictResolver(dbB, idB).reviews(vaultKey).isEmpty())
        } finally {
            dbA.close(); dbB.close(); idA.clear(); idB.clear()
        }
    }

    @Test fun cleanupKeepsOnlyPendingPhotoPartials() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "photo-partials-${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val pending = "a".repeat(64)
            val abandoned = "b".repeat(64)
            File(root, "$pending.part").writeBytes(byteArrayOf(1))
            File(root, "$abandoned.part").writeBytes(byteArrayOf(2))
            PhotoSyncBlobs(root).pruneAbandonedPartials(setOf(pending))
            assertTrue(File(root, "$pending.part").exists())
            assertFalse(File(root, "$abandoned.part").exists())
            val current = File(root, pending).apply { writeBytes(byteArrayOf(3)) }
            val historical = File(root, abandoned).apply { writeBytes(byteArrayOf(4)) }
            val recent = File(root, "c".repeat(64)).apply { writeBytes(byteArrayOf(5)) }
            val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            current.setLastModified(cutoff - 1000)
            historical.setLastModified(cutoff - 1000)
            PhotoSyncBlobs(root).pruneCompleted(setOf(pending), cutoff)
            assertTrue(current.exists())
            assertFalse(historical.exists())
            assertTrue(recent.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun photoChangesTransferAndResumeAcrossPairedPhones() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-live-photo-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(label: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$label-$name", mode)
            override fun getFilesDir(): File = File(root, "$label/files").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = File(root, "$label/no-backup").apply { mkdirs() }
        }
        val a = context("a"); val b = context("b")
        val dbA = Room.inMemoryDatabaseBuilder(a, VaultDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(b, VaultDatabase::class.java).build()
        val idA = AndroidDeviceIdentityStore(a); val idB = AndroidDeviceIdentityStore(b)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = idA.getOrCreate(); val second = idB.getOrCreate()
            val keyA = ByteArray(32) { 3 }; val keyB = ByteArray(32) { 4 }
            val content = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 9 })
            val transport = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 10 })
            val genesis = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 1,
                GENESIS_HASH, MembershipAction.GENESIS, first.deviceId, first.deviceId,
                first.publicKeyBase64Url, 1, idA::sign))
            val admission = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 2,
                genesis.hash, MembershipAction.ADD, first.deviceId, second.deviceId,
                second.publicKeyBase64Url, 1, idA::sign))
            listOf(dbA, dbB).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = content, transportSecret = transport))
                db.syncDao().insertMembershipEvent(genesis); db.syncDao().insertMembershipEvent(admission)
                listOf(first, second).forEachIndexed { index, member ->
                    db.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault",
                        member.deviceId, "Phone", member.publicKeyBase64Url, MemberStatus.ACTIVE,
                        first.deviceId, index + 1L, 1)))
                }
            }
            val storeA = LockedSyncStore(a, File(root, "a/transport"))
            val storeB = LockedSyncStore(b, File(root, "b/transport"))
            fun exchange() {
                ServerSocket(0).use { listener ->
                    val server = pool.submit {
                        listener.accept().use { socket ->
                            socket.soTimeout = 15_000
                            LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), storeA, true)
                        }
                    }
                    Socket("127.0.0.1", listener.localPort).use { socket ->
                        socket.soTimeout = 15_000
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), storeB, false)
                    }
                    server.get(20, TimeUnit.SECONDS)
                }
            }
            LocalEntryChangeWriter(dbA, idA).save(VaultEntry(id = "entry", type = EntryType.NOTE,
                title = "With photo"), emptySet(), keyA)
            storeA.publish(dbA); storeB.publish(dbB)
            exchange()
            val historicalBlob = File(root, "a/transport/blobs/${"d".repeat(64)}")
            historicalBlob.parentFile!!.mkdirs()
            historicalBlob.writeBytes(byteArrayOf(1))
            historicalBlob.setLastModified(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000)
            storeA.publish(dbA)
            assertTrue(historicalBlob.exists()) // Receipt alone must not permit cleanup.
            storeB.applyQueued(dbB, keyB)
            val entryHash = dbA.syncDao().operations().single().hash
            exchange() // The sender now sees the receiver's applied, rather than merely received, head.
            storeA.publish(dbA)
            assertFalse(File(root, "a/transport/outgoing/$entryHash").exists())
            assertFalse(historicalBlob.exists())
            val photosA = EncryptedPhotoStore(a); val photosB = EncryptedPhotoStore(b)
            val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
            try { photosA.encrypt(bitmap, "first.vaultphoto", keyA) } finally { bitmap.recycle() }
            photosA.createThumbnail("first.vaultphoto", "first.vaultthumb", keyA)
            val photo = VaultPhoto("photo", "entry", "first.vaultphoto", "first.vaultthumb")
            val writer = LocalPhotoChangeWriter(dbA, idA, storeA.photoBlobs, photosA)
            writer.save(photo, keyA)
            LocalEntryChangeWriter(dbA, idA).save(requireNotNull(dbA.dao().entry("entry")).entry
                .copy(notes = "Edited after photo"), emptySet(), keyA)
            storeA.publish(dbA)
            exchange()
            assertEquals(0, storeB.applyQueued(dbB, keyB))
            assertEquals(0, storeB.rejectedCount())
            val ref = requireNotNull(dbA.syncDao().attachment(photo.id))
            assertEquals(listOf(ref.ciphertextHash), storeB.missingPhotos().map { it.first })
            val partial = File(root, "b/transport/blobs/${ref.ciphertextHash}.part")
            partial.parentFile!!.mkdirs()
            partial.writeBytes(ByteArray(requireNotNull(storeA.photoBlobs.file(ref.ciphertextHash)).length().toInt() + 1))
            exchange()
            assertFalse(partial.exists())
            assertEquals(0L, storeB.missingPhotos().single().second)
            storeA.photoBlobs.file(ref.ciphertextHash)!!.inputStream().use { input ->
                val prefix = ByteArray(64)
                val count = input.read(prefix)
                partial.writeBytes(prefix.copyOf(count))
            }
            // Restart from a saved partial ciphertext range rather than relying on a fresh copy.
            assertEquals(partial.length(), storeB.missingPhotos().single().second)
            exchange()
            assertEquals(2, storeB.applyQueued(dbB, keyB))
            assertEquals("Edited after photo", dbB.dao().entry("entry")?.entry?.notes)
            val received = requireNotNull(dbB.dao().photo(photo.id))
            assertArrayEquals(photosA.decryptedBytes(photo.encryptedFileName, keyA),
                photosB.decryptedBytes(received.encryptedFileName, keyB))
            photosA.transform(photo.encryptedFileName, "edited.vaultphoto", "edited.vaultthumb",
                keyA, rotateDegrees = 90f)
            writer.save(photo.copy(encryptedFileName = "edited.vaultphoto",
                encryptedThumbnailFileName = "edited.vaultthumb"), keyA)
            storeA.publish(dbA); exchange()
            assertEquals(0, storeB.applyQueued(dbB, keyB))
            exchange(); assertEquals(1, storeB.applyQueued(dbB, keyB))
            assertArrayEquals(photosA.decryptedBytes("edited.vaultphoto", keyA),
                photosB.decryptedBytes(requireNotNull(dbB.dao().photo(photo.id)).encryptedFileName, keyB))
            assertFalse(photosB.encryptedFile(received.encryptedFileName).exists())
            writer.setCover(requireNotNull(dbA.dao().photo(photo.id)), keyA); storeA.publish(dbA)
            exchange(); storeB.applyQueued(dbB, keyB)
            assertTrue(requireNotNull(dbB.dao().photo(photo.id)).isCover)
            val secondBitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
            try { photosA.encrypt(secondBitmap, "other.vaultphoto", keyA) } finally { secondBitmap.recycle() }
            photosA.createThumbnail("other.vaultphoto", "other.vaultthumb", keyA)
            writer.save(VaultPhoto("other", "entry", "other.vaultphoto", "other.vaultthumb"), keyA)
            storeA.publish(dbA); exchange(); storeB.applyQueued(dbB, keyB)
            exchange(); storeB.applyQueued(dbB, keyB)
            assertNotNull(dbB.dao().photo("other"))
            writer.setCover(requireNotNull(dbA.dao().photo(photo.id)), keyA)
            LocalPhotoChangeWriter(dbB, idB, storeB.photoBlobs, photosB)
                .setCover(requireNotNull(dbB.dao().photo("other")), keyB)
            storeA.publish(dbA); storeB.publish(dbB); exchange()
            storeA.applyQueued(dbA, keyA); storeB.applyQueued(dbB, keyB)
            val coverConflict = SyncConflictResolver(dbA, idA, storeA.photoBlobs, a).reviews(keyA).single()
            assertEquals("photo_cover", coverConflict.type)
            SyncConflictResolver(dbA, idA, storeA.photoBlobs, a).resolve(coverConflict.id, true, keyA)
            storeA.publish(dbA); exchange(); storeB.applyQueued(dbB, keyB)
            assertTrue(requireNotNull(dbA.dao().photo("other")).isCover)
            assertTrue(requireNotNull(dbB.dao().photo("other")).isCover)
            val removedFile = requireNotNull(dbB.dao().photo(photo.id)).encryptedFileName
            writer.delete(requireNotNull(dbA.dao().photo(photo.id)), keyA); storeA.publish(dbA)
            exchange(); storeB.applyQueued(dbB, keyB)
            assertNull(dbB.dao().photo(photo.id))
            assertFalse(photosB.encryptedFile(removedFile).exists())
        } finally {
            pool.shutdownNow(); dbA.close(); dbB.close(); idA.clear(); idB.clear()
            root.deleteRecursively()
        }
    }

    @Test fun thirdDeviceSyncsDirectlyAfterSignedAdmissionReachesExistingPeer() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-three-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(label: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$label-$name", mode)
        }
        val contexts = listOf(context("a"), context("b"), context("c"))
        val databases = contexts.map { Room.inMemoryDatabaseBuilder(it, VaultDatabase::class.java).build() }
        val identities = contexts.map(::AndroidDeviceIdentityStore)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val devices = identities.map { it.getOrCreate() }
            val transport = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 11 })
            val content = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 12 })
            val events = mutableListOf(SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 1,
                GENESIS_HASH, MembershipAction.GENESIS, devices[0].deviceId, devices[0].deviceId,
                devices[0].publicKeyBase64Url, 1, identities[0]::sign)))
            (1..2).forEach { index ->
                events += SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", index + 1L,
                    events.last().hash, MembershipAction.ADD, devices[0].deviceId, devices[index].deviceId,
                    devices[index].publicKeyBase64Url, 1, identities[0]::sign))
            }
            databases.forEachIndexed { index, database ->
                database.dao().saveSettings(VaultSettings(vaultId = "vault"))
                database.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = content, transportSecret = transport))
                val known = if (index == 1) events.take(2) else events
                known.forEach { database.syncDao().insertMembershipEvent(it) }
                val verified = SyncMembershipManager.verify(known.map { it.toEvent() })
                verified.members.forEach { database.syncDao().upsertMembership(it) }
            }
            val stores = contexts.indices.map { LockedSyncStore(contexts[it], File(root, "store-$it")) }
            stores.indices.forEach { stores[it].publish(databases[it]) }
            stores[1].recordPeerAddress(devices[0].deviceId, "192.168.1.20")
            stores[1].recordPeerContact(devices[0].deviceId, completed = false)
            val contact = requireNotNull(stores[1].snapshot()?.lastContactAt?.get(devices[0].deviceId))
            assertTrue(contact > 0)
            assertNull(stores[1].snapshot()?.lastExchangeAt?.get(devices[0].deviceId))
            stores[1].recordPeerContact(devices[0].deviceId, completed = true)
            stores[1].publish(databases[1])
            assertEquals("192.168.1.20", stores[1].snapshot()?.peerAddresses?.get(devices[0].deviceId))
            assertTrue((stores[1].snapshot()?.lastExchangeAt?.get(devices[0].deviceId) ?: 0L) >= contact)
            fun exchange(first: Int, second: Int) {
                ServerSocket(0).use { listener ->
                    val server = pool.submit {
                        listener.accept().use { socket ->
                            socket.soTimeout = 15_000
                            LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                                stores[first], true)
                        }
                    }
                    Socket("127.0.0.1", listener.localPort).use { socket ->
                        socket.soTimeout = 15_000
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()),
                            stores[second], false)
                    }
                    server.get(20, TimeUnit.SECONDS)
                }
            }
            exchange(0, 1)
            assertEquals(3, stores[1].snapshot()?.membershipEvents?.size)
            stores[1].applyQueued(databases[1], ByteArray(32) { 2 })
            assertEquals(3, databases[1].syncDao().membershipEvents("vault").size)
            LocalEntryChangeWriter(databases[2], identities[2]).save(
                VaultEntry(id = "from-c", type = EntryType.NOTE, title = "Third phone"),
                emptySet(), ByteArray(32) { 3 })
            stores[2].publish(databases[2])
            exchange(1, 2)
            stores[1].applyQueued(databases[1], ByteArray(32) { 2 })
            assertEquals("Third phone", databases[1].dao().entry("from-c")?.entry?.title)
        } finally {
            pool.shutdownNow()
            databases.forEach { it.close() }
            identities.forEach { it.clear() }
            root.deleteRecursively()
        }
    }

    @Test fun outgoingHistoryUsesBoundedBatchesAcrossRestart() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "sync-index-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val identity = AndroidDeviceIdentityStore(context)
        try {
            identity.clear()
            val device = identity.getOrCreate()
            db.dao().saveSettings(VaultSettings(vaultId = "indexed-vault"))
            db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "indexed-vault",
                contentKey = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 7 }),
                transportSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 8 })))
            db.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("indexed-vault",
                device.deviceId, "Phone", device.publicKeyBase64Url, MemberStatus.ACTIVE,
                device.deviceId, 1, 1)))
            db.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(SyncMembershipEvent.sign(
                "indexed-vault", 1, GENESIS_HASH, MembershipAction.GENESIS, device.deviceId,
                device.deviceId, device.publicKeyBase64Url, 1, identity::sign)))
            val writer = LocalEntryChangeWriter(db, identity)
            repeat(101) { index -> writer.save(VaultEntry(id = "entry-$index", type = EntryType.NOTE,
                title = "Note $index"), emptySet(), ByteArray(32) { 7 }) }
            val store = LockedSyncStore(context, root)
            store.publish(db)
            assertEquals(100, store.pending(SyncFrontier(emptyMap())).size)
            assertEquals(listOf(101L), store.pending(SyncFrontier(mapOf(device.deviceId to 100L)))
                .map { it.sequence })
            assertEquals(100, LockedSyncStore(context, root).pending(SyncFrontier(emptyMap())).size)
        } finally {
            db.close()
            identity.clear()
            root.deleteRecursively()
        }
    }

    @Test fun ciphertextSurvivesRestartAndAppliesOnlyWhenRequestedAfterUnlock() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "locked-sync-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(prefix: String) = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$prefix-$name", mode)
        }
        val a = context("a"); val b = context("b")
        val dbA = Room.inMemoryDatabaseBuilder(a, VaultDatabase::class.java).build()
        val dbB = Room.inMemoryDatabaseBuilder(b, VaultDatabase::class.java).build()
        val idA = AndroidDeviceIdentityStore(a); val idB = AndroidDeviceIdentityStore(b)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val first = idA.getOrCreate(); val second = idB.getOrCreate()
            val sharedKey = ByteArray(32) { 17 }
            val transport = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 28 })
            val genesis = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 1,
                GENESIS_HASH, MembershipAction.GENESIS, first.deviceId, first.deviceId,
                first.publicKeyBase64Url, 1, idA::sign))
            val admission = SyncMembershipEventEntity.from(SyncMembershipEvent.sign("vault", 2,
                genesis.hash, MembershipAction.ADD, first.deviceId, second.deviceId,
                second.publicKeyBase64Url, 1, idA::sign))
            listOf(dbA, dbB).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = Base64.getUrlEncoder().withoutPadding().encodeToString(sharedKey),
                    transportSecret = transport))
                db.syncDao().insertMembershipEvent(genesis)
                db.syncDao().insertMembershipEvent(admission)
                listOf(first, second).forEachIndexed { index, identity ->
                    db.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("vault",
                        identity.deviceId, "Phone", identity.publicKeyBase64Url, MemberStatus.ACTIVE,
                        first.deviceId, index + 1L, 1)))
                }
            }
            val keyA = ByteArray(32) { 1 }; val keyB = ByteArray(32) { 2 }
            LocalEntryChangeWriter(dbA, idA).save(VaultEntry(id = "from-a", type = EntryType.NOTE,
                title = "Private note from A"), emptySet(), keyA)
            LocalEntryChangeWriter(dbB, idB).save(VaultEntry(id = "from-b", type = EntryType.NOTE,
                title = "Private note from B"), emptySet(), keyB)
            val storeA = LockedSyncStore(a, File(root, "a"))
            var storeB = LockedSyncStore(b, File(root, "b"))
            storeA.publish(dbA); storeB.publish(dbB)
            var serverPeer: String? = null
            var clientPeer: String? = null
            fun exchange() {
                ServerSocket(0).use { listener ->
                    val server = pool.submit {
                        listener.accept().use { socket ->
                            socket.soTimeout = 15_000
                            LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), storeA, true) { id, _ ->
                                serverPeer = id
                            }
                        }
                    }
                    Socket("127.0.0.1", listener.localPort).use { socket ->
                        socket.soTimeout = 15_000
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), storeB, false) { id, _ ->
                            clientPeer = id
                        }
                    }
                    server.get(20, TimeUnit.SECONDS)
                }
            }
            exchange()
            assertEquals(second.deviceId, serverPeer)
            assertEquals(first.deviceId, clientPeer)
            assertNull(dbA.dao().entry("from-b"))
            assertNull(dbB.dao().entry("from-a"))
            assertEquals(1, storeA.queuedCount())
            assertEquals(1, storeB.queuedCount())
            assertEquals(1L, storeA.peerProgress(second.deviceId)?.received?.get(first.deviceId)?.sequence)
            assertNull(storeA.peerProgress(second.deviceId)?.applied?.get(first.deviceId))
            assertTrue(runCatching { storeA.recordPeerProgress(second.deviceId,
                SyncProgress(mapOf(first.deviceId to SyncChainHead(1, "0".repeat(64))), emptyMap())) }.isFailure)
            storeB = LockedSyncStore(b, File(root, "b"))
            exchange()
            assertEquals("Retries must not duplicate queued changes", 1, storeB.queuedCount())
            root.walkTopDown().filter { it.isFile }.forEach {
                assertFalse(it.readBytes().toString(Charsets.ISO_8859_1).contains("Private note"))
            }
            assertEquals(1, storeA.applyQueued(dbA, keyA))
            assertEquals(1, storeB.applyQueued(dbB, keyB))
            assertEquals("Private note from A", dbB.dao().entry("from-a")?.entry?.title)
            assertEquals("Private note from B", dbA.dao().entry("from-b")?.entry?.title)
            exchange()
            assertEquals(1L, storeA.peerProgress(second.deviceId)?.applied?.get(first.deviceId)?.sequence)
            assertEquals(1L, storeB.peerProgress(first.deviceId)?.applied?.get(second.deviceId)?.sequence)

            val original = requireNotNull(dbA.dao().entry("from-a")).entry
            LocalEntryChangeWriter(dbA, idA).save(original.copy(title = "Edited on A"), emptySet(), keyA)
            LocalEntryChangeWriter(dbB, idB).save(original.copy(title = "Edited on B"), emptySet(), keyB)
            storeA.publish(dbA); storeB.publish(dbB)
            exchange()
            storeA.applyQueued(dbA, keyA); storeB.applyQueued(dbB, keyB)
            assertEquals("Edited on A", dbA.dao().entry("from-a")?.entry?.title)
            assertEquals("Edited on B", dbB.dao().entry("from-a")?.entry?.title)
            val resolver = SyncConflictResolver(dbA, idA)
            val review = resolver.reviews(keyA).single()
            assertTrue(review.current.description.contains("Edited on A"))
            assertTrue(review.incoming.description.contains("Edited on B"))
            assertTrue(review.current.device.contains(first.deviceId.take(8)))
            assertTrue(review.incoming.device.contains(second.deviceId.take(8)))
            resolver.resolve(review.id, useIncoming = true, keyA)
            storeA.publish(dbA)
            exchange()
            storeB.applyQueued(dbB, keyB)
            assertEquals("Edited on B", dbA.dao().entry("from-a")?.entry?.title)
            assertEquals("Edited on B", dbB.dao().entry("from-a")?.entry?.title)
            assertTrue(dbB.syncDao().conflicts().all { it.resolvedAtUtc.isNotEmpty() })

            val resolved = requireNotNull(dbA.dao().entry("from-a")).entry
            LocalEntryChangeWriter(dbA, idA).delete(resolved, keyA)
            LocalEntryChangeWriter(dbB, idB).save(resolved.copy(title = "Changed while deleted"), emptySet(), keyB)
            storeA.publish(dbA); storeB.publish(dbB)
            exchange()
            storeA.applyQueued(dbA, keyA); storeB.applyQueued(dbB, keyB)
            val deletion = resolver.reviews(keyA).single()
            assertEquals("Deleted", deletion.current.description)
            assertTrue(deletion.current.deleted)
            resolver.resolve(deletion.id, useIncoming = false, keyA)
            storeA.publish(dbA)
            exchange()
            storeB.applyQueued(dbB, keyB)
            assertNull(dbA.dao().entry("from-a"))
            assertNull(dbB.dao().entry("from-a"))
            assertTrue(dbA.syncDao().conflicts().all { it.resolvedAtUtc.isNotEmpty() })
            assertTrue(dbB.syncDao().conflicts().all { it.resolvedAtUtc.isNotEmpty() })
            val tampered = dbA.syncDao().operations().first().copy(hash = "0".repeat(64))
            assertTrue(runCatching { storeB.queue(tampered) }.isFailure)

            LocalEntryChangeWriter(dbA, idA).save(VaultEntry(id = "retryable", type = EntryType.NOTE,
                title = "Retry after key repair"), emptySet(), keyA)
            storeA.publish(dbA)
            exchange()
            val originalState = requireNotNull(dbB.syncDao().vaultState())
            dbB.syncDao().saveVaultState(originalState.copy(contentKey = Base64.getUrlEncoder()
                .withoutPadding().encodeToString(ByteArray(32) { 99 })))
            assertEquals(0, storeB.applyQueued(dbB, keyB))
            assertEquals(1, storeB.rejectedCount())
            assertEquals(0, storeB.queuedCount())
            dbB.syncDao().saveVaultState(originalState)
            storeB.retryRejected()
            assertEquals(1, storeB.applyQueued(dbB, keyB))
            assertEquals("Retry after key repair", dbB.dao().entry("retryable")?.entry?.title)

            val password = VaultEntry(id = "password-conflict", type = EntryType.PASSWORD,
                title = "Login", primaryValue = "user", secondaryValue = "initial")
            LocalEntryChangeWriter(dbA, idA).save(password, emptySet(), keyA)
            storeA.publish(dbA); exchange(); storeB.applyQueued(dbB, keyB)
            LocalEntryChangeWriter(dbA, idA).save(password.copy(secondaryValue = "first"), emptySet(), keyA)
            LocalEntryChangeWriter(dbB, idB).save(password.copy(secondaryValue = "second"), emptySet(), keyB)
            storeA.publish(dbA); storeB.publish(dbB); exchange()
            storeA.applyQueued(dbA, keyA)
            val passwordReview = resolver.reviews(keyA).single()
            assertTrue(passwordReview.current.description.contains("Password"))
            assertTrue(passwordReview.incoming.description.contains("Password"))
            assertFalse(passwordReview.current.description.contains("first"))
            assertFalse(passwordReview.incoming.description.contains("second"))

            LocalEntryChangeWriter(dbA, idA).save(VaultEntry(id = "totp-sync",
                type = EntryType.AUTHENTICATOR, title = "Test code", primaryValue = "Demo",
                secondaryValue = "JBSWY3DPEHPK3PXP"), emptySet(), keyA)
            storeA.publish(dbA)
            exchange()
            storeB.applyQueued(dbB, keyB)
            assertEquals("JBSWY3DPEHPK3PXP", dbB.dao().entry("totp-sync")?.entry?.secondaryValue)
        } finally {
            pool.shutdownNow()
            dbA.close(); dbB.close(); idA.clear(); idB.clear()
            root.deleteRecursively()
        }
    }
}
