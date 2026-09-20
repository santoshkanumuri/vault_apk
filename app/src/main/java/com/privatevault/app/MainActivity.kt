package com.privatevault.app

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.PersistableBundle
import android.view.WindowManager
import androidx.activity.compose.setContent
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
    private lateinit var viewModel: VaultViewModel
    private var screenOffReceiver: BroadcastReceiver? = null
    private val biometricGate by lazy { BiometricGate(applicationContext) }
    private val nfcReader by lazy { NfcCardReader(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
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
                onCopySecret = ::copySecret
            )
        }
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
                }
            })
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Enable fingerprint for 24 hours")
            .setSubtitle("Confirm once, then use fingerprint for the rest of the day")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText("Not now")
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun showBiometricAction(onSuccess: () -> Unit) {
        val cipher = runCatching { BiometricActionGate().encryptionCipher() }.getOrNull() ?: return
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    onSuccess()
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
        val clip = ClipData.newPlainText(label, value)
        if (Build.VERSION.SDK_INT >= 33) {
            clip.description.extras = PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        window.decorView.postDelayed({
            if (clipboard.primaryClip?.getItemAt(0)?.text?.toString() == value) clipboard.clearPrimaryClip()
        }, 30_000)
    }
}
