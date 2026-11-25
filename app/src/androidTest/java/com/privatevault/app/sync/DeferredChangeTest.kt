package com.privatevault.app.sync

import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SyncAuthorCount
import com.privatevault.app.data.SyncDeviceHeadEntity
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M4: a kind of change a newer app version writes is stored in its author's chain without being
 * read, so the author's later changes still apply and it is passed on; it applies after an update.
 */
class DeferredChangeTest {
    @Test fun aNewerKindOfChangeIsStoredAndTheAuthorsLaterChangesStillApply() = runBlocking {
        val base: android.content.Context = InstrumentationRegistry.getInstrumentation().targetContext
        val authorContext = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                base.getSharedPreferences("deferred-test-$name", mode)
        }
        val receiverIdentity = AndroidDeviceIdentityStore(base)
        val authorIdentity = AndroidDeviceIdentityStore(authorContext)
        receiverIdentity.clear(); authorIdentity.clear()
        val author: VaultDatabase = Room.inMemoryDatabaseBuilder(authorContext, VaultDatabase::class.java).build()
        val receiver: VaultDatabase = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        try {
            val receiverDevice = receiverIdentity.getOrCreate()
            val authorDevice = authorIdentity.getOrCreate()
            val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
            listOf(author, receiver).forEach { db ->
                db.dao().saveSettings(VaultSettings(vaultId = "vault"))
                db.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault",
                    contentKey = encoder.encodeToString(ByteArray(32) { 3 }),
                    transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
                listOf(receiverDevice, authorDevice).forEachIndexed { index, member ->
                    db.syncDao().upsertMembership(SyncMembershipEntity("vault", member.deviceId,
                        "Device", member.publicKeyBase64Url, MemberStatus.ACTIVE.name,
                        receiverDevice.deviceId, index + 1L, 1))
                }
            }
            val vaultKey = ByteArray(32) { 9 }
            // A kind this version doesn't know. Its payload is never decrypted here.
            val newer = SyncChangeRecord.createSigned("vault", authorDevice.deviceId, 1, GENESIS_HASH,
                "mutation-newer", "future_kind", "a".repeat(64), ChangeKind.UPSERT, 0,
                RecordVersion(mapOf(authorDevice.deviceId to 1L)), "2026-11-25T00:00:00Z",
                encoder.encodeToString(ByteArray(40) { 7 }), encoder.encodeToString(ByteArray(12) { 8 }),
                authorIdentity::sign)
            val newerOperation = with(SyncPayloadCrypto) { newer.toEntity() }
            author.syncDao().insertOperation(newerOperation)
            author.syncDao().upsertDeviceHead(SyncDeviceHeadEntity("vault", authorDevice.deviceId, 1, newer.hash))
            LocalEntryChangeWriter(author, authorIdentity)
                .save(VaultEntry(id = "later", type = EntryType.NOTE, title = "Written after"), emptySet(), vaultKey)
            val later = author.syncDao().operations().single { it.entityType == "entry" }
            assertEquals(2L, later.sequence)
            assertEquals(newer.hash, later.previousHash)

            val applier = IncomingEntryChangeApplier(receiver, announceRemoteChanges = false)
            assertEquals(IncomingResult.DEFERRED, applier.apply(newerOperation, vaultKey))
            assertEquals(SyncDeviceHeadEntity("vault", authorDevice.deviceId, 1, newer.hash),
                receiver.syncDao().deviceHead("vault", authorDevice.deviceId))
            assertEquals(IncomingResult.REPLAY, applier.apply(newerOperation, vaultKey))
            // A forged copy is refused before it is stored, like any change.
            assertTrue(runCatching { applier.apply(newerOperation.copy(entityId = "b".repeat(64)), vaultKey) }.isFailure)
            // The author's next change applies: nothing waits behind the newer kind.
            assertEquals(IncomingResult.APPLIED, applier.apply(later, vaultKey))
            assertNotNull(receiver.dao().entry("later"))
            // Counted for "Update Nuvori" and found again after an update.
            assertEquals(listOf(SyncAuthorCount(authorDevice.deviceId, 1)),
                receiver.syncDao().otherTypeCounts("vault", SUPPORTED_ENTITY_TYPES.sorted()))
            assertEquals(listOf(newer.hash), receiver.syncDao().operationsOfTypes("vault", listOf("future_kind")).map { it.hash })
            // Still unknown to this version: the retry leaves it stored.
            assertTrue(runCatching { applier.apply(newerOperation, vaultKey, stored = true) }
                .exceptionOrNull() is UnsupportedSyncEntityException)
            // Trying a stored change again is harmless: it is outdated against what it already set.
            assertEquals(IncomingResult.OBSOLETE, applier.apply(later, vaultKey, stored = true))
            assertEquals(2, receiver.syncDao().operations().size)
        } finally {
            author.close(); receiver.close(); receiverIdentity.clear(); authorIdentity.clear()
        }
    }
}
