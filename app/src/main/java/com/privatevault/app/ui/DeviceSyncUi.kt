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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WifiFind
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
import com.privatevault.app.sync.PeerSyncCounts
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
    val icon = when (phase) {
        DeviceSyncPhase.TRANSFERRING -> Icons.Outlined.Sync
        DeviceSyncPhase.CHECKED -> Icons.Outlined.CheckCircleOutline
        DeviceSyncPhase.ATTENTION -> Icons.Outlined.ErrorOutline
        DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING -> Icons.Outlined.Link
        DeviceSyncPhase.SEARCHING -> Icons.Outlined.WifiFind
        else -> Icons.Outlined.HourglassEmpty
    }
    Surface(color = tone.background, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tone.accent, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelSmall, color = tone.foreground,
                maxLines = 1)
        }
    }
}

@Composable
internal fun DeviceSyncState(status: DeviceSyncStatus, connected: Int, paired: Int,
    waiting: Int = 0) {
    val tone = syncTone(status.phase)
    val hasWaitingChanges = waiting > 0 && status.phase == DeviceSyncPhase.CHECKED
    Surface(Modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(20.dp), color = tone.background) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(12.dp).background(tone.accent, CircleShape))
                Text(if (hasWaitingChanges) "Changes still waiting" else status.phase.title,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold, color = tone.foreground)
            }
            Text(if (hasWaitingChanges) "The last exchange finished. Some changes still need delivery or application."
                else status.detail, style = MaterialTheme.typography.bodySmall, color = tone.foreground,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (status.phase == DeviceSyncPhase.CONNECTING || status.phase == DeviceSyncPhase.TRANSFERRING)
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = tone.accent,
                    trackColor = tone.accent.copy(alpha = .24f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple(waiting, "Waiting", if (waiting > 0) MaterialTheme.colorScheme.primary
                    else tone.foreground),
                    Triple(connected, "Syncing", if (connected == 0) tone.foreground
                    else if (MaterialTheme.colorScheme.background.luminance() > .5f)
                        Color(0xFF225F99) else Color(0xFF9DCEFF)),
                    Triple(paired, "Devices", tone.foreground)).forEach { (count, label, color) ->
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
    onTryAddress: (String) -> Unit, onRemove: () -> Unit,
    counts: PeerSyncCounts = PeerSyncCounts(0, 0, 0, 0), advanced: Boolean = true) {
    val needsCheck = counts.toSend > 0 || counts.toReceiveHere > 0 || counts.toApplyHere > 0 ||
        counts.receivedThere > 0
    val peerLabel = when {
        status?.phase == DeviceSyncPhase.TRANSFERRING -> "Syncing"
        status?.phase == DeviceSyncPhase.CONNECTING -> "Verifying"
        status?.phase == DeviceSyncPhase.ATTENTION -> "Needs attention"
        counts.toApplyHere > 0 -> "Waiting to apply"
        needsCheck -> "Changes waiting"
        status?.phase == DeviceSyncPhase.SEARCHING || status?.phase == DeviceSyncPhase.FOUND -> "Looking"
        lastExchange > 0 -> "Checked"
        else -> "Not checked yet"
    }
    val displayPhase = if (needsCheck && status?.phase !in setOf(DeviceSyncPhase.TRANSFERRING,
            DeviceSyncPhase.CONNECTING, DeviceSyncPhase.ATTENTION)) DeviceSyncPhase.RECEIVED
        else status?.phase ?: DeviceSyncPhase.WAITING
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
                    Text(if (isManager) "Manages this sync group" else "In this sync group",
                        style = MaterialTheme.typography.labelSmall)
                }
                SyncStatusTag(peerLabel, displayPhase)
            }
            Text(when {
                status?.phase == DeviceSyncPhase.TRANSFERRING -> "Sending and receiving encrypted changes."
                status?.phase == DeviceSyncPhase.CONNECTING -> "Confirming this is your paired device."
                status?.phase == DeviceSyncPhase.ATTENTION -> status.detail
                counts.toSend > 0 -> "${counts.toSend} change${if (counts.toSend == 1L) "" else "s"} from this device still need to reach it."
                counts.toApplyHere > 0 -> "${counts.toApplyHere} received change${if (counts.toApplyHere == 1L) "" else "s"} still need to apply here."
                counts.toReceiveHere > 0 -> "Changes from this device are waiting to arrive here."
                counts.receivedThere > 0 -> "It received changes and still needs to apply them."
                lastExchange > 0 -> "The last check finished with no known changes waiting."
                else -> "Linked to your vault. Waiting for the first check."
            },
                style = MaterialTheme.typography.bodySmall,
                color = syncTone(displayPhase).foreground,
                maxLines = if (status?.phase == DeviceSyncPhase.ATTENTION) 3 else 2,
                overflow = TextOverflow.Ellipsis)
            if (status?.phase == DeviceSyncPhase.TRANSFERRING)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            if (advanced) {
                status?.let { SyncStageTrail(it.phase) }
                if (lastContact > 0) Text("Last verified · ${dateFormat.format(Date(lastContact))}",
                    style = MaterialTheme.typography.bodySmall)
                if (lastExchange > 0) Text("Last exchange · ${dateFormat.format(Date(lastExchange))}",
                    style = MaterialTheme.typography.bodySmall)
                if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
                Text("To send: ${counts.toSend} · To apply here: ${counts.toApplyHere}",
                    style = MaterialTheme.typography.bodySmall)
                Text("Received there, not applied: ${counts.receivedThere} · Known changes to receive: ${counts.toReceiveHere}",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { showAddress = !showAddress }) {
                    Text(if (showAddress) "Hide connection help" else "Connection help")
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
            if (showRemove) TextButton(onClick = onRemove, enabled = canRemove) {
                Text("Remove device", color = if (canRemove) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            }
        }
    }
}

@Composable
internal fun DeviceSyncSettings(viewModel: VaultViewModel, copyLink: (String) -> Unit,
    joiningExisting: Boolean = false) {
    val state by viewModel.devicePairingState.collectAsStateWithLifecycle()
    val memberships by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val localDevice by viewModel.localSyncDevice.collectAsStateWithLifecycle()
    val canRemoveOnlyPeer by viewModel.canRemoveOnlyPeer.collectAsStateWithLifecycle()
    val canHostPairing by viewModel.canHostDevicePairing.collectAsStateWithLifecycle()
    val managerDeviceId by viewModel.syncManagerDeviceId.collectAsStateWithLifecycle()
    val authorityTransfer by viewModel.authorityTransfer.collectAsStateWithLifecycle()
    val syncStatus by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    val peerStatuses by viewModel.devicePeerStatus.collectAsStateWithLifecycle()
    val nearbyCount by viewModel.nearbySyncServices.collectAsStateWithLifecycle()
    val conflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val rejected by viewModel.rejectedSyncChanges.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val devices = memberships.filter { it.status == MemberStatus.ACTIVE.name }
    val activeDeviceCount = 1 + devices.size
    val connectedCount = devices.count { peerStatuses[it.deviceId]?.phase == DeviceSyncPhase.TRANSFERRING }
    val counts = devices.associate { it.deviceId to viewModel.deviceSyncCounts(it.deviceId) }
    val waitingCount = counts.values.count { it.toSend > 0 || it.toApplyHere > 0 ||
        it.toReceiveHere > 0 || it.receivedThere > 0 }
    var link by remember { mutableStateOf("") }
    var showLink by rememberSaveable { mutableStateOf(false) }
    var showSchedule by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var joinLink by rememberSaveable { mutableStateOf<String?>(null) }
    var joinPassword by rememberSaveable { mutableStateOf("") }
    var changeJoinPassword by rememberSaveable { mutableStateOf(false) }
    var oldJoinPassword by rememberSaveable { mutableStateOf("") }
    var newJoinPassword by rememberSaveable { mutableStateOf("") }
    var confirmJoinPassword by rememberSaveable { mutableStateOf("") }
    var joinError by remember { mutableStateOf<String?>(null) }
    var joining by remember { mutableStateOf(false) }
    var reviewConflictId by rememberSaveable { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var notificationDenied by remember { mutableStateOf(
        android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) }
    var removeDeviceId by remember { mutableStateOf<String?>(null) }
    var leaveGroup by remember { mutableStateOf(false) }
    var transferTargetId by remember { mutableStateOf<String?>(null) }
    var acceptTransfer by remember { mutableStateOf(false) }
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
        Text(if (joiningExisting) "On the managing device, open Android devices and tap Create QR. Scan it here."
            else "Connect another Android device to sync over Wi-Fi.",
            style = MaterialTheme.typography.bodyMedium)
        Text("Keep both devices unlocked during setup. The joining device needs an empty vault.",
            style = MaterialTheme.typography.bodySmall)
    }
    if (devices.isEmpty() || canHostPairing || state.stage != "idle") Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add a device", style = MaterialTheme.typography.titleMedium)
            if (state.message.isNotBlank()) Surface(Modifier.fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
                color = if (state.stage == "failed") MaterialTheme.colorScheme.error.copy(alpha = .12f)
                else MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp)) {
                Text(state.message, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall,
                    color = if (state.stage == "failed") MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (state.stage) {
                "offering" -> {
                    Text("1. Scan this QR on the new device", style = MaterialTheme.typography.titleSmall)
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
                    Text("Use this link on an empty device with the same master password. Confirm the matching code before the vault copy begins.",
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
                    Text("2. Compare the code on both devices", style = MaterialTheme.typography.titleSmall)
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
                    Text(if (state.stage == "connecting") "Connecting to the new device"
                        else "3. Copying the vault", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    OutlinedButton(onClick = viewModel::cancelDevicePairing, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancel")
                    }
                }
                "paired", "enrolled_pending" -> {
                    TextButton(onClick = viewModel::cancelDevicePairing) { Text("Done") }
                }
                else -> {
                    if (state.stage == "failed") TextButton(onClick = viewModel::cancelDevicePairing) {
                        Text("Dismiss error")
                    }
                    if (!canHostPairing && devices.isEmpty())
                        Text("Start a new pairing invitation from the managing device. You can still scan its QR code here.",
                            style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canHostPairing && !joiningExisting) Button(onClick = { passwordError = null; verifyPairing = true },
                            enabled = canHostPairing, modifier = Modifier.weight(1f)) {
                            Text(if (activeDeviceCount < MAX_ACTIVE_SYNC_DEVICES) "Create QR" else "Reconnect")
                        }
                        if (devices.isEmpty()) OutlinedButton(onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED) scanner = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }, modifier = if (joiningExisting) Modifier.fillMaxWidth() else Modifier.weight(1f)) {
                            Text("Scan QR")
                        }
                    }
                    if (devices.isEmpty()) {
                        if (permissionDenied) Text("Camera access was denied. Paste the setup link instead.")
                        TextButton(onClick = { showLink = !showLink }) {
                            Text(if (showLink) "Hide setup link" else "Use setup link instead")
                        }
                        if (showLink) {
                            OutlinedTextField(link, { link = it }, label = { Text("Setup link from the other device") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            OutlinedButton(onClick = { joinLink = link; link = "" },
                                enabled = link.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Text("Continue pairing")
                            }
                        }
                    }
                }
            }
        }
    }

    if (devices.isNotEmpty()) {
        Text("Your sync group", style = MaterialTheme.typography.titleMedium)
        Text("These devices share one vault. Each keeps its own copy and catches up when connected.",
            style = MaterialTheme.typography.bodySmall)
        DeviceSyncState(syncStatus, connectedCount, activeDeviceCount - 1, waitingCount)
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
            TextButton(onClick = { requestAutoSyncTile(context) }) {
                Text("Add Auto sync tile")
            }
            viewModel.deviceSyncQueueStatus()?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error) }
            if (notificationDenied) Text("Allow notifications to keep automatic sync active when Nuvori is closed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }

    if (devices.isNotEmpty() && (localDevice?.deviceId == managerDeviceId ||
            authorityTransfer?.targetDeviceId == localDevice?.deviceId)) Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transfer management", style = MaterialTheme.typography.titleMedium)
            val transfer = authorityTransfer
            when {
                transfer == null -> {
                    Text("Transfer this role while both devices can sync. The recipient must accept, then this device completes the handoff.",
                        style = MaterialTheme.typography.bodySmall)
                    devices.forEach { device ->
                        OutlinedButton(onClick = { transferTargetId = device.deviceId },
                            modifier = Modifier.fillMaxWidth()) {
                            Text("Transfer to ${device.displayName}")
                        }
                    }
                }
                localDevice?.deviceId == managerDeviceId -> {
                    val name = devices.firstOrNull { it.deviceId == transfer.targetDeviceId }?.displayName
                        ?: "the other device"
                    Text(if (transfer.accepted) "$name accepted. Sync both devices, then complete the transfer."
                        else "Waiting for $name to accept. Keep both devices syncing.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (transfer.accepted) Button(onClick = viewModel::completeAuthorityTransfer,
                        modifier = Modifier.fillMaxWidth()) { Text("Complete transfer") }
                    TextButton(onClick = viewModel::cancelAuthorityTransfer) { Text("Cancel transfer") }
                }
                transfer.targetDeviceId == localDevice?.deviceId -> {
                    Text(if (transfer.accepted) "You accepted. Waiting for the current managing device to finish the handoff."
                        else "The managing device offered you this role. Both devices must finish syncing before you accept.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    if (!transfer.accepted) Button(onClick = { acceptTransfer = true },
                        modifier = Modifier.fillMaxWidth()) { Text("Accept managing role") }
                }
            }
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

    if (devices.isNotEmpty()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Devices in this vault", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f))
            Text("$activeDeviceCount of $MAX_ACTIVE_SYNC_DEVICES",
                style = MaterialTheme.typography.labelSmall)
        }
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide connection details" else "Show connection details")
        }
        if (showAdvanced) {
            Text("$nearbyCount nearby Nuvori service${if (nearbyCount == 1) "" else "s"} found on Wi-Fi. Nearby does not mean paired.",
                style = MaterialTheme.typography.bodySmall)
            Text("Counts describe known changes at the last contact. Another device may have newer changes until the next check.",
                style = MaterialTheme.typography.bodySmall)
        }
        localDevice?.let { device ->
            DeviceSyncSelfCard(device, device.deviceId == managerDeviceId, viewModel::nameThisDevice)
        }
        if (activeDeviceCount > 1) OutlinedButton(onClick = { leaveGroup = true },
            modifier = Modifier.fillMaxWidth()) { Text("Stop sharing on this device") }
        if (activeDeviceCount > 1 && localDevice?.deviceId != managerDeviceId) Text(
            "Lost the managing device? Stop sharing here to keep this vault copy, then pair a new empty device from this one.",
            style = MaterialTheme.typography.bodySmall)
        if (activeDeviceCount > 2) Text(
            "Removing another member while the rest stay together is not available yet. Each device can stop sharing and keep its own copy.",
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
                canRemove = canRemoveOnlyPeer && localDevice?.deviceId == managerDeviceId,
                showRemove = localDevice?.deviceId == managerDeviceId &&
                    canRemoveOnlyPeer,
                onTryAddress = { viewModel.setDeviceSyncAddress(device.deviceId, it) },
                onRemove = { removeDeviceId = device.deviceId },
                counts = counts.getValue(device.deviceId), advanced = showAdvanced)
        }
    }

    conflicts.firstOrNull { it.id == reviewConflictId }?.let { conflict ->
        ConflictReviewSheet(conflict, onDismiss = { reviewConflictId = null }) { useIncoming ->
            viewModel.resolveSyncConflict(conflict.id, useIncoming, conflict.currentVersion)
            reviewConflictId = null
        }
    }
    if (scanner) QrScanner(onResult = { value -> scanner = false; joinLink = value },
        close = { scanner = false }, title = "Scan Nuvori pairing QR",
        help = "Scan the QR shown on the other device. Keep both devices unlocked on the same Wi-Fi.")
    if (joinLink != null) AlertDialog(
        onDismissRequest = {
            if (joining) return@AlertDialog
            joinLink = null; joinPassword = ""; oldJoinPassword = ""; newJoinPassword = ""
            confirmJoinPassword = ""; changeJoinPassword = false; joinError = null
        },
        title = { Text(if (changeJoinPassword) "Use the same password" else "Check this device's password") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Both devices must use the same master password. Nothing is copied until they match.")
                joinError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }) }
                if (changeJoinPassword) {
                    Text("This changes the password for this empty vault, then starts pairing.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(oldJoinPassword, { oldJoinPassword = it },
                        label = { Text("Current password on this device") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(newJoinPassword, { newJoinPassword = it },
                        label = { Text("Password used on managing device") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(confirmJoinPassword, { confirmJoinPassword = it },
                        label = { Text("Confirm new password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                        isError = confirmJoinPassword.isNotEmpty() && confirmJoinPassword != newJoinPassword,
                        supportingText = { if (confirmJoinPassword.isNotEmpty() && confirmJoinPassword != newJoinPassword)
                            Text("Passwords do not match") })
                } else {
                    OutlinedTextField(joinPassword, { joinPassword = it },
                        label = { Text("This device's master password") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    TextButton(onClick = { changeJoinPassword = true; oldJoinPassword = joinPassword; joinPassword = "" }) {
                        Text("Change this empty vault's password")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val currentLink = requireNotNull(joinLink)
                val currentPassword = if (changeJoinPassword) oldJoinPassword.toCharArray()
                    else joinPassword.toCharArray()
                val replacementPassword = if (changeJoinPassword) newJoinPassword.toCharArray() else null
                joining = true; joinError = null
                scope.launch {
                    try {
                        if (replacementPassword != null)
                            viewModel.changePasswordAndJoinDevice(currentLink, currentPassword, replacementPassword)
                        else viewModel.joinDevicePairing(currentLink, currentPassword)
                        joinPassword = ""; oldJoinPassword = ""; newJoinPassword = ""
                        confirmJoinPassword = ""; changeJoinPassword = false; joinLink = null
                    } catch (_: java.security.GeneralSecurityException) {
                        joinError = "The current password on this device is incorrect."
                    } catch (error: Exception) {
                        joinError = error.message ?: "Could not start pairing. Try again."
                    } finally {
                        currentPassword.fill('\u0000'); replacementPassword?.fill('\u0000')
                        joining = false
                    }
                }
            }, enabled = !joining && if (changeJoinPassword) oldJoinPassword.isNotEmpty() &&
                newJoinPassword.length >= 12 && newJoinPassword == confirmJoinPassword
                else joinPassword.isNotEmpty()) {
                Text(if (joining) "Checking…" else if (changeJoinPassword) "Change and pair" else "Verify and pair")
            }
        },
        dismissButton = { TextButton(onClick = {
            joinError = null
            if (changeJoinPassword) {
                changeJoinPassword = false; oldJoinPassword = ""; newJoinPassword = ""; confirmJoinPassword = ""
            } else { joinLink = null; joinPassword = "" }
        }, enabled = !joining) { Text(if (changeJoinPassword) "Back" else "Cancel") } })
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
    if (leaveGroup) AlertDialog(onDismissRequest = { leaveGroup = false },
        title = { Text("Stop sharing on this device?") },
        text = { Text("This device keeps its current vault and starts a separate sync group with new keys. Other devices keep their copies and continue syncing with each other. Changes waiting to arrive here must finish first. If another device manages the old group, remove this device there later. If this device manages it, the others cannot add or remove members until they start a new group. Pair the watch again for codes.") },
        confirmButton = { TextButton(onClick = {
            leaveGroup = false
            viewModel.leaveSyncGroup()
        }) { Text("Stop sharing", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { leaveGroup = false }) { Text("Cancel") } })
    transferTargetId?.let { targetId -> AlertDialog(onDismissRequest = { transferTargetId = null },
        title = { Text("Transfer management?") },
        text = { Text("The paired device must be up to date and accept this role. This device keeps syncing records after the handoff.") },
        confirmButton = { TextButton(onClick = {
            transferTargetId = null
            viewModel.offerAuthorityTransfer(targetId)
        }) { Text("Offer transfer") } },
        dismissButton = { TextButton(onClick = { transferTargetId = null }) { Text("Cancel") } }) }
    if (acceptTransfer) AlertDialog(onDismissRequest = { acceptTransfer = false },
        title = { Text("Accept managing role?") },
        text = { Text("This device will manage enrollment and removal after the current manager completes the signed handoff. Ordinary vault sync continues on every device.") },
        confirmButton = { TextButton(onClick = {
            acceptTransfer = false
            viewModel.acceptAuthorityTransfer()
        }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { acceptTransfer = false }) { Text("Cancel") } })
    if (verifyPairing) AlertDialog(
        onDismissRequest = { if (!verifyingPassword) { verifyPairing = false; pairingPassword = "" } },
        title = { Text("Verify before connecting") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Enter this vault's master password. The new device must use the same password before it can receive a copy.")
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
