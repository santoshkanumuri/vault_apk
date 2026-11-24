package com.privatevault.app.sync

import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.privatevault.app.SiteIconLogic
import com.privatevault.app.data.SyncedSiteIcon
import com.privatevault.app.data.VaultDatabase
import java.util.Base64

/**
 * The synced website icons in memory, for drawing. It holds them only while the vault is open in the app
 * ([open] by the main screen or the picker) and forgets them when the last one locks ([close]).
 * Icons are decoded on first use; a PNG that Android can't decode falls back to the built-in icon or the letter.
 */
internal object SyncedSiteIconCache {
    private val owners = HashSet<String>()
    private var icons: Map<String, ByteArray> = emptyMap()
    private val decoded = HashMap<String, ImageBitmap?>()

    /** Changes when the icons change, so drawing code reads them again. */
    var generation by mutableIntStateOf(0)
        private set

    @Synchronized fun open(owner: String, rows: List<SyncedSiteIcon>) {
        owners += owner
        icons = rows.associate { it.site to it.png }
        decoded.clear()
        generation++
    }

    @Synchronized fun close(owner: String) {
        owners -= owner
        if (owners.isNotEmpty()) return
        icons = emptyMap()
        decoded.clear()
        generation++
    }

    /** A newly synced icon. Ignored while the vault is locked everywhere. */
    @Synchronized fun put(site: String, png: ByteArray) {
        if (owners.isEmpty()) return
        icons = icons + (site to png)
        decoded.remove(site)
        generation++
    }

    /** The icon of [site] (a login URL or a passkey rpId): the host first, then each parent domain. */
    @Synchronized fun lookup(site: String?): ImageBitmap? {
        for (key in SiteIconLogic.lookupKeys(site)) {
            val png = icons[key] ?: continue
            decoded.getOrPut(key) {
                runCatching { BitmapFactory.decodeByteArray(png, 0, png.size)?.asImageBitmap() }.getOrNull()
            }?.let { return it }
        }
        return null
    }
}

internal object SiteIconSync {
    /**
     * A device that joined from a snapshot holds the group's site icon changes without applying them. It stores
     * the value each one settles on ([SiteIconSyncRules.current]). Best effort: a later change corrects it.
     */
    suspend fun adoptFromSnapshot(database: VaultDatabase, state: SnapshotSyncState) {
        val keyring = state.keyring()
        val contentKey = Base64.getUrlDecoder().decode(state.contentKey)
        try {
            val icons = database.syncedSiteIcons()
            for (chosen in SiteIconSyncRules.current(state.operations)) {
                val icon = runCatching {
                    val plaintext = IncomingEntryChangeApplier.decryptBytes(chosen.toSyncChange(), keyring)
                    try { SiteIconSyncRules.parsePayload(plaintext.toString(Charsets.UTF_8), chosen.entityId, contentKey) }
                    finally { plaintext.fill(0) }
                }.getOrNull() ?: continue
                val stored = icons.icon(icon.site)
                if (stored == null || AppearanceSyncRules.incomingWins(stored.updatedAtUtc, stored.updatedByDevice,
                        chosen.occurredAtUtc, chosen.deviceId)) icons.save(SyncedSiteIcon(icon.site, icon.png,
                    chosen.occurredAtUtc, SiteIconSyncRules.millis(chosen.occurredAtUtc), chosen.deviceId))
            }
            icons.trim()
        } finally { contentKey.fill(0); keyring.wipe() }
    }
}
