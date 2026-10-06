package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
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

class SixDeviceSyncTest {
    @Test fun sixPhonesRelayOfflineEditsWithoutTheManagerConnectingToEveryPhone() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "sync-six-${UUID.randomUUID()}").apply { mkdirs() }
        val contexts = (0..5).map { index -> object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$index-$name", mode)
        } }
        val databases = contexts.map { Room.inMemoryDatabaseBuilder(it, VaultDatabase::class.java).build() }
        val identities = contexts.map(::AndroidDeviceIdentityStore)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val devices = identities.map { it.getOrCreate() }
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val events = mutableListOf(SyncMembershipEventEntity.from(SyncMembershipEvent.sign("six-vault", 1,
                GENESIS_HASH, MembershipAction.GENESIS, devices[0].deviceId, devices[0].deviceId,
                devices[0].publicKeyBase64Url, 1, identities[0]::sign)))
            (1..5).forEach { index -> events += SyncMembershipEventEntity.from(SyncMembershipEvent.sign(
                "six-vault", index + 1L, events.last().hash, MembershipAction.ADD, devices[0].deviceId,
                devices[index].deviceId, devices[index].publicKeyBase64Url, 1, identities[0]::sign)) }
            val members = SyncMembershipManager.verify(events.map { it.toEvent() }).members
            val keys = contexts.indices.map { index -> ByteArray(32) { (index + 1).toByte() } }
            databases.forEachIndexed { index, db ->
                db.dao().saveSettings(VaultSettings(vaultId = "six-vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "six-vault",
                    contentKey = encoder.encodeToString(ByteArray(32) { 12 }),
                    transportSecret = encoder.encodeToString(ByteArray(32) { 11 })))
                events.forEach { db.syncDao().insertMembershipEvent(it) }
                members.forEach { db.syncDao().upsertMembership(it) }
                LocalEntryChangeWriter(db, identities[index]).save(VaultEntry(id = "note-$index",
                    type = EntryType.NOTE, title = "Phone $index offline edit"), emptySet(), keys[index])
            }
            val stores = contexts.indices.map { LockedSyncStore(contexts[it], File(root, "store-$it")) }
            stores.indices.forEach { stores[it].publish(databases[it]) }
            suspend fun exchange(first: Int, second: Int) {
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
                listOf(first, second).forEach { index -> stores[index].applyQueued(databases[index], keys[index]) }
            }
            (0..4).forEach { exchange(it, it + 1) }
            (5 downTo 1).forEach { exchange(it, it - 1) }
            databases.forEachIndexed { index, db ->
                (0..5).forEach { author -> assertEquals("Phone $author offline edit", db.dao().entry("note-$author")?.entry?.title) }
                assertEquals(0, stores[index].queuedCount())
                assertEquals(0, stores[index].rejectedCount())
            }
        } finally {
            pool.shutdownNow()
            databases.forEach { it.close() }
            identities.forEach { it.clear() }
            root.deleteRecursively()
        }
    }
}
