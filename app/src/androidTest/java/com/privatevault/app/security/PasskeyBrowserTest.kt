package com.privatevault.app.security

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import com.privatevault.app.backup.VaultBackupManager
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Opt-in live HTTPS test. Only synthetic accounts; never runs against a pre-existing debug vault. */
class PasskeyBrowserTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation = instrumentation.uiAutomation
    private fun shell(command: String) = automation.executeShellCommand(command).use {
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
    private fun waitFor(label: String, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo {
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) { find(predicate)?.let { return it }; SystemClock.sleep(100) }
        shell("screencap -p /sdcard/passkey-test.png")
        error("Missing UI: $label")
    }
    private fun tap(node: AccessibilityNodeInfo) {
        if (node.packageName?.toString() == context.packageName) {
            var target = node
            while (!target.isClickable && target.parent != null) target = target.parent
            val end = SystemClock.elapsedRealtime() + 5_000
            while (!target.isEnabled && SystemClock.elapsedRealtime() < end) { SystemClock.sleep(100); target.refresh() }
            check(target.isEnabled && target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            return
        }
        val bounds = android.graphics.Rect().also(node::getBoundsInScreen)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }
    private fun text(value: String) = waitFor(value) { it.text?.toString() == value }
    private fun set(node: AccessibilityNodeInfo, value: String) {
        check(node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }))
    }
    private fun unlock() {
        // Android may first ask for confirmation of the chosen provider.
        val end = SystemClock.elapsedRealtime() + 25_000
        while (SystemClock.elapsedRealtime() < end) {
            val input = find { it.isEditable && it.packageName?.toString() == context.packageName }
            if (input != null) { set(input, "Passkey test master password"); tap(text("Use master password")); return }
            find { it.packageName?.toString() !in setOf(context.packageName, "com.brave.browser") &&
                it.text?.toString() in setOf("Continue", "Create", "Save", "Use passkey", "Sign in") }?.let(::tap)
            find { it.packageName?.toString() != context.packageName && it.text?.toString() in setOf("Private Vault", "Unlock Private Vault") }?.let(::tap)
            SystemClock.sleep(300)
        }
        waitFor("Passkey authentication") { it.isEditable && it.packageName?.toString() == context.packageName }
    }
    @Test fun browserRegistersAndSignsInAfterEncryptedRestore(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("livePasskeys") == "true")
        check(context.packageName.endsWith(".debug") && !VaultKeyManager(context).isInitialized)
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val old = shell("settings get secure credential_service")
        val oldPrimary = shell("settings get secure credential_service_primary")
        val oldAutofill = shell("settings get secure autofill_service")
        val master = "Passkey test master password".toCharArray()
        var key = VaultKeyManager(context).create(master)
        val username = "vault-test-${System.currentTimeMillis()}@example.com"
        val provider = "${context.packageName}/com.privatevault.app.passkeys.VaultCredentialService"
        try {
            val initial = VaultDatabase.open(context, key)
            try { initial.dao().allPasskeys() } finally { initial.close() }
            shell("settings put secure credential_service $provider")
            shell("settings put secure credential_service_primary $provider")
            shell("settings put secure autofill_service ${context.packageName}/com.privatevault.app.autofill.VaultAutofillService")
            fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage("com.brave.browser").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            open("https://fill.dev/passkeys")
            set(waitFor("Passkey username") { it.viewIdResourceName == "username" }, username)
            tap(text("Create passkey"))
            unlock()
            tap(text("Create passkey"))
            waitFor("Registration verified by website") { it.text?.toString()?.startsWith("Passkey created for") == true }
            var db = VaultDatabase.open(context, key)
            try {
                val keys = db.dao().allPasskeys()
                assertEquals(1, keys.size)
                val backup = ByteArrayOutputStream()
                VaultBackupManager(context, db.dao(), EncryptedPhotoStore(context)).export(backup, master, key)
                db.close()
                // Simulate a fresh vault with a different random encryption key, not a restore over the old database.
                check(context.deleteDatabase("vault.db"))
                context.getSharedPreferences("vault_bootstrap", 0).edit().clear().commit()
                key.fill(0)
                key = VaultKeyManager(context).create(master)
                db = VaultDatabase.open(context, key)
                assertTrue(db.dao().allPasskeys().isEmpty())
                val manager = VaultBackupManager(context, db.dao(), EncryptedPhotoStore(context))
                manager.prepareRestore(backup.toByteArray().inputStream(), master, key).use { manager.commitRestore(it) }
                assertEquals(keys, db.dao().allPasskeys())
            } finally { db.close() }
            open("https://fill.dev/passkeys?mode=explicit")
            text("Sign in with passkey")
            set(waitFor("Sign-in username") { it.viewIdResourceName == "username" && it.isEditable }, username)
            tap(text("Sign in with passkey"))
            unlock()
            tap(text("Sign in as $username"))
            waitFor("Restored passkey accepted by website") { it.text?.toString()?.startsWith("Signed in as") == true }
        } finally {
            shell("am force-stop com.brave.browser")
            for ((name, value) in listOf("credential_service" to old, "credential_service_primary" to oldPrimary, "autofill_service" to oldAutofill)) {
                shell(if (value == "null") "settings delete secure $name" else "settings put secure $name $value")
            }
            context.deleteDatabase("vault.db")
            context.getSharedPreferences("vault_bootstrap", 0).edit().clear().commit()
            BiometricGate(context).clearDailySession()
            master.fill('\u0000'); key.fill(0)
        }
    }
}
