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
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.privatevault.app.data.VaultDatabase
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultPasskey
import com.privatevault.app.sync.AndroidDeviceIdentityStore
import com.privatevault.app.sync.captureEntryUpserts
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.Totp
import com.privatevault.app.security.VaultKeyManager
import com.privatevault.app.security.recentCodeApp
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.loginAuthorized
import com.privatevault.app.security.loginAuthorizedForDestination
import com.privatevault.app.security.possibleLoginMatches
import com.privatevault.app.autofill.AutofillPreferences
import com.privatevault.app.autofill.LoginFillRequest
import com.privatevault.app.autofill.PendingLoginFills
import com.privatevault.app.autofill.PendingLoginSaves
import com.privatevault.app.autofill.LoginSaveRequest
import com.privatevault.app.autofill.passwordSuggestions
import com.privatevault.app.autofill.saveInfo
import com.privatevault.app.autofill.saveClientState
import com.privatevault.app.autofill.delayedUsernameSave
import com.privatevault.app.passkeys.PasskeyOperation
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

/** Who is asking: a website (lock icon and host) or an app (its icon, label and package). */
private class PickerDestination(val name: String, val detail: String?, val packageName: String?)

/** A separate authenticated session. Never borrows the main activity's unlocked state. */
class VaultCodesActivity : FragmentActivity() {
    private val gate by lazy { BiometricGate(this) }
    private val keyManager by lazy { VaultKeyManager(this) }
    private var entries by mutableStateOf<List<VaultEntry>>(emptyList())
    private var unlocked by mutableStateOf(false)
    private var pickerVisible by mutableStateOf(true)
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf<String?>(null)
    private var unlockFailed by mutableStateOf(false)
    private var savingId by mutableStateOf<String?>(null)
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
            val preferences = getSharedPreferences("vault_preferences", MODE_PRIVATE)
            val light = preferences.getBoolean("light_mode", false)
            // Device-only convenience: the tab index says nothing about the vault.
            val tabStore = remember {
                QuickAccessTabStore(preferences.getInt("quick_access_tab", 1)) { tab ->
                    preferences.edit().putInt("quick_access_tab", tab).apply()
                }
            }
            MaterialTheme(colorScheme = if (light) VaultLightColors else VaultColors) {
                CompositionLocalProvider(LocalQuickAccessTabStore provides tabStore) {
                    if (pickerVisible) Picker()
                }
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
        unlockFailed = false
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
                if (pickerVisible) {
                    unlockFailed = password != null
                    message = if (password == null) "Fingerprint was cancelled or unavailable. Try again or use your master password."
                        else "Could not unlock. Check your master password and try again."
                }
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

    /** Toasts name what happened and never include the copied value. */
    private fun toast(text: String, long: Boolean = false) {
        Toast.makeText(applicationContext, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
    }

    /** Sensitive clip, cleared after 30 s unless something newer was copied in the meantime. */
    private fun copySensitive(label: String, value: String) {
        val clipboard = applicationContext.getSystemService(ClipboardManager::class.java)
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
    }

    private fun copy(entry: VaultEntry, username: Boolean = false) {
        if (!pickerVisible || !unlocked || !hasWindowFocus()) return
        val label: String
        val value: String
        val done: String
        when (entry.type) {
            EntryType.PASSWORD -> {
                label = if (username) "Username" else "Password"
                value = if (username) entry.primaryValue else entry.secondaryValue
                done = if (username) "Username copied" else "Password copied; it clears in 30 seconds."
            }
            EntryType.AUTHENTICATOR -> {
                label = "Authenticator code"
                value = runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod) }
                    .getOrElse { message = "Could not generate this code. Check its settings in the vault."; return }
                done = "Code copied; it clears in 30 seconds."
            }
            else -> return
        }
        copySensitive(label, value)
        toast(done)
        dismissPicker()
    }

    /** After a picker fill the next screen often asks for a code, so copy it now. Returns the code's account name. */
    private fun copyLinkedCode(entry: VaultEntry): String? {
        if (entry.linkedAuthenticatorId.isEmpty() || !AutofillPreferences.copyLinkedCode(this)) return null
        val authenticator = entries.firstOrNull { it.type == EntryType.AUTHENTICATOR && it.id == entry.linkedAuthenticatorId } ?: return null
        return runCatching {
            copySensitive("Authenticator code",
                Totp.code(authenticator.secondaryValue, authenticator.totpAlgorithm, authenticator.totpDigits, authenticator.totpPeriod))
            authenticator.title.ifBlank { "linked authenticator" }
        }.getOrNull()
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

    private fun appLabel(packageName: String): String? = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString()
    }.getOrNull()

    private fun appIcon(packageName: String): ImageBitmap? = runCatching {
        val px = (32 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
        packageManager.getApplicationIcon(packageName).toBitmap(px, px).asImageBitmap()
    }.getOrNull()

    private fun saveDestination(request: LoginSaveRequest): String = request.origin ?: appLabel(request.packageName) ?: request.packageName

    private fun fillDestination(request: LoginFillRequest): String = request.origin ?: appLabel(request.packageName) ?: request.packageName

    private fun destinationFor(packageName: String, origin: String?): PickerDestination {
        if (origin != null) return PickerDestination(origin.removePrefix("https://"), null, null)
        val label = appLabel(packageName)
        return PickerDestination(label ?: packageName, if (label != null) packageName else null, packageName)
    }

    private fun pickerDestination(): PickerDestination? {
        loginSave?.let { return destinationFor(it.packageName, it.origin) }
        loginFill?.let { return destinationFor(it.packageName, it.origin) }
        if (Build.VERSION.SDK_INT >= 34) passkeyOperation?.let { operation ->
            return destinationFor(operation.callerPackage, operation.origin.takeIf { it.startsWith("https://") })
        }
        return null
    }

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
        message = null
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

    /**
     * [expected] is the saved login to update, or null to add a new one. A new login uses [typedUsername]
     * when the form had no username, and [title] as its name. An update keeps the saved login's own username.
     */
    private fun saveLogin(expected: VaultEntry?, typedUsername: String = "", title: String? = null) {
        val request = loginSave ?: return
        if (busy || !pickerVisible || !unlocked || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) return
        if (request.expiresAt <= android.os.SystemClock.elapsedRealtime() ||
            appSigningIdentity(this, request.packageName) != request.identity ||
            (request.origin != null && !trustedBrowser(request.packageName, request.identity))) {
            dismissPicker(); return
        }
        val username = expected?.primaryValue ?: request.username.ifBlank { typedUsername.trim() }
        if (username.isBlank() || (request.username.isBlank() && expected == null && username.length > 1024)) return
        val key = saveKey?.copyOf() ?: return
        val secret = request.password.copyOf()
        busy = true
        message = null
        savingId = expected?.id ?: "new"
        saving = lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val database = VaultDatabase.open(applicationContext, key)
                    try {
                        database.captureEntryUpserts(AndroidDeviceIdentityStore(applicationContext), key) {
                            if (request.origin != null) saveBrowserLogin(request.origin, username, secret.concatToString(), expected, title)
                            else saveNativeLogin(request.packageName, request.identity, saveDestination(request), username, secret.concatToString(), expected, title)
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
                toast(if (expected != null) "Password updated in Nuvori" else "Login saved to Nuvori")
                dismissPicker()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { message = "Could not save. The login may have changed. Cancel and try again." }
            finally { key.fill(0); secret.fill('\u0000'); busy = false; savingId = null }
        }
    }

    @androidx.annotation.RequiresApi(34)
    private fun usePasskey(selected: com.privatevault.app.data.VaultPasskey?) {
        val operation = passkeyOperation ?: return
        if (busy || !pickerVisible || !unlocked || !hasWindowFocus()) return
        val key = saveKey?.copyOf() ?: return
        busy = true
        message = null
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
                if (pickerVisible && unlocked) {
                    setResult(RESULT_OK, result)
                    if (operation.create) toast("Passkey saved to Nuvori")
                }
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
        message = null
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
        // The next sign-in screen usually asks for the linked code. Copy it while this window is still in front.
        if (request.otp == null && request.password != null && request.newPasswords.isEmpty() && entry != null && generated == null) {
            copyLinkedCode(entry)?.let { toast("Login filled. Code for $it copied; it clears in 30 seconds.", long = true) }
        }
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
        val title = if (passkeyOperation != null) "Passkeys" else if (saveMode) "Save login" else if (passwordCredentialOperation != null) "Sign in" else if (autofillMode) "Nuvori passwords" else "Vault codes"
        val destination = remember { pickerDestination() }
        // Short screens hug their content; lists use the full height.
        val compact = !unlocked || saveMode || (Build.VERSION.SDK_INT >= 34 && passkeyOperation?.create == true)
        val submit: () -> Unit = {
            if (!busy && password.isNotEmpty()) { val value = password.toCharArray(); password = ""; authenticate(value) }
        }
        Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(8.dp), contentAlignment = Alignment.Center) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().then(if (compact) Modifier else Modifier.fillMaxHeight()),
                shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NuvoriLogo(Modifier.size(28.dp))
                        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    destination?.let { DestinationRow(it) }
                    val operation = passkeyOperation
                    if (unlocked && operation != null && Build.VERSION.SDK_INT >= 34) {
                        PasskeyContent(operation)
                    } else if (unlocked && saveMode) {
                        SaveContent()
                    } else if (unlocked && autofillMode) {
                        FillContent()
                    } else if (unlocked) {
                        VaultQuickAccessContent(entries, suggestedApp, ::copy)
                    } else {
                        UnlockContent(password, { password = it }, submit) { password = ""; authenticate(null) }
                    }
                    if (unlocked) message?.let { StatusBanner(StatusKind.ERROR, it) }
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
                onClick = ::saveGeneratedLogin) { PickerButtonLabel("Save new login and fill", busy) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { generating = false }) { Text("Cancel") } })
        selectedUpdate?.let { entry ->
            AlertDialog(onDismissRequest = { selectedUpdate = null }, title = { Text("Update saved password?") },
                text = { Text("Replace the saved password for ${entry.primaryValue} at ${loginSave?.let(::saveDestination).orEmpty()} in \"${entry.title}\"? The old password is replaced and can't be recovered. Groups, photos and the linked authenticator code stay unchanged.") },
                confirmButton = { Button(enabled = !busy, onClick = { selectedUpdate = null; saveLogin(entry) }) { Text("Replace password") } },
                dismissButton = { TextButton(onClick = { selectedUpdate = null }) { Text("Cancel") } })
        }
        pendingLoginLink?.let { entry ->
            val request = loginFill
            val destinationName = request?.let(::fillDestination).orEmpty()
            val account = entry.primaryValue.ifBlank { entry.title }
            AlertDialog(
                onDismissRequest = { if (!busy) pendingLoginLink = null },
                title = { Text("Fill and link this login?") },
                text = { Text(if (request?.embeddedWebView == true)
                    "Fill $account in the embedded browser of $destinationName (${request.packageName})? This app can read the credentials. Linking authorizes this app's signing identity for future suggestions, not the website shown inside it."
                else if (request?.origin != null)
                    "Fill $account and link it to the exact website $destinationName. Nuvori can suggest it there next time."
                else "Fill $account and link it to $destinationName (${request?.packageName.orEmpty()}). Nuvori will also bind the link to the app's current signing certificate.") },
                confirmButton = { Button(enabled = !busy, onClick = { linkAndFill(entry) }) { PickerButtonLabel("Fill and link", busy) } },
                dismissButton = { TextButton(enabled = !busy, onClick = { pendingLoginLink = null }) { Text("Cancel") } },
            )
        }
    }

    @Composable
    private fun DestinationRow(destination: PickerDestination) {
        val icon = remember(destination.packageName) { destination.packageName?.let { appIcon(it) } }
        Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
            else if (destination.packageName != null) AccountLetterAvatar(destination.name, size = 32.dp)
            else Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(destination.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                destination.detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    @Composable
    private fun EmbeddedBrowserWarning(request: LoginFillRequest) {
        StatusBanner(StatusKind.WARNING, title = "Embedded browser",
            message = "${fillDestination(request)} (${request.packageName}) can read what you fill. Choose a login only if you trust this app. Website links do not authorize this app.")
    }

    @Composable
    private fun ColumnScope.UnlockContent(password: String, onPasswordChange: (String) -> Unit, onSubmit: () -> Unit, onFingerprint: () -> Unit) {
        val keyboard = LocalSoftwareKeyboardController.current
        val focus = remember { FocusRequester() }
        var shown by remember { mutableStateOf(false) }
        val fingerprint = remember(busy) { gate.hasValidDailySession && biometricAvailable() }
        // With a usable fingerprint session the prompt comes first; otherwise go straight to typing.
        LaunchedEffect(busy, fingerprint) {
            if (!busy && !fingerprint && keyManager.isInitialized) {
                runCatching { focus.requestFocus() }
                keyboard?.show()
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!keyManager.isInitialized) {
                StatusBanner(StatusKind.INFO, "Open Nuvori to create your vault first.")
            } else {
                Text(if (passkeyOperation != null) "Unlock to review this passkey request. Creation and sign-in require your confirmation."
                    else if (saveMode) "Unlock to review this login. Nothing is saved until you confirm."
                    else if (autofillMode) "Unlock to choose a login. Nothing is filled until you select an account."
                    else "Unlock to search and copy passwords or authenticator codes.", style = MaterialTheme.typography.bodyMedium)
                loginFill?.takeIf { it.embeddedWebView }?.let { EmbeddedBrowserWarning(it) }
                if (fingerprint) FilledTonalButton(onClick = onFingerprint, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Icon(Icons.Outlined.Fingerprint, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text("Use fingerprint")
                }
                OutlinedTextField(password, onPasswordChange, label = { Text("Master password") },
                    singleLine = true, enabled = !busy, isError = unlockFailed,
                    visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { keyboard?.hide(); onSubmit() }),
                    trailingIcon = {
                        IconButton(onClick = { shown = !shown }) {
                            Icon(if (shown) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (shown) "Hide master password" else "Show master password")
                        }
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focus))
                message?.let { StatusBanner(StatusKind.ERROR, it) }
                Button(onClick = { keyboard?.hide(); onSubmit() }, enabled = !busy && password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Unlock", busy) }
                Text("A master password starts a new fingerprint session. Fingerprint use does not extend it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun ColumnScope.FillContent() {
        val request = loginFill ?: return
        var search by remember { mutableStateOf("") }
        var findLogin by remember { mutableStateOf(false) }
        val keyboard = LocalSoftwareKeyboardController.current
        val otp = request.otp != null
        val generation = request.newPasswords.isNotEmpty()
        val searchable = !otp && !generation
        val destination = request.origin?.removePrefix("https://") ?: appLabel(request.packageName) ?: request.packageName
        val authenticatorIds = remember(entries) { entries.filter { it.type == EntryType.AUTHENTICATOR }.map { it.id }.toSet() }
        fun authorized(entry: VaultEntry) = loginAuthorizedForDestination(entry, request.packageName, request.identity, request.origin)
        val eligible = remember(entries) {
            entries.filter { it.type == EntryType.PASSWORD && it.secondaryValue.isNotEmpty() && (!otp || it.linkedAuthenticatorId in authenticatorIds) }
        }
        val matches = eligible.filter { entry -> authorized(entry) &&
            (!otp || search.isBlank() || listOf(entry.title, entry.primaryValue).any { it.contains(search, true) }) }
        val noMatches = matches.isEmpty()
        // Heuristic hints only: every one still goes through the "Fill and link" confirmation.
        val possible = remember(entries, noMatches) {
            if (searchable && noMatches) possibleLoginMatches(eligible, request.packageName, appLabel(request.packageName), request.origin) { authorized(it) }
            else emptyList()
        }
        val showSearch = searchable && (noMatches || findLogin)
        val searched = if (!showSearch) emptyList() else eligible.filter { entry ->
            (search.isNotBlank() || possible.none { it.id == entry.id }) && (search.isBlank() ||
                listOf(entry.title, entry.primaryValue, entry.tertiaryValue, entry.autofillOrigins).any { it.contains(search, ignoreCase = true) })
        }
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (request.embeddedWebView) item(key = "embedded-browser") { EmbeddedBrowserWarning(request) }
            if (otp) item(key = "code-search") {
                OutlinedTextField(search, { search = it }, label = { Text("Search linked codes") },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }), modifier = Modifier.fillMaxWidth())
            }
            if (generation && request.password == null) item(key = "generate-new") {
                Button(onClick = { prepareGeneration(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Generate for a new account") }
            }
            if (noMatches) {
                item(key = "no-matches") {
                    PickerHint(if (otp) {
                        if (search.isBlank()) "No saved login for $destination has a linked authenticator code. Link a code to its login in Nuvori first."
                        else "No linked codes match this search."
                    } else if (eligible.isEmpty()) "You have no saved logins yet. Open Nuvori to add one."
                    else if (searchable) "No saved login is linked to $destination yet. Pick one below or search your logins. Nuvori asks before it links anything."
                    else "No saved login is linked to $destination yet.")
                }
            } else {
                item(key = "suggested-header") { PickerSectionHeader("Suggested for $destination") }
                items(matches, key = { "matched-${it.id}" }) { entry ->
                    val codeLinked = entry.linkedAuthenticatorId.isNotEmpty() && entry.linkedAuthenticatorId in authenticatorIds
                    if (generation) GenerationCard(entry, codeLinked)
                    else AutofillAccountRow(entry, if (otp) "Fill code" else "Fill login", codeLinked) { fillLogin(entry) }
                }
            }
            if (searchable && !noMatches) item(key = "toggle-saved") {
                OutlinedButton(
                    onClick = { findLogin = !findLogin; search = "" },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) { Text(if (findLogin) "Hide saved logins" else "Use another saved login") }
            }
            if (possible.isNotEmpty()) {
                item(key = "possible-header") {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        PickerSectionHeader("Possible matches")
                        PickerHint("These look related to $destination. You confirm before one is filled and linked.")
                    }
                }
                items(possible, key = { "possible-${it.id}" }) { entry ->
                    AutofillAccountRow(entry, "Fill and link", entry.linkedAuthenticatorId in authenticatorIds && entry.linkedAuthenticatorId.isNotEmpty()) {
                        pendingLoginLink = entry
                    }
                }
            }
            if (showSearch) {
                item(key = "login-search") {
                    OutlinedTextField(
                        search,
                        { search = it },
                        label = { Text(if (noMatches) "Search all saved logins" else "Search saved logins") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searched.isEmpty()) item(key = "no-search-results") {
                    PickerHint(if (search.isBlank()) "No other saved logins." else "No saved logins match this search.")
                }
                items(searched, key = { "search-${it.id}" }) { entry ->
                    val linked = authorized(entry)
                    val codeLinked = entry.linkedAuthenticatorId.isNotEmpty() && entry.linkedAuthenticatorId in authenticatorIds
                    AutofillAccountRow(entry, if (linked) "Fill" else "Fill and link", codeLinked) {
                        if (linked) fillLogin(entry) else pendingLoginLink = entry
                    }
                }
            }
            item(key = "hint") {
                PickerHint(if (generation) "Generate a 24-character password and save a separate login before filling. Your current login stays available until you confirm the website accepted the change."
                    else if (otp) "Choose a login to fill its linked authenticator code."
                    else if (passwordCredentialOperation != null) "Choose a login to return through Android Credential Manager."
                    else if (request.password == null) "Choose an account to fill its username. The next screen requires a separate selection."
                    else "Choose a login to fill its username and password.")
            }
            item(key = "open-nuvori") {
                TextButton(onClick = {
                    dismissPicker()
                    startActivity(Intent(this@VaultCodesActivity, MainActivity::class.java))
                }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Open Nuvori to manage passwords") }
            }
        }
    }

    @Composable
    private fun GenerationCard(entry: VaultEntry, codeLinked: Boolean) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    AccountLetterAvatar(entry.title)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(entry.primaryValue.ifBlank { entry.title }, style = MaterialTheme.typography.titleMedium,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (entry.primaryValue.isNotBlank() && entry.title != entry.primaryValue) Text(entry.title,
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (codeLinked) StatusChip(StatusKind.INFO, "Code linked", Modifier.padding(top = 4.dp))
                    }
                }
                Button(onClick = { prepareGeneration(entry) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    Text("Generate new password for this account")
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.SaveContent() {
        val request = loginSave ?: return
        val defaultTitle = remember(request) { request.origin?.removePrefix("https://") ?: appLabel(request.packageName) ?: request.packageName }
        var name by remember(request) { mutableStateOf(defaultTitle) }
        var typedUsername by remember(request) { mutableStateOf("") }
        var showPassword by remember(request) { mutableStateOf(false) }
        // Some forms have no username field. Then the user types one, or updates a login that already has one.
        val needsUsername = request.username.isBlank()
        val candidates = entries.filter { entry ->
            entry.type == EntryType.PASSWORD &&
                (if (needsUsername) entry.primaryValue.isNotBlank() else entry.primaryValue == request.username) &&
                (if (request.origin != null) httpsOrigin(entry.tertiaryValue) == request.origin
                 else loginAuthorized(entry, request.packageName, request.identity))
        }
        val identical = candidates.filter { samePassword(it, request.password) }
        val different = candidates.filter { candidate -> identical.none { it.id == candidate.id } }
        val canCreate = !needsUsername || (typedUsername.isNotBlank() && typedUsername.length <= 1024)
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (needsUsername) OutlinedTextField(typedUsername, { typedUsername = it.take(1024) },
                        label = { Text("Username or email") }, singleLine = true,
                        supportingText = { Text("Required to save a new login.") }, modifier = Modifier.fillMaxWidth())
                    else PickerLabeledValue("Username", request.username)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Password", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Fixed length when hidden: neither the password nor its length belongs on screen.
                            if (showPassword) Text(request.password.concatToString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            else Text("••••••••", Modifier.weight(1f).clearAndSetSemantics { contentDescription = "Password hidden" },
                                style = MaterialTheme.typography.bodyLarge)
                            TextButton(onClick = { showPassword = !showPassword }, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(if (showPassword) "Hide" else "Show")
                            }
                        }
                    }
                    OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth())
                }
            }
            if (identical.isNotEmpty()) {
                StatusBanner(StatusKind.SUCCESS, "This password is already saved in Nuvori.")
                Button(onClick = ::dismissPicker, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Done") }
                OutlinedButton(enabled = !busy && canCreate, onClick = { saveLogin(null, typedUsername, name) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Save as new login", busy && savingId == "new") }
            } else {
                if (different.isNotEmpty()) {
                    StatusBanner(StatusKind.INFO, if (different.size == 1) "This account is already saved with a different password. Update it, or keep both."
                        else "These logins for this account have a different password. Update one, or keep both.")
                    different.forEach { entry ->
                        key(entry.id) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                if (different.size == 1) Button(enabled = !busy, onClick = { selectedUpdate = entry },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Update ${entry.title}", busy && savingId == entry.id) }
                                else FilledTonalButton(enabled = !busy, onClick = { selectedUpdate = entry },
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Update ${entry.title}", busy && savingId == entry.id) }
                                if (needsUsername) PickerHint("Username: ${entry.primaryValue}")
                            }
                        }
                    }
                }
                if (different.isEmpty()) Button(enabled = !busy && canCreate, onClick = { saveLogin(null, typedUsername, name) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Save as new login", busy && savingId == "new") }
                else OutlinedButton(enabled = !busy && canCreate, onClick = { saveLogin(null, typedUsername, name) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { PickerButtonLabel("Save as new login", busy && savingId == "new") }
            }
        }
    }

    private fun samePassword(entry: VaultEntry, password: CharArray): Boolean =
        entry.secondaryValue.length == password.size && password.indices.all { entry.secondaryValue[it] == password[it] }

    @androidx.annotation.RequiresApi(34)
    @Composable
    private fun ColumnScope.PasskeyContent(operation: PasskeyOperation) {
        val source = remember(operation) { operation.displaySource(this@VaultCodesActivity) }
        if (operation.create) {
            val rp = remember(operation) { runCatching { operation.input.getJSONObject("rp") }.getOrNull() }
            val user = remember(operation) { runCatching { operation.input.getJSONObject("user") }.getOrNull() }
            val rpId = rp?.optString("id").orEmpty()
            val rpName = rp?.optString("name").orEmpty()
            val userName = user?.optString("name").orEmpty()
            val displayName = user?.optString("displayName").orEmpty()
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PickerKeyBadge()
                            Text("New passkey", style = MaterialTheme.typography.titleMedium)
                        }
                        PickerLabeledValue("Website or app", if (rpName.isNotBlank() && rpName != rpId) "$rpName ($rpId)" else rpId)
                        PickerLabeledValue("Account", if (displayName.isNotBlank() && displayName != userName) "$displayName ($userName)" else userName)
                        PickerHint(source)
                    }
                }
                PickerHint("Keep an encrypted backup to transfer it to another phone.")
                Button(enabled = !busy, onClick = { usePasskey(null) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                    PickerButtonLabel("Create passkey", busy)
                }
            }
        } else {
            val matches = remember(passkeys, operation) { passkeys.filter { com.privatevault.app.passkeys.PasskeyCrypto.matches(it, operation.input) } }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { PickerHint(source) }
                if (matches.isEmpty()) item { Text("No matching passkeys in this vault.") }
                items(matches, key = { it.id }) { passkey -> PickerPasskeyRow(passkey, !busy) { usePasskey(passkey) } }
                if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun PickerKeyBadge() {
    Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer).clearAndSetSemantics { },
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun PickerPasskeyRow(passkey: VaultPasskey, enabled: Boolean, onClick: () -> Unit) {
    val account = passkey.username.ifBlank { passkey.displayName.ifBlank { passkey.rpId } }
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()
        .semantics { role = Role.Button; contentDescription = "Sign in as $account on ${passkey.rpId}" }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PickerKeyBadge()
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(passkey.rpId, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Sign in", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

@Composable
private fun PickerLabeledValue(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun PickerSectionHeader(text: String) {
    Text(text, Modifier.padding(top = 4.dp).semantics { heading() }, style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PickerHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Button label that shows a small spinner while that button's action is running. */
@Composable
private fun RowScope.PickerButtonLabel(text: String, spinning: Boolean) {
    if (spinning) {
        CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
    }
    Text(text)
}
