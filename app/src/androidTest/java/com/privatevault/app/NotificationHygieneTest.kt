package com.privatevault.app

import android.app.NotificationManager
import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.sync.LanSyncService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Nuvori posts a notification only while it is needed, and removes it when the job is done. */
class NotificationHygieneTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val card = VaultEntry(id = "card", type = EntryType.CARD, title = "Visa", primaryValue = "4111 1111 1111 1111",
        secondaryValue = "A Person", tertiaryValue = "8/2029", fourthValue = "123")

    @Before fun allowNotifications() {
        instrumentation.uiAutomation.executeShellCommand(
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS").close()
        Thread.sleep(500)
    }

    @After fun cleanUp() {
        instrumentation.runOnMainSync { QuickCopySession.end(context) }
        context.stopService(Intent(context, LanSyncService::class.java))
    }

    private fun active(id: Int) = manager.activeNotifications.firstOrNull { it.id == id }

    @Test fun quickCopyLastsTwoMinutesAndClosesAfterTheLastField() {
        val values = quickCopyValues(card, emptyList())
        var shown = false
        instrumentation.runOnMainSync { shown = QuickCopySession.start(context, card.title, values, QuickField.CARD_NUMBER) }
        assertTrue(shown)
        val posted = requireNotNull(active(7301)).notification
        assertEquals(setOf("Number", "Expiry", "CVV"), posted.actions.map { it.title.toString() }.toSet())
        assertTrue(posted.timeoutAfter in 110_000L..120_000L)
        assertTrue(posted.extras.getBoolean("android.chronometerCountDown"))

        val session = requireNotNull(QuickCopySession.currentTokenForTest())
        instrumentation.runOnMainSync { QuickCopySession.copy(context, session, QuickField.EXPIRY) }
        Thread.sleep(300)
        assertNotNull("one field is still left", active(7301))
        instrumentation.runOnMainSync { QuickCopySession.copy(context, session, QuickField.CVV) }
        Thread.sleep(300)
        assertNull("every field is copied, so the notification is gone", active(7301))
    }

    @Test fun aOneTimeSyncPassPostsNoNotification() {
        val preferences = context.getSharedPreferences("sync-service", android.content.Context.MODE_PRIVATE)
        val wasPaused = preferences.getBoolean("paused", false)
        preferences.edit().putBoolean("paused", true).commit()
        try {
            context.startService(Intent(context, LanSyncService::class.java).setAction(LanSyncService.SYNC_ON_OPEN))
            Thread.sleep(2_000)
            assertNull("no foreground notification for a one-time pass", active(410))
            assertNull("no paused notification", active(411))
        } finally {
            preferences.edit().putBoolean("paused", wasPaused).commit()
        }
    }

    @Test fun aLeftoverPausedNotificationIsRemoved() {
        LanSyncService.createChannel(context)
        manager.notify(411, android.app.Notification.Builder(context, LanSyncService.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_auto_sync).setContentTitle("Device sync paused").build())
        Thread.sleep(300)
        assertNotNull(active(411))
        LanSyncService.clearLegacyNotifications(context)
        Thread.sleep(300)
        assertNull(active(411))
    }
}
