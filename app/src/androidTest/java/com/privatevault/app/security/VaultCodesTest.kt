package com.privatevault.app.security

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.test.core.app.ActivityScenario
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.VaultCodesTileService
import org.junit.Assert.*
import org.junit.Test

class VaultCodesTest {
    @Test fun pickerIsPrivateAndTileIsSystemBound() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val activity = context.packageManager.getActivityInfo(ComponentName(context, VaultCodesActivity::class.java), 0)
        assertFalse(activity.exported)
        val service = context.packageManager.getServiceInfo(ComponentName(context, VaultCodesTileService::class.java), 0)
        assertTrue(service.exported)
        assertEquals("android.permission.BIND_QUICK_SETTINGS_TILE", service.permission)
        val permissions = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty()
        assertFalse(permissions.contains("android.permission.SYSTEM_ALERT_WINDOW"))
        assertFalse(permissions.contains("android.permission.INTERNET"))
    }

    @Test fun pickerBlocksScreenshotsAndClosesWhenBackgrounded() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        ActivityScenario.launch<VaultCodesActivity>(Intent(context, VaultCodesActivity::class.java)).use { scenario ->
            scenario.onActivity { activity ->
                assertTrue(activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                activity.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
            while (scenario.state != Lifecycle.State.DESTROYED && android.os.SystemClock.elapsedRealtime() < deadline) {
                android.os.SystemClock.sleep(50)
            }
            assertEquals(Lifecycle.State.DESTROYED, scenario.state)
        }
    }
}
