package com.privatevault.app.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class SyncRunTest {
    @Test fun oneTimeChecksWhilePausedNeverRunInTheForeground() {
        assertEquals(SyncRun.ONE_PASS, syncRun(LanSyncService.SYNC_ON_OPEN, paused = true, notificationsOn = true))
        assertEquals(SyncRun.ONE_PASS, syncRun(LanSyncService.SYNC_NOW, paused = true, notificationsOn = true))
    }

    @Test fun oneTimeChecksWithNotificationsOffRunOnce() {
        assertEquals(SyncRun.ONE_PASS, syncRun(LanSyncService.SYNC_ON_OPEN, paused = false, notificationsOn = false))
        assertEquals(SyncRun.ONE_PASS, syncRun(LanSyncService.SYNC_NOW, paused = false, notificationsOn = false))
    }

    @Test fun autoSyncOnRunsInTheForeground() {
        assertEquals(SyncRun.AUTO, syncRun(null, paused = false, notificationsOn = true))
        assertEquals(SyncRun.AUTO, syncRun(LanSyncService.SYNC_NOW, paused = false, notificationsOn = true))
        assertEquals(SyncRun.AUTO, syncRun(LanSyncService.SYNC_ON_OPEN, paused = false, notificationsOn = true))
    }

    @Test fun pausedWithoutAOneTimeCheckStops() {
        assertEquals(SyncRun.STOP, syncRun(null, paused = true, notificationsOn = true))
        assertEquals(SyncRun.STOP, syncRun("com.privatevault.app.RESUME_SYNC", paused = true, notificationsOn = true))
    }
}
