package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/** Wi-Fi session rules from the sync audit: M10 (every device), M11 (stalls), M12 (refusals), M13 (photos). */
class SyncSessionGuardTest {
    @Test fun aSessionThatStopsMovingOrRunsTooLongExpires() {
        var now = 0L
        val watch = SessionWatch({ now })
        val sink = ByteArrayOutputStream()
        val out = watch.output(sink)
        now = 10_000; out.write(ByteArray(10)); out.flush()
        assertFalse(watch.expired(now + 29_000, quietLimit = 30_000, deadline = 600_000))
        assertTrue("nothing moved for 31 s", watch.expired(now + 31_000, quietLimit = 30_000, deadline = 600_000))
        val input = watch.input(ByteArrayInputStream(ByteArray(4)))
        now = 50_000; input.read(ByteArray(4))
        assertFalse(watch.expired(now + 1_000, quietLimit = 30_000, deadline = 600_000))
        assertTrue("past the session deadline", watch.expired(600_001, quietLimit = 30_000, deadline = 600_000))
    }

    /** A large frame goes out in chunks, each one counting as progress. */
    @Test fun aLargeWriteCountsProgressPerChunk() {
        var now = 0L
        val watch = SessionWatch({ now })
        val stamps = mutableListOf<Long>()
        val slow = object : OutputStream() {
            override fun write(b: Int) = throw IOException("byte writes are not expected")
            override fun write(b: ByteArray, off: Int, len: Int) { now += 1_000; stamps += now }
        }
        watch.output(slow).write(ByteArray(SessionWatch.CHUNK * 3 + 5))
        assertEquals(4, stamps.size)
        assertEquals(now, watch.lastProgressAt)
    }

    @Test fun refusalsAreToldApartFromDroppedConnections() {
        assertEquals(SessionFailure.TEMPORARY, SessionFailure.of(java.net.SocketTimeoutException()))
        assertEquals(SessionFailure.TEMPORARY, SessionFailure.of(java.io.EOFException()))
        assertEquals(SessionFailure.REFUSED, SessionFailure.of(IllegalArgumentException("Invalid device signature")))
        assertEquals(SessionFailure.REFUSED, SessionFailure.of(IllegalStateException("Peer reported a conflicting change head")))
        assertEquals(SessionFailure.TEMPORARY, SessionFailure.of(RuntimeException()))
    }

    @Test fun aOneTimePassEndsOnlyOnceEveryDeviceWasReached() {
        assertFalse(oneTimePassDone(listOf("pc", "tablet"), listOf("pc")))
        assertTrue(oneTimePassDone(listOf("pc", "tablet"), listOf("tablet", "pc")))
        assertFalse("no paired device means nothing was checked", oneTimePassDone(emptyList(), emptyList()))
    }

    @Test fun imagesAPeerLacksAreAskedForLast() {
        val missing = (1..40).map { "%064x".format(it) }
        assertEquals(missing.take(32), PhotoRequestOrder.pick(missing, emptyMap(), 32))
        val lacked = missing.take(32).associateWith { 100L }
        val next = PhotoRequestOrder.pick(missing, lacked, 32)
        assertEquals("the 8 not yet tried come first", missing.drop(32), next.take(8))
        assertEquals(32, next.size)
    }

    @Test fun autoSyncRunningTakesOneTimeChecks() {
        assertEquals(SyncRun.AUTO, syncRun(LanSyncService.SYNC_NOW, paused = false, notificationsOn = false, autoRunning = true))
        assertEquals(SyncRun.AUTO, syncRun(LanSyncService.SYNC_ON_OPEN, paused = false, notificationsOn = false, autoRunning = true))
        assertEquals(SyncRun.ONE_PASS, syncRun(LanSyncService.SYNC_NOW, paused = false, notificationsOn = false, autoRunning = false))
    }
}
