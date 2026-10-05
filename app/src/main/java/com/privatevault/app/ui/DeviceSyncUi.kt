@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.InetAddresses
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
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
import com.privatevault.app.sync.PairingInvitation
import com.privatevault.app.sync.ReversePairingInvitation
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.sync.isPrivateAddress
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Maps a sync phase to the shared status meaning. `waitingChanges` marks a finished check that left changes undelivered. */
private fun DeviceSyncPhase.statusKind(waitingChanges: Boolean = false): StatusKind = when (this) {
    DeviceSyncPhase.ATTENTION -> StatusKind.ERROR
    DeviceSyncPhase.TRANSFERRING, DeviceSyncPhase.CONNECTING, DeviceSyncPhase.SEARCHING,
        DeviceSyncPhase.FOUND -> StatusKind.PROGRESS
    DeviceSyncPhase.RECEIVED, DeviceSyncPhase.PAUSED -> StatusKind.WARNING
    DeviceSyncPhase.CHECKED -> if (waitingChanges) StatusKind.WARNING else StatusKind.SUCCESS
    DeviceSyncPhase.OFF, DeviceSyncPhase.WAITING -> StatusKind.NEUTRAL
}

private fun relativeTime(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> (seconds / 60).let { if (it == 1L) "1 minute ago" else "$it minutes ago" }
        seconds < 86_400 -> (seconds / 3_600).let { if (it == 1L) "1 hour ago" else "$it hours ago" }
        else -> DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(then))
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
    val accent = statusColors(phase.statusKind()).accent
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf("Find", "Verify", "Exchange", "Checked").forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(
                    if (index <= step) accent else muted.copy(alpha = .32f), CircleShape))
                Text(label, style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1)
            }
        }
    }
}

/** The state of this vault's sync at a glance, with the one action people need most. */
@Composable
internal fun DeviceSyncState(status: DeviceSyncStatus, connected: Int, paired: Int,
    waiting: Int = 0, lastChecked: Long = 0L, onSyncNow: (() -> Unit)? = null) {
    val hasWaitingChanges = waiting > 0 && status.phase == DeviceSyncPhase.CHECKED
    val kind = status.phase.statusKind(hasWaitingChanges)
    val colors = statusColors(kind)
    val busy = status.phase == DeviceSyncPhase.CONNECTING || status.phase == DeviceSyncPhase.TRANSFERRING
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (lastChecked > 0) LaunchedEffect(lastChecked, status.phase) {
        while (true) {
            now = System.currentTimeMillis()
            delay(30_000)
        }
    }
    Surface(Modifier.fillMaxWidth().animateContentSize(), shape = NuvoriShapes.Card,
        color = colors.container, contentColor = colors.content) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusIcon(kind, colors.accent, size = 28.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (hasWaitingChanges) "Changes still waiting" else status.phase.title,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    if (lastChecked > 0) Text("Last checked ${relativeTime(lastChecked, now)}",
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            Text(if (hasWaitingChanges) "The last exchange finished. Some changes still need delivery or application."
                else status.detail, style = MaterialTheme.typography.bodySmall,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.accent,
                trackColor = colors.accent.copy(alpha = .24f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(waiting to "Waiting", connected to "Syncing", paired to "Devices").forEach { (count, label) ->
                    Column(Modifier.weight(1f)) {
                        Text("$count", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(label, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (onSyncNow != null) Button(onClick = onSyncNow, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(ButtonDefaults.IconSize), strokeWidth = 2.dp,
                    color = LocalContentColor.current)
                else Icon(NuvoriIcons.Sync, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text("Sync now")
            }
        }
    }
}

@Composable
private fun ConflictInbox(conflicts: List<SyncConflictReview>, onReview: (String) -> Unit) {
    if (conflicts.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusBanner(kind = StatusKind.WARNING, title = "Conflicts to review",
            message = "${conflicts.size} ${if (conflicts.size == 1) "record was" else "records were"} changed on two devices. Choose which version to keep.")
        conflicts.forEach { conflict ->
            Card(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(conflict.type.replaceFirstChar { it.uppercase() } + " conflict",
                            style = MaterialTheme.typography.titleSmall)
                        Text(conflict.current.description.lineSequence().firstOrNull().orEmpty(),
                            style = MaterialTheme.typography.bodySmall, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    OutlinedButton(onClick = { onReview(conflict.id) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Review")
                    }
                }
            }
        }
    }
}

@Composable
private fun ConflictVersion(label: String, side: SyncConflictSide) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceVariant,
        shape = NuvoriShapes.Card) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (side.deleted) StatusChip(StatusKind.ERROR, "Deleted")
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
                StatusBanner(kind = StatusKind.WARNING, message = "A deleted version removes this record.")
            if (conflict.type == "passkey")
                StatusBanner(kind = StatusKind.INFO, message = "The incoming passkey cannot replace another credential with the same ID. Keep this device's version, then register a new passkey on the website.")
            if (pendingChoice == null) {
                OutlinedButton(onClick = { pendingChoice = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Text("Keep this device's version")
                }
                if (conflict.type != "passkey")
                    Button(onClick = { pendingChoice = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                        colors = if (conflict.incoming.deleted) ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError)
                        else ButtonDefaults.buttonColors()) {
                        Text("Use incoming version")
                    }
            } else {
                val choosingDeleted = if (pendingChoice == true) conflict.incoming.deleted
                    else conflict.current.deleted
                val panel = statusColors(if (choosingDeleted) StatusKind.ERROR else StatusKind.INFO)
                Surface(color = panel.container, contentColor = panel.content,
                    shape = NuvoriShapes.Card) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (pendingChoice == true) "Use the incoming version?" else "Keep this device's version?",
                            style = MaterialTheme.typography.titleSmall)
                        Text(if (choosingDeleted) "This removes the record on paired devices."
                            else "This replaces the current whole record on paired devices.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pendingChoice = null },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Text("Go back", color = panel.content)
                            }
                            Button(onClick = { onChoose(pendingChoice == true) },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
                                colors = if (choosingDeleted) ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                    contentColor = MaterialTheme.colorScheme.onError)
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
        Row(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsIconBadge(devicePlatformIcon(device))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("This device", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary)
                Text(device.displayName, style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (isManager) ManagingBadge()
                Text("${if (isManager) "Adds and removes devices" else "Member"} · ID ${device.deviceId.take(8)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { name = device.displayName; editing = true },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("Rename") }
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
    counts: PeerSyncCounts = PeerSyncCounts(0, 0, 0, 0), advanced: Boolean = true,
    transferRole: String? = null, removeHint: String? = null) {
    val needsCheck = counts.toSend > 0 || counts.toReceiveHere > 0 || counts.toApplyHere > 0 ||
        counts.receivedThere > 0
    val (peerLabel, peerKind) = when {
        status?.phase == DeviceSyncPhase.TRANSFERRING -> "Syncing" to StatusKind.PROGRESS
        status?.phase == DeviceSyncPhase.CONNECTING -> "Verifying" to StatusKind.PROGRESS
        status?.phase == DeviceSyncPhase.ATTENTION -> "Needs attention" to StatusKind.ERROR
        counts.toApplyHere > 0 -> "Waiting to apply" to StatusKind.WARNING
        needsCheck -> "Changes waiting" to StatusKind.WARNING
        status?.phase == DeviceSyncPhase.SEARCHING || status?.phase == DeviceSyncPhase.FOUND ->
            "Looking" to StatusKind.PROGRESS
        lastExchange > 0 -> "Active" to StatusKind.SUCCESS
        else -> "Not checked yet" to StatusKind.NEUTRAL
    }
    val now = remember(lastContact, lastExchange, status?.phase) { System.currentTimeMillis() }
    val lastSeen = maxOf(lastContact, lastExchange)
    var address by rememberSaveable(device.deviceId) { mutableStateOf(savedAddress) }
    var showAddress by rememberSaveable(device.deviceId) { mutableStateOf(false) }
    LaunchedEffect(savedAddress) { if (!showAddress) address = savedAddress }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIconBadge(devicePlatformIcon(device))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(device.displayName, style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isManager) ManagingBadge()
                    Text(if (lastSeen > 0) "Last seen ${relativeTime(lastSeen, now)}" else "Not seen yet",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusChip(peerKind, peerLabel)
                if (transferRole != null) StatusChip(StatusKind.INFO, transferRole)
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
                TextButton(onClick = { showAddress = !showAddress },
                    modifier = Modifier.heightIn(min = 48.dp)) {
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
                    SettingsSecondaryButton("Try this address", onClick = { onTryAddress(address) },
                        enabled = validAddress)
                }
            }
            if (showRemove) {
                SettingsDangerButton("Remove ${device.displayName}", onClick = onRemove, enabled = canRemove,
                    icon = NuvoriIcons.Delete)
                if (removeHint != null) Text(removeHint, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PairingStep(number: Int, title: String, detail: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(28.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
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
    var scanWindowsQr by remember { mutableStateOf(false) }
    var reverseLink by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
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
    var autoOverride by remember { mutableStateOf<Boolean?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        scanner = allowed
        permissionDenied = !allowed
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationDenied = !allowed
        if (allowed) {
            autoOverride = true
            viewModel.resumeDeviceSync()
        }
    }
    DisposableEffect(Unit) { onDispose { viewModel.cancelDevicePairing() } }
    LaunchedEffect(syncStatus.phase) { autoOverride = null }

    val lastChecked = devices.maxOfOrNull { viewModel.deviceSyncContact(it.deviceId).second } ?: 0L
    val autoRunning = !LanSyncService.isPaused(context) &&
        syncStatus.phase != DeviceSyncPhase.OFF && syncStatus.phase != DeviceSyncPhase.PAUSED
    val autoOn = autoOverride ?: autoRunning
    val intervalLabel = when (interval) {
        30_000L -> "30 seconds"
        60_000L -> "1 minute"
        300_000L -> "5 minutes"
        else -> "15 minutes"
    }
    val setAutomatic: (Boolean) -> Unit = { on ->
        if (!on) {
            autoOverride = false
            viewModel.pauseDeviceSync()
        } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            autoOverride = true
            viewModel.resumeDeviceSync()
        }
    }

    val addDeviceCard: @Composable () -> Unit = {
        SettingsSection("Add a device") {
            if (devices.isEmpty() && (state.stage == "idle" || state.stage == "failed")) {
                Text(if (joiningExisting) "Scan the QR code from the device that manages your vault."
                    else "Connect another Android device to sync over Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium)
                PairingStep(1, "Create a QR on the managing device", "Open Android devices there and tap Create QR.")
                PairingStep(2, "Scan it here", "On the empty device, tap Scan QR and point the camera at the code.")
                PairingStep(3, "Compare the codes", "Confirm the same code appears on both devices before the vault copy starts.")
                Text("Keep both devices unlocked during setup. The joining device needs an empty vault.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (state.message.isNotBlank()) StatusBanner(kind = when (state.stage) {
                "failed" -> StatusKind.ERROR
                "paired", "enrolled_pending" -> StatusKind.SUCCESS
                "connecting", "transferring" -> StatusKind.PROGRESS
                else -> StatusKind.INFO
            }, message = state.message)
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
                    val phoneAddress = remember(state.invitation) {
                        PairingInvitation.decode(state.invitation).address
                    }
                    Text("Phone Wi-Fi address: $phoneAddress", style = MaterialTheme.typography.bodySmall)
                    Text("On another Android phone, start with an empty vault. On Windows, scan this QR with the PC camera or use Copy link. The phone and PC must be able to reach each other across their local networks. Enter this phone's master password on Windows, then confirm the matching code before copying the vault.",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsSecondaryButton("Copy link", onClick = { copyLink(state.invitation) },
                            modifier = Modifier.weight(1f), icon = NuvoriIcons.Copy, fill = false)
                        SettingsSecondaryButton("Cancel", onClick = viewModel::cancelDevicePairing,
                            modifier = Modifier.weight(1f), fill = false)
                    }
                }
                "confirm" -> {
                    Text("2. Compare the code on both devices", style = MaterialTheme.typography.titleSmall)
                    Text(state.confirmation, style = MaterialTheme.typography.headlineLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsPrimaryButton("Codes match", onClick = viewModel::approveDevicePairing,
                            modifier = Modifier.weight(1f), icon = NuvoriIcons.Check, fill = false)
                        SettingsSecondaryButton("Cancel", onClick = viewModel::cancelDevicePairing,
                            modifier = Modifier.weight(1f), fill = false)
                    }
                }
                "connecting", "transferring" -> {
                    Text(if (state.stage == "connecting") "Connecting to the new device"
                        else "3. Copying the vault", style = MaterialTheme.typography.titleSmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    SettingsSecondaryButton("Cancel", onClick = viewModel::cancelDevicePairing)
                }
                "paired", "enrolled_pending" -> {
                    SettingsPrimaryButton("Done", onClick = viewModel::cancelDevicePairing, icon = NuvoriIcons.Check)
                }
                else -> {
                    if (state.stage == "failed") TextButton(onClick = viewModel::cancelDevicePairing,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("Dismiss error")
                    }
                    if (devices.isNotEmpty() && !canHostPairing) Text(
                        "To add a device, start pairing from ${devices.firstOrNull { it.deviceId == managerDeviceId }?.displayName ?: "the managing device"}.",
                        style = MaterialTheme.typography.bodyMedium)
                    if (!canHostPairing && devices.isEmpty())
                        Text("Start a new pairing invitation from the managing device. You can still scan its QR code here.",
                            style = MaterialTheme.typography.bodySmall)
                    val canCreate = canHostPairing && !joiningExisting
                    if (canCreate || devices.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canCreate) SettingsPrimaryButton(
                            if (activeDeviceCount < MAX_ACTIVE_SYNC_DEVICES) "Create QR" else "Reconnect",
                            onClick = { reverseLink = null; passwordError = null; verifyPairing = true },
                            modifier = Modifier.weight(1f), icon = Icons.Outlined.QrCode, fill = false)
                        if (devices.isEmpty()) SettingsSecondaryButton("Scan QR", onClick = {
                            scanWindowsQr = false
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                                PackageManager.PERMISSION_GRANTED) scanner = true
                            else cameraPermission.launch(Manifest.permission.CAMERA)
                        }, modifier = Modifier.weight(1f), icon = NuvoriIcons.Scan, fill = false)
                    }
                    if (canCreate) SettingsSecondaryButton("Scan Windows QR if the PC cannot reach this phone", onClick = {
                        scanWindowsQr = true
                        scanError = null
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                            PackageManager.PERMISSION_GRANTED) scanner = true
                        else cameraPermission.launch(Manifest.permission.CAMERA)
                    }, icon = NuvoriIcons.Scan)
                    scanError?.let { StatusBanner(StatusKind.ERROR, it) }
                    if (devices.isEmpty()) {
                        if (permissionDenied) Text("Camera access was denied. Paste the setup link instead.",
                            style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showLink = !showLink }, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(if (showLink) "Hide setup link" else "Use setup link instead")
                        }
                        if (showLink) {
                            OutlinedTextField(link, { link = it }, label = { Text("Setup link from the other device") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth(), maxLines = 3)
                            SettingsSecondaryButton("Continue pairing", onClick = { joinLink = link; link = "" },
                                enabled = link.isNotBlank())
                        }
                    }
                }
            }
        }
    }

    val isManager = localDevice != null && localDevice?.deviceId == managerDeviceId
    val managerName = (memberships + listOfNotNull(localDevice)).firstOrNull { it.deviceId == managerDeviceId }?.displayName
        ?: "the managing device"
    val membershipNotice = rememberMembershipNotice(viewModel)

    // A persistent removed/left notice comes before everything else.
    membershipNotice?.let { MembershipNoticeBanner(it, onDismiss = { dismissMembershipNotice(viewModel, it, context) }) }

    // Not paired yet: pairing is the only thing to do, so it comes first.
    if (devices.isEmpty()) addDeviceCard()

    // 1. Status and the main action.
    if (devices.isNotEmpty()) DeviceSyncState(syncStatus, connectedCount, activeDeviceCount - 1, waitingCount,
        lastChecked, onSyncNow = { SyncRequests.request(); viewModel.syncDevicesNow() })

    // 2. Problems next.
    if (rejected > 0) StatusBanner(kind = StatusKind.ERROR, title = "Changes need attention",
        message = "$rejected incoming ${if (rejected == 1) "change" else "changes"} could not be applied. Update both devices, then retry.",
        actionLabel = "Retry changes", onAction = { viewModel.retryRejectedSyncChanges() })
    ConflictInbox(conflicts) { reviewConflictId = it }

    // 3. Devices in this vault.
    if (devices.isNotEmpty()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SettingsHeading("Devices in this vault", Modifier.weight(1f))
                Text("$activeDeviceCount of $MAX_ACTIVE_SYNC_DEVICES", style = MaterialTheme.typography.labelMedium)
            }
            Text("Each device keeps its own copy and catches up when connected. $managerName manages the group.",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showAdvanced = !showAdvanced }, modifier = Modifier.heightIn(min = 48.dp)) {
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
            devices.forEach { device ->
                val (lastContact, lastExchange) = viewModel.deviceSyncContact(device.deviceId)
                val pendingTransfer = authorityTransfer
                DeviceSyncPeerCard(device = device, isManager = device.deviceId == managerDeviceId,
                    status = peerStatuses[device.deviceId], lastContact = lastContact,
                    lastExchange = lastExchange, progress = viewModel.deviceSyncProgress(device.deviceId),
                    savedAddress = viewModel.deviceSyncAddress(device.deviceId),
                    // A signed REMOVE cannot be issued while a managing-role transfer is pending.
                    canRemove = isManager && pendingTransfer == null,
                    showRemove = isManager,
                    onTryAddress = { viewModel.setDeviceSyncAddress(device.deviceId, it) },
                    onRemove = { removeDeviceId = device.deviceId },
                    counts = counts.getValue(device.deviceId), advanced = showAdvanced,
                    transferRole = when {
                        pendingTransfer == null || pendingTransfer.targetDeviceId != device.deviceId -> null
                        pendingTransfer.accepted -> "Accepted managing role"
                        else -> "Waiting to accept managing role"
                    },
                    removeHint = if (isManager && pendingTransfer != null)
                        "Cancel the managing-role transfer before removing a device." else null)
            }
            val removedDevices = memberships.filter { it.status != MemberStatus.ACTIVE.name && it.deviceId != localDevice?.deviceId }
            if (removedDevices.isNotEmpty()) HairlineCard(Modifier.fillMaxWidth()) {
                Column {
                    removedDevices.forEachIndexed { index, device ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.hairline)
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(devicePlatformIcon(device), contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                            Text(device.displayName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            StatusChip(StatusKind.NEUTRAL, "Removed")
                        }
                    }
                }
            }
        }
    }

    // 4. Automatic sync.
    if (devices.isNotEmpty()) SettingsSection("Automatic sync",
        description = "Nuvori checks your paired devices while it can reach them on Wi-Fi.") {
        SettingsSwitchRow("Automatic sync", autoOn, setAutomatic,
            description = if (autoOn) "On. Checks every $intervalLabel." else "Paused. Sync now still checks once.")
        Text("Check every", style = MaterialTheme.typography.labelLarge)
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
                    }, label = { Text(label) }, modifier = Modifier.weight(1f),
                        leadingIcon = if (interval == option) {
                            { Icon(NuvoriIcons.Check, contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                        } else null)
                }
            }
        }
        SettingsSecondaryButton("Add Auto sync tile", onClick = { requestAutoSyncTile(context) },
            icon = Icons.Outlined.AppShortcut)
        if (notificationDenied) StatusBanner(kind = StatusKind.WARNING,
            message = "Allow notifications to keep automatic sync active when Nuvori is closed.",
            actionLabel = "Allow notifications",
            onAction = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
        viewModel.deviceSyncQueueStatus()?.let { StatusBanner(kind = StatusKind.ERROR, message = it) }
    }

    // 5. Add a device (after the devices people already have).
    if (devices.isNotEmpty()) addDeviceCard()

    // 6. Managing role: a three-step handoff that every device can follow.
    val transfer = authorityTransfer
    if (devices.isNotEmpty() && (isManager || transfer != null)) SettingsSection("Managing role",
        description = "The managing device adds and removes devices and holds pairing invitations. Vault items sync the same on every device.") {
        val me = localDevice?.deviceId
        val targetName = (devices + listOfNotNull(localDevice)).firstOrNull { it.deviceId == transfer?.targetDeviceId }?.displayName
            ?: "the other device"
        TransferStepper(when {
            transfer == null -> TransferStep.OFFER
            !transfer.accepted -> TransferStep.ACCEPT
            else -> TransferStep.COMPLETE
        })
        when {
            transfer == null -> {
                Text("Choose the device that should take over. It accepts, then this device completes the handoff. Keep both devices syncing.",
                    style = MaterialTheme.typography.bodyMedium)
                devices.forEach { device ->
                    SettingsSecondaryButton("Offer to ${device.displayName}", icon = devicePlatformIcon(device),
                        onClick = { transferTargetId = device.deviceId })
                }
            }
            isManager -> {
                if (transfer.accepted) StatusBanner(kind = StatusKind.SUCCESS, title = "Complete transfer",
                    message = "$targetName accepted. Sync both devices, then complete the transfer.")
                else StatusBanner(kind = StatusKind.PROGRESS, title = "Waiting for $targetName to accept",
                    message = "Open Devices & sync on $targetName and accept. Keep both devices syncing.")
                if (transfer.accepted) SettingsPrimaryButton("Complete transfer",
                    onClick = viewModel::completeAuthorityTransfer, icon = NuvoriIcons.Check)
                SettingsSecondaryButton("Cancel transfer", onClick = viewModel::cancelAuthorityTransfer,
                    icon = NuvoriIcons.Close)
            }
            transfer.targetDeviceId == me -> {
                if (transfer.accepted) StatusBanner(kind = StatusKind.PROGRESS, title = "Accepted",
                    message = "Waiting for $managerName to complete the handoff.")
                else StatusBanner(kind = StatusKind.INFO, title = "Accept managing role?",
                    message = "$managerName offered this device the managing role. Both devices must finish syncing before you accept.")
                if (!transfer.accepted) SettingsPrimaryButton("Accept managing role",
                    onClick = { acceptTransfer = true }, icon = NuvoriIcons.Check)
                // TODO(sync): the membership chain only lets the managing device sign CANCEL_TRANSFER.
                // A recipient-side decline needs a new signed action or a cancel request to the manager.
                Text("To decline or cancel, use Cancel transfer on $managerName.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> StatusBanner(kind = StatusKind.INFO,
                title = if (transfer.accepted) "Handoff in progress" else "Managing role offered",
                message = if (transfer.accepted) "$targetName accepted. $managerName completes the handoff."
                    else "$managerName offered the managing role to $targetName. Waiting for it to accept.")
        }
    }

    // 7. Membership: leave the group, or for the managing device, why it cannot yet.
    if (devices.isNotEmpty()) Card(Modifier.fillMaxWidth(),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = .5f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Leave the group", modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            if (isManager) {
                Text("This device manages the group, so it can't leave yet. Transfer the managing role to another device first.",
                    style = MaterialTheme.typography.bodyMedium)
                SettingsDangerButton("Remove this device from the group", onClick = {}, enabled = false,
                    icon = Icons.Outlined.LinkOff)
                if (transfer == null) SettingsSecondaryButton("Transfer managing role", onClick = {
                    if (devices.size == 1) transferTargetId = devices.first().deviceId
                    else viewModel.notify("Choose a device under Managing role.", StatusKind.INFO)
                }, icon = NuvoriIcons.Shield)
                TextButton(onClick = { leaveGroup = true }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Start a separate group on this device instead", color = MaterialTheme.colorScheme.error)
                }
            } else {
                Text("This device keeps its vault copy and stops syncing. $managerName removes it from the group; no one needs to approve.",
                    style = MaterialTheme.typography.bodyMedium)
                SettingsDangerButton("Remove this device from the group", onClick = { leaveGroup = true },
                    icon = Icons.Outlined.LinkOff)
                Text("Lost the managing device? Leaving here keeps this vault copy; you can pair a new empty device from this one later.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    conflicts.firstOrNull { it.id == reviewConflictId }?.let { conflict ->
        ConflictReviewSheet(conflict, onDismiss = { reviewConflictId = null }) { useIncoming ->
            viewModel.resolveSyncConflict(conflict.id, useIncoming, conflict.currentVersion)
            reviewConflictId = null
        }
    }
    if (scanner) QrScanner(onResult = { value ->
        scanner = false
        if (scanWindowsQr) {
            if (runCatching { ReversePairingInvitation.decode(value) }.isSuccess) {
                reverseLink = value
                passwordError = null
                verifyPairing = true
            } else scanError = "Scan the Windows QR shown in Nuvori's Sync page."
        } else joinLink = value
    }, close = { scanner = false }, title = if (scanWindowsQr) "Scan Windows pairing QR" else "Scan Nuvori pairing QR",
        help = "Scan the QR shown on the other device. Keep both devices unlocked on reachable local networks.")
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
        val name = devices.firstOrNull { it.deviceId == deviceId }?.displayName ?: "this device"
        AlertDialog(onDismissRequest = { removeDeviceId = null },
            title = { Text("Remove $name?") },
            text = { Text("$name doesn't need to approve. It keeps its own copy of the vault but stops syncing with this group, and sees a notice the next time it reaches any of your devices. The group gets new sync keys.") },
            confirmButton = {
                TextButton(onClick = {
                    removeDeviceId = null
                    viewModel.notify("Removing $name…", StatusKind.PROGRESS)
                    viewModel.removeSyncDevice(deviceId)
                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeDeviceId = null }) { Text("Cancel") } })
    }
    if (leaveGroup) AlertDialog(onDismissRequest = { leaveGroup = false },
        title = { Text(if (isManager) "Start a separate group?" else "Remove this device from the group?") },
        text = { Text(if (isManager)
            "This device keeps its vault and starts a separate sync group with new keys. The other devices keep their copies, but nobody can add or remove members in the old group until it has a managing device again. Pair the watch again for codes."
            else "This device keeps its current vault and stops syncing with the group. $managerName removes it the next time they connect; no approval is needed. Changes waiting to arrive here must finish first. Pair the watch again for codes.") },
        confirmButton = { TextButton(onClick = {
            leaveGroup = false
            MembershipNoticeStore.leaveRequestedAt = System.currentTimeMillis()
            // For a member this also queues the signed leave request for the managing device.
            viewModel.leaveSyncGroup()
        }) { Text(if (isManager) "Start separate group" else "Leave group", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { leaveGroup = false }) { Text("Cancel") } })
    transferTargetId?.let { targetId ->
        val name = devices.firstOrNull { it.deviceId == targetId }?.displayName ?: "the other device"
        AlertDialog(onDismissRequest = { transferTargetId = null },
            title = { Text("Offer the managing role to $name?") },
            text = { Text("Step 1 of 3. $name must be up to date and accept. Then you complete the handoff here. You can cancel at any step. This device keeps syncing items afterwards.") },
            confirmButton = { TextButton(onClick = {
                transferTargetId = null
                viewModel.offerAuthorityTransfer(targetId)
            }) { Text("Offer") } },
            dismissButton = { TextButton(onClick = { transferTargetId = null }) { Text("Cancel") } })
    }
    if (acceptTransfer) AlertDialog(onDismissRequest = { acceptTransfer = false },
        title = { Text("Accept managing role?") },
        text = { Text("Step 2 of 3. This device will add and remove devices after $managerName completes the signed handoff. Vault items keep syncing on every device.") },
        confirmButton = { TextButton(onClick = {
            acceptTransfer = false
            viewModel.acceptAuthorityTransfer()
        }) { Text("Accept") } },
        dismissButton = { TextButton(onClick = { acceptTransfer = false }) { Text("Cancel") } })
    if (verifyPairing) AlertDialog(
        onDismissRequest = { if (!verifyingPassword) { verifyPairing = false; pairingPassword = ""; reverseLink = null } },
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
                        viewModel.hostDevicePairing(password, reverseLink)
                        reverseLink = null
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
            TextButton(enabled = !verifyingPassword, onClick = { verifyPairing = false; pairingPassword = ""; reverseLink = null }) {
                Text("Cancel")
            }
        })
}
