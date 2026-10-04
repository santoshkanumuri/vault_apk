package com.privatevault.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.service.quicksettings.TileService
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.combine
import com.privatevault.app.nfc.NfcCardReader
import com.privatevault.app.security.BiometricGate
import com.privatevault.app.security.BiometricActionGate

class MainActivity : FragmentActivity() {
    companion object {
        const val ACTION_OPEN_SYNC_SETTINGS = "com.privatevault.app.OPEN_SYNC_SETTINGS"
    }

    private lateinit var viewModel: VaultViewModel
    private var syncSettingsRequested by mutableStateOf(false)
    private var screenOffReceiver: BroadcastReceiver? = null
    private val biometricGate by lazy { BiometricGate(applicationContext) }
    private val nfcReader by lazy { NfcCardReader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        syncSettingsRequested = opensSyncSettings(intent)
        val emulatorDebug = applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0 &&
            Build.FINGERPRINT.contains("sdk_gphone")
        if (!emulatorDebug) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        if (Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(false)
        viewModel = ViewModelProvider(this)[VaultViewModel::class.java]
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                combine(viewModel.nfcScanning, viewModel.nfcEnabled, viewModel.status) { scanning, enabled, state ->
                    scanning && enabled && state is VaultStatus.Unlocked
                }.collect { active ->
                    if (active) nfcReader.start(viewModel::canReadNfc, viewModel::finishNfcScan, viewModel::failNfcScan)
                    else nfcReader.stop()
                }
            }
        }
        screenOffReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                viewModel.lock(LockReason.SCREEN_OFF)
            }
        }.also { registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_OFF)) }
        setContent {
            PrivateVaultApp(
                viewModel = viewModel,
                biometricAvailable = biometricAvailable(),
                onBiometricUnlock = ::showBiometricPrompt,
                onEnableDailyBiometric = ::enableDailyBiometric,
                onBiometricAction = ::showBiometricAction,
                onCopySecret = ::copySecret,
                openSyncSettings = syncSettingsRequested,
                onSyncSettingsOpened = { syncSettingsRequested = false }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (opensSyncSettings(intent)) syncSettingsRequested = true
    }

    @Suppress("DEPRECATION")
    private fun opensSyncSettings(intent: Intent?): Boolean {
        if (intent?.action == ACTION_OPEN_SYNC_SETTINGS) return true
        if (intent?.action != TileService.ACTION_QS_TILE_PREFERENCES) return false
        val source = intent.getParcelableExtra<ComponentName>(Intent.EXTRA_COMPONENT_NAME)
        return source == null || source.className == AutoSyncTileService::class.java.name
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) viewModel.onAppBackgrounded()
    }

    override fun onPause() {
        nfcReader.stop()
        viewModel.cancelNfcScan()
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        viewModel.onAppForegrounded()
    }

    override fun onDestroy() {
        nfcReader.stop()
        screenOffReceiver?.let { unregisterReceiver(it) }
        super.onDestroy()
    }

    private fun biometricDismissed(errorCode: Int) = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON ||
        errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED

    private fun biometricAvailable(): Boolean = BiometricManager.from(this)
        .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    private fun showBiometricPrompt() {
        val cipher = biometricGate.dailyDecryptionCipher() ?: run {
            viewModel.requireMasterPasswordForBiometric()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    val authenticatedCipher = result.cryptoObject?.cipher ?: return
                    runCatching { biometricGate.finishDailyUnlock(authenticatedCipher) }
                        .onSuccess(viewModel::unlockWithBiometric)
                        .onFailure { biometricGate.clearDailySession(); viewModel.requireMasterPasswordForBiometric() }
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Nuvori")
            .setSubtitle("Confirm your fingerprint")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Use master password")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun enableDailyBiometric() {
        if (!biometricAvailable()) {
            viewModel.skipDailyBiometric()
            return
        }
        val cipher = viewModel.dailyBiometricEncryptionCipher() ?: run {
            viewModel.skipDailyBiometric()
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    result.cryptoObject?.cipher?.let(viewModel::enableDailyBiometric)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    viewModel.skipDailyBiometric()
                    if (!biometricDismissed(errorCode))
                        viewModel.notify("Fingerprint unlock was not turned on. Use the master password next time.", StatusKind.INFO)
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable fingerprint unlock")
            .setSubtitle("Confirm your fingerprint to protect the vault key")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Not now")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showBiometricAction(onSuccess: () -> Unit) {
        val cipher = runCatching { BiometricActionGate().encryptionCipher() }.getOrNull() ?: run {
            viewModel.notify("Fingerprint check is unavailable on this device.", StatusKind.WARNING)
            return
        }
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (!biometricDismissed(errorCode))
                        viewModel.notify(errString.toString().ifBlank { "Fingerprint check did not complete." }, StatusKind.WARNING)
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Reveal CVV")
            .setSubtitle("Confirm your fingerprint")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Cancel")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun copySecret(label: String, value: String) {
        val clipboard = getSystemService<ClipboardManager>() ?: return
        val token = java.util.UUID.randomUUID().toString()
        val clip = ClipData.newPlainText(label, value)
        clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
            putString("vault_clip_token", token)
        }
        if (runCatching { clipboard.setPrimaryClip(clip) }.isFailure) {
            viewModel.notify("Could not copy $label.", StatusKind.ERROR)
            return
        }
        // Android may deny background clipboard access. Never erase a newer clipboard item.
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching {
                if (clipboard.primaryClipDescription?.extras?.getString("vault_clip_token") == token) clipboard.clearPrimaryClip()
            }
        }, 30_000)
        viewModel.notify("$label copied", StatusKind.SUCCESS)
    }
}
