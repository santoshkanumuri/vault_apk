package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.InetAddresses
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.privatevault.app.sync.LanSyncService
import com.privatevault.app.sync.DeviceSyncPhase
import com.privatevault.app.sync.DeviceSyncStatus
import com.privatevault.app.sync.MAX_ACTIVE_SYNC_DEVICES
import com.privatevault.app.sync.MemberStatus
import com.privatevault.app.sync.isPrivateAddress
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@Composable
private fun DeviceSyncState(status: DeviceSyncStatus) {
    val colors = MaterialTheme.colorScheme
    val (background, foreground) = when (status.phase) {
        DeviceSyncPhase.ATTENTION -> colors.errorContainer to colors.onErrorContainer
        DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING, DeviceSyncPhase.TRANSFERRING ->
            colors.primaryContainer to colors.onPrimaryContainer
        DeviceSyncPhase.RECEIVED, DeviceSyncPhase.CHECKED ->
            colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceVariant to colors.onSurfaceVariant
    }
    Surface(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = MaterialTheme.shapes.small, color = background) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status.phase.title, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold, color = foreground)
            Text(status.detail, style = MaterialTheme.typography.bodySmall, color = foreground)
            if (status.phase == DeviceSyncPhase.CONNECTING || status.phase == DeviceSyncPhase.TRANSFERRING)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp), color = colors.primary)
        }
    }
}

@Composable
internal fun DeviceSyncSettings(viewModel: VaultViewModel, copyLink: (String) -> Unit) {
    val state by viewModel.devicePairingState.collectAsStateWithLifecycle()
    val devices by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val canRemoveOnlyPeer by viewModel.canRemoveOnlyPeer.collectAsStateWithLifecycle()
    val canHostPairing by viewModel.canHostDevicePairing.collectAsStateWithLifecycle()
    val managerDeviceId by viewModel.syncManagerDeviceId.collectAsStateWithLifecycle()
    val syncStatus by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    val peerStatuses by viewModel.devicePeerStatus.collectAsStateWithLifecycle()
    val conflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val rejected by viewModel.rejectedSyncChanges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeDeviceCount = 1 + devices.count { it.status == MemberStatus.ACTIVE.name }
    val connectedCount = devices.count { peerStatuses[it.deviceId]?.phase == DeviceSyncPhase.TRANSFERRING }
    var link by remember { mutableStateOf("") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var scanner by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(false) }
    var removeDeviceId by remember { mutableStateOf<String?>(null) }
    var verifyPairing by remember { mutableStateOf(false) }
    var pairingPassword by remember { mutableStateOf("") }
    var verifyingPassword by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
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
    if (devices.isEmpty()) Text("Keep both devices unlocked during setup. The receiving device needs an empty vault. Its password stays local.",
        style = MaterialTheme.typography.bodySmall)
    Text(when {
        managerDeviceId == null && canHostPairing -> "This device will manage the sync group when you connect another device."
        canHostPairing -> "This is the managing device. Approve new devices here."
        else -> "Managing device: ${devices.firstOrNull { it.deviceId == managerDeviceId }?.displayName ?: "unavailable"}. Add devices there."
    }, style = MaterialTheme.typography.bodySmall)
    if (devices.isNotEmpty()) Text("Changes stay on this device while offline and sync when a paired device is reachable.",
        style = MaterialTheme.typography.bodySmall)
    if (state.message.isNotBlank()) Text(state.message,
        color = if (state.stage == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)

    if (devices.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Device sync", style = MaterialTheme.typography.titleMedium)
            Text("$connectedCount connected now · ${activeDeviceCount - 1} paired",
                style = MaterialTheme.typography.labelMedium)
            DeviceSyncState(syncStatus)
            viewModel.deviceSyncQueueStatus()?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::syncDevicesNow, modifier = Modifier.weight(1f)) {
                    Text("Sync now")
                }
                if (LanSyncService.isPaused(context) || notificationDenied ||
                    syncStatus.phase == DeviceSyncPhase.OFF || syncStatus.phase == DeviceSyncPhase.PAUSED) {
                    OutlinedButton(onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= 33 &&
                            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                            PackageManager.PERMISSION_GRANTED)
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else viewModel.resumeDeviceSync()
                    }, modifier = Modifier.weight(1f)) { Text("Resume auto") }
                } else {
                    OutlinedButton(onClick = viewModel::pauseDeviceSync, modifier = Modifier.weight(1f)) {
                        Text("Pause auto")
                    }
                }
            }
            Text("Check every", style = MaterialTheme.typography.labelMedium)
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
            Text("Automatic checks run while the sync notification is active. Received changes apply after unlock.",
                style = MaterialTheme.typography.bodySmall)
            Text("Connections are brief. A completed check does not mean the other device has applied every change.",
                style = MaterialTheme.typography.bodySmall)
            if (notificationDenied) Text("Allow notifications to keep automatic sync active when Nuvori is closed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (devices.isNotEmpty() && canHostPairing && !showPairing && state.stage in setOf("idle", "paired"))
        TextButton(onClick = { showPairing = true }) { Text("Add another device") }

    if (devices.isEmpty() || showPairing || state.stage !in setOf("idle", "paired")) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Pair a device", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (devices.isNotEmpty() && state.stage in setOf("idle", "paired"))
                    TextButton(onClick = { showPairing = false }) { Text("Close") }
            }
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
                        Button(onClick = { passwordError = null; verifyPairing = true },
                            enabled = canHostPairing, modifier = Modifier.weight(1f)) {
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
        Text("Paired devices", style = MaterialTheme.typography.titleMedium)
        if (activeDeviceCount > 2) Text(
            "Secure removal from a group of three or four devices is not available yet. Keep each device trusted until key rotation can reach the others.",
            style = MaterialTheme.typography.bodySmall)
        devices.forEach { device ->
            val peerStatus = peerStatuses[device.deviceId]
            val (lastContact, lastExchange) = viewModel.deviceSyncContact(device.deviceId)
            val peerLabel = when (peerStatus?.phase) {
                DeviceSyncPhase.CONNECTING -> "Connecting"
                DeviceSyncPhase.TRANSFERRING -> "Connected now"
                DeviceSyncPhase.CHECKED -> "Last check passed"
                DeviceSyncPhase.RECEIVED -> "Changes received"
                DeviceSyncPhase.ATTENTION -> "Needs attention"
                DeviceSyncPhase.SEARCHING -> "Looking"
                else -> "Not connected"
            }
            val colors = MaterialTheme.colorScheme
            val (badgeColor, badgeTextColor) = when (peerStatus?.phase) {
                DeviceSyncPhase.CONNECTING, DeviceSyncPhase.TRANSFERRING ->
                    colors.primaryContainer to colors.onPrimaryContainer
                DeviceSyncPhase.CHECKED, DeviceSyncPhase.RECEIVED ->
                    colors.secondaryContainer to colors.onSecondaryContainer
                DeviceSyncPhase.ATTENTION -> colors.errorContainer to colors.onErrorContainer
                else -> colors.surfaceVariant to colors.onSurfaceVariant
            }
            var address by rememberSaveable(device.deviceId) {
                mutableStateOf(viewModel.deviceSyncAddress(device.deviceId))
            }
            var showAddress by rememberSaveable(device.deviceId) { mutableStateOf(false) }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(device.displayName, style = MaterialTheme.typography.titleSmall,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${if (device.deviceId == managerDeviceId) "Managing device" else "Paired device"} · ${device.deviceId.take(8)}",
                                style = MaterialTheme.typography.labelSmall)
                        }
                        Surface(color = badgeColor, shape = MaterialTheme.shapes.small) {
                            Text(peerLabel, Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                style = MaterialTheme.typography.labelSmall, color = badgeTextColor)
                        }
                    }
                    peerStatus?.let { Text(it.detail, style = MaterialTheme.typography.bodySmall) }
                    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
                    if (lastContact > 0) Text("Last contact: ${dateFormat.format(Date(lastContact))}",
                        style = MaterialTheme.typography.bodySmall)
                    if (lastExchange > 0) Text("Last exchange completed: ${dateFormat.format(Date(lastExchange))}",
                        style = MaterialTheme.typography.bodySmall)
                    Text(viewModel.deviceSyncProgress(device.deviceId), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { showAddress = !showAddress }) {
                        Text(if (showAddress) "Hide address" else "Connection help")
                    }
                    if (showAddress) {
                        val validAddress = runCatching { InetAddresses.parseNumericAddress(address.trim()) }
                            .getOrNull()?.let { isPrivateAddress(it) && !it.isLinkLocalAddress } == true
                        Text("Use this device's current IP from Android Wi-Fi details if discovery cannot find it. Saved addresses may change.",
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(address, { address = it },
                            label = { Text("Paired device's Wi-Fi IP address") },
                            singleLine = true, isError = address.isNotBlank() && !validAddress,
                            supportingText = {
                                if (address.isNotBlank() && !validAddress)
                                    Text("Enter a private Wi-Fi IP address")
                            },
                            modifier = Modifier.fillMaxWidth())
                        OutlinedButton(onClick = { viewModel.setDeviceSyncAddress(device.deviceId, address) },
                            enabled = validAddress,
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Connect using this address")
                        }
                    }
                    if (canRemoveOnlyPeer && device.status == MemberStatus.ACTIVE.name)
                        TextButton(onClick = { removeDeviceId = device.deviceId }) {
                            Text("Stop sharing", color = MaterialTheme.colorScheme.error)
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
    removeDeviceId?.let { deviceId ->
        AlertDialog(onDismissRequest = { removeDeviceId = null },
            title = { Text("Stop sharing and keep this vault?") },
            text = { Text("This device becomes independent and keeps its current contents. The other device keeps its copy. Future edits will not sync between them. Finish any waiting sync first. You will also need to pair your watch again.") },
            confirmButton = {
                TextButton(onClick = {
                    removeDeviceId = null
                    viewModel.removeOnlyPairedDevice(deviceId)
                }) { Text("Stop sharing") }
            },
            dismissButton = { TextButton(onClick = { removeDeviceId = null }) { Text("Cancel") } })
    }
    if (verifyPairing) AlertDialog(
        onDismissRequest = { if (!verifyingPassword) { verifyPairing = false; pairingPassword = "" } },
        title = { Text("Verify before connecting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter this device's master password to create a temporary pairing QR code. Your password stays on this device.")
                OutlinedTextField(pairingPassword, { pairingPassword = it; passwordError = null },
                    label = { Text("Master password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !verifyingPassword,
                    isError = passwordError != null,
                    supportingText = { passwordError?.let { Text(it) } }, modifier = Modifier.fillMaxWidth())
                if (verifyingPassword) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = !verifyingPassword, onClick = {
                val password = pairingPassword.toCharArray()
                pairingPassword = ""
                verifyingPassword = true
                scope.launch {
                    try {
                        viewModel.hostDevicePairing(password)
                        verifyPairing = false
                    } catch (cancelled: kotlinx.coroutines.CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        passwordError = if (error is IllegalArgumentException || error is IllegalStateException)
                            error.message else "Could not verify the password. Try again."
                    } finally { password.fill('\u0000'); verifyingPassword = false }
                }
            }) { Text("Verify and connect") }
        },
        dismissButton = {
            TextButton(enabled = !verifyingPassword, onClick = { verifyPairing = false; pairingPassword = "" }) {
                Text("Cancel")
            }
        })
}
