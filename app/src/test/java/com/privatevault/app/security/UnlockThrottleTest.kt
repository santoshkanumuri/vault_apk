package com.privatevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

/** Security review L-03: wrong master passwords slow down further tries. */
class UnlockThrottleTest {
    @Test fun firstTriesAreFree() {
        (0 until UnlockThrottle.FREE_ATTEMPTS).forEach { assertEquals(0L, UnlockThrottle.delayAfter(it)) }
    }

    @Test fun delayDoublesUpToTheCap() {
        assertEquals(30_000L, UnlockThrottle.delayAfter(5))
        assertEquals(60_000L, UnlockThrottle.delayAfter(6))
        assertEquals(120_000L, UnlockThrottle.delayAfter(7))
        assertEquals(UnlockThrottle.MAX_DELAY_MILLIS, UnlockThrottle.delayAfter(20))
        assertEquals(UnlockThrottle.MAX_DELAY_MILLIS, UnlockThrottle.delayAfter(Int.MAX_VALUE))
    }

    @Test fun waitCountsDownAndIgnoresAClockMovedBack() {
        assertEquals(30_000L, UnlockThrottle.waitMillis(5, lastFailureAt = 1_000_000, now = 1_000_000))
        assertEquals(10_000L, UnlockThrottle.waitMillis(5, lastFailureAt = 1_000_000, now = 1_020_000))
        assertEquals(0L, UnlockThrottle.waitMillis(5, lastFailureAt = 1_000_000, now = 1_040_000))
        assertEquals(30_000L, UnlockThrottle.waitMillis(5, lastFailureAt = 1_000_000, now = 500_000))
        assertEquals(0L, UnlockThrottle.waitMillis(2, lastFailureAt = 1_000_000, now = 1_000_000))
    }

    @Test fun describesWaitsRoundedUp() {
        assertEquals("1 second", UnlockThrottle.describe(1))
        assertEquals("30 seconds", UnlockThrottle.describe(29_500))
        assertEquals("1 minute", UnlockThrottle.describe(60_000))
        assertEquals("2 minutes", UnlockThrottle.describe(60_001))
    }
}
