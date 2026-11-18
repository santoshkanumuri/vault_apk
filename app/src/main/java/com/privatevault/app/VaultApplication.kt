package com.privatevault.app

import android.app.Application

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Discard an unfinished capture after process death, before opening the vault.
        java.io.File(cacheDir, "camera").listFiles()?.forEach { it.delete() }
        // Paused sync no longer posts a notification. Remove one that an older version left behind.
        com.privatevault.app.sync.LanSyncService.clearLegacyNotifications(this)
    }
}
