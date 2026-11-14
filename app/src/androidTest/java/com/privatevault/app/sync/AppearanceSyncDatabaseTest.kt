package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.appearance.Appearance
import com.privatevault.app.appearance.AppearanceStore
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Writer and applier of protocol `appearance-v1` on two databases. [AppearanceStore] is one object
 * per process, so the test sets the second device's stored value by hand before applying there.
 */
class AppearanceSyncDatabaseTest {
    @Test fun concurrentChoicesSettleOnTheLastWriterWithoutConflicts() = runBlocking {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("appearance-sync-test-$name", mode)
        }
        val secondContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("appearance-sync-test-2-$name", mode)
        }
        val previous = AppearanceStore.read(context)
        val firstIdentity = AndroidDeviceIdentityStore(context)
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
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault", contentKey = content,
                    transportSecret = transport))
                listOf(phone, tablet).forEachIndexed { index, identity ->
                    db.syncDao().upsertMembership(SyncMembershipEntity("vault", identity.deviceId, "Android device",
                        identity.publicKeyBase64Url, MemberStatus.ACTIVE.name, phone.deviceId, index + 1L, 1))
                }
            }
            val key = ByteArray(32) { 9 }
            val early = "2026-11-13T10:00:00Z"
            val late = "2026-11-13T10:00:05Z"
            val slate = Appearance("blue", "slate")
            val forest = Appearance("green", "forest")

            // The tablet chooses first, the phone later; neither has seen the other.
            AppearanceStore.setLocal(context, slate, early, null)
            assertTrue(LocalAppearanceChangeWriter(second, secondIdentity).write(context, key, "vault"))
            val fromTablet = second.syncDao().operations().single()
            assertEquals(tablet.deviceId, AppearanceStore.read(context).updatedByDevice)
            assertFalse(AppearanceStore.read(context).pendingSync)
            AppearanceStore.setLocal(context, forest, late, null)
            assertTrue(LocalAppearanceChangeWriter(first, firstIdentity).write(context, key, "vault"))
            assertFalse("nothing pending, nothing written",
                LocalAppearanceChangeWriter(first, firstIdentity).write(context, key, "vault"))
            val fromPhone = first.syncDao().operations().single()
            assertEquals(AppearanceSyncRules.ENTITY_TYPE, fromPhone.entityType)
            assertEquals(AppearanceSyncRules.ENTITY_ID, fromPhone.entityId)
            assertEquals(late, fromPhone.occurredAtUtc)

            // On the phone the tablet's earlier choice loses; versions merge, no conflict is recorded.
            assertEquals(IncomingResult.OBSOLETE, IncomingEntryChangeApplier(first, null, context).apply(fromTablet, key))
            assertEquals(forest, AppearanceStore.read(context).appearance)
            val merged = RecordVersion(mapOf(phone.deviceId to 1L, tablet.deviceId to 1L))
            assertEquals(merged, RecordVersion.parse(requireNotNull(first.syncDao()
                .recordState(AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID)).recordVersionJson))
            assertTrue(first.syncDao().conflicts().isEmpty())

            // On the tablet (its stored value is the slate choice) the phone's later choice wins.
            AppearanceStore.applyRemote(context, slate, early, tablet.deviceId)
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(second, null, context).apply(fromPhone, key))
            val applied = AppearanceStore.read(context)
            assertEquals(forest, applied.appearance)
            assertEquals(phone.deviceId, applied.updatedByDevice)
            assertFalse("applying never echoes", applied.pendingSync)
            assertEquals(2, second.syncDao().operations().size)
            assertTrue(second.syncDao().conflicts().isEmpty())
            assertEquals(IncomingResult.REPLAY, IncomingEntryChangeApplier(second, null, context).apply(fromPhone, key))

            // The next local write includes both histories, so it dominates everywhere.
            AppearanceStore.setLocal(context, slate, "2026-11-13T09:00:00Z", null)
            assertTrue(LocalAppearanceChangeWriter(second, secondIdentity).write(context, key, "vault"))
            val dominating = second.syncDao().lastOperationForEntity(AppearanceSyncRules.ENTITY_TYPE,
                AppearanceSyncRules.ENTITY_ID)!!
            assertEquals(VersionRelation.AFTER, RecordVersion.parse(dominating.recordVersionJson).relationTo(merged))
            AppearanceStore.applyRemote(context, forest, late, phone.deviceId)
            assertEquals(IncomingResult.APPLIED, IncomingEntryChangeApplier(first, null, context).apply(dominating, key))
            assertEquals(slate, AppearanceStore.read(context).appearance)

            // A delete of the appearance record is invalid.
            val head = requireNotNull(first.syncDao().deviceHead("vault", phone.deviceId))
            val nonce = ByteArray(12) { 1 }
            val sealed = AppearanceSyncRules.encrypt(ByteArray(32) { 3 }, AppearanceSyncRules.aad("vault", "delete-appearance"),
                nonce, AppearanceSyncRules.encodePayload(slate))
            val encoder = Base64.getUrlEncoder().withoutPadding()
            val delete = SyncChangeRecord.createSigned("vault", phone.deviceId, head.sequence + 1, head.hash,
                "delete-appearance", AppearanceSyncRules.ENTITY_TYPE, AppearanceSyncRules.ENTITY_ID, ChangeKind.DELETE, 3,
                RecordVersion(mapOf(phone.deviceId to 5L, tablet.deviceId to 5L)), late, encoder.encodeToString(sealed),
                encoder.encodeToString(nonce), firstIdentity::sign)
            val deleteOperation = SyncOperationEntity(delete.mutationId, delete.formatVersion, delete.vaultId,
                delete.deviceId, delete.sequence, delete.previousHash, delete.entityType, delete.entityId, "delete",
                delete.baseRevision, delete.recordVersion.toJson(), delete.occurredAtUtc, delete.payloadCiphertext,
                delete.payloadNonce, delete.deviceSignature, delete.hash)
            assertTrue(runCatching { IncomingEntryChangeApplier(second, null, context).apply(deleteOperation, key) }
                .exceptionOrNull() is IllegalArgumentException)
        } finally {
            first.close(); second.close(); firstIdentity.clear(); secondIdentity.clear()
            AppearanceStore.applyRemote(context, previous.appearance, previous.updatedAtUtc ?: "1970-01-01T00:00:00Z",
                previous.updatedByDevice ?: "")
        }
    }
}
