package com.privatevault.app.appearance

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The accent and background a vault uses. Ids only; [AppearancePalette] turns them into colors. */
internal data class Appearance(val accent: String = AppearancePalette.DEFAULT_ACCENT, val surface: String = AppearancePalette.DEFAULT_SURFACE) {
    init { require(isValidAppearanceId(accent) && isValidAppearanceId(surface)) { "Invalid appearance id" } }
}

/**
 * [updatedAtUtc] and [updatedByDevice] identify the change that set this value (ISO-8601 instant and sync device id;
 * null before the first change), so concurrent changes on two devices resolve the same way everywhere.
 * [pendingSync] is true while a local change has not been written to the sync group yet.
 */
internal data class StoredAppearance(val appearance: Appearance, val updatedAtUtc: String?, val updatedByDevice: String?,
    val pendingSync: Boolean)

/**
 * A lower-case id of up to 32 characters. A synced id this build does not know (a newer palette on another device) is
 * still valid: it is stored as received and drawn with the default colors, so it never blocks sync.
 */
internal fun isValidAppearanceId(id: String): Boolean = id.matches(Regex("[a-z][a-z0-9-]{0,31}"))

/**
 * Device-only copy of the vault appearance, in the plain `vault_preferences` file. Colors are not secret, and the
 * unlock screen must use them before the vault is open. The synced record (entity type "appearance") is the shared copy.
 */
internal object AppearanceStore {
    private const val FILE = "vault_preferences"
    private const val ACCENT = "appearance_accent"
    private const val SURFACE = "appearance_surface"
    private const val UPDATED_AT = "appearance_updated_at"
    private const val UPDATED_BY = "appearance_updated_by"
    private const val PENDING = "appearance_pending_sync"

    private var state: MutableStateFlow<StoredAppearance>? = null

    @Synchronized fun flow(context: Context): StateFlow<StoredAppearance> =
        (state ?: MutableStateFlow(load(context)).also { state = it }).asStateFlow()

    fun read(context: Context): StoredAppearance = flow(context).value

    /** A change made on this device. It waits in [StoredAppearance.pendingSync] until the sync layer writes it. */
    fun setLocal(context: Context, appearance: Appearance, updatedAtUtc: String, deviceId: String?) =
        save(context, StoredAppearance(appearance, updatedAtUtc, deviceId, pendingSync = true))

    /** A change received from another device that won (see the sync rules). Never marked pending: no echo. */
    fun applyRemote(context: Context, appearance: Appearance, updatedAtUtc: String, deviceId: String) =
        save(context, StoredAppearance(appearance, updatedAtUtc, deviceId, pendingSync = false))

    /** The pending local change was written to the sync group. */
    fun markSynced(context: Context) {
        val current = read(context)
        if (current.pendingSync) save(context, current.copy(pendingSync = false))
    }

    /**
     * The pending change [appearance], chosen at [expectedUpdatedAt], was written to the sync group as a
     * change at [updatedAtUtc] by [deviceId]. A value chosen or received in the meantime is kept as it is.
     */
    @Synchronized fun markWritten(context: Context, appearance: Appearance, expectedUpdatedAt: String?, updatedAtUtc: String,
        deviceId: String) {
        val current = read(context)
        if (!current.pendingSync || current.appearance != appearance || current.updatedAtUtc != expectedUpdatedAt) return
        save(context, StoredAppearance(appearance, updatedAtUtc, deviceId, pendingSync = false))
    }

    @Synchronized private fun save(context: Context, value: StoredAppearance) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(ACCENT, value.appearance.accent).putString(SURFACE, value.appearance.surface)
            .putString(UPDATED_AT, value.updatedAtUtc).putString(UPDATED_BY, value.updatedByDevice)
            .putBoolean(PENDING, value.pendingSync).commit()
        check(saved) { "Could not save the appearance" }
        (state ?: MutableStateFlow(value).also { state = it }).value = value
    }

    private fun load(context: Context): StoredAppearance {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val accent = prefs.getString(ACCENT, null)?.takeIf(::isValidAppearanceId) ?: AppearancePalette.DEFAULT_ACCENT
        val surface = prefs.getString(SURFACE, null)?.takeIf(::isValidAppearanceId) ?: AppearancePalette.DEFAULT_SURFACE
        return StoredAppearance(Appearance(accent, surface), prefs.getString(UPDATED_AT, null), prefs.getString(UPDATED_BY, null),
            prefs.getBoolean(PENDING, false))
    }
}
