package com.privatevault.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * M9: after a reboot or an app update, automatic sync comes back on its own when it was left on, and
 * the background Google Drive check is scheduled again. Nothing starts for a device with no group.
 */
class SyncRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val app = context.applicationContext
        val pending = goAsync()
        // Reading the sync store decrypts a small file; keep it off the main thread.
        Thread {
            try {
                runCatching { CloudSyncJob.schedulePeriodic(app) }
                LanSyncService.restartIfOn(app)
            } finally { pending.finish() }
        }.start()
    }
}
