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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AppShortcut
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.QrCode
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.privatevault.app.sync.LanSyncService
import com.privatevault.app.sync.SyncOnOpen
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

internal fun relativeTime(then: Long, now: Long): String {
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
    val labels = listOf("Find", "Verify", "Sync", "Done")
    // Inactive dots use the full muted color (7.7:1 or more) so they pass 3:1; the current step is also bold,
    // and TalkBack reads the step instead of the dot colors.
    Row(modifier.fillMaxWidth().clearAndSetSemantics {
        contentDescription = "Sync progress"
        stateDescription = "Step ${step + 1} of ${labels.size}, ${labels[step].lowercase()}"
    }, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        labels.forEachIndexed { index, label ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).background(if (index <= step) accent else muted, CircleShape))
                Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    color = if (index == step) MaterialTheme.colorScheme.onSurface else muted,
                    fontWeight = if (index == step) FontWeight.Bold else FontWeight.Normal)
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
                listOf(waiting to "Waiting", connected to "Connected", paired to "Devices").forEach { (count, label) ->
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
    onChoose: (Boolean) -> Unit, onCombine: (() -> Unit)? = null) {
    var pendingChoice by remember(conflict.id) { mutableStateOf<Boolean?>(null) }
    var pendingCombine by remember(conflict.id) { mutableStateOf(false) }
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
            if (pendingCombine) {
                val panel = statusColors(StatusKind.INFO)
                Surface(color = panel.container, contentColor = panel.content, shape = NuvoriShapes.Card) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Keep both edits?", style = MaterialTheme.typography.titleSmall)
                        Text("The note keeps the lines and formatting of both versions, with text found in both shown once. Check it afterwards and tidy anything repeated.",
                            style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { pendingCombine = false },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Text("Go back", color = panel.content)
                            }
                            Button(onClick = { onCombine?.invoke() },
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                                Text("Keep both")
                            }
                        }
                    }
                }
            } else if (pendingChoice == null) {
                if (conflict.canCombineNotes && onCombine != null)
                    Button(onClick = { pendingCombine = true },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Text("Keep both edits")
                    }
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
                if (lastContact > 0) Text("Last connected: ${dateFormat.format(Date(lastContact))}",
                    style = MaterialTheme.typography.bodySmall)
                if (lastExchange > 0) Text("Last synced: ${dateFormat.format(Date(lastExchange))}",
                    style = MaterialTheme.typography.bodySmall)
                if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
                Text("Waiting to send: ${counts.toSend}. Waiting to apply here: ${counts.toApplyHere}.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Waiting to apply there: ${counts.receivedThere}. Waiting to arrive here: ${counts.toReceiveHere}.",
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
    // A screen timeout locks the vault, and locking cancels pairing while the other device connects.
    val pairingView = LocalView.current
    val pairingActive = state.stage in setOf("offering", "connecting", "confirm", "transferring")
    DisposableEffect(pairingActive) {
        pairingView.keepScreenOn = pairingActive
        onDispose { pairingView.keepScreenOn = false }
    }
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
    val lostManager by viewModel.lostManager.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val devices = memberships.filter { it.status == MemberStatus.ACTIVE.name }
    val roleCandidates = viewModel.managingRoleCandidates(devices)
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
    var markLost by remember { mutableStateOf(false) }
    var startNewGroup by remember { mutableStateOf(false) }
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
                    else "Connect another Android device or a Windows PC to sync over Wi-Fi.",
                    style = MaterialTheme.typography.bodyMedium)
                PairingStep(1, "Create a QR on the managing device", "Open Devices & sync there and tap Create QR.")
                PairingStep(2, "Scan it here", "On the empty device, tap Scan QR and point the camera at the code.")
                PairingStep(3, "Compare the codes", "Confirm the same code appears on both devices before the vault copy starts.")
                Text("Keep both devices unlocked during setup. A device that already has items keeps them. Items that differ show up for review.",
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
                    // After "Mark as lost", this device can also join the new group.
                    val canScan = devices.isEmpty() || lostManager != null
                    if (canCreate || canScan) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canCreate) SettingsPrimaryButton(
                            if (activeDeviceCount < MAX_ACTIVE_SYNC_DEVICES) "Create QR" else "Reconnect",
                            onClick = { reverseLink = null; passwordError = null; verifyPairing = true },
                            modifier = Modifier.weight(1f), icon = Icons.Outlined.QrCode, fill = false)
                        if (canScan) SettingsSecondaryButton("Scan QR", onClick = {
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
    // A device silent for months holds back the cleanup of old sync history: a suggestion only.
    viewModel.syncCleanupSuggestion()?.let { (deviceId, text) ->
        val removable = isManager && devices.any { it.deviceId == deviceId }
        StatusBanner(kind = StatusKind.INFO, message = text,
            actionLabel = if (removable) "Remove it" else null,
            onAction = if (removable) ({ removeDeviceId = deviceId }) else null)
    }
    // A removal changed the sync keys, and this device's package has not arrived yet (epoch-keys-v1).
    if (viewModel.waitingForSyncKeys()) StatusBanner(kind = StatusKind.INFO,
        message = "Waiting for new sync keys from $managerName. Changes made here stay on this device until they arrive. " +
            "Open Nuvori on $managerName, or put both devices on the same Wi-Fi.")
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
            Text("Up to 6 mobile devices and 2 Windows PCs share this vault. Each keeps its own copy and catches up when connected. $managerName manages the group.",
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
        var syncOnOpen by remember { mutableStateOf(SyncOnOpen.enabled(context)) }
        SettingsSwitchRow("Sync when Nuvori opens", syncOnOpen, { on ->
            syncOnOpen = on
            SyncOnOpen.setEnabled(context, on)
            viewModel.notify(if (on) "Nuvori syncs each time you open it" else "Opening Nuvori no longer starts a sync",
                StatusKind.SUCCESS)
        }, description = if (syncOnOpen) "On. Opening Nuvori checks nearby devices and Google Drive, also while automatic sync is paused."
            else "Off. Opening Nuvori doesn't start a check.")
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
        // Android needs a notification while Auto sync runs. On Android 13+ the user can hide it: the service keeps
        // running, Android lists Nuvori under Active apps in Quick Settings, and the tile shows On or Paused.
        if (autoOn && !notificationDenied && android.os.Build.VERSION.SDK_INT >= 33) {
            Text("Android shows a notification while Auto sync is on. To hide it, turn off the Auto sync notification. " +
                "Sync keeps running, and the Auto sync tile still shows On or Paused.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SettingsSecondaryButton("Hide Auto sync notification", onClick = {
                LanSyncService.createChannel(context)
                runCatching { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, LanSyncService.CHANNEL_ID)) }
            }, icon = Icons.Outlined.NotificationsOff)
        }
        if (notificationDenied) StatusBanner(kind = StatusKind.WARNING,
            message = "Allow notifications to keep automatic sync active when Nuvori is closed.",
            actionLabel = "Allow notifications",
            onAction = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) })
        viewModel.deviceSyncQueueStatus()?.let { StatusBanner(kind = StatusKind.ERROR, message = it) }
    }

    // 4b. Optional sync through the user's own Google Drive, for devices that are apart.
    if (devices.isNotEmpty()) CloudSyncCard(viewModel)

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
                if (roleCandidates.size < devices.size) Text("Only an Android phone or tablet can manage the group. A Windows PC can't take this role.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                roleCandidates.forEach { device ->
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
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Leave the group", modifier = Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            if (isManager) {
                Text("This device manages the group, so it can't leave yet. Transfer the managing role to another device first.",
                    style = MaterialTheme.typography.bodyMedium)
                SettingsDangerButton("Remove this device from the group", onClick = {}, enabled = false,
                    icon = Icons.Outlined.LinkOff)
                if (transfer == null) SettingsSecondaryButton("Transfer managing role", onClick = {
                    if (roleCandidates.size == 1) transferTargetId = roleCandidates.first().deviceId
                    else if (roleCandidates.isEmpty()) viewModel.notify("Only an Android phone or tablet can manage the group. Pair one first.", StatusKind.INFO)
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
                // A lost managing device: freeze the old group here, then start a new one or join one.
                if (lostManager == null) {
                    Text("Lost $managerName? Mark it as lost. This device then stops taking changes from it.",
                        style = MaterialTheme.typography.bodySmall)
                    SettingsSecondaryButton("Mark $managerName as lost", onClick = { markLost = true },
                        icon = NuvoriIcons.Shield)
                } else {
                    StatusBanner(StatusKind.WARNING, title = "$managerName is marked as lost",
                        message = "This device no longer takes changes from it. Make this device the managing device of a new group, or scan the QR of a device that already did.")
                    SettingsPrimaryButton("Make this the managing device", onClick = { startNewGroup = true },
                        icon = NuvoriIcons.Shield)
                    TextButton(onClick = viewModel::undoSyncManagerLost, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text("$managerName is back")
                    }
                }
            }
        }
    }

    conflicts.firstOrNull { it.id == reviewConflictId }?.let { conflict ->
        ConflictReviewSheet(conflict, onDismiss = { reviewConflictId = null },
            onChoose = { useIncoming ->
                viewModel.resolveSyncConflict(conflict.id, useIncoming, conflict.currentVersion)
                reviewConflictId = null
            },
            onCombine = {
                viewModel.resolveSyncConflict(conflict.id, useIncoming = false, conflict.currentVersion, combineNotes = true)
                reviewConflictId = null
            })
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
                        joinError = error.message ?: "Couldn't start pairing. Try again."
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
        val behind by produceState(emptyList<String>(), deviceId) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.devicesMissingChangesFrom(deviceId) }
        }
        val outdated by produceState<List<String>?>(null, deviceId) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { viewModel.devicesNeedingUpdateForRemoval(deviceId) }
        }
        AlertDialog(onDismissRequest = { removeDeviceId = null },
            title = { Text("Remove $name?") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$name doesn't need to approve. It keeps its own copy of the vault. Your devices stop accepting its changes, and it sees a notice the next time it reaches any of your devices.")
                // New sync keys only when every other device sent its signed key statement.
                if (outdated?.isEmpty() == true) Text("$name can't read anything you add from now on.",
                    style = MaterialTheme.typography.bodyMedium)
                else if (outdated != null) Text("${outdated!!.joinToString()} ${if (outdated!!.size == 1) "needs" else "need"} an update before Nuvori can change the sync keys. $name can't send changes, but it could still read new ones.",
                    color = MaterialTheme.colorScheme.error)
                Text("If $name was connected to Google Drive sync, also remove Nuvori's access in your Google account.",
                    style = MaterialTheme.typography.bodySmall)
                if (behind.isNotEmpty()) Text("${behind.joinToString()} ${if (behind.size == 1) "has" else "have"} not received every change from $name yet. Sync ${if (behind.size == 1) "that device" else "those devices"} first, or ${if (behind.size == 1) "it" else "they"} will not get those changes.",
                    color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = {
                TextButton(onClick = {
                    removeDeviceId = null
                    viewModel.notify("Removing $name…", StatusKind.PROGRESS)
                    viewModel.removeSyncDevice(deviceId)
                }) { Text(if (outdated.isNullOrEmpty()) "Remove" else "Remove anyway", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removeDeviceId = null }) { Text("Cancel") } })
    }
    if (markLost) AlertDialog(onDismissRequest = { markLost = false },
        title = { Text("Mark $managerName as lost?") },
        text = { Text("This device stops taking changes from $managerName. Your items stay. You can undo this.") },
        confirmButton = { TextButton(onClick = { markLost = false; viewModel.markSyncManagerLost() }) {
            Text("Mark as lost", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { markLost = false }) { Text("Cancel") } })
    if (startNewGroup) AlertDialog(onDismissRequest = { startNewGroup = false },
        title = { Text("Start a new group here?") },
        text = { Text("This device keeps its items and manages a new group. Then pair your other devices with it. They keep their items too, with no duplicates. $managerName can't read anything you add after that.") },
        confirmButton = { TextButton(onClick = { startNewGroup = false; viewModel.startGroupAfterLostManager() }) {
            Text("Start new group") } },
        dismissButton = { TextButton(onClick = { startNewGroup = false }) { Text("Cancel") } })
    if (leaveGroup) {
        // "Leave and empty this phone" (as Windows "Start with an empty vault"): ready to join another group.
        var emptyOnLeave by remember { mutableStateOf(false) }
        val canEmpty = remember { viewModel.changesHeldElsewhere() }
        AlertDialog(onDismissRequest = { leaveGroup = false },
        title = { Text(if (isManager) "Start a separate group?" else "Remove this device from the group?") },
        text = { if (isManager) Text(
            "This device keeps its vault and starts a separate sync group with new keys. The other devices keep their copies, but nobody can add or remove members in the old group until it has a managing device again. Pair the watch again for codes.")
            else Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This device stops syncing with the group. $managerName removes it the next time they connect; no approval is needed. Changes waiting to arrive here must finish first. Pair the watch again for codes.")
                listOf(false to ("Keep this device's items" to "Everything stays here as a separate vault. You can pair this device again later; its items are added as new copies."),
                    true to ("Leave and empty this device" to "Ready to join a sync group again. Items move to Recently deleted on this device for 30 days. Passkeys and folders are deleted here. Your other devices keep their copies."))
                    .forEach { (empty, labels) ->
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .selectable(selected = emptyOnLeave == empty, enabled = !empty || canEmpty,
                                role = androidx.compose.ui.semantics.Role.RadioButton) { emptyOnLeave = empty },
                            verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(selected = emptyOnLeave == empty, onClick = null, enabled = !empty || canEmpty)
                            Column(Modifier.padding(start = 8.dp)) {
                                Text(labels.first, style = MaterialTheme.typography.bodyLarge)
                                Text(labels.second, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                if (!canEmpty) Text("To empty this device, sync first: some changes from it have not reached another device yet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } },
        confirmButton = { TextButton(onClick = {
            leaveGroup = false
            MembershipNoticeStore.leaveRequestedAt = System.currentTimeMillis()
            // For a member this also queues the signed leave request for the managing device.
            viewModel.leaveSyncGroup(emptyItems = !isManager && emptyOnLeave)
        }) { Text(if (isManager) "Start separate group" else if (emptyOnLeave) "Leave and empty" else "Leave group",
            color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { leaveGroup = false }) { Text("Cancel") } })
    }
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
                            error.message else "Couldn't verify the password. Try again."
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

@Composable
private fun CloudPoint(title: String, detail: String) {
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(NuvoriIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Sync through the user's own Google Drive, for devices that are not on the same Wi-Fi. Same
 * wording as the Windows Sync page card. Shown only while this device is in a sync group.
 */
@Composable
internal fun CloudSyncCard(viewModel: VaultViewModel) {
    val status by viewModel.cloudSyncStatus.collectAsStateWithLifecycle()
    val checkInterval by com.privatevault.app.sync.CloudSyncRunner.checkInterval.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var waiting by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(status.lastSyncAt) {
        while (true) { now = System.currentTimeMillis(); delay(30_000) }
    }
    val googleScreen = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        // Google also closes its screen with RESULT_CANCELED when the sign-in fails (for example an
        // unregistered app or account), and puts the reason in the answer. Read it in every case.
        scope.launch {
            try { viewModel.connectGoogleDrive(result.data, fromGoogle = true) } finally { waiting = false }
        }
    }
    val connect: () -> Unit = {
        waiting = true
        scope.launch {
            var opened = false
            try {
                viewModel.connectGoogleDrive()?.let { screen ->
                    googleScreen.launch(androidx.activity.result.IntentSenderRequest.Builder(screen).build())
                    opened = true
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { viewModel.notify("Couldn't open Google sign-in. Try again.", StatusKind.ERROR) }
            finally { if (!opened) waiting = false }
        }
    }
    val (chipKind, chipLabel) = when {
        status.syncing -> StatusKind.PROGRESS to "Syncing"
        status.needsSignIn -> StatusKind.WARNING to "Sign in again"
        status.lastError != null -> StatusKind.WARNING to "Needs attention"
        else -> StatusKind.SUCCESS to "On"
    }
    HairlineCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsIconBadge(NuvoriIcons.Cloud)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (status.connected) "Google Drive" else "Sync when your devices are apart",
                        modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
                    Text(if (status.connected) status.account else "Optional. Uses your own Google Drive, not a Nuvori server.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (status.connected) StatusChip(chipKind, chipLabel)
            }
            if (!status.connected) {
                CloudPoint("Works anywhere", "Your devices sync through your Drive when they are not on the same Wi-Fi.")
                CloudPoint("Google can't read it", "Every change is encrypted on this device before it is uploaded.")
                CloudPoint("Only a hidden Nuvori folder", "Nuvori can't see or change your other Drive files.")
                CloudPoint("One account for all devices", "Use the same Google account on every device.")
                SettingsPrimaryButton(if (waiting) "Waiting for Google…" else "Connect Google Drive", onClick = connect,
                    icon = NuvoriIcons.Cloud, enabled = !waiting)
            } else {
                if (status.needsSignIn) StatusBanner(kind = StatusKind.WARNING, title = "Google needs you to sign in again",
                    message = "Access was removed or expired. Nothing is lost. Sync over Wi-Fi keeps working.",
                    actionLabel = if (waiting) null else "Sign in again", onAction = connect)
                else status.lastError?.let { StatusBanner(kind = StatusKind.WARNING, title = "Last Drive sync didn't finish", message = it) }
                Text("Last Drive sync: ${if (status.lastSyncAt > 0) relativeTime(status.lastSyncAt, now) else "not yet"}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("Check Google Drive every", style = MaterialTheme.typography.labelLarge)
                com.privatevault.app.sync.CloudSyncRunner.CHECK_INTERVALS.chunked(2).forEach { options ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { option ->
                            val label = when (option) {
                                15_000L -> "15 s"
                                60_000L -> "1 min"
                                300_000L -> "5 min"
                                else -> "Sync now only"
                            }
                            FilterChip(selected = checkInterval == option,
                                onClick = { if (checkInterval != option) viewModel.setCloudCheckInterval(option) },
                                label = { Text(label) }, modifier = Modifier.weight(1f),
                                leadingIcon = if (checkInterval == option) {
                                    { Icon(NuvoriIcons.Check, contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize)) }
                                } else null)
                        }
                    }
                }
                Text(if (checkInterval == 0L) "Checks when you open or unlock Nuvori and when you tap Sync now. This phone's own changes still upload right away."
                    else "While Nuvori is open. Shorter means other devices' changes arrive sooner. This phone's own changes upload right away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Your Drive stores encrypted changes only. On your other devices, connect the same Google account. Changes then sync through Drive whenever a device is unlocked and online.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsSecondaryButton("Disconnect", onClick = { confirmDisconnect = true }, icon = NuvoriIcons.LogOut)
            }
        }
    }
    if (confirmDisconnect) AlertDialog(onDismissRequest = { confirmDisconnect = false },
        title = { Text("Disconnect Google Drive on this device?") },
        text = { Text("This device stops syncing through Google Drive. Sync over Wi-Fi keeps working. The encrypted files stay in your Drive for your other devices.") },
        confirmButton = { TextButton(onClick = {
            confirmDisconnect = false
            viewModel.disconnectGoogleDrive()
        }) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = { confirmDisconnect = false }) { Text("Cancel") } })
}
