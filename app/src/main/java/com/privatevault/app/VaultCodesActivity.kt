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
import com.privatevault.app.sync.AndroidDeviceIdentityStore
import com.privatevault.app.sync.captureEntryUpserts
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.Totp
import com.privatevault.app.security.VaultKeyManager
import com.privatevault.app.security.recentCodeApp
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.loginAuthorizedForDestination
import com.privatevault.app.autofill.LoginFillRequest
import com.privatevault.app.autofill.PendingLoginFills
import com.privatevault.app.autofill.PendingLoginSaves
import com.privatevault.app.autofill.LoginSaveRequest
import com.privatevault.app.autofill.passwordSuggestions
import com.privatevault.app.autofill.saveInfo
import com.privatevault.app.autofill.saveClientState
import com.privatevault.app.autofill.delayedUsernameSave
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
    private var passwordCredentialOperation: com.privatevault.app.passkeys.PasswordCredentialOperation? = null
    private var passkeys by mutableStateOf<List<com.privatevault.app.data.VaultPasskey>>(emptyList())
    private var selectedUpdate by mutableStateOf<VaultEntry?>(null)
    private var pendingLoginLink by mutableStateOf<VaultEntry?>(null)
    private var generating by mutableStateOf(false)
    private var generationEntry by mutableStateOf<VaultEntry?>(null)
    private var generationUsername by mutableStateOf("")
    private var autofillMode = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { dismissPicker() }
    private var inactivityTimeoutMs = 60_000L
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = dismissPicker()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        saveMode = intent.hasExtra("login_save_token") || intent.hasExtra("credential_password_create")
        autofillMode = intent.hasExtra("login_fill_token") || saveMode || intent.hasExtra("passkey_create") || intent.hasExtra("credential_password_get")
        if (intent.hasExtra("passkey_create")) {
            if (Build.VERSION.SDK_INT < 34) { dismissPicker(); return }
            passkeyOperation = runCatching { com.privatevault.app.passkeys.PasskeyOperation.from(this, intent) }.getOrNull()
            if (passkeyOperation == null) { dismissPicker(); return }
            handler.postDelayed({ dismissPicker() }, 120_000)
        } else if (intent.hasExtra("credential_password_get") || intent.hasExtra("credential_password_create")) {
            if (Build.VERSION.SDK_INT < 34) { dismissPicker(); return }
            passwordCredentialOperation = runCatching {
                com.privatevault.app.passkeys.PasswordCredentialOperation.from(this, intent)
            }.getOrNull()
            val operation = passwordCredentialOperation
            if (operation == null) { dismissPicker(); return }
            if (operation.create) {
                loginSave = LoginSaveRequest(
                    operation.destination.packageName,
                    operation.destination.identity,
                    operation.destination.origin,
                    operation.username,
                    operation.password.toCharArray(),
                    android.os.SystemClock.elapsedRealtime() + 120_000,
                )
            } else {
                loginFill = LoginFillRequest(
                    operation.destination.packageName,
                    operation.destination.identity,
                    null,
                    null,
                    null,
                    android.os.SystemClock.elapsedRealtime() + 120_000,
                    operation.destination.origin,
                )
            }
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
                }
                var loadedPasskeys = emptyList<com.privatevault.app.data.VaultPasskey>()
                var masterInterval = 86_400_000L
                val loaded = withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, requireNotNull(key))
                    try {
                        val settings = database.dao().settings()
                        masterInterval = settings?.masterPasswordIntervalMs ?: 86_400_000L
                        inactivityTimeoutMs = settings?.inactivityTimeoutMs ?: 60_000L
                        if (passkeyOperation != null) { loadedPasskeys = database.dao().allPasskeys(); emptyList() }
                        else database.dao().loginAndCodeEntries()
                    } finally { database.close() }
                }
                if (password != null && masterInterval > 0 && biometricAvailable()) {
                    try {
                        val cipher = confirm(gate.dailyEncryptionCipher(), "Enable fingerprint unlock")
                        gate.enableDailySession(cipher, requireNotNull(key), masterInterval)
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { /* Password authentication still permits this visit. */ }
                }
                if (pickerVisible) {
                    if (autofillMode || passkeyOperation != null) saveKey = requireNotNull(key).copyOf()
                    passkeys = loadedPasskeys
                    entries = loaded; unlocked = true; onUserInteraction()
                    if (intent.getBooleanExtra("keyboard_suggestions", false)) returnKeyboardSuggestions()
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

    private fun returnKeyboardSuggestions() {
        val request = loginFill ?: return
        if (request.otp != null || request.newPasswords.isNotEmpty()) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (request.origin != null && !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        val response = runCatching {
            val inlineRequest = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(
                AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST, android.view.inputmethod.InlineSuggestionsRequest::class.java)
            else if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<android.view.inputmethod.InlineSuggestionsRequest>(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST)
            } else null
            passwordSuggestions(this, request, entries, inlineRequest)
        }.getOrElse { message = "Could not show keyboard suggestions. Choose a login here."; return }
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        dismissPicker()
    }

    private fun copy(entry: VaultEntry, username: Boolean = false) {
        if (!pickerVisible || !unlocked || !hasWindowFocus()) return
        val label: String
        val value: String
        when (entry.type) {
            EntryType.PASSWORD -> {
                label = if (username) "Username" else "Password"
                value = if (username) entry.primaryValue else entry.secondaryValue
            }
            EntryType.AUTHENTICATOR -> {
                label = "Authenticator code"
                value = runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod) }
                    .getOrElse { message = "Could not generate this code. Check its settings in the vault."; return }
            }
            else -> return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val token = java.util.UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, value)
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
        // Lifecycle callbacks can follow finish(); an Autofill result must only be sent once.
        if (!pickerVisible) return
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
        pendingLoginLink = null
        generating = false
        generationEntry = null
        generationUsername = ""
        passkeys = emptyList()
        passkeyOperation = null
        passwordCredentialOperation = null
        saving?.cancel()
        authentication?.cancel()
        handler.removeCallbacksAndMessages(null)
        if (autofillMode) finish() else finishAndRemoveTask()
    }

    private fun saveDestination(request: LoginSaveRequest): String = request.origin ?: runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(request.packageName, 0)).toString()
    }.getOrDefault(request.packageName)

    private fun fillDestination(request: LoginFillRequest): String = request.origin ?: runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(request.packageName, 0)).toString()
    }.getOrDefault(request.packageName)

    private fun linkAndFill(entry: VaultEntry) {
        val request = loginFill ?: return
        if (busy || !pickerVisible || !unlocked ||
            !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (request.origin != null && !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        val key = saveKey?.copyOf() ?: return
        busy = true
        saving = lifecycleScope.launch {
            try {
                val linked = withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, key)
                    try {
                        val current = requireNotNull(database.dao().entry(entry.id))
                        val updated = com.privatevault.app.security.linkLoginToDestination(
                            current.entry,
                            request.packageName,
                            request.identity,
                            request.origin,
                        )
                        com.privatevault.app.sync.LocalEntryChangeWriter(
                            database,
                            com.privatevault.app.sync.AndroidDeviceIdentityStore(applicationContext),
                        ).save(updated, current.groups.map { it.id }.toSet(), key)
                        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(applicationContext, database)
                        updated
                    } finally { database.close() }
                }
                entries = entries.map { if (it.id == linked.id) linked else it }
                pendingLoginLink = null
                fillLogin(linked, confirmedDialogAction = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not link this login. Nothing was filled." }
            finally { key.fill(0); busy = false }
        }
    }

    private fun saveLogin(expected: VaultEntry?) {
        val request = loginSave ?: return
        if (busy || !pickerVisible || !unlocked || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (request.origin != null && !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        val key = saveKey?.copyOf() ?: return
        val secret = request.password.copyOf()
        busy = true
        saving = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, key)
                    try {
                        database.captureEntryUpserts(AndroidDeviceIdentityStore(applicationContext), key) {
                            if (request.origin != null) saveBrowserLogin(request.origin, request.username, secret.concatToString(), expected)
                            else saveNativeLogin(request.packageName, request.identity, saveDestination(request), request.username, secret.concatToString(), expected)
                        }
                        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(applicationContext, database)
                    }
                    finally { database.close() }
                }
                if (passwordCredentialOperation?.create == true) {
                    if (Build.VERSION.SDK_INT >= 34) passwordCredentialOperation?.revalidate(this@VaultCodesActivity)
                    val result = Intent().also {
                        androidx.credentials.provider.PendingIntentHandler.setCreateCredentialResponse(
                            it,
                            androidx.credentials.CreatePasswordResponse(),
                        )
                    }
                    setResult(RESULT_OK, result)
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
                            com.privatevault.app.sync.LocalPasskeyChangeWriter(db,
                                com.privatevault.app.sync.AndroidDeviceIdentityStore(applicationContext)).save(created, key)
                            com.privatevault.app.sync.LanSyncService.publishCredentialChanges(applicationContext, db)
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
                    try {
                        db.captureEntryUpserts(AndroidDeviceIdentityStore(applicationContext), key) {
                            saveGeneratedLogin(origin, username, generated, entry)
                        }
                        com.privatevault.app.sync.LanSyncService.publishCredentialChanges(applicationContext, db)
                    } finally { db.close() }
                }
                generating = false
                fillLogin(entry, generated, username)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not save the generated login. Nothing was filled. Cancel and try again." }
            finally { key.fill(0); busy = false }
        }
    }

    @Suppress("DEPRECATION")
    private fun fillLogin(
        entry: VaultEntry?,
        generated: String? = null,
        generatedUsername: String? = null,
        confirmedDialogAction: Boolean = false,
    ) {
        val request = loginFill ?: return
        if (!pickerVisible || !unlocked || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) ||
            !com.privatevault.app.security.trustedFillFocus(hasWindowFocus(), confirmedDialogAction, generated != null)) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (if (entry != null) !loginAuthorizedForDestination(entry, request.packageName, request.identity, request.origin)
             else request.newPasswords.isEmpty() || request.password != null || request.origin == null || !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        if (passwordCredentialOperation?.create == false) {
            val selected = entry ?: return
            val result = runCatching {
                if (Build.VERSION.SDK_INT >= 34) passwordCredentialOperation?.revalidate(this)
                require(selected.secondaryValue.isNotEmpty())
                Intent().also {
                    androidx.credentials.provider.PendingIntentHandler.setGetCredentialResponse(
                        it,
                        androidx.credentials.GetCredentialResponse(
                            androidx.credentials.PasswordCredential(selected.primaryValue, selected.secondaryValue),
                        ),
                    )
                }
            }.getOrElse { message = "Could not return this login to the app."; return }
            setResult(RESULT_OK, result)
            dismissPicker()
            return
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
        val authenticationResult = if (intent.getBooleanExtra("keyboard_suggestions", false)) {
            android.service.autofill.FillResponse.Builder().addDataset(result).apply {
                (request.saveInfo() ?: request.delayedUsernameSave())?.let(::setSaveInfo)
                request.saveClientState()?.let(::setClientState)
            }.build()
        } else result
        setResult(RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, authenticationResult)
            .putExtra(AutofillManager.EXTRA_CLIENT_STATE, (request.saveClientState() ?: Bundle()).apply {
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
        handler.postDelayed(timeout, inactivityTimeoutMs)
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
        pendingLoginLink = null
        passkeys = emptyList()
        passkeyOperation = null
        passwordCredentialOperation = null
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(screenOff)
        super.onDestroy()
    }

    @Composable
    private fun Picker() {
        var password by remember { mutableStateOf("") }
        var search by remember { mutableStateOf("") }
        var findLogin by remember { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (passkeyOperation != null) "Passkeys" else if (saveMode) "Save login" else if (passwordCredentialOperation != null) "Sign in" else if (autofillMode) "Nuvori passwords" else "Vault codes",
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
                        val destination = request?.let(::saveDestination).orEmpty()
                        val matches = entries.filter { entry -> request != null && entry.type == EntryType.PASSWORD && entry.primaryValue == request.username &&
                            (if (request.origin != null) httpsOrigin(entry.tertiaryValue) == request.origin
                             else com.privatevault.app.security.loginAuthorized(entry, request.packageName, request.identity)) }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text(if (request?.origin != null) "Website: $destination" else "App: $destination") }
                            item { Text("Username: ${request?.username.orEmpty()}") }
                            item { Text("Password: hidden. Save the submitted password? This only saves a copy in Nuvori.") }
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
                        val eligible = entries.filter { entry -> entry.type == EntryType.PASSWORD && entry.secondaryValue.isNotEmpty() &&
                            if (request?.otp != null) entries.any { it.id == entry.linkedAuthenticatorId && it.type == EntryType.AUTHENTICATOR } else true }
                        val matches = eligible.filter { entry -> request != null && loginAuthorizedForDestination(entry, request.packageName, request.identity, request.origin) &&
                            (request.otp == null || search.isBlank() || listOf(entry.title, entry.primaryValue).any { it.contains(search, true) }) }
                        val searched = eligible.filter { entry -> search.isBlank() || listOf(entry.title, entry.primaryValue, entry.tertiaryValue, entry.autofillOrigins)
                            .any { it.contains(search, ignoreCase = true) } }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            item { Text("Destination: ${request?.let(::fillDestination).orEmpty()}", style = MaterialTheme.typography.bodySmall) }
                            if (request?.embeddedWebView == true) item {
                                Text("Embedded browser in ${fillDestination(request)} (${request.packageName}). This app can read what you fill. Choose a login only if you trust this app. Website links do not authorize this app.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (request?.otp != null) item {
                                OutlinedTextField(search, { search = it }, label = { Text("Search linked codes") },
                                    singleLine = true, modifier = Modifier.fillMaxWidth())
                            }
                            if (request?.newPasswords?.isNotEmpty() == true && request.password == null) item {
                                Button(onClick = { prepareGeneration(null) }, modifier = Modifier.fillMaxWidth()) { Text("Generate for a new account") }
                            }
                            if (matches.isEmpty()) item { Text(if (request?.origin != null) "No login is linked to this exact HTTPS website." else "No login is linked to this app and signing identity.") }
                            items(matches, key = { "matched-${it.id}" }) { entry ->
                                if (request?.newPasswords?.isNotEmpty() == true) Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(16.dp)) {
                                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                                        Text(entry.primaryValue)
                                        Button(onClick = { prepareGeneration(entry) }) { Text("Generate new password for this account") }
                                    }
                                } else AutofillAccountRow(entry, if (request?.otp != null) "Fill code" else "Fill login") { fillLogin(entry) }
                            }
                            if (request?.newPasswords?.isEmpty() == true && request.otp == null) item {
                                OutlinedButton(
                                    onClick = { findLogin = !findLogin; search = "" },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text(if (findLogin) "Hide saved logins" else if (matches.isEmpty()) "Find and link an existing login" else "Use another saved login") }
                            }
                            if (findLogin) {
                                item {
                                    OutlinedTextField(
                                        search,
                                        { search = it },
                                        label = { Text("Search saved logins") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                                if (searched.isEmpty()) item { Text("No saved logins match this search.") }
                                items(searched, key = { "search-${it.id}" }) { entry ->
                                    val linked = request != null && loginAuthorizedForDestination(
                                        entry, request.packageName, request.identity, request.origin)
                                    val action = if (linked) "Fill" else "Fill and link"
                                    AutofillAccountRow(entry, action) {
                                        if (linked) fillLogin(entry) else pendingLoginLink = entry
                                    }
                                }
                            }
                            item { Text(if (request?.newPasswords?.isNotEmpty() == true) "Generate a 24-character password and save a separate login before filling. Your current login stays available until you confirm the website accepted the change." else if (request?.otp != null) "Choose a login to fill its linked authenticator code." else if (passwordCredentialOperation != null) "Choose a login to return through Android Credential Manager." else if (request?.password == null) "Choose an account to fill its username. The next screen requires a separate selection." else "Choose a login to fill its username and password.") }
                            item {
                                TextButton(onClick = {
                                    dismissPicker()
                                    startActivity(Intent(this@VaultCodesActivity, MainActivity::class.java))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Open Nuvori to manage passwords") }
                            }
                        }
                    } else if (unlocked) {
                        VaultQuickAccessContent(entries, suggestedApp, ::copy)
                    } else {
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            if (!keyManager.isInitialized) item { Text("Open Nuvori to create your vault first.") }
                            else {
                                if (gate.hasValidDailySession && biometricAvailable()) item {
                                    Button(onClick = { password = ""; authenticate(null) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Use fingerprint") }
                                }
                                item { OutlinedTextField(password, { password = it }, label = { Text("Master password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth()) }
                                item { Text(if (passkeyOperation != null) "Unlock to review this passkey request. Creation and sign-in require your confirmation." else if (saveMode) "Unlock to review a login from ${loginSave?.let(::saveDestination).orEmpty()}. Nothing is saved until you confirm." else if (autofillMode) "Unlock to choose a login for ${loginFill?.let(::fillDestination).orEmpty()}. Nothing is filled until you select an account." else "Unlock to search and copy passwords or authenticator codes.") }
                                if (loginFill?.embeddedWebView == true) item {
                                    Text("Embedded browser in ${loginFill?.let(::fillDestination).orEmpty()}. This app can read filled credentials. Only select a login if you trust this app.", style = MaterialTheme.typography.bodySmall)
                                }
                                item { Text("A master password starts a new fingerprint session. Fingerprint use does not extend it.", style = MaterialTheme.typography.bodySmall) }
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
                Text("Save the username or email, generated password, and website link for ${loginFill?.origin.orEmpty()} before filling. Submit the website form to finish. If it rejects the password, remove the generated login. Your old login will remain unchanged.")
                OutlinedTextField(generationUsername, { generationUsername = it }, label = { Text("Username or email") },
                    enabled = !busy && generationEntry == null, singleLine = true, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { Button(enabled = !busy && generationUsername.isNotBlank() && generationUsername.length <= 1024,
                onClick = ::saveGeneratedLogin) { Text("Save new login and fill") } },
            dismissButton = { TextButton(enabled = !busy, onClick = { generating = false }) { Text("Cancel") } })
        selectedUpdate?.let { entry ->
            AlertDialog(onDismissRequest = { selectedUpdate = null }, title = { Text("Replace saved password?") },
                text = { Text("Replace the password for ${entry.primaryValue} at ${loginSave?.let(::saveDestination).orEmpty()}? Groups, photos and the linked authenticator stay unchanged. Password history is not available; the previous password will be replaced.") },
                confirmButton = { Button(enabled = !busy, onClick = { selectedUpdate = null; saveLogin(entry) }) { Text("Replace password") } },
                dismissButton = { TextButton(onClick = { selectedUpdate = null }) { Text("Cancel") } })
        }
        pendingLoginLink?.let { entry ->
            val request = loginFill
            val destination = request?.let(::fillDestination).orEmpty()
            AlertDialog(
                onDismissRequest = { if (!busy) pendingLoginLink = null },
                title = { Text("Fill and link this login?") },
                text = { Text(if (request?.embeddedWebView == true)
                    "Fill ${entry.primaryValue} in the embedded browser of $destination (${request.packageName})? This app can read the credentials. Linking authorizes this app's signing identity for future suggestions, not the website shown inside it."
                else if (request?.origin != null)
                    "Fill ${entry.primaryValue} and link it to the exact website $destination. Nuvori can suggest it there next time."
                else "Fill ${entry.primaryValue} and link it to $destination (${request?.packageName.orEmpty()}). Nuvori will also bind the link to the app's current signing certificate.") },
                confirmButton = { Button(enabled = !busy, onClick = { linkAndFill(entry) }) { Text("Fill and link") } },
                dismissButton = { TextButton(enabled = !busy, onClick = { pendingLoginLink = null }) { Text("Cancel") } },
            )
        }
    }
}
