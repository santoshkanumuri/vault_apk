package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AndroidPairingTest {
    @Test fun qrPairingCopiesTheVaultAndLaterEditsSync() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "android-pairing-${UUID.randomUUID()}").apply { mkdirs() }
        fun context(label: String) = object : ContextWrapper(base) {
            override fun getFilesDir() = File(root, "$label/files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "$label/cache").apply { mkdirs() }
            override fun getNoBackupFilesDir() = File(root, "$label/no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int) =
                base.getSharedPreferences("${root.name}-$label-$name", mode)
        }
        val hostContext = context("host")
        val joinContext = context("join")
        val hostDb = Room.inMemoryDatabaseBuilder(hostContext, VaultDatabase::class.java).build()
        val joinDb = Room.inMemoryDatabaseBuilder(joinContext, VaultDatabase::class.java).build()
        val hostIdentity = AndroidDeviceIdentityStore(hostContext)
        val joinIdentity = AndroidDeviceIdentityStore(joinContext)
        val hostPairing = AndroidPairing(hostContext)
        val joinPairing = AndroidPairing(joinContext)
        val hostKey = ByteArray(32) { 6 }
        val joinKey = ByteArray(32) { 7 }
        val password = "pairing-test-password".toCharArray()
        val pool = Executors.newSingleThreadExecutor()
        try {
            hostDb.dao().saveSettings(VaultSettings(vaultId = "pairing-vault"))
            joinDb.dao().saveSettings(VaultSettings(vaultId = "empty-vault"))
            val creator = hostIdentity.getOrCreate()
            hostDb.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership("pairing-vault",
                creator.deviceId, "Host", creator.publicKeyBase64Url, MemberStatus.ACTIVE, creator.deviceId, 1, 1)))
            hostDb.prepareSyncGroup(hostIdentity)
            val writer = LocalEntryChangeWriter(hostDb, hostIdentity)
            writer.save(VaultEntry(id = "pairing-note", type = EntryType.NOTE, title = "Initial vault copy"),
                emptySet(), hostKey)
            suspend fun stage(pairing: AndroidPairing, expected: String): PairingUiState {
                val state = withTimeout(30_000) { pairing.state.first { it.stage in setOf(expected, "failed") } }
                assertEquals(state.message, expected, state.stage)
                return state
            }
            hostPairing.host(hostDb, hostKey, password)
            val offered = stage(hostPairing, "offering")
            joinPairing.join(joinDb, joinKey, offered.invitation, password)
            val hostConfirm = stage(hostPairing, "confirm")
            val joinConfirm = stage(joinPairing, "confirm")
            assertEquals(hostConfirm.confirmation, joinConfirm.confirmation)
            hostPairing.approve()
            joinPairing.approve()
            stage(hostPairing, "paired")
            stage(joinPairing, "paired")
            assertEquals("Initial vault copy", joinDb.dao().entry("pairing-note")?.entry?.title)
            assertEquals("pairing-vault", joinDb.dao().settings()?.vaultId)
            assertEquals(hostDb.syncDao().membershipEvents("pairing-vault"),
                joinDb.syncDao().membershipEvents("pairing-vault"))

            writer.save(requireNotNull(hostDb.dao().entry("pairing-note")).entry.copy(title = "Later Android edit"),
                emptySet(), hostKey)
            val hostStore = LockedSyncStore(hostContext)
            val joinStore = LockedSyncStore(joinContext)
            hostStore.publish(hostDb)
            joinStore.publish(joinDb)
            ServerSocket(0).use { listener ->
                val server = pool.submit {
                    listener.accept().use { socket ->
                        SyncFrames(socket.getInputStream(), socket.getOutputStream()).send(
                            MembershipWire.BUSY.toByteArray(), MembershipWire.CONTROL_FRAME)
                    }
                    listener.accept().use { socket ->
                        socket.soTimeout = 15_000
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), hostStore, true)
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 15_000
                    assertThrows(LanSyncExchange.PeerBusy::class.java) {
                        LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), joinStore, false)
                    }
                }
                Socket("127.0.0.1", listener.localPort).use { socket ->
                    socket.soTimeout = 15_000
                    LanSyncExchange.run(SyncFrames(socket.getInputStream(), socket.getOutputStream()), joinStore, false)
                }
                server.get(20, TimeUnit.SECONDS)
            }
            joinStore.applyQueued(joinDb, joinKey)
            assertEquals("Later Android edit", joinDb.dao().entry("pairing-note")?.entry?.title)
        } finally {
            hostPairing.close()
            joinPairing.close()
            pool.shutdownNow()
            hostDb.close()
            joinDb.close()
            hostIdentity.clear()
            joinIdentity.clear()
            password.fill('\u0000')
            root.deleteRecursively()
        }
    }
}
