package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import java.util.UUID

class SyncEnrollmentPolicyTest {
    @Test fun onlyCurrentManagerCanPrepareEnrollmentButSecondaryCanEdit() = runBlocking {
        val prefix = "enrollment-policy-${UUID.randomUUID()}"
        val context = object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("$prefix-$name", mode)
        }
        val identities = AndroidDeviceIdentityStore(context)
        val database = Room.inMemoryDatabaseBuilder(context, VaultDatabase::class.java).build()
        try {
            val local = identities.getOrCreate()
            val remoteSecret = ByteArray(32) { 7 }
            val remoteKey = Base64.getUrlEncoder().withoutPadding().encodeToString(DeviceIdentityCrypto.publicKey(remoteSecret))
            val vaultId = "policy-vault"
            database.dao().saveSettings(VaultSettings(vaultId = vaultId))
            database.syncDao().upsertMembership(SyncMembershipEntity.from(DeviceMembership(vaultId,
                local.deviceId, "Phone", local.publicKeyBase64Url, MemberStatus.ACTIVE, local.deviceId, 1, 1)))
            database.prepareSyncGroup(identities)
            val genesis = database.syncDao().membershipEvents(vaultId).single().toEvent()
            val admission = SyncMembershipEvent.sign(vaultId, 2, genesis.hash, MembershipAction.ADD,
                local.deviceId, "tablet", remoteKey, 1, identities::sign)
            val transfer = SyncMembershipEvent.sign(vaultId, 3, admission.hash, MembershipAction.TRANSFER,
                local.deviceId, "tablet", "", 1, identities::sign)
            for (event in listOf(admission, transfer)) database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(event))
            val stateBefore = database.syncDao().vaultState()
            try {
                database.prepareSyncGroup(identities)
                fail("A secondary must not host enrollment")
            } catch (expected: IllegalArgumentException) {
                assertEquals("Add devices from the managing device", expected.message)
            }
            assertEquals(stateBefore, database.syncDao().vaultState())
            assertEquals(3, database.syncDao().membershipEvents(vaultId).size)
            LocalEntryChangeWriter(database, identities).save(VaultEntry(id = "offline-note",
                type = EntryType.NOTE, title = "Secondary offline edit"), emptySet(), ByteArray(32) { 9 })
            assertEquals("Secondary offline edit", database.dao().entry("offline-note")?.entry?.title)
            val transferBack = SyncMembershipEvent.sign(vaultId, 4, transfer.hash, MembershipAction.TRANSFER,
                "tablet", local.deviceId, "", 1) { DeviceIdentityCrypto.sign(remoteSecret, it) }
            database.syncDao().insertMembershipEvent(SyncMembershipEventEntity.from(transferBack))
            database.prepareSyncGroup(identities)
            assertEquals(stateBefore, database.syncDao().vaultState())
        } finally { database.close(); identities.clear() }
    }
}
