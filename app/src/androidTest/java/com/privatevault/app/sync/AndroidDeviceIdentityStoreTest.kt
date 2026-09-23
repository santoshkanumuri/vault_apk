package com.privatevault.app.sync

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AndroidDeviceIdentityStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = AndroidDeviceIdentityStore(context)

    @Before
    fun clearBeforeTest() = store.clear()

    @After
    fun clearAfterTest() = store.clear()

    @Test
    fun persistsIdentityAndSigns() {
        val created = store.getOrCreate()
        val loaded = AndroidDeviceIdentityStore(context).getOrCreate()
        val message = "device-possession-test".toByteArray()
        val signature = store.sign(message)

        assertEquals(created.deviceId, loaded.deviceId)
        assertArrayEquals(created.publicKey, loaded.publicKey)
        assertTrue(DeviceIdentityCrypto.verify(loaded.publicKey, message, signature))
    }

    @Test
    fun rejectsAChangedDeviceId() {
        store.getOrCreate()
        check(
            context.getSharedPreferences("device_identity", android.content.Context.MODE_PRIVATE)
                .edit()
                .putString("device_id", "00000000-0000-4000-8000-000000000000")
                .commit(),
        )

        assertThrows(Exception::class.java) { store.getOrCreate() }
    }
}
