package com.privatevault.app.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review L-06: unproved connections are limited per source and in total. */
class PreAuthLimiterTest {
    @Test fun oneSourceCannotTakeEverySlot() {
        val limiter = PreAuthLimiter(total = 4, perSource = 2)
        assertTrue(limiter.tryStart("10.0.0.9", 0))
        assertTrue(limiter.tryStart("10.0.0.9", 0))
        assertFalse("a third unproved connection from one source", limiter.tryStart("10.0.0.9", 0))
        assertTrue("a paired device still gets in", limiter.tryStart("10.0.0.2", 0))
        limiter.finish("10.0.0.9", proved = false, now = 1)
        assertTrue(limiter.tryStart("10.0.0.9", 2))
    }

    @Test fun theTotalIsCapped() {
        val limiter = PreAuthLimiter(total = 3, perSource = 2)
        assertTrue(limiter.tryStart("a", 0)); assertTrue(limiter.tryStart("b", 0)); assertTrue(limiter.tryStart("c", 0))
        assertFalse(limiter.tryStart("d", 0))
        limiter.finish("b", proved = true, now = 1)
        assertTrue(limiter.tryStart("d", 1))
    }

    @Test fun repeatedFailuresBlockASourceForAWhile() {
        val limiter = PreAuthLimiter(maxFailures = 3, windowMillis = 60_000, blockMillis = 120_000)
        repeat(3) { assertTrue(limiter.tryStart("10.0.0.9", it.toLong())); limiter.finish("10.0.0.9", false, it.toLong()) }
        assertFalse(limiter.tryStart("10.0.0.9", 10))
        assertTrue("other devices are not affected", limiter.tryStart("10.0.0.2", 10))
        assertTrue("the block ends", limiter.tryStart("10.0.0.9", 2 + 120_000))
    }

    @Test fun failuresOutsideTheWindowAndASuccessResetTheCount() {
        val limiter = PreAuthLimiter(maxFailures = 3, windowMillis = 1_000, blockMillis = 60_000)
        repeat(2) { limiter.tryStart("s", 0); limiter.finish("s", false, 0) }
        limiter.tryStart("s", 5_000); limiter.finish("s", false, 5_000)
        assertTrue("old failures expired", limiter.tryStart("s", 5_001)); limiter.finish("s", true, 5_001)
        repeat(2) { limiter.tryStart("s", 5_002); limiter.finish("s", false, 5_002) }
        assertTrue("a proved connection cleared the count", limiter.tryStart("s", 5_003))
    }
}
