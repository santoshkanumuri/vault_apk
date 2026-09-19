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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
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
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.loginAuthorizedForDestination
import com.privatevault.app.autofill.LoginFillRequest
import com.privatevault.app.autofill.PendingLoginFills
import com.privatevault.app.autofill.PendingLoginSaves
import com.privatevault.app.autofill.LoginSaveRequest
import com.privatevault.app.security.trustedBrowser
import com.privatevault.app.security.httpsOrigin
import com.privatevault.app.data.EntryType
import android.view.autofill.AutofillManager
import android.view.autofill.AutofillValue
import android.service.autofill.Dataset
import android.widget.RemoteViews
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.crypto.Cipher
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A separate authenticated session. Never borrows the main activity's unlocked state. */
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
    private var loginFill: LoginFillRequest? = null
    private var loginSave: LoginSaveRequest? = null
    private var saveKey: ByteArray? = null
    private var saving: Job? = null
    private var saveMode = false
    private var passkeyOperation: com.privatevault.app.passkeys.PasskeyOperation? = null
    private var passkeys by mutableStateOf<List<com.privatevault.app.data.VaultPasskey>>(emptyList())
    private var selectedUpdate by mutableStateOf<VaultEntry?>(null)
    private var generating by mutableStateOf(false)
    private var generationEntry by mutableStateOf<VaultEntry?>(null)
    private var generationUsername by mutableStateOf("")
    private var autofillMode = false
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
        saveMode = intent.hasExtra("login_save_token")
        autofillMode = intent.hasExtra("login_fill_token") || saveMode || intent.hasExtra("passkey_create")
        if (intent.hasExtra("passkey_create")) {
            if (Build.VERSION.SDK_INT < 34) { dismissPicker(); return }
            passkeyOperation = runCatching { com.privatevault.app.passkeys.PasskeyOperation.from(this, intent) }.getOrNull()
            if (passkeyOperation == null) { dismissPicker(); return }
            handler.postDelayed({ dismissPicker() }, 120_000)
        } else if (saveMode) {
            loginSave = PendingLoginSaves.take(intent.getStringExtra("login_save_token"))
            if (loginSave == null) { dismissPicker(); return }
            handler.postDelayed({ dismissPicker() }, (requireNotNull(loginSave).expiresAt - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0))
        } else if (autofillMode) {
            loginFill = PendingLoginFills.take(intent.getStringExtra("login_fill_token"))
            if (loginFill == null) { dismissPicker(); return }
        } else suggestedApp = recentCodeApp(this)
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
                    key = gate.finishDailyUnlock(confirm(cipher, if (autofillMode) "Unlock autofill" else "Unlock Vault codes"))
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
                var loadedPasskeys = emptyList<com.privatevault.app.data.VaultPasskey>()
                val loaded = withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, requireNotNull(key))
                    try {
                        if (passkeyOperation != null) { loadedPasskeys = database.dao().allPasskeys(); emptyList() }
                        else if (autofillMode) database.dao().loginAndCodeEntries() else database.dao().authenticatorEntries()
                    } finally { database.close() }
                }
                if (pickerVisible) {
                    if (saveMode || passkeyOperation != null || loginFill?.newPasswords?.isNotEmpty() == true) saveKey = requireNotNull(key).copyOf()
                    passkeys = loadedPasskeys
                    entries = loaded; unlocked = true; onUserInteraction()
                }
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
        loginFill = null
        loginSave?.clear()
        loginSave = null
        saveKey?.fill(0)
        saveKey = null
        selectedUpdate = null
        generating = false
        generationEntry = null
        generationUsername = ""
        passkeys = emptyList()
        passkeyOperation = null
        saving?.cancel()
        authentication?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (autofillMode) finish() else finishAndRemoveTask()
    }

    private fun saveLogin(expected: VaultEntry?) {
        val request = loginSave ?: return
        if (busy || !pickerVisible || !unlocked || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity || !trustedBrowser(request.packageName, request.identity)) {
            dismissPicker(); return
        }
        val key = saveKey?.copyOf() ?: return
        val secret = request.password.copyOf()
        busy = true
        saving = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, key)
                    try { database.dao().saveBrowserLogin(request.origin, request.username, secret.concatToString(), expected) }
                    finally { database.close() }
                }
                dismissPicker()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not save. The login may have changed. Cancel and try again." }
            finally { key.fill(0); secret.fill('\u0000'); busy = false }
        }
    }

    @androidx.annotation.RequiresApi(34)
    private fun usePasskey(selected: com.privatevault.app.data.VaultPasskey?) {
        val operation = passkeyOperation ?: return
        if (busy || !pickerVisible || !unlocked || !hasWindowFocus()) return
        val key = saveKey?.copyOf() ?: return
        busy = true
        saving = lifecycleScope.launch {
            try {
                operation.revalidate(this@VaultCodesActivity)
                val result = withContext(Dispatchers.IO) {
                    val db = VaultDatabase.open(applicationContext, key)
                    try {
                        if (operation.create) {
                            check(db.dao().allPasskeys().none { com.privatevault.app.passkeys.PasskeyCrypto.excluded(it, operation.input) })
                            val (created, response) = com.privatevault.app.passkeys.PasskeyCrypto.create(operation.input, operation.origin, operation.clientHash)
                            db.dao().insertPasskeys(listOf(created))
                            Intent().also { androidx.credentials.provider.PendingIntentHandler.setCreateCredentialResponse(it, androidx.credentials.CreatePublicKeyCredentialResponse(response)) }
                        } else {
                            val current = db.dao().allPasskeys().single { it.id == requireNotNull(selected).id }
                            val response = com.privatevault.app.passkeys.PasskeyCrypto.sign(current, operation.input, operation.origin, operation.clientHash)
                            Intent().also { androidx.credentials.provider.PendingIntentHandler.setGetCredentialResponse(it, androidx.credentials.GetCredentialResponse(androidx.credentials.PublicKeyCredential(response))) }
                        }
                    } finally { db.close() }
                }
                if (pickerVisible && unlocked) setResult(RESULT_OK, result)
                dismissPicker()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not complete this passkey request. It may be unsupported or already registered." }
            finally { key.fill(0); busy = false }
        }
    }

    private fun prepareGeneration(entry: VaultEntry?) {
        generationEntry = entry
        generationUsername = entry?.primaryValue.orEmpty()
        generating = true
    }

    private fun saveGeneratedLogin() {
        val request = loginFill ?: return
        if (busy || !pickerVisible || !unlocked || !generating) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() || request.newPasswords.isEmpty() ||
            appSigningIdentity(this, request.packageName) != request.identity || !trustedBrowser(request.packageName, request.identity)) { dismissPicker(); return }
        val origin = request.origin ?: return
        val username = generationUsername
        if (username.isBlank() || username.length > 1024) return
        val entry = generationEntry
        val key = saveKey?.copyOf() ?: return
        val generated = com.privatevault.app.security.generateLoginPassword()
        busy = true
        saving = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val db = VaultDatabase.open(applicationContext, key)
                    try { db.dao().saveGeneratedLogin(origin, username, generated, entry) } finally { db.close() }
                }
                generating = false
                fillLogin(entry, generated, username)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not save the generated login. Nothing was filled. Cancel and try again." }
            finally { key.fill(0); busy = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun fillLogin(entry: VaultEntry?, generated: String? = null, generatedUsername: String? = null) {
        val request = loginFill ?: return
        if (!pickerVisible || !unlocked || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            (generated == null && !hasWindowFocus())) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (if (entry != null) !loginAuthorizedForDestination(entry, request.packageName, request.identity, request.origin)
             else request.newPasswords.isEmpty() || request.password != null || request.origin == null || !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        val result = runCatching {
            val presentation = RemoteViews(packageName, R.layout.autofill_suggestion)
            val dataset = Dataset.Builder(presentation)
            if (request.otp != null) {
                val code = entries.single { it.type == EntryType.AUTHENTICATOR && it.id == requireNotNull(entry).linkedAuthenticatorId }
                dataset.setValue(request.otp, AutofillValue.forText(Totp.code(code.secondaryValue, code.totpAlgorithm, code.totpDigits, code.totpPeriod)))
            } else {
                if (entry != null) {
                    require(entry.secondaryValue.isNotEmpty())
                    request.username?.let { dataset.setValue(it, AutofillValue.forText(entry.primaryValue)) }
                    request.password?.let { dataset.setValue(it, AutofillValue.forText(entry.secondaryValue)) }
                }
                if (request.newPasswords.isNotEmpty()) {
                    requireNotNull(generated)
                    request.username?.let { dataset.setValue(it, AutofillValue.forText(requireNotNull(generatedUsername))) }
                    request.newPasswords.forEach { dataset.setValue(it, AutofillValue.forText(generated)) }
                }
            }
            dataset.build()
        }.getOrElse { message = "Could not fill this account. Check its password or linked authenticator."; return }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, result)
            .putExtra(AutofillManager.EXTRA_CLIENT_STATE, Bundle().apply {
                if (entry != null && request.newPasswords.isNotEmpty()) {
                    putString("selected_username", entry.primaryValue)
                    putString("selected_origin", request.origin)
                    putString("selected_browser", request.packageName)
                }
            })
            .putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET, true))
        dismissPicker()
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
        saving?.cancel()
        loginSave?.clear()
        saveKey?.fill(0)
        selectedUpdate = null
        passkeys = emptyList()
        passkeyOperation = null
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    @Composable
    private fun Picker() {
        var password by remember { mutableStateOf("") }
        var search by remember { mutableStateOf("") }
        var showAll by remember { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (passkeyOperation != null) "Passkeys" else if (saveMode) "Save login" else if (autofillMode) "Autofill" else "Codes",
                            Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!unlocked && keyManager.isInitialized) Button(
                            onClick = { keyboard?.hide(); val value = password.toCharArray(); password = ""; authenticate(value) },
                            enabled = !busy && password.isNotEmpty()
                        ) { Text("Unlock") }
                    }
                    if (unlocked && passkeyOperation != null && Build.VERSION.SDK_INT >= 34) {
                        val operation = requireNotNull(passkeyOperation)
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text(operation.displaySource(this@VaultCodesActivity)) }
                            if (operation.create) {
                                item { Text("Create a passkey for ${operation.input.getJSONObject("user").getString("name")}? Keep an encrypted backup to transfer it to another phone.") }
                                item { Button(enabled = !busy, onClick = { usePasskey(null) }, modifier = Modifier.fillMaxWidth()) { Text("Create passkey") } }
                            } else {
                                val matches = passkeys.filter { com.privatevault.app.passkeys.PasskeyCrypto.matches(it, operation.input) }
                                if (matches.isEmpty()) item { Text("No matching passkeys in this vault.") }
                                items(matches, key = { it.id }) { passkey ->
                                    Button(enabled = !busy, onClick = { usePasskey(passkey) }, modifier = Modifier.fillMaxWidth()) { Text("Sign in as ${passkey.username}") }
                                }
                            }
                            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                    } else if (unlocked && saveMode) {
                        val request = loginSave
                        val matches = entries.filter { it.type == EntryType.PASSWORD && it.primaryValue == request?.username && httpsOrigin(it.tertiaryValue) == request.origin }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("Website: ${request?.origin.orEmpty()}") }
                            item { Text("Username: ${request?.username.orEmpty()}") }
                            item { Text("Password: hidden. Save the password submitted from this website? This does not change it on the website.") }
                            if (request != null && matches.any { entry -> entry.secondaryValue.length == request.password.size &&
                                    request.password.indices.all { entry.secondaryValue[it] == request.password[it] } }) item { Text("This password is already saved for this account.") }
                            item { Button(enabled = !busy, onClick = { saveLogin(null) }, modifier = Modifier.fillMaxWidth()) { Text("Save as new login") } }
                            items(matches, key = { it.id }) { entry ->
                                OutlinedButton(enabled = !busy, onClick = { selectedUpdate = entry }, modifier = Modifier.fillMaxWidth()) { Text("Update ${entry.title}") }
                            }
                            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        }
                    } else if (unlocked && autofillMode) {
                        val request = loginFill
                        val matches = entries.filter { entry -> request != null && loginAuthorizedForDestination(entry, request.packageName, request.identity, request.origin) &&
                            if (request.otp != null) entries.any { it.id == entry.linkedAuthenticatorId && it.type == EntryType.AUTHENTICATOR } else entry.secondaryValue.isNotEmpty() }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("Destination: ${request?.origin ?: request?.packageName.orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                            if (request?.newPasswords?.isNotEmpty() == true && request.password == null) item {
                                Button(onClick = { prepareGeneration(null) }, modifier = Modifier.fillMaxWidth()) { Text("Generate for a new account") }
                            }
                            if (matches.isEmpty()) item { Text(if (request?.origin != null) "No logins for this exact HTTPS website. Add its URL to the login in Private Vault. Subdomains must match exactly." else "No authorized logins. Open Passwords in Private Vault and link this app to a login. App identity changes require linking again.") }
                            items(matches, key = { it.id }) { entry ->
                                Card(Modifier.fillMaxWidth()) {
                                    if (request?.newPasswords?.isNotEmpty() == true) Column(Modifier.padding(16.dp)) {
                                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                        Text(entry.primaryValue)
                                        Button(onClick = { prepareGeneration(entry) }) { Text("Generate new password for this account") }
                                    } else Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                            Text(entry.primaryValue, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        TextButton(onClick = { fillLogin(entry) }) { Text(if (request?.otp != null) "Fill code" else "Fill login") }
                                    }
                                }
                            }
                            item { Text(if (request?.newPasswords?.isNotEmpty() == true) "Generate a 24-character password and save a separate login before filling. Your current login stays available until you confirm the website accepted the change." else if (request?.otp != null) "Choose a login to fill its linked authenticator code." else if (request?.password == null) "Choose an account to fill its username. The next screen requires a separate selection." else "Choose a login to fill its username and password.") }
                        }
                    } else if (unlocked) {
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
                            if (!keyManager.isInitialized) item { Text("Open Private Vault to create your vault first.") }
                            else {
                                if (gate.hasValidDailySession && biometricAvailable()) item {
                                    Button(onClick = { password = ""; authenticate(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint") }
                                }
                                item { OutlinedTextField(password, { password = it }, label = { Text("Master password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                                item { Text(if (passkeyOperation != null) "Unlock to review this passkey request. Creation and sign-in require your confirmation." else if (saveMode) "Unlock to review a login from ${loginSave?.origin.orEmpty()}. Nothing is saved until you confirm." else if (autofillMode) "Unlock to choose a login for ${loginFill?.origin ?: loginFill?.packageName.orEmpty()}. Nothing is filled until you select an account." else "Unlock to choose an account and copy its current code. Your password autofill app stays unchanged.") }
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
        if (generating) AlertDialog(onDismissRequest = { if (!busy) generating = false },
            title = { Text("Save generated password?") },
            text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Save a new login for ${loginFill?.origin.orEmpty()}, then fill the form. Submit the website form to finish. If it rejects the password, remove the generated login. Your old login will remain unchanged.")
                OutlinedTextField(generationUsername, { generationUsername = it }, label = { Text("Username or email") },
                    enabled = !busy && generationEntry == null, singleLine = true, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(enabled = !busy && generationUsername.isNotBlank() && generationUsername.length <= 1024,
                onClick = ::saveGeneratedLogin) { Text("Save new login and fill") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { generating = false }) { Text("Cancel") } })
        selectedUpdate?.let { entry ->
            AlertDialog(onDismissRequest = { selectedUpdate = null }, title = { Text("Replace saved password?") },
                text = { Text("Replace the password for ${entry.primaryValue} at ${loginSave?.origin.orEmpty()}? Groups, photos and the linked authenticator stay unchanged. Password history is not available; the previous password will be replaced.") },
                confirmButton = { Button(enabled = !busy, onClick = { selectedUpdate = null; saveLogin(entry) }) { Text("Replace password") } },
                dismissButton = { TextButton(onClick = { selectedUpdate = null }) { Text("Cancel") } })
        }
    }
}
