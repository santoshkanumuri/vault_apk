package com.privatevault.app.sync

import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDeviceCapacityTest {
    private val mobiles = (1..6).map { "mobile-$it" }.toSet()
    private val windows = setOf("pc-1", "pc-2")

    @Test fun sixMobilesCanAddTwoWindows() {
        requireDeviceCapacity(mobiles, emptySet(), "pc-1", true)
        requireDeviceCapacity(mobiles + "pc-1", setOf("pc-1"), "pc-2", true)
    }

    @Test fun eachPlatformHasItsOwnLimit() {
        assertTrue(runCatching { requireDeviceCapacity(mobiles, emptySet(), "mobile-7", false) }.isFailure)
        assertTrue(runCatching { requireDeviceCapacity(windows + "mobile-1", windows, "pc-3", true) }.isFailure)
        requireDeviceCapacity(windows + (mobiles - "mobile-6"), windows, "mobile-6", false)
    }

    @Test fun fullGroupCanReconnectAnExistingDeviceButCannotAddAnother() {
        requireDeviceCapacity(mobiles + windows, windows, "pc-1", true)
        assertTrue(runCatching { requireDeviceCapacity(mobiles + windows, windows, "pc-3", true) }.isFailure)
    }

}
