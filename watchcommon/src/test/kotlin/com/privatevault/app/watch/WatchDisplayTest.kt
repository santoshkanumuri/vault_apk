package com.privatevault.app.watch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchDisplayTest {
    @Test fun codesAreGroupedForReading() {
        assertEquals("123 456", WatchDisplay.groupCode("123456"))
        assertEquals("1234 5678", WatchDisplay.groupCode("12345678"))
        assertEquals("123 456 7", WatchDisplay.groupCode("1234567"))
    }

    @Test fun remainingSecondsCountDownWithinThePeriod() {
        assertEquals(30, WatchDisplay.remainingSeconds(30, 60))
        assertEquals(1, WatchDisplay.remainingSeconds(30, 89))
        assertEquals(45, WatchDisplay.remainingSeconds(60, 15))
    }

    @Test fun urgencyAndNextCodeFollowTheLastSeconds() {
        assertEquals(CodeUrgency.CALM, WatchDisplay.urgency(11))
        assertEquals(CodeUrgency.SOON, WatchDisplay.urgency(10))
        assertEquals(CodeUrgency.EXPIRING, WatchDisplay.urgency(5))
        assertFalse(WatchDisplay.showNextCode(11))
        assertTrue(WatchDisplay.showNextCode(10))
    }

    @Test fun recentAccountsMoveToTheFrontWithoutDuplicates() {
        assertEquals(listOf("b", "a"), WatchDisplay.recordRecent(listOf("a", "b"), "b").take(2))
        assertEquals(listOf("e", "a", "b", "c"), WatchDisplay.recordRecent(listOf("a", "b", "c", "d"), "e"))
    }

    @Test fun syncedLabelDescribesAgeAndNothingBeforeTheFirstSync() {
        val now = 1_000_000_000_000L
        assertNull(WatchDisplay.syncedLabel(now, 0))
        assertEquals("Synced just now", WatchDisplay.syncedLabel(now, now - 20_000))
        assertEquals("Synced 5 min ago", WatchDisplay.syncedLabel(now, now - 5 * 60_000))
        assertEquals("Synced 3 h ago", WatchDisplay.syncedLabel(now, now - 3 * 3_600_000))
        assertTrue(WatchDisplay.syncedLabel(now, now - 3 * 86_400_000)!!.startsWith("Synced "))
        // A clock that moved backwards never shows a negative age.
        assertEquals("Synced just now", WatchDisplay.syncedLabel(now, now + 60_000))
        assertEquals("Last sent 2 min ago", WatchDisplay.syncedLabel(now, now - 2 * 60_000, prefix = "Last sent"))
    }
}
