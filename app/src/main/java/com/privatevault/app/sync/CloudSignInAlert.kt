package com.privatevault.app.sync

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.privatevault.app.MainActivity

/**
 * M14: a Google Drive sign-in that stopped working is easy to miss while Nuvori is closed. After
 * [FAILURES_BEFORE_NOTICE] passes in a row needed a sign-in, one notification says so, at most once a
 * day; a pass that works again clears it. The headline in Nuvori shows it at once ([SyncOverviewRules]).
 */
internal object CloudSignInAlert {
    private const val PREFERENCES = "cloud_sync"
    private const val CHANNEL = "sync-attention"
    private const val NOTIFICATION_ID = 412
    const val FAILURES_BEFORE_NOTICE = 3
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** True when the failure count and the last notice time call for a notification now. */
    fun shouldNotify(failures: Int, lastNoticeAt: Long, now: Long): Boolean =
        failures >= FAILURES_BEFORE_NOTICE && (lastNoticeAt == 0L || now - lastNoticeAt !in 0 until DAY_MS)

    fun failed(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val failures = preferences.getInt("auth_failures", 0) + 1
        val now = System.currentTimeMillis()
        val notify = shouldNotify(failures, preferences.getLong("auth_notice_at", 0L), now)
        preferences.edit().putInt("auth_failures", failures).apply { if (notify) putLong("auth_notice_at", now) }.apply()
        if (notify) runCatching { post(context) }
    }

    fun passed(context: Context) {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        if (preferences.getInt("auth_failures", 0) == 0) return
        preferences.edit().putInt("auth_failures", 0).putLong("auth_notice_at", 0L).apply()
        runCatching { context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID) }
    }

    private fun post(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Sync needs you", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Tells you when sync can't go on until you do something, such as signing in again."
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        val open = PendingIntent.getActivity(context, 2, Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_SYNC_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(NOTIFICATION_ID, Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.privatevault.app.R.drawable.ic_auto_sync)
            .setContentTitle("Google Drive sync needs you to sign in")
            .setContentText("Open Nuvori and sign in again so your devices keep in step.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setLocalOnly(true)
            .build())
    }
}
