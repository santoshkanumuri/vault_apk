package com.privatevault.app.security

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import com.privatevault.app.data.VaultEntry

internal fun linkedAppPackages(value: String): Set<String> = value.lineSequence().filter { it.isNotBlank() }.toSet()

internal fun matchingCodeEntries(entries: List<VaultEntry>, app: String?): List<VaultEntry> =
    if (app == null) emptyList() else entries.filter { app in linkedAppPackages(it.linkedApps) }

internal fun usageAccessGranted(context: Context): Boolean = context.getSystemService(AppOpsManager::class.java)
    .unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED

/** A suggestion only. Recent app events cannot identify a browser tab or verify a login. */
internal fun recentCodeApp(context: Context): String? {
    if (!context.getSharedPreferences("vault_preferences", Context.MODE_PRIVATE).getBoolean("code_app_detection", false) || !usageAccessGranted(context)) return null
    return runCatching {
        val now = System.currentTimeMillis()
        val events = context.getSystemService(UsageStatsManager::class.java).queryEvents(now - 300_000, now) ?: return null
        val event = UsageEvents.Event()
        var previous: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED &&
                event.packageName != context.packageName && event.packageName != "com.android.systemui") previous = event.packageName
        }
        previous
    }.getOrNull()
}
