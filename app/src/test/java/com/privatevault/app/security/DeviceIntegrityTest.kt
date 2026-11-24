package com.privatevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review L-12: the local root and debugger warning. */
class DeviceIntegrityTest {
    private val clean = DeviceIntegrity.Signals(debuggerAttached = false, debuggableRelease = false, suBinary = false, testKeys = false)

    @Test fun aCleanPhoneGetsNoWarning() {
        assertTrue(DeviceIntegrity.assess(clean).isEmpty())
        assertNull(DeviceIntegrity.warning(DeviceIntegrity.assess(clean)))
    }

    @Test fun eachSignalLeadsToAWarning() {
        listOf(clean.copy(debuggerAttached = true), clean.copy(debuggableRelease = true)).forEach {
            assertEquals(listOf(DeviceIntegrity.Concern.DEBUGGER), DeviceIntegrity.assess(it))
        }
        listOf(clean.copy(suBinary = true), clean.copy(testKeys = true)).forEach {
            assertEquals(listOf(DeviceIntegrity.Concern.ROOT), DeviceIntegrity.assess(it))
        }
        val both = DeviceIntegrity.assess(clean.copy(debuggerAttached = true, suBinary = true))
        assertEquals(listOf(DeviceIntegrity.Concern.DEBUGGER, DeviceIntegrity.Concern.ROOT), both)
        assertNotNull(DeviceIntegrity.warning(both))
    }
}
