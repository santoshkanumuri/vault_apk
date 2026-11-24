package com.privatevault.app.sync

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncVaultStateEntity
import com.privatevault.app.data.SyncedSiteIcon
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * The applier of protocol `site-icons-v1` on a real (in-memory) vault database. Two other members stand in for
 * Nuvori for Windows: they sign site icon changes the way its writer does.
 */
class SiteIconSyncDatabaseTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val contentKey = ByteArray(32) { 3 }
    private val vaultKey = ByteArray(32) { 9 }

    private fun context(tag: String): Context = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            base.getSharedPreferences("site-icon-sync-test-$tag-$name", mode)
    }

    private fun png(color: Int, side: Int = 32): ByteArray {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
    }

    /** A signed site icon change by [identity], encrypted under the group's content key. */
    private fun change(identity: AndroidDeviceIdentityStore, db: VaultDatabase, sequence: Long, previous: String,
        mutationId: String, site: String, png: ByteArray, version: Map<String, Long>, at: String,
        entityId: String = SiteIconSyncRules.entityId(contentKey, site), kind: ChangeKind = ChangeKind.UPSERT): SyncOperationEntity {
        val plaintext = """{"siteIcon":{"site":"$site","png":"${Base64.getEncoder().encodeToString(png)}"}}"""
        val nonce = ByteArray(12) { sequence.toByte() }
        val sealed = AppearanceSyncRules.encrypt(contentKey, "nuvori-sync-site_icon-v1:vault:$mutationId:$entityId", nonce,
            plaintext.toByteArray(Charsets.UTF_8))
        val signed = SyncChangeRecord.createSigned("vault", identity.getOrCreate().deviceId, sequence, previous, mutationId,
            SiteIconSyncRules.ENTITY_TYPE, entityId, kind, sequence - 1, RecordVersion(version), at,
            encoder.encodeToString(sealed), encoder.encodeToString(nonce), identity::sign)
        return SyncOperationEntity(signed.mutationId, signed.formatVersion, signed.vaultId, signed.deviceId, signed.sequence,
            signed.previousHash, signed.entityType, signed.entityId, kind.name.lowercase(), signed.baseRevision,
            signed.recordVersion.toJson(), signed.occurredAtUtc, signed.payloadCiphertext, signed.payloadNonce,
            signed.deviceSignature, signed.hash)
    }

    @Test fun iconsFromAnotherDeviceApplyMergeAndShow() = runBlocking {
        val pcIdentity = AndroidDeviceIdentityStore(context("pc"))
        val laptopIdentity = AndroidDeviceIdentityStore(context("laptop"))
        pcIdentity.clear(); laptopIdentity.clear()
        val phone = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        try {
            val pc = pcIdentity.getOrCreate()
            val laptop = laptopIdentity.getOrCreate()
            phone.dao().saveSettings(VaultSettings(vaultId = "vault"))
            phone.syncDao().saveVaultState(SyncVaultStateEntity(vaultId = "vault", contentKey = encoder.encodeToString(contentKey),
                transportSecret = encoder.encodeToString(ByteArray(32) { 4 })))
            listOf(pc, laptop).forEachIndexed { index, identity ->
                phone.syncDao().upsertMembership(SyncMembershipEntity("vault", identity.deviceId, "Windows",
                    identity.publicKeyBase64Url, MemberStatus.ACTIVE.name, pc.deviceId, index + 1L, 1))
            }
            val applier = IncomingEntryChangeApplier(phone, null, base)
            SyncedSiteIconCache.open("test", emptyList())
            val blue = png(Color.BLUE)
            val red = png(Color.RED)

            // A new icon applies, is stored under its site, and shows at once.
            val first = change(pcIdentity, phone, 1, GENESIS_HASH, "icon-1", "github.com", blue, mapOf(pc.deviceId to 1L),
                "2026-11-21T10:00:00Z")
            assertEquals(IncomingResult.APPLIED, applier.apply(first, vaultKey))
            assertArrayEquals(blue, requireNotNull(phone.syncedSiteIcons().icon("github.com")).png)
            assertNotNull("a login on any github.com page shows it", SyncedSiteIconCache.lookup("https://gist.github.com/x"))
            assertNull(SyncedSiteIconCache.lookup("https://gitlab.com"))
            // The entity id travels in plain text and does not show the site.
            assertTrue(!first.entityId.contains("github"))

            // A later change by the same device replaces it, even with an earlier clock.
            val second = change(pcIdentity, phone, 2, first.hash, "icon-2", "github.com", red, mapOf(pc.deviceId to 2L),
                "2026-11-21T09:00:00Z")
            assertEquals(IncomingResult.APPLIED, applier.apply(second, vaultKey))
            assertArrayEquals(red, requireNotNull(phone.syncedSiteIcons().icon("github.com")).png)

            // A concurrent change with an earlier time loses; versions merge, and nothing waits for review.
            val concurrent = change(laptopIdentity, phone, 1, GENESIS_HASH, "icon-3", "github.com", blue,
                mapOf(laptop.deviceId to 1L), "2026-11-21T08:00:00Z")
            assertEquals(IncomingResult.OBSOLETE, applier.apply(concurrent, vaultKey))
            assertArrayEquals(red, requireNotNull(phone.syncedSiteIcons().icon("github.com")).png)
            assertTrue(phone.syncDao().conflicts().isEmpty())
            assertEquals(RecordVersion(mapOf(pc.deviceId to 2L, laptop.deviceId to 1L)), RecordVersion.parse(requireNotNull(
                phone.syncDao().recordState(SiteIconSyncRules.ENTITY_TYPE, first.entityId)).recordVersionJson))
            assertEquals(IncomingResult.REPLAY, applier.apply(second, vaultKey))

            // An icon under another site's id, and a delete, are refused.
            val forged = change(pcIdentity, phone, 3, second.hash, "icon-4", "gitlab.com", blue, mapOf(pc.deviceId to 3L),
                "2026-11-21T11:00:00Z", entityId = first.entityId)
            assertTrue(runCatching { applier.apply(forged, vaultKey) }.exceptionOrNull() is IllegalArgumentException)
            val delete = change(pcIdentity, phone, 3, second.hash, "icon-5", "github.com", blue, mapOf(pc.deviceId to 3L),
                "2026-11-21T11:00:00Z", kind = ChangeKind.DELETE)
            assertTrue(runCatching { applier.apply(delete, vaultKey) }.exceptionOrNull() is IllegalArgumentException)
            assertArrayEquals(red, requireNotNull(phone.syncedSiteIcons().icon("github.com")).png)

            // Locking forgets the icons in memory; the stored ones stay in the vault.
            SyncedSiteIconCache.close("test")
            assertNull(SyncedSiteIconCache.lookup("https://github.com"))
            assertEquals(1, phone.syncedSiteIcons().count())
        } finally {
            SyncedSiteIconCache.close("test")
            phone.close(); pcIdentity.clear(); laptopIdentity.clear()
        }
    }

    @Test fun theStoreKeepsTheNewestIcons() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(base, VaultDatabase::class.java).build()
        try {
            val icons = db.syncedSiteIcons()
            listOf("a.com" to 3L, "b.com" to 1L, "c.com" to 2L).forEach { (site, at) ->
                icons.save(SyncedSiteIcon(site, byteArrayOf(1), "t", at, "pc"))
            }
            icons.trim(keep = 2)
            assertEquals(setOf("a.com", "c.com"), icons.all().map { it.site }.toSet())
        } finally { db.close() }
    }
}
