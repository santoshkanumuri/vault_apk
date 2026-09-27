package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.InetAddresses
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
import com.privatevault.app.sync.SyncConflictReview
import com.privatevault.app.sync.SyncConflictSide
import com.privatevault.app.sync.DevicePeerStatus
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.sync.isPrivateAddress
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private data class SyncTone(val background: Color, val foreground: Color, val accent: Color)

@Composable
private fun syncTone(phase: DeviceSyncPhase): SyncTone {
    val colors = MaterialTheme.colorScheme
    val light = colors.background.luminance() > .5f
    return when (phase) {
        DeviceSyncPhase.ATTENTION -> SyncTone(colors.error.copy(alpha = .12f), colors.error, colors.error)
        DeviceSyncPhase.TRANSFERRING, DeviceSyncPhase.CHECKED ->
            SyncTone(colors.primaryContainer, colors.onPrimaryContainer, colors.primary)
        DeviceSyncPhase.SEARCHING, DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING, DeviceSyncPhase.RECEIVED ->
            if (light) SyncTone(Color(0xFFE4F1FF), Color(0xFF153A5B), Color(0xFF225F99))
            else SyncTone(Color(0xFF152D43), Color(0xFFDAEDFF), Color(0xFF9DCEFF))
        else -> SyncTone(colors.surfaceVariant, colors.onSurfaceVariant, colors.outline)
    }
}

@Composable
private fun SyncStageTrail(phase: DeviceSyncPhase, modifier: Modifier = Modifier) {
    val step = when (phase) {
        DeviceSyncPhase.SEARCHING, DeviceSyncPhase.FOUND -> 0
        DeviceSyncPhase.CONNECTING -> 1
        DeviceSyncPhase.TRANSFERRING -> 2
        DeviceSyncPhase.RECEIVED -> 2
        DeviceSyncPhase.CHECKED -> 3
        else -> -1
    }
    if (step < 0) return
    val tone = syncTone(phase)
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Find", "Verify", "Exchange", "Checked").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(
                    if (index <= step) tone.accent else tone.foreground.copy(alpha = .32f), CircleShape))
                Text(label, style = MaterialTheme.typography.labelSmall, color = tone.foreground,
                    maxLines = 1)
            }
        }
    }
}

@Composable
private fun SyncStatusTag(label: String, phase: DeviceSyncPhase) {
    val tone = syncTone(phase)
    Surface(color = tone.background, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(tone.accent, CircleShape))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tone.foreground,
                maxLines = 1)
        }
    }
}

@Composable
internal fun DeviceSyncState(status: DeviceSyncStatus, connected: Int, nearby: Int, paired: Int) {
    val tone = syncTone(status.phase)
    Surface(Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp), color = tone.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(tone.accent, CircleShape))
                Text(status.phase.title, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = tone.foreground)
            }
            Text(status.detail, style = MaterialTheme.typography.bodySmall, color = tone.foreground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status.phase == DeviceSyncPhase.CONNECTING || status.phase == DeviceSyncPhase.TRANSFERRING)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = tone.accent,
                    trackColor = tone.accent.copy(alpha = .24f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(connected, "Connected", if (connected > 0) MaterialTheme.colorScheme.primary
                    else tone.foreground),
                    Triple(nearby, "Nearby", if (nearby == 0) tone.foreground
                    else if (MaterialTheme.colorScheme.background.luminance() > .5f)
                        Color(0xFF225F99) else Color(0xFF9DCEFF)),
                    Triple(paired, "Paired", tone.foreground)).forEach { (count, label, color) ->
                    Column(Modifier.weight(1f)) {
                        Text("$count", style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold, color = color)
                        Text(label, style = MaterialTheme.typography.labelSmall, color = tone.foreground)
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictInbox(conflicts: List<SyncConflictReview>, onReview: (String) -> Unit) {
    if (conflicts.isEmpty()) return
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = .08f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Conflicts to review", style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                SyncStatusTag("${conflicts.size} waiting", DeviceSyncPhase.ATTENTION)
            }
            conflicts.forEach { conflict ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(conflict.type.replaceFirstChar { it.uppercase() } + " conflict",
                            style = MaterialTheme.typography.titleSmall)
                        Text(conflict.current.description.lineSequence().firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { onReview(conflict.id) }) { Text("Review") }
                }
            }
        }
    }
}

@Composable
private fun ConflictVersion(label: String, side: SyncConflictSide) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (side.deleted) SyncStatusTag("Deleted", DeviceSyncPhase.ATTENTION)
            }
            Text(side.device, style = MaterialTheme.typography.labelMedium)
            Text("Changed ${side.reportedAtUtc}", style = MaterialTheme.typography.bodySmall)
            Text(side.description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConflictReviewSheet(conflict: SyncConflictReview, onDismiss: () -> Unit,
    onChoose: (Boolean) -> Unit) {
    var pendingChoice by remember(conflict.id) { mutableStateOf<Boolean?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${conflict.type.replaceFirstChar { it.uppercase() }} conflict",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Close") }
            }
            Text("Choose the version to keep across paired devices.",
                style = MaterialTheme.typography.bodyMedium)
            ConflictVersion("On this device", conflict.current)
            ConflictVersion("Incoming version", conflict.incoming)
            Text("Times may differ. Secret values stay hidden.",
                style = MaterialTheme.typography.bodySmall)
            if (conflict.current.deleted || conflict.incoming.deleted)
                Text("A deleted version removes this record.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if (conflict.type == "passkey")
                Text("The incoming passkey cannot replace another credential with the same ID. Keep this device's version, then register a new passkey on the website.",
                    style = MaterialTheme.typography.bodySmall)
            if (pendingChoice == null) {
                OutlinedButton(onClick = { pendingChoice = false }, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep this device's version")
                }
                if (conflict.type != "passkey")
                    Button(onClick = { pendingChoice = true }, modifier = Modifier.fillMaxWidth(),
                        colors = if (conflict.incoming.deleted) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error)
                        else ButtonDefaults.buttonColors()) {
                        Text("Use incoming version")
                    }
            } else {
                val choosingDeleted = if (pendingChoice == true) conflict.incoming.deleted
                    else conflict.current.deleted
                Surface(color = if (choosingDeleted) MaterialTheme.colorScheme.error.copy(alpha = .12f)
                    else MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(14.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (pendingChoice == true) "Use the incoming version?" else "Keep this device's version?",
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (choosingDeleted) "This removes the record on paired devices."
                            else "This replaces the current whole record on paired devices.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pendingChoice = null }, modifier = Modifier.weight(1f)) {
                                Text("Go back")
                            }
                            Button(onClick = { onChoose(pendingChoice == true) }, modifier = Modifier.weight(1f),
                                colors = if (choosingDeleted) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error)
                                else ButtonDefaults.buttonColors()) {
                                Text("Confirm choice")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DeviceSyncSelfCard(device: SyncMembershipEntity, isManager: Boolean,
    onRename: (String) -> Unit) {
    var editing by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    var name by rememberSaveable(device.deviceId) { mutableStateOf(device.displayName) }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("This device", style = MaterialTheme.typography.labelSmall)
                Text(device.displayName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${if (isManager) "Managing device" else "Paired device"} · ${device.deviceId.take(8)}",
                    style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { name = device.displayName; editing = true }) { Text("Rename") }
        }
    }
    if (editing) AlertDialog(onDismissRequest = { editing = false },
        title = { Text("Name this device") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This name appears on paired devices. Your verified device ID stays the same.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(name, { if (it.length <= 40) name = it },
                    label = { Text("Device name") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onRename(name.trim()); editing = false },
                enabled = name.trim().isNotEmpty() && name.trim() != device.displayName) {
                Text("Save name")
            }
        },
        dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
}

@Composable
internal fun DeviceSyncPeerCard(device: SyncMembershipEntity, isManager: Boolean,
    status: DevicePeerStatus?, lastContact: Long, lastExchange: Long, progress: String,
    savedAddress: String, canRemove: Boolean, showRemove: Boolean,
    onTryAddress: (String) -> Unit, onRemove: () -> Unit) {
    val peerLabel = when (status?.phase) {
        DeviceSyncPhase.CONNECTING -> "Verifying"
        DeviceSyncPhase.TRANSFERRING -> "Connected"
        DeviceSyncPhase.CHECKED -> "Check complete"
        DeviceSyncPhase.RECEIVED -> "More to sync"
        DeviceSyncPhase.ATTENTION -> "Check failed"
        DeviceSyncPhase.SEARCHING -> "Looking"
        else -> "Not connected"
    }
    var address by rememberSaveable(device.deviceId) { mutableStateOf(savedAddress) }
    var showAddress by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    LaunchedEffect(savedAddress) { if (!showAddress) address = savedAddress }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${if (isManager) "Managing device" else "Paired device"} · ${device.deviceId.take(8)}",
                        style = MaterialTheme.typography.labelSmall)
                }
                SyncStatusTag(peerLabel, status?.phase ?: DeviceSyncPhase.WAITING)
            }
            Text(status?.detail ?: "No live connection. Tap Sync now to check this device.",
                style = MaterialTheme.typography.bodySmall,
                color = syncTone(status?.phase ?: DeviceSyncPhase.WAITING).foreground,
                maxLines = if (status?.phase == DeviceSyncPhase.ATTENTION) 3 else 2,
                overflow = TextOverflow.Ellipsis)
            status?.let { SyncStageTrail(it.phase) }
            if (lastContact > 0) Text("Last verified · ${dateFormat.format(Date(lastContact))}",
                style = MaterialTheme.typography.bodySmall)
            if (lastExchange > 0) Text("Last exchange · ${dateFormat.format(Date(lastExchange))}",
                style = MaterialTheme.typography.bodySmall)
            if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { showAddress = !showAddress }, modifier = Modifier.weight(1f)) {
                    Text(if (showAddress) "Hide help" else "Connection help")
                }
                if (showRemove) TextButton(onClick = onRemove, enabled = canRemove) {
                    Text("Remove device", color = if (canRemove) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
                }
            }
            if (showAddress) {
                val validAddress = runCatching { InetAddresses.parseNumericAddress(address.trim()) }
                    .getOrNull()?.let { isPrivateAddress(it) && !it.isLinkLocalAddress } == true
                Text("If discovery cannot find this device, enter its current address from Android Wi-Fi settings. Addresses can change.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(address, { address = it },
                    label = { Text("Device Wi-Fi IP address") },
                    singleLine = true, isError = address.isNotBlank() && !validAddress,
                    supportingText = {
                        if (address.isNotBlank() && !validAddress)
                            Text("Enter a private Wi-Fi IP address")
                    }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { onTryAddress(address) }, enabled = validAddress,
                    modifier = Modifier.fillMaxWidth()) { Text("Try this address") }
            }
        }
    }
}

@Composable
internal fun DeviceSyncSettings(viewModel: VaultViewModel, copyLink: (String) -> Unit) {
    val state by viewModel.devicePairingState.collectAsStateWithLifecycle()
    val memberships by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val localDevice by viewModel.localSyncDevice.collectAsStateWithLifecycle()
    val canRemoveOnlyPeer by viewModel.canRemoveOnlyPeer.collectAsStateWithLifecycle()
    val canHostPairing by viewModel.canHostDevicePairing.collectAsStateWithLifecycle()
    val managerDeviceId by viewModel.syncManagerDeviceId.collectAsStateWithLifecycle()
    val syncStatus by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    val peerStatuses by viewModel.devicePeerStatus.collectAsStateWithLifecycle()
    val nearbyCount by viewModel.nearbySyncServices.collectAsStateWithLifecycle()
    val conflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val rejected by viewModel.rejectedSyncChanges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val devices = memberships.filter { it.status == MemberStatus.ACTIVE.name }
    val activeDeviceCount = 1 + devices.size
    val connectedCount = devices.count { peerStatuses[it.deviceId]?.phase == DeviceSyncPhase.TRANSFERRING }
    var link by remember { mutableStateOf("") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var showPairing by rememberSaveable { mutableStateOf(false) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var reviewConflictId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(
        android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) }
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

    if (devices.isEmpty()) {
        Text("Connect another Android device to sync over Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Keep both devices unlocked during setup. The joining device needs an empty vault.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (state.message.isNotBlank()) Surface(Modifier.fillMaxWidth(),
        color = if (state.stage == "failed") MaterialTheme.colorScheme.error.copy(alpha = .12f)
        else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
        Text(state.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
            color = if (state.stage == "failed") MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (devices.isNotEmpty()) {
        DeviceSyncState(syncStatus, connectedCount, nearbyCount, activeDeviceCount - 1)
    }
    if (devices.isNotEmpty()) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            val intervalLabel = when (interval) {
                30_000L -> "30 seconds"
                60_000L -> "1 minute"
                300_000L -> "5 minutes"
                else -> "15 minutes"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Automatic check", style = MaterialTheme.typography.labelMedium)
                    Text("Every $intervalLabel", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { showSchedule = !showSchedule }) {
                    Text(if (showSchedule) "Done" else "Change")
                }
            }
            if (showSchedule) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LanSyncService.SYNC_INTERVALS.toList().chunked(2).forEach { options ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            val label = when (option) {
                                30_000L -> "30 s"
                                60_000L -> "1 min"
                                300_000L -> "5 min"
                                else -> "15 min"
                            }
                            FilterChip(selected = interval == option, onClick = {
                                interval = option
                                viewModel.setDeviceSyncInterval(option)
                            }, label = { Text(label) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
            viewModel.deviceSyncQueueStatus()?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
            if (notificationDenied) Text("Allow notifications to keep automatic sync active when Nuvori is closed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (rejected > 0) Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.error.copy(alpha = .08f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Changes need attention", style = MaterialTheme.typography.titleMedium)
            Text("$rejected incoming changes could not be applied. Update both devices, then retry.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = viewModel::retryRejectedSyncChanges) { Text("Retry changes") }
        }
    }
    ConflictInbox(conflicts) { reviewConflictId = it }

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
                    Text(if (state.stage == "connecting") "Verifying the new device…"
                        else "Copying the encrypted vault…", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = viewModel::cancelDevicePairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
                else -> {
                    if (!canHostPairing)
                        Text("Start a new pairing invitation from the managing device. You can still scan its QR code here.",
                            style = MaterialTheme.typography.bodySmall)
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Your devices", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Text("$activeDeviceCount of $MAX_ACTIVE_SYNC_DEVICES",
                style = MaterialTheme.typography.labelSmall)
        }
        localDevice?.let { device ->
            DeviceSyncSelfCard(device, device.deviceId == managerDeviceId, viewModel::nameThisDevice)
        }
        if (activeDeviceCount > 2) Text(
            "Removing a device from a group of 3 or 4 is not available yet.",
            style = MaterialTheme.typography.bodySmall)
        if (!canHostPairing) Text(
            "To add a device, start pairing from ${devices.firstOrNull { it.deviceId == managerDeviceId }?.displayName ?: "the managing device"}.",
            style = MaterialTheme.typography.bodySmall)
        devices.forEach { device ->
            val (lastContact, lastExchange) = viewModel.deviceSyncContact(device.deviceId)
            DeviceSyncPeerCard(device = device, isManager = device.deviceId == managerDeviceId,
                status = peerStatuses[device.deviceId], lastContact = lastContact,
                lastExchange = lastExchange, progress = viewModel.deviceSyncProgress(device.deviceId),
                savedAddress = viewModel.deviceSyncAddress(device.deviceId),
                canRemove = canRemoveOnlyPeer, showRemove = canRemoveOnlyPeer || activeDeviceCount > 2,
                onTryAddress = { viewModel.setDeviceSyncAddress(device.deviceId, it) },
                onRemove = { removeDeviceId = device.deviceId })
        }
        if (canHostPairing && !showPairing && state.stage in setOf("idle", "paired"))
            OutlinedButton(onClick = { showPairing = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Add another device")
            }
    }

    conflicts.firstOrNull { it.id == reviewConflictId }?.let { conflict ->
        ConflictReviewSheet(conflict, onDismiss = { reviewConflictId = null }) { useIncoming ->
            viewModel.resolveSyncConflict(conflict.id, useIncoming, conflict.currentVersion)
            reviewConflictId = null
        }
    }
    if (scanner) QrScanner(onResult = { value -> scanner = false; viewModel.joinDevicePairing(value) },
        close = { scanner = false }, title = "Scan Nuvori pairing QR",
        help = "Scan the QR shown on the other device. Keep both devices unlocked on the same Wi-Fi.")
    removeDeviceId?.let { deviceId ->
        AlertDialog(onDismissRequest = { removeDeviceId = null },
            title = { Text("Remove this device from sync?") },
            text = { Text("Both devices keep their current vault data. This device gets new sync keys, and future changes stop syncing with the other device. Finish any waiting changes first. You will also need to pair your watch again.") },
            confirmButton = {
                TextButton(onClick = {
                    removeDeviceId = null
                    viewModel.removeOnlyPairedDevice(deviceId)
                }) { Text("Remove device", color = MaterialTheme.colorScheme.error) }
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
