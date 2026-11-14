package com.privatevault.app.watch

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatchPairingStoreTest {
    @get:Rule val folder = TemporaryFolder()
    private val vaultKey = ByteArray(32) { it.toByte() }
    private val watchKey = ByteArray(32) { (it * 7 + 3).toByte() }

    @Test fun wrappedKeyRoundTripsAndHidesTheKey() {
        val blob = WatchKeyWrap.wrap("vault-1", vaultKey, watchKey)
        assertEquals(62, blob.size)
        assertFalse(blob.toList().windowed(32).any { it.toByteArray().contentEquals(watchKey) })
        assertArrayEquals(watchKey, WatchKeyWrap.unwrap("vault-1", vaultKey, blob))
        assertFalse(WatchKeyWrap.wrap("vault-1", vaultKey, watchKey).contentEquals(blob))
    }

    @Test fun wrongVaultKeyVaultIdOrTamperingFails() {
        val blob = WatchKeyWrap.wrap("vault-1", vaultKey, watchKey)
        assertTrue(runCatching { WatchKeyWrap.unwrap("vault-1", ByteArray(32), blob) }.isFailure)
        assertTrue(runCatching { WatchKeyWrap.unwrap("vault-2", vaultKey, blob) }.isFailure)
        for (index in blob.indices) {
            val tampered = blob.copyOf().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertTrue("byte $index", runCatching { WatchKeyWrap.unwrap("vault-1", vaultKey, tampered) }.isFailure)
        }
        assertTrue(runCatching { WatchKeyWrap.unwrap("vault-1", vaultKey, blob.copyOf(61)) }.isFailure)
        assertTrue(runCatching { WatchKeyWrap.unwrap("vault-1", vaultKey, blob + 0.toByte()) }.isFailure)
    }

    @Test fun storeMovesFromLegacyToPairedToRemovedAndBack() {
        val file = File(folder.root, "watch-pairing")
        val store = WatchPairingStore(file)
        assertEquals(WatchPairingStore.State.NONE, store.state())
        assertSame(WatchPairing.Legacy, store.load("vault-1", vaultKey))

        store.savePaired("vault-1", vaultKey, watchKey)
        assertEquals(WatchPairingStore.State.PAIRED, store.state())
        assertArrayEquals(watchKey, (store.load("vault-1", vaultKey) as WatchPairing.Paired).key)

        store.markRemoved()
        assertEquals(WatchPairingStore.State.REMOVED, store.state())
        assertSame(WatchPairing.Removed, store.load("vault-1", vaultKey))
        assertArrayEquals(byteArrayOf(1, 2), file.readBytes())
        assertSame(WatchPairing.Removed, WatchPairingStore(file).load("vault-1", vaultKey))

        val next = ByteArray(32) { 9 }
        store.savePaired("vault-1", vaultKey, next)
        assertArrayEquals(next, (WatchPairingStore(file).load("vault-1", vaultKey) as WatchPairing.Paired).key)
        assertFalse(File(file.path + ".new").exists())
    }

    @Test fun unreadableStoredKeyNeverFallsBackToTheLegacyKey() {
        val file = File(folder.root, "watch-pairing")
        val store = WatchPairingStore(file)
        store.savePaired("vault-1", vaultKey, watchKey)
        assertTrue(runCatching { store.load("vault-1", ByteArray(32)) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { store.load("vault-2", vaultKey) }.isFailure)
        file.writeBytes(file.readBytes().also { it[20] = (it[20].toInt() xor 1).toByte() })
        assertTrue(runCatching { store.load("vault-1", vaultKey) }.isFailure)
        file.writeBytes(byteArrayOf(9, 9))
        assertTrue(runCatching { store.load("vault-1", vaultKey) }.isFailure)
        assertEquals(WatchPairingStore.State.PAIRED, store.state()) // A damaged record is not shown as a legacy pairing.
    }
}
