package com.privatevault.app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

/**
 * A website icon another device captured and shared (sync protocol `site-icons-v1`). Nuvori for Windows
 * captures it through the browser extension; this phone only receives it. One row per registrable
 * domain, inside the encrypted vault. [updatedAtUtc] and [updatedByDevice] decide which of two
 * concurrent changes wins (last writer wins).
 */
@Entity(tableName = "synced_site_icons")
data class SyncedSiteIcon(
    @PrimaryKey val site: String,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val png: ByteArray,
    val updatedAtUtc: String,
    val updatedAtMillis: Long,
    val updatedByDevice: String,
) {
    override fun equals(other: Any?): Boolean = other is SyncedSiteIcon && site == other.site &&
        png.contentEquals(other.png) && updatedAtUtc == other.updatedAtUtc && updatedAtMillis == other.updatedAtMillis &&
        updatedByDevice == other.updatedByDevice

    override fun hashCode(): Int = site.hashCode() * 31 + png.contentHashCode()

    companion object {
        /** The same limit as the Windows store: the oldest icons go first. */
        const val MAX_ICONS = 2000
    }
}

@Dao
interface SyncedSiteIconDao {
    @Query("SELECT * FROM synced_site_icons WHERE site = :site") suspend fun icon(site: String): SyncedSiteIcon?
    @Query("SELECT * FROM synced_site_icons") suspend fun all(): List<SyncedSiteIcon>
    @Query("SELECT COUNT(*) FROM synced_site_icons") suspend fun count(): Int
    @Upsert suspend fun save(icon: SyncedSiteIcon)
    @Query("DELETE FROM synced_site_icons WHERE site NOT IN (SELECT site FROM synced_site_icons ORDER BY updatedAtMillis DESC, site LIMIT :keep)")
    suspend fun trim(keep: Int = SyncedSiteIcon.MAX_ICONS)
    @Query("DELETE FROM synced_site_icons") suspend fun clear()
    @Query("DELETE FROM synced_site_icons WHERE site IN (:sites)") suspend fun delete(sites: List<String>)
}

/**
 * Drops the icons whose site no longer has a login or a passkey, when the vault opens (as Nuvori for Windows
 * does in `site_icons.rs` `purge`). A site counts as used when a login website or passkey rpId would look the
 * icon up ([com.privatevault.app.SiteIconLogic.lookupKeys]). Local only: an icon that syncs again comes back.
 */
internal object SiteIconPurge {
    fun orphans(iconSites: Collection<String>, usedSites: Collection<String>): List<String> {
        val used = usedSites.flatMapTo(HashSet()) { com.privatevault.app.SiteIconLogic.lookupKeys(it) }
        return iconSites.filter { it !in used }.sorted()
    }

    /** Deletes the orphan icons and returns the icons that stay. */
    suspend fun purge(database: VaultDatabase): List<SyncedSiteIcon> {
        val icons = database.syncedSiteIcons()
        val stored = icons.all()
        if (stored.isEmpty()) return stored
        val used = database.dao().loginAndCodeEntries()
            .filter { it.type == EntryType.PASSWORD }.map { it.tertiaryValue } +
            database.dao().passkeySummaries().map { it.rpId }
        val orphans = orphans(stored.map { it.site }, used)
        // SQLite allows 999 bound values in one statement.
        orphans.chunked(500).forEach { icons.delete(it) }
        val gone = orphans.toHashSet()
        return stored.filter { it.site !in gone }
    }
}
