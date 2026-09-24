package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.privatevault.app.sync.LanSyncService
import com.privatevault.app.sync.MAX_ACTIVE_SYNC_DEVICES
import com.privatevault.app.sync.MemberStatus

@Composable
internal fun DeviceSyncSettings(viewModel: VaultViewModel, copyLink: (String) -> Unit) {
    val state by viewModel.devicePairingState.collectAsStateWithLifecycle()
    val devices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val syncStatus by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    val conflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val rejected by viewModel.rejectedSyncChanges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeDeviceCount = 1 + devices.count { it.status == MemberStatus.ACTIVE.name }
    var link by remember { mutableStateOf("") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var scanner by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }
    var interval by remember { mutableLongStateOf(LanSyncService.syncInterval(context)) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        scanner = allowed
        permissionDenied = !allowed
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationDenied = !allowed
        if (allowed) viewModel.resumeDeviceSync()
    }
    DisposableEffect(Unit) { onDispose { viewModel.cancelDevicePairing() } }

    Text("Android devices", style = MaterialTheme.typography.titleLarge)
    Text("$activeDeviceCount of $MAX_ACTIVE_SYNC_DEVICES active · Same Wi-Fi required",
        style = MaterialTheme.typography.bodySmall)
    Text("Keep both phones unlocked during setup. The receiving phone needs an empty vault and its own master password.",
        style = MaterialTheme.typography.bodySmall)
    if (state.message.isNotBlank()) Text(state.message,
        color = if (state.stage == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Pair a device", style = MaterialTheme.typography.titleMedium)
            when (state.stage) {
                "offering" -> {
                    val bitmap = remember(state.invitation) {
                        val matrix = QRCodeWriter().encode(state.invitation, BarcodeFormat.QR_CODE, 600, 600)
                        Bitmap.createBitmap(600, 600, Bitmap.Config.ARGB_8888).apply {
                            setPixels(IntArray(600 * 600) { index ->
                                if (matrix[index % 600, index / 600]) android.graphics.Color.BLACK
                                else android.graphics.Color.WHITE
                            }, 0, 600, 0, 0, 600, 600)
                        }
                    }
                    Image(bitmap.asImageBitmap(), "Temporary pairing QR code",
                        Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f))
                    Text("This link grants vault access after confirmation. Share it only with the device you are pairing.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { copyLink(state.invitation) }, modifier = Modifier.weight(1f)) {
                            Text("Copy link")
                        }
                        OutlinedButton(onClick = viewModel::cancelDevicePairing, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }
                "confirm" -> {
                    Text(state.confirmation, style = MaterialTheme.typography.headlineLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::approveDevicePairing, modifier = Modifier.weight(1f)) {
                            Text("Codes match")
                        }
                        OutlinedButton(onClick = viewModel::cancelDevicePairing, modifier = Modifier.weight(1f)) {
                            Text("Cancel")
                        }
                    }
                }
                "connecting", "transferring" -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = viewModel::cancelDevicePairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = viewModel::hostDevicePairing, modifier = Modifier.weight(1f)) {
                            Text(if (activeDeviceCount < MAX_ACTIVE_SYNC_DEVICES) "Connect" else "Reconnect")
                        }
                        OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED) scanner = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }, modifier = Modifier.weight(1f)) { Text("Scan QR") }
                    }
                    if (permissionDenied) Text("Camera access was denied. Paste the setup link instead.")
                    TextButton(onClick = { showLink = !showLink }) {
                        Text(if (showLink) "Hide setup link" else "Use setup link instead")
                    }
                    if (showLink) {
                        OutlinedTextField(link, { link = it }, label = { Text("Setup link from the other device") },
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth(), maxLines = 3)
                        OutlinedButton(onClick = { viewModel.joinDevicePairing(link); link = "" },
                            enabled = link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Join that vault")
                        }
                    }
                }
            }
        }
    }

    if (devices.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sync", style = MaterialTheme.typography.titleMedium)
                Text(syncStatus)
                Text("Checks paired devices on Wi-Fi while the sync notification is active. Locked devices hold encrypted changes until unlock. Large photos resume after interruptions.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Check interval", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    LanSyncService.SYNC_INTERVALS.forEach { option ->
                        val label = when (option) {
                            30_000L -> "30s"
                            60_000L -> "1m"
                            300_000L -> "5m"
                            else -> "15m"
                        }
                        FilterChip(selected = interval == option, onClick = {
                            interval = option
                            viewModel.setDeviceSyncInterval(option)
                        }, label = { Text(label) }, modifier = Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = viewModel::syncDevicesNow, modifier = Modifier.weight(1f)) {
                        Text("Sync now")
                    }
                    OutlinedButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else viewModel.resumeDeviceSync()
                    }, modifier = Modifier.weight(1f)) { Text("Resume auto") }
                }
                TextButton(onClick = viewModel::pauseDeviceSync) { Text("Pause automatic sync") }
                if (notificationDenied) Text("Allow notifications to keep automatic sync visible while Nuvori is closed.")
            }
        }
        Text("Paired devices", style = MaterialTheme.typography.titleMedium)
        devices.forEach { device ->
            var address by rememberSaveable(device.deviceId) { mutableStateOf("") }
            var showAddress by rememberSaveable(device.deviceId) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${device.displayName} · ${device.deviceId.take(8)}", style = MaterialTheme.typography.titleSmall)
                    Text(viewModel.deviceSyncProgress(device.deviceId), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showAddress = !showAddress }) {
                        Text(if (showAddress) "Hide address" else "Connection help")
                    }
                    if (showAddress) {
                        Text("If this device stays at Looking, enter its IP from Android Wi-Fi details.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(address, { address = it },
                            label = { Text("Paired device's Wi-Fi IP address") },
                            singleLine = true, modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { viewModel.setDeviceSyncAddress(device.deviceId, address) },
                            enabled = address.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                            Text("Connect using this address")
                        }
                    }
                }
            }
        }
    }

    if (rejected > 0) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Sync needs attention", style = MaterialTheme.typography.titleMedium)
                Text("$rejected encrypted changes could not be applied. Check that both devices are updated.")
                OutlinedButton(onClick = viewModel::retryRejectedSyncChanges) { Text("Retry changes") }
            }
        }
    }
    if (conflicts.isNotEmpty()) {
        Text("Conflicts to review (${conflicts.size})", style = MaterialTheme.typography.titleMedium)
        Text("Both versions changed without seeing each other. Choosing one replaces the whole record. Other conflicts may still need review.",
            style = MaterialTheme.typography.bodySmall)
        conflicts.forEach { conflict ->
            var expanded by rememberSaveable(conflict.id) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(conflict.type.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
                    Text("On this device: ${conflict.current.description.lineSequence().firstOrNull().orEmpty()}")
                    Text("Incoming: ${conflict.incoming.description.lineSequence().firstOrNull().orEmpty()}")
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Hide version details" else "Compare versions")
                    }
                    if (expanded) {
                        Text("On this device · ${conflict.current.device}", style = MaterialTheme.typography.titleSmall)
                        Text("Reported ${conflict.current.reportedAtUtc}", style = MaterialTheme.typography.bodySmall)
                        Text(conflict.current.description)
                        Text("Incoming · ${conflict.incoming.device}", style = MaterialTheme.typography.titleSmall)
                        Text("Reported ${conflict.incoming.reportedAtUtc}", style = MaterialTheme.typography.bodySmall)
                        Text(conflict.incoming.description)
                        Text("Device clocks may differ. Passwords, code setup keys, and passkey private keys stay hidden.",
                            style = MaterialTheme.typography.bodySmall)
                        if (conflict.current.deleted || conflict.incoming.deleted)
                            Text("Choosing Deleted removes the entire record on paired devices.",
                                color = MaterialTheme.colorScheme.error)
                        if (conflict.type == "passkey")
                            Text("An incoming passkey cannot replace a different credential with the same ID. Keep this device's passkey and register a new one on the website.",
                                style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = {
                                viewModel.resolveSyncConflict(conflict.id, false, conflict.currentVersion)
                            }, modifier = Modifier.weight(1f)) { Text("Keep this") }
                            if (conflict.type != "passkey")
                                Button(onClick = {
                                    viewModel.resolveSyncConflict(conflict.id, true, conflict.currentVersion)
                                }, modifier = Modifier.weight(1f)) { Text("Use incoming") }
                        }
                    }
                }
            }
        }
    }
    if (scanner) QrScanner(onResult = { value -> scanner = false; viewModel.joinDevicePairing(value) },
        close = { scanner = false }, title = "Scan Nuvori pairing QR",
        help = "Scan the QR shown on the other device. Keep both devices unlocked on the same Wi-Fi.")
}
