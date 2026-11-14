package com.privatevault.app

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.backup.DriveBackupFile
import com.privatevault.app.backup.DriveBackupPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private val backupDialogModifier = Modifier.imePadding().widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp)
private val backupDialogProperties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)

private fun intervalLabel(interval: Long) = when (interval) {
    DriveBackupPlan.HOUR -> "Hour"
    DriveBackupPlan.DAY -> "Day"
    DriveBackupPlan.WEEK -> "Week"
    else -> "Month"
}

private fun intervalSentence(interval: Long) = when (interval) {
    DriveBackupPlan.HOUR -> "every hour"
    DriveBackupPlan.DAY -> "every day"
    DriveBackupPlan.WEEK -> "every week"
    else -> "every month"
}

/**
 * Runs Google sign-in for Drive backup. [start] shows Google's screen only when Google asks for
 * one, then runs [onSignedIn] once the account is ready.
 */
@Composable
private fun rememberDriveSignIn(viewModel: VaultViewModel, onSignedIn: suspend () -> Unit): Pair<Boolean, () -> Unit> {
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    val latest by rememberUpdatedState(onSignedIn)
    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        viewModel.externalFlowActive = false
        // Google also closes its screen with RESULT_CANCELED when sign-in fails, with the reason in the answer.
        scope.launch {
            try { viewModel.signInForDriveBackup(result.data, fromGoogle = true) { latest() } } finally { waiting = false }
        }
    }
    val start: () -> Unit = {
        waiting = true
        scope.launch {
            var opened = false
            try {
                viewModel.signInForDriveBackup { latest() }?.let { screen ->
                    viewModel.externalFlowActive = true
                    googleScreen.launch(IntentSenderRequest.Builder(screen).build())
                    opened = true
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) {
                viewModel.externalFlowActive = false
                viewModel.notify("Couldn't open Google sign-in. Try again.", StatusKind.ERROR)
            } finally { if (!opened) waiting = false }
        }
    }
    return waiting to start
}

@Composable
private fun IntervalChips(selected: Long, onSelect: (Long) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DriveBackupPlan.INTERVALS.forEach { option ->
            FilterChip(selected = selected == option, onClick = { if (selected != option) onSelect(option) },
                label = { Text(intervalLabel(option)) }, modifier = Modifier.weight(1f))
        }
    }
}

/** Scheduled `.pvault` backups to the hidden Nuvori folder in the user's Google Drive. */
@Composable
internal fun DriveBackupCard(viewModel: VaultViewModel) {
    val status by viewModel.driveBackupStatus.collectAsStateWithLifecycle()
    var chosenInterval by remember { mutableLongStateOf(DriveBackupPlan.DEFAULT_INTERVAL) }
    var askPassword by remember { mutableStateOf(false) }
    var password by remember { mutableStateOf("") }
    var confirmOff by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.lastBackupAt) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
    // Turning on asks for the master password next; signing in again only retries the backup.
    val (waiting, signIn) = rememberDriveSignIn(viewModel) {
        if (status.enabled) viewModel.backUpToDriveNow() else askPassword = true
    }

    val (chipKind, chipLabel) = when {
        status.running -> StatusKind.PROGRESS to "Backing up"
        status.needsSignIn -> StatusKind.WARNING to "Sign in again"
        status.lastError != null -> StatusKind.WARNING to "Needs attention"
        else -> StatusKind.SUCCESS to "On"
    }
    HairlineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIconBadge(NuvoriIcons.Cloud)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Google Drive backup", modifier = Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleMedium)
                    Text(if (status.enabled && status.account.isNotEmpty()) status.account
                        else "Restore your vault on a new phone if this one is lost.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (status.enabled) StatusChip(chipKind, chipLabel)
            }
            if (!status.enabled) {
                Text("Nuvori saves an encrypted backup in a hidden folder of your Google Drive. It works without a sync group. On a new phone, sign in with the same Google account and restore it with your master password.",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Back up every", style = MaterialTheme.typography.labelLarge)
                IntervalChips(chosenInterval) { chosenInterval = it }
                Text("Backups run while Nuvori is unlocked. If the phone stays locked, the backup runs at the next unlock. Nuvori keeps this phone's newest backup and the one before it.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsPrimaryButton(if (waiting) "Waiting for Google…" else "Turn on Google Drive backup",
                    onClick = signIn, icon = NuvoriIcons.Cloud, enabled = !waiting)
            } else {
                if (status.needsSignIn) StatusBanner(kind = StatusKind.WARNING, title = "Google needs you to sign in again",
                    message = "Access was removed or expired. Your vault on this phone is safe.",
                    actionLabel = if (waiting) null else "Sign in again", onAction = signIn)
                else status.lastError?.let { StatusBanner(kind = StatusKind.WARNING, title = "Last backup didn't finish", message = it) }
                Text("Last backup: " + if (status.lastBackupAt > 0) relativeTime(status.lastBackupAt, now) else "not yet",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Back up every", style = MaterialTheme.typography.labelLarge)
                IntervalChips(status.interval, viewModel::setDriveBackupInterval)
                Text("Backs up ${intervalSentence(status.interval)} while Nuvori is unlocked. A backup opens with your master password.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsPrimaryButton(if (status.running) "Backing up…" else "Back up now", icon = NuvoriIcons.Upload,
                    enabled = !status.running, onClick = viewModel::backUpToDriveNow)
                SettingsSecondaryButton("Turn off", icon = NuvoriIcons.LogOut, onClick = { confirmOff = true })
            }
        }
    }
    if (askPassword) AlertDialog(
        modifier = backupDialogModifier,
        properties = backupDialogProperties,
        onDismissRequest = { askPassword = false; password = "" },
        title = { Text("Protect Drive backups") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Backups open with your current master password. You need it to restore on a new phone, because Nuvori cannot recover it.")
            SecretField("Current master password", password) { password = it }
        } },
        confirmButton = { Button(enabled = password.isNotEmpty(), onClick = {
            viewModel.turnOnDriveBackup(password.toCharArray(), chosenInterval)
            password = ""; askPassword = false
        }) { Text("Turn on") } },
        dismissButton = { TextButton(onClick = { askPassword = false; password = "" }) { Text("Cancel") } }
    )
    if (confirmOff) AlertDialog(onDismissRequest = { confirmOff = false },
        title = { Text("Turn off Google Drive backup?") },
        text = { Text("This phone stops making backups. The backups already in your Drive stay there, and you can still restore them. To delete them, remove Nuvori's hidden app data in your Google Drive settings.") },
        confirmButton = { TextButton(onClick = { confirmOff = false; viewModel.turnOffDriveBackup() }) { Text("Turn off") } },
        dismissButton = { TextButton(onClick = { confirmOff = false }) { Text("Cancel") } })
}

/**
 * Finds the backups in the Google Drive of the signed-in account and restores one after the usual
 * review. The restored vault gets a new vault ID, so it is not part of any sync chain.
 */
@Composable
internal fun DriveRestoreSection(viewModel: VaultViewModel, primary: Boolean) {
    val backups by viewModel.driveBackups.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<DriveBackupFile?>(null) }
    var password by remember { mutableStateOf("") }
    val (waiting, signIn) = rememberDriveSignIn(viewModel) { viewModel.findDriveBackups() }
    SettingsSection("Restore from Google Drive",
        description = "Use a backup that Nuvori saved in your Google Drive. Sign in with the Google account of your old phone.") {
        val label = if (waiting) "Looking in Google Drive…" else "Find backups in Google Drive"
        if (primary) SettingsPrimaryButton(label, icon = NuvoriIcons.Cloud, enabled = !waiting, onClick = signIn)
        else SettingsSecondaryButton(label, icon = NuvoriIcons.Cloud, enabled = !waiting, onClick = signIn)
    }
    val found = backups ?: return
    LaunchedEffect(found) { selected = found.firstOrNull() }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
    AlertDialog(
        modifier = backupDialogModifier,
        properties = backupDialogProperties,
        onDismissRequest = { viewModel.closeDriveBackups(); password = "" },
        title = { Text(if (found.isEmpty()) "No backups found" else "Restore a backup") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (found.isEmpty()) {
                Text("This Google account has no Nuvori backups. Check that you signed in with the account of your old phone, and that Google Drive backup was on there.")
                return@Column
            }
            Text("Choose a backup. It replaces this vault after you review it.")
            Column(Modifier.selectableGroup()) {
                // Several phones can each keep two backups; the newest is first.
                val phones = found.map { it.backupId }.distinct()
                found.forEach { backup ->
                    val phone = if (phones.size > 1) " · Phone ${phones.indexOf(backup.backupId) + 1}" else ""
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected == backup, role = Role.RadioButton) { selected = backup },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RadioButton(selected = selected == backup, onClick = null)
                        Column {
                            Text(dateFormat.format(Date(backup.createdAt)), style = MaterialTheme.typography.bodyLarge)
                            Text(Formatter.formatShortFileSize(context, backup.size) + phone,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            SecretField("Master password of the backup", password) { password = it }
            Text("This is the master password of your old phone when the backup was made.",
                style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = {
            if (found.isEmpty()) TextButton(onClick = { viewModel.closeDriveBackups() }) { Text("Close") }
            else Button(enabled = selected != null && password.isNotEmpty(), onClick = {
                selected?.let { viewModel.restoreFromDrive(it, password.toCharArray()) }
                password = ""
            }) { Text("Check backup") }
        },
        dismissButton = {
            if (found.isNotEmpty()) TextButton(onClick = { viewModel.closeDriveBackups(); password = "" }) { Text("Cancel") }
        }
    )
}
