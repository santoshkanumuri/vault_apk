package com.privatevault.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.Totp
import com.privatevault.app.security.VaultKeyManager
import com.privatevault.app.security.recentCodeApp
import com.privatevault.app.security.matchingCodeEntries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A separate, read-only session. Never borrows the main activity's unlocked state. */
class VaultCodesActivity : FragmentActivity() {
    private val gate by lazy { BiometricGate(this) }
    private val keyManager by lazy { VaultKeyManager(this) }
    private var entries by mutableStateOf<List<VaultEntry>>(emptyList())
    private var unlocked by mutableStateOf(false)
    private var pickerVisible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var authentication: Job? = null
    private var suggestedApp: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismissPicker() }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = dismissPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        suggestedApp = recentCodeApp(this)
        setContent {
            val light = getSharedPreferences("vault_preferences", MODE_PRIVATE).getBoolean("light_mode", false)
            MaterialTheme(colorScheme = if (light) VaultLightColors else VaultColors) {
                if (pickerVisible) Picker()
            }
        }
        if (gate.hasValidDailySession && biometricAvailable()) authenticate(null)
    }

    private fun biometricAvailable() = BiometricManager.from(this).canAuthenticate(
        BiometricManager.Authenticators.BIOMETRIC_STRONG
    ) == BiometricManager.BIOMETRIC_SUCCESS

    private suspend fun confirm(cipher: Cipher, title: String): Cipher = suspendCancellableCoroutine { continuation ->
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (!continuation.isActive) return
                    val authenticated = result.cryptoObject?.cipher
                    if (authenticated != null) continuation.resume(authenticated)
                    else continuation.resumeWithException(IllegalStateException())
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (continuation.isActive) continuation.resumeWithException(IllegalStateException())
                }
            })
        continuation.invokeOnCancellation { prompt.cancelAuthentication() }
        prompt.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title)
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel").build(), BiometricPrompt.CryptoObject(cipher))
    }

    private fun authenticate(password: CharArray?) {
        if (busy || !pickerVisible) { password?.fill('\u0000'); return }
        busy = true
        message = null
        authentication = lifecycleScope.launch {
            var key: ByteArray? = null
            try {
                if (password == null) {
                    val cipher = gate.dailyDecryptionCipher() ?: error("Expired")
                    key = gate.finishDailyUnlock(confirm(cipher, "Unlock Vault codes"))
                } else {
                    // Keep ownership here so cancellation during derivation still wipes the key.
                    withContext(Dispatchers.IO) { key = keyManager.unlock(password) }
                    if (biometricAvailable()) {
                        try {
                            val cipher = confirm(gate.dailyEncryptionCipher(), "Enable fingerprint for 24 hours")
                            gate.enableDailySession(cipher, requireNotNull(key))
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Password authentication still permits this visit. */ }
                    }
                }
                val loaded = withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, requireNotNull(key))
                    try { database.dao().authenticatorEntries() } finally { database.close() }
                }
                if (pickerVisible) { entries = loaded; unlocked = true; onUserInteraction() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (pickerVisible) message = if (password == null) "Fingerprint was cancelled or unavailable. Try again or use your master password."
                    else "Could not unlock. Check your master password and try again."
            } finally {
                key?.fill(0)
                password?.fill('\u0000')
                busy = false
            }
        }
    }

    private fun copy(entry: VaultEntry) {
        if (!pickerVisible || !unlocked || !hasWindowFocus()) return
        val code = runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod) }
            .getOrElse { message = "Could not generate this code. Check its settings in the vault."; return }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val token = java.util.UUID.randomUUID().toString()
        val clip = ClipData.newPlainText("Authenticator code", code)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString("vault_clip_token", token)
        }
        clipboard.setPrimaryClip(clip)
        // Android may deny background clipboard access. Never erase a newer clipboard item.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                if (clipboard.primaryClipDescription?.extras?.getString("vault_clip_token") == token) clipboard.clearPrimaryClip()
            }
        }, 30_000)
        dismissPicker()
    }

    private fun dismissPicker() {
        pickerVisible = false
        unlocked = false
        entries = emptyList()
        suggestedApp = null
        authentication?.cancel()
        handler.removeCallbacks(timeout)
        finishAndRemoveTask()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 60_000)
    }

    override fun onStop() {
        super.onStop()
        dismissPicker()
    }

    override fun onPause() {
        // Do not leave account names or codes visible behind another window.
        if (unlocked) dismissPicker()
        super.onPause()
    }

    override fun onDestroy() {
        authentication?.cancel()
        entries = emptyList()
        handler.removeCallbacks(timeout)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    @Composable
    private fun Picker() {
        var password by remember { mutableStateOf("") }
        var search by remember { mutableStateOf("") }
        var showAll by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(16.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(.85f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Vault codes", style = MaterialTheme.typography.headlineSmall)
                    if (unlocked) {
                        val suggested = matchingCodeEntries(entries, suggestedApp)
                        val filtered = if (!showAll && suggested.isNotEmpty()) suggested else entries
                        Text(if (!showAll && suggested.isNotEmpty()) "Suggested accounts for your previous app" else "All accounts", style = MaterialTheme.typography.bodySmall)
                        if (suggested.isNotEmpty()) TextButton(onClick = { showAll = !showAll; search = "" }) { Text(if (showAll) "Show suggested" else "Show all") }
                        OutlinedTextField(search, { search = it }, label = { Text("Search accounts") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                        val matches = filtered.filter { it.title.contains(search, true) || it.primaryValue.contains(search, true) }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (matches.isEmpty()) item { Text(if (entries.isEmpty()) "No authenticator accounts yet. Add one in the vault's Codes tab." else "No matching accounts.") }
                            items(matches, key = { it.id }) { entry -> TotpTile(entry, { _, _ -> copy(entry) }, {}, initiallyMasked = true) }
                        }
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("Unlock to choose an account and copy its current code. Your password autofill app stays unchanged.") }
                            if (!keyManager.isInitialized) item { Text("Open Private Vault to create your vault first.") }
                            else {
                                if (gate.hasValidDailySession && biometricAvailable()) item {
                                    Button(onClick = { password = ""; authenticate(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint") }
                                }
                                item { OutlinedTextField(password, { password = it }, label = { Text("Master password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                                item { Button(onClick = { val value = password.toCharArray(); password = ""; authenticate(value) }, enabled = !busy && password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) { Text("Use master password") } }
                                item { Text("A master password starts a new 24-hour fingerprint session. Fingerprint use does not extend it.", style = MaterialTheme.typography.bodySmall) }
                            }
                            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                    }
                    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    TextButton(onClick = ::dismissPicker, modifier = Modifier.align(Alignment.End)) { Text("Cancel") }
                }
            }
        }
    }
}
