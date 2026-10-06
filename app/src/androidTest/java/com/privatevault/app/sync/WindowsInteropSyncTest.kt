package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import java.util.UUID

/** Run with the Windows `android_emulator_sync_interop` test and adb forward tcp:18474 tcp:18474. */
class WindowsInteropSyncTest {
    @Test fun exchangesEditsWithWindowsInBothConnectionRoles() = runBlocking {
        val host = InstrumentationRegistry.getArguments().getString("windowsInteropHost")
        assumeTrue("Requires the Windows interoperability test host", host != null)
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "windows-interop-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$name", mode)
        }
        val db = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        val identityStore = AndroidDeviceIdentityStore(context)
        val localKey = ByteArray(32) { 7 }
        val gson = Gson()
        try {
            Socket(host, 18472).use { control ->
                control.soTimeout = 60_000
                val frames = SyncFrames(control.getInputStream(), control.getOutputStream())
                val windows = JsonParser.parseString(frames.receive().toString(Charsets.UTF_8)).asJsonObject
                android.util.Log.i("WindowsInteropTest", "Received Windows fixture identity")
                val local = identityStore.getOrCreate()
                val genesis = SyncMembershipEvent.sign("vault-a", 1, GENESIS_HASH, MembershipAction.GENESIS,
                    local.deviceId, local.deviceId, local.publicKeyBase64Url, 1, identityStore::sign)
                val admission = SyncMembershipEvent.sign("vault-a", 2, genesis.hash, MembershipAction.ADD,
                    local.deviceId, windows.get("deviceId").asString, windows.get("publicKey").asString,
                    1, identityStore::sign)
                val history = listOf(genesis, admission)
                val events = history.map(SyncMembershipEventEntity::from)
                val encoder = Base64.getUrlEncoder().withoutPadding()
                db.dao().saveSettings(VaultSettings(vaultId = "vault-a"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault-a",
                    contentKey = encoder.encodeToString(ByteArray(32) { 4 }),
                    transportSecret = encoder.encodeToString(ByteArray(32) { 5 })))
                events.forEach { db.syncDao().insertMembershipEvent(it) }
                SyncMembershipManager.verify(history).members.forEach { db.syncDao().upsertMembership(it) }
                val writer = LocalEntryChangeWriter(db, identityStore)
                writer.save(VaultEntry(id = "android-note", type = EntryType.NOTE,
                    title = "Android offline edit"), emptySet(), localKey)
                val store = LockedSyncStore(context, File(root, "transport"))
                store.publish(db)
                frames.send(gson.toJson(events).toByteArray(Charsets.UTF_8))
                assertEquals("ready", frames.receive().toString(Charsets.UTF_8))
                android.util.Log.i("WindowsInteropTest", "Fixture ready; starting Android client")

                Socket(host, 18473).use { socket ->
                    socket.soTimeout = 30_000
                    LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), store, false)
                }
                store.applyQueued(db, localKey)
                android.util.Log.i("WindowsInteropTest", "Android client exchange completed")
                val windowsId = windows.get("entryId").asString
                assertEquals("Windows offline edit", db.dao().entry(windowsId)?.entry?.title)
                assertEquals("received", frames.receive().toString(Charsets.UTF_8))
                writer.save(requireNotNull(db.dao().entry("android-note")).entry.copy(
                    title = "Android later edit"), emptySet(), localKey)
                store.publish(db)

                ServerSocket(18474).use { listener ->
                    listener.soTimeout = 30_000
                    frames.send("listening".toByteArray())
                    listener.accept().use { socket ->
                        socket.soTimeout = 30_000
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), store, true)
                    }
                }
                store.applyQueued(db, localKey)
                assertEquals("Windows later edit", db.dao().entry(windowsId)?.entry?.title)
                assertEquals(0, store.queuedCount())
                assertEquals(0, store.rejectedCount())
                assertEquals("complete", frames.receive().toString(Charsets.UTF_8))
            }
        } finally {
            db.close()
            identityStore.clear()
            root.deleteRecursively()
        }
    }
}
