package com.privatevault.app.autofill

import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.security.VaultKeyManager
import org.junit.Assert.*
import org.junit.Test

class ProfileAutofillFlowTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    private fun shell(command: String) = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }

    private fun find(text: String): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (node.text?.toString()?.equals(text, true) == true || node.contentDescription?.toString() == text) return node
            for (index in 0 until node.childCount) node.getChild(index)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit) ?: automation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
    }

    private fun waitFor(text: String): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            find(text)?.let { return it }
            SystemClock.sleep(100)
        }
        shell("screencap -p /sdcard/profile-autofill.png")
        error("Missing $text")
    }

    private fun click(text: String) {
        var node: AccessibilityNodeInfo? = waitFor(text)
        while (node != null && !node.isClickable) node = node.parent
        check(node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) { "Could not select $text" }
    }

    @Test fun phoneAutofillFillsRecognizedContactAndAddressFieldsWithoutVaultUnlock() {
        check(context.packageName.endsWith(".debug"))
        val keyManager = VaultKeyManager(context)
        check(!keyManager.isInitialized && !context.getDatabasePath("vault.db").exists())
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val previousProvider = shell("settings get secure autofill_service")
        val preferences = context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE)
        val previousEnabled = preferences.getBoolean("autofill_unlocked_profiles", false)
        val store = UnlockedProfileStore(context)
        val key = keyManager.create("Autofill profile test password".toCharArray())
        try {
            preferences.edit().putBoolean("autofill_unlocked_profiles", true).commit()
            store.publish("profile-test-vault", listOf(com.privatevault.app.data.VaultEntry(
                type = com.privatevault.app.data.EntryType.AUTOFILL, title = "Home details",
                primaryValue = AutofillProfile(email = "home@example.invalid", phone = "5551230000",
                    address1 = "1 Test Lane", city = "Test City", postalCode = "12345").encode())))
            shell("settings put secure autofill_service ${context.packageName}/com.privatevault.app.autofill.VaultAutofillService")
            val testPackage = instrumentation.context.packageName
            val requestLabel = "Request ${SystemClock.elapsedRealtime()}"
            context.startActivity(Intent().setComponent(ComponentName(testPackage, NativeLoginTestActivity::class.java.name))
                .putExtra("profile", true).putExtra("requestLabel", requestLabel)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            click(requestLabel)
            val email = waitFor("Profile email")
            val bounds = android.graphics.Rect().also(email::getBoundsInScreen)
            shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
            click("Home details")
            assertEquals("home@example.invalid", waitFor("Profile email").text?.toString())
            assertEquals("5551230000", waitFor("Profile phone").text?.toString())
            assertEquals("Test City", waitFor("Profile city").text?.toString())
            assertEquals("12345", waitFor("Profile postcode").text?.toString())
        } finally {
            key.fill(0)
            store.clear()
            preferences.edit().putBoolean("autofill_unlocked_profiles", previousEnabled).commit()
            if (previousProvider.isBlank() || previousProvider == "null") shell("settings delete secure autofill_service")
            else shell("settings put secure autofill_service $previousProvider")
        }
    }
}
