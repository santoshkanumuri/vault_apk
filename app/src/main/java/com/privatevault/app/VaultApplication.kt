package com.privatevault.app

import android.app.Application

class VaultApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Discard an unfinished capture after process death, before opening the vault.
        java.io.File(cacheDir, "camera").listFiles()?.forEach { it.delete() }
    }
}
