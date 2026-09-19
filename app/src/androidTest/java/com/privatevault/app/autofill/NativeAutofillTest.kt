package com.privatevault.app.autofill

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.data.*
import com.privatevault.app.security.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class NativeAutofillTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation

    private fun shell(command: String): String = automation.executeShellCommand(command).use {
        android.os.ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun find(predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun visit(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (predicate(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { visit(it)?.let { found -> return found } }
            return null
        }
        return automation.rootInActiveWindow?.let(::visit) ?: automation.windows.firstNotNullOfOrNull { it.root?.let(::visit) }
    }
    private fun waitNode(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(predicate)?.let { return it }
            SystemClock.sleep(100)
        }
        shell("screencap -p /sdcard/autofill-test.png")
        error("Missing UI element: $label")
    }
    private fun click(text: String) {
        val deadline = SystemClock.elapsedRealtime() + 10_000
        while (SystemClock.elapsedRealtime() < deadline) {
            var node = find { it.text?.toString()?.equals(text, ignoreCase = true) == true ||
                (text == "Private Vault" && it.contentDescription?.toString()?.startsWith("Unlock Private Vault") == true) }
            while (node != null && !node.isClickable && node.parent != null) node = node.parent
            if (node?.isEnabled == true && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return
            SystemClock.sleep(100)
        }
        if (text == "Private Vault") shell("screencap -p /sdcard/autofill-test.png")
        error("Could not click: $text")
    }

    @Test fun frameworkAuthenticatesAndFillsOnlyAuthorizedLoginAndLinkedCode() = runBlocking {
        // Never modify a pre-existing vault, even if this test is run outside a clean install.
        check(context.packageName.endsWith(".debug"))
        val keyManager = VaultKeyManager(context)
        check(!keyManager.isInitialized && !context.getDatabasePath("vault.db").exists()) { "Test needs an empty debug installation" }
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        val oldProvider = shell("settings get secure autofill_service")
        val testPackage = instrumentation.context.packageName
        // Optional live-browser check: use an installed official release with third-party autofill enabled.
        val browser = InstrumentationRegistry.getArguments().getString("browserPackage")
        val usernameFirst = InstrumentationRegistry.getArguments().getString("usernameFirst") == "true"
        val generatedFlow = InstrumentationRegistry.getArguments().getString("generatedFlow")
        val newAccount = InstrumentationRegistry.getArguments().getString("newAccount") == "true"
        val testUsername = if (generatedFlow != null) "dummy@example.com" else "test-account"
        if (browser != null) check(trustedBrowser(browser, requireNotNull(appSigningIdentity(context, browser))))
        val password = "Autofill test master password".toCharArray()
        val key = keyManager.create(password)
        try {
            val database = VaultDatabase.open(context, key)
            try {
                val code = VaultEntry(type = EntryType.AUTHENTICATOR, title = "Test authenticator", secondaryValue = "JBSWY3DPEHPK3PXP")
                val identity = requireNotNull(appSigningIdentity(context, testPackage))
                database.dao().saveEntry(code, emptySet())
                database.dao().saveEntry(VaultEntry(type = EntryType.PASSWORD, title = "Authorized test login", primaryValue = testUsername, secondaryValue = "test-password-123", tertiaryValue = "https://fill.dev/login", linkedAuthenticatorId = code.id, autofillSignatures = "$testPackage=$identity"), emptySet())
                database.dao().saveEntry(VaultEntry(type = EntryType.PASSWORD, title = "Unauthorized login", secondaryValue = "never-fill"), emptySet())
            } finally { database.close() }
            shell("settings put secure autofill_service ${context.packageName}/com.privatevault.app.autofill.VaultAutofillService")
            for (otp in if (browser == null) listOf(false, true) else listOf(false)) {
                if (browser == null) {
                    context.startActivity(Intent().setComponent(ComponentName(testPackage, NativeLoginTestActivity::class.java.name))
                        .putExtra("otp", otp).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                    waitNode("Test form") { it.contentDescription?.toString() == (if (otp) "Test code" else "Test password") }
                    click("Request fill")
                } else {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(if (generatedFlow != null) "https://fill.dev/$generatedFlow" else if (usernameFirst) "https://fill.dev/login?flow=multistep" else "https://fill.dev/login"))
                        .setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    val field = waitNode("Browser login field") { it.viewIdResourceName == (if (generatedFlow == "change-password") "new-password" else if (usernameFirst) "username" else "password") && it.isVisibleToUser }
                    val bounds = android.graphics.Rect().also(field::getBoundsInScreen)
                    val time = SystemClock.uptimeMillis()
                    for (action in listOf(android.view.MotionEvent.ACTION_DOWN, android.view.MotionEvent.ACTION_UP)) {
                        val event = android.view.MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
                        try { check(automation.injectInputEvent(event, true)) } finally { event.recycle() }
                    }
                }
                click("Private Vault")
                waitNode("Autofill") { it.text?.toString() == "Autofill" }
                if (!otp) {
                    val wrongPasswordField = waitNode("Master password") { it.isEditable && it.packageName?.toString() == context.packageName }
                    check(wrongPasswordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "Wrong dummy master password")
                    }))
                    click("Unlock")
                    waitNode("Wrong password rejected") { it.text?.toString()?.startsWith("Could not unlock.") == true }
                    assertNull(find { it.text?.toString() == "Authorized test login" })
                }
                val passwordField = waitNode("Master password") { it.isEditable && it.packageName?.toString() == context.packageName }
                check(passwordField.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password.concatToString())
                }))
                click("Unlock")
                waitNode("Authorized test login") { it.text?.toString() == "Authorized test login" }
                assertNull(find { it.text?.toString() == "Unauthorized login" })
                if (generatedFlow != null) {
                    if (newAccount) {
                        click("Generate for a new account")
                        val username = waitNode("New account username") { it.isEditable && it.packageName?.toString() == context.packageName }
                        check(username.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "new-dummy@example.com")
                        }))
                    } else click("Generate new password for this account")
                    click("Save new login and fill")
                    val newId = if (generatedFlow == "change-password") "new-password" else "password"
                    val confirmId = if (generatedFlow == "change-password") "confirm-password" else "password-confirm"
                    waitNode("Generated password") { it.viewIdResourceName == newId && it.text?.length == 24 }
                    waitNode("Generated confirmation") { it.viewIdResourceName == confirmId && it.text?.length == 24 }
                    if (generatedFlow == "change-password") waitNode("Current password preserved") { it.viewIdResourceName == "current-password" && it.text?.length == 17 }
                    val submit = waitNode("Submit generated form") { it.className?.toString() == "android.widget.Button" && it.text?.toString() == (if (generatedFlow == "change-password") "Change password" else "Register") }
                    submit.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                    SystemClock.sleep(500)
                    submit.refresh()
                    val bounds = android.graphics.Rect().also(submit::getBoundsInScreen)
                    shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
                    waitNode("Generated form submitted") { it.text?.toString() == "Form submit results" }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var saved = false
                    while (!saved && SystemClock.elapsedRealtime() < deadline) {
                        val db = VaultDatabase.open(context, key)
                        try {
                            val logins = db.dao().loginAndCodeEntries()
                            assertEquals("test-password-123", logins.single { it.title == "Authorized test login" }.secondaryValue)
                            val generated = logins.singleOrNull { it.title == (if (newAccount) "fill.dev (generated)" else "Authorized test login (generated)") }
                            saved = generated?.secondaryValue?.length == 24 &&
                                (if (newAccount) generated.primaryValue == "new-dummy@example.com" && generated.linkedAuthenticatorId.isBlank() else generated.linkedAuthenticatorId.isNotBlank())
                            if (saved) waitNode("Saved password matches submitted form") { it.text?.toString() == generated?.secondaryValue }
                        }
                        finally { db.close() }
                        if (!saved) SystemClock.sleep(100)
                    }
                    assertTrue("Generated password must be saved, not the current password", saved)
                    continue
                }
                click(if (otp) "Fill code" else "Fill login")
                val filled = waitNode("Filled field") {
                    (if (browser != null) it.viewIdResourceName == "username" else it.contentDescription?.toString() == (if (otp) "Test code" else "Test username")) && !it.text.isNullOrBlank() &&
                        it.text?.toString() != (if (otp) "Test code" else "Test username")
                }
                if (otp) {
                    val now = System.currentTimeMillis() / 1000
                    assertTrue(listOf(now - 1, now, now + 1).map { Totp.code("JBSWY3DPEHPK3PXP", "SHA1", 6, 30, it) }.contains(filled.text.toString()))
                } else {
                    assertEquals("test-account", filled.text.toString())
                    if (browser == null) waitNode("Password verified") { it.contentDescription?.toString() == "Verified test password" }
                    else if (!usernameFirst) waitNode("Browser password filled") { it.viewIdResourceName == "password" && it.text?.length == "test-password-123".length }
                }
                if (browser != null && usernameFirst) {
                    val next = waitNode("Next login step") { it.text?.toString() == "Next" && it.className?.toString() == "android.widget.Button" }
                    val nextBounds = android.graphics.Rect().also(next::getBoundsInScreen)
                    shell("input tap ${nextBounds.centerX()} ${nextBounds.centerY()}")
                    val nextPassword = waitNode("Second page password") { it.viewIdResourceName == "password" && it.isVisibleToUser }
                    assertTrue("Password must not follow automatically", nextPassword.text.isNullOrEmpty())
                    val nextFieldBounds = android.graphics.Rect().also(nextPassword::getBoundsInScreen)
                    shell("input tap ${nextFieldBounds.centerX()} ${nextFieldBounds.centerY()}")
                    click("Private Vault")
                    val unlock = waitNode("Second page authentication") { it.isEditable && it.packageName?.toString() == context.packageName }
                    check(unlock.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password.concatToString())
                    }))
                    click("Unlock")
                    click("Fill login")
                    waitNode("Second page filled") { it.viewIdResourceName == "password" && it.text?.length == "test-password-123".length }
                }
                if (browser != null && InstrumentationRegistry.getArguments().getString("testBrowserSave") == "true") {
                    val field = waitNode("Browser password") { it.viewIdResourceName == "password" }
                    check(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
                    }))
                    check(field.performAction(AccessibilityNodeInfo.ACTION_FOCUS))
                    shell("input text browser-updated-password")
                    waitNode("Edited browser password") { it.viewIdResourceName == "password" && it.text?.length == "browser-updated-password".length }
                    SystemClock.sleep(500)
                    val submit = waitNode("Submit dummy login") { it.text?.toString() == "Login" && it.className?.toString() == "android.widget.Button" }
                    val submitBounds = android.graphics.Rect().also(submit::getBoundsInScreen)
                    shell("input tap ${submitBounds.centerX()} ${submitBounds.centerY()}")
                    val saveAction = waitNode("Android save/update prompt") {
                        it.packageName?.toString() == "android" && it.text?.toString()?.lowercase() in setOf("save", "update")
                    }
                    click(saveAction.text.toString())
                    waitNode("Save review") { it.text?.toString() == "Save login" }
                    val unlock = waitNode("Save master password") { it.isEditable && it.packageName?.toString() == context.packageName }
                    check(unlock.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, password.concatToString())
                    }))
                    click("Unlock")
                    val saveAsNew = InstrumentationRegistry.getArguments().getString("saveAsNew") == "true"
                    if (saveAsNew) click("Save as new login") else {
                        click("Update Authorized test login")
                        click("Replace password")
                    }
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    var updated = false
                    while (!updated && SystemClock.elapsedRealtime() < deadline) {
                        val db = VaultDatabase.open(context, key)
                        try {
                            val saved = db.dao().loginAndCodeEntries()
                            updated = if (saveAsNew) saved.any { it.title == "fill.dev" && it.primaryValue == "test-account" && it.secondaryValue == "browser-updated-password" } &&
                                saved.any { it.title == "Authorized test login" && it.secondaryValue == "test-password-123" }
                            else saved.any { it.title == "Authorized test login" && it.secondaryValue == "browser-updated-password" && it.linkedAuthenticatorId.isNotEmpty() }
                        }
                        finally { db.close() }
                        if (!updated) SystemClock.sleep(100)
                    }
                    assertTrue("Confirmed browser save must be persisted", updated)
                }
            }
        } finally {
            shell("am force-stop $testPackage")
            if (browser != null) shell("am force-stop $browser")
            if (oldProvider == "null" || oldProvider.isBlank()) shell("settings delete secure autofill_service")
            else shell("settings put secure autofill_service $oldProvider")
            context.deleteDatabase("vault.db")
            context.getSharedPreferences("vault_bootstrap", 0).edit().clear().commit()
            BiometricGate(context).clearDailySession()
            key.fill(0); password.fill('\u0000')
        }
    }
}
