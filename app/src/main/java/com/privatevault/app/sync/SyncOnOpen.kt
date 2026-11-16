package com.privatevault.app.sync

import android.content.Context
import androidx.core.content.edit

/**
 * "Sync when Nuvori opens": each time the app comes to the front, one pass checks Google Drive
 * and the paired devices on this Wi-Fi, also while automatic sync is paused and while the vault
 * is locked (received changes wait encrypted and apply after unlock). A setting of this device,
 * on by default. Passes are at least [MIN_GAP_MS] apart, so switching between apps, a biometric
 * prompt, or a Google sign-in screen does not start a new pass each time.
 */
internal object SyncOnOpen {
    const val MIN_GAP_MS = 60_000L
    private const val PREFERENCES = "sync-service"
    private const val KEY = "sync_on_open"

    @Volatile private var lastPassAt: Long? = null

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getBoolean(KEY, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit { putBoolean(KEY, enabled) }
    }

    /** True when a pass may start at [now] (elapsed-realtime ms). A clock that went back counts as due. */
    fun due(last: Long?, now: Long): Boolean = last == null || now < last || now - last >= MIN_GAP_MS

    /** Claims the next pass at [now]; false when the last one was too recent. */
    @Synchronized fun claim(now: Long): Boolean {
        if (!due(lastPassAt, now)) return false
        lastPassAt = now
        return true
    }
}
