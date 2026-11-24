package com.privatevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class LockTimingTest {
    @Test fun everyBackgroundTimeoutUsesTheSelectedDeadline() {
        backgroundTimeouts.forEach { timeout ->
            assertEquals(minOf(1_000L + timeout, 1_000_000L), backgroundLockDeadline(1_000L, 1_000_000L, false, timeout))
        }
    }
    @Test fun appSwitchCannotExtendInactivityDeadline() {
        assertEquals(5_000L, backgroundLockDeadline(1_000L, 5_000L, false))
    }
    @Test fun pickerThatNuvoriOpenedCountsAsTimeInTheAppUpToTheLimit() {
        // A 1-minute inactivity lock must not end a backup or file choice that takes longer.
        backgroundTimeouts.forEach { timeout ->
            assertEquals(50_000L + EXTERNAL_FLOW_LIMIT_MS, backgroundLockDeadline(50_000L, 60_000L, true, timeout))
        }
        assertEquals(600_000L, EXTERNAL_FLOW_LIMIT_MS)
    }
    @Test fun pickerNeverShortensALaterInactivityDeadline() {
        val deadline = 1_000L + 1_800_000L
        assertEquals(deadline, backgroundLockDeadline(2_000L, deadline, true))
    }
    @Test fun appSwitchWithoutPickerStillLocksOnTheBackgroundTimer() {
        backgroundTimeouts.forEach { timeout ->
            val deadline = backgroundLockDeadline(50_000L, 60_000L, false, timeout)
            assert(deadline <= 60_000L) { "An app switch must never extend the inactivity deadline" }
        }
    }
    @Test fun everyInactivityOptionHasAnAbsoluteDeadline() {
        inactivityTimeouts.forEach { timeout ->
            val lastTouch = 1_000L
            val deadline = lastTouch + timeout
            assertEquals(maxOf(deadline, deadline - 1 + EXTERNAL_FLOW_LIMIT_MS), backgroundLockDeadline(deadline - 1, deadline, true))
            assertEquals(deadline, backgroundLockDeadline(deadline - 1, deadline, false, 300_000L))
        }
    }
    @Test fun biometricExpiryRejectsElapsedDeadlineRollbackAndReboot() {
        masterPasswordIntervals.drop(1).forEach { duration ->
            fun valid(wall: Long, elapsed: Long, boot: Int = 4, seen: Long = 1_000L) =
                biometricSessionValid(wall, elapsed, boot, 1_000L, 500L, 4, seen, duration)
            assertEquals(true, valid(1_000L + duration - 1, 500L + duration - 1))
            assertEquals(false, valid(1_000L + duration, 501L))
            assertEquals(false, valid(1_001L, 500L + duration))
            assertEquals(false, valid(999L, 501L))
            assertEquals(false, valid(1_100L, 501L, seen = 1_200L))
            assertEquals(false, valid(1_100L, 501L, boot = 5))
        }
        assertEquals(false, biometricSessionValid(1_001L, 501L, 4, 1_000L, 500L, 4, 1_000L, 0L))
    }
}
