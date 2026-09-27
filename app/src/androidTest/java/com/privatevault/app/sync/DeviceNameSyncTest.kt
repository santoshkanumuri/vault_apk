package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64

class DeviceNameSyncTest {
    @Test fun ownDeviceNameReachesPeerThroughSignedChange() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val secondContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("device-name-test-$name", mode)
        }
        val firstIdentity = AndroidDeviceIdentityStore(base)
        val secondIdentity = AndroidDeviceIdentityStore(secondContext)
        firstIdentity.clear(); secondIdentity.clear()
        val first = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        val second = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        try {
            val phone = firstIdentity.getOrCreate()
            val tablet = secondIdentity.getOrCreate()
            val content = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
            val transport = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 })
            listOf(first, second).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = content, transportSecret = transport))
                listOf(phone, tablet).forEachIndexed { index, identity ->
                    db.syncDao().upsertMembership(SyncMembershipEntity("vault", identity.deviceId,
                        "Android device", identity.publicKeyBase64Url, MemberStatus.ACTIVE.name,
                        phone.deviceId, index + 1L, 1))
                }
            }
            val key = ByteArray(32) { 9 }
            LocalDeviceNameChangeWriter(first, firstIdentity).save("My phone", key)
            val change = first.syncDao().operations().single()
            assertEquals("device_name", change.entityType)
            assertEquals(phone.deviceId, change.entityId)
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(second).apply(change, key))
            assertEquals("My phone", second.syncDao().membership("vault", phone.deviceId)?.displayName)
            assertEquals("Android device", second.syncDao().membership("vault", tablet.deviceId)?.displayName)
            LocalDeviceNameChangeWriter(first, firstIdentity).save("My phone", key)
            assertEquals(1, first.syncDao().operations().size)
            assertFalse(runCatching { LocalDeviceNameChangeWriter(first, firstIdentity).save(" ", key) }
                .isSuccess)
        } finally {
            first.close(); second.close(); firstIdentity.clear(); secondIdentity.clear()
        }
    }
}
