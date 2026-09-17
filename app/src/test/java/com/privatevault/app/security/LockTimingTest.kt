package com.privatevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Test

class LockTimingTest {
    @Test fun ordinaryAppSwitchHasTenSeconds() {
        assertEquals(11_000L, backgroundLockDeadline(1_000L, 60_000L, false))
    }
    @Test fun appSwitchCannotExtendInactivityDeadline() {
        assertEquals(5_000L, backgroundLockDeadline(1_000L, 5_000L, false))
    }
    @Test fun cameraKeepsExistingDeadlineRatherThanExtendingIt() {
        assertEquals(60_000L, backgroundLockDeadline(50_000L, 60_000L, true))
    }
}
