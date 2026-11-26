package com.privatevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.controls.Button
import com.privatevault.app.controls.TextButton

/**
 * The managing device changed the shared master password ([VaultViewModel.masterPasswordSwitch]): asks for the
 * new one once per unlock. The banner ([MasterPasswordSwitchBanner]) opens it again.
 */
@Composable
internal fun MasterPasswordSwitchPrompt(viewModel: VaultViewModel) {
    val pending by viewModel.masterPasswordSwitch.collectAsStateWithLifecycle()
    // Kept while the vault stays unlocked: a later unlock asks again.
    var dismissedEpoch by remember { mutableStateOf<Long?>(null) }
    val current = pending ?: return
    if (dismissedEpoch == current.epoch) return
    MasterPasswordSwitchDialog(viewModel, current, onClose = { dismissedEpoch = current.epoch })
}

/** For Home and Devices & sync while a switch is owed. */
@Composable
internal fun MasterPasswordSwitchBanner(viewModel: VaultViewModel, modifier: Modifier = Modifier) {
    val pending by viewModel.masterPasswordSwitch.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val current = pending ?: return
    StatusBanner(kind = StatusKind.WARNING, modifier = modifier,
        title = stringResource(R.string.master_password_switch_banner_title),
        message = stringResource(R.string.master_password_switch_banner, managerOf(current)),
        actionLabel = stringResource(R.string.master_password_switch_banner_action), onAction = { open = true })
    if (open) MasterPasswordSwitchDialog(viewModel, current, onClose = { open = false })
}

@Composable
private fun managerOf(pending: MasterPasswordSwitchState) =
    pending.managerName.ifBlank { stringResource(R.string.master_password_switch_manager_fallback) }

@Composable
private fun MasterPasswordSwitchDialog(viewModel: VaultViewModel, pending: MasterPasswordSwitchState, onClose: () -> Unit) {
    var password by remember { mutableStateOf("") }
    val manager = managerOf(pending)
    val checking by viewModel.switchingMasterPassword.collectAsStateWithLifecycle()
    val error by viewModel.masterPasswordSwitchError.collectAsStateWithLifecycle()
    val submit = {
        if (password.isNotEmpty() && !checking) {
            viewModel.switchMasterPassword(password.toCharArray())
            password = ""
        }
    }
    val close = { if (!checking) { password = ""; viewModel.clearMasterPasswordSwitchError(); onClose() } }
    AlertDialog(
        modifier = Modifier.imePadding().widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp),
        // No screenshots or screen recording while a password is on screen, as in the other password dialogs.
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = close,
        title = { Text(stringResource(R.string.master_password_switch_title)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.master_password_switch_body, manager))
            SecretField(stringResource(R.string.master_password_switch_field), password, error = error, onDone = submit) { password = it }
            Text(stringResource(R.string.master_password_switch_note), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = submit, enabled = password.isNotEmpty() && !checking) {
            Text(stringResource(if (checking) R.string.master_password_switch_checking else R.string.master_password_switch_confirm))
        } },
        dismissButton = { TextButton(onClick = close, enabled = !checking) { Text(stringResource(R.string.master_password_switch_later)) } },
    )
}
