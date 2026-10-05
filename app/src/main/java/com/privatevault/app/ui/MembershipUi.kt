package com.privatevault.app

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.sync.MemberStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.DateFormat
import java.util.Date

/**
 * A membership change this device should keep telling the user about until they dismiss it:
 * it was removed by the managing device, or it left the group on its own. Contains names and a time only.
 */
internal data class MembershipNotice(val kind: Kind, val by: String, val at: Long, val pendingLeave: Boolean = false) {
    enum class Kind { REMOVED, LEFT }
}

/**
 * Device-only store for the "left the group" banner. Nothing secret is stored. A verified removal comes from
 * [VaultViewModel.removedNotice] instead, which the sync stream persists and clears.
 */
internal object MembershipNoticeStore {
    private const val PREFS = "nuvori_membership_ui"
    private val state = MutableStateFlow<MembershipNotice?>(null)
    private var loaded = false
    val notice = state.asStateFlow()

    /** Set while a user-confirmed leave is running, so the banner appears only if leaving succeeded. */
    @Volatile var leaveRequestedAt = 0L

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val kind = prefs.getString("kind", null)?.let { runCatching { MembershipNotice.Kind.valueOf(it) }.getOrNull() }
        state.value = kind?.let { MembershipNotice(it, prefs.getString("by", "").orEmpty(), prefs.getLong("at", 0L)) }
    }

    fun record(context: Context, notice: MembershipNotice) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("kind", notice.kind.name).putString("by", notice.by).putLong("at", notice.at).apply()
        loaded = true
        state.value = notice
    }

    fun dismiss(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        state.value = null
    }
}

/** The banner to show, if any: a verified removal first, otherwise a leave this device finished. */
@Composable
internal fun rememberMembershipNotice(viewModel: VaultViewModel): MembershipNotice? {
    val context = LocalContext.current
    LaunchedEffect(Unit) { MembershipNoticeStore.load(context.applicationContext) }
    val left by MembershipNoticeStore.notice.collectAsStateWithLifecycle()
    val removed by viewModel.removedNotice.collectAsStateWithLifecycle()
    val pendingLeave by viewModel.pendingLeave.collectAsStateWithLifecycle()
    return removed?.let { MembershipNotice(MembershipNotice.Kind.REMOVED, it.byName, it.at) }
        ?: left?.copy(pendingLeave = pendingLeave)
}

/** Dismisses whichever notice is showing. */
internal fun dismissMembershipNotice(viewModel: VaultViewModel, notice: MembershipNotice, context: Context) {
    if (notice.kind == MembershipNotice.Kind.REMOVED) viewModel.dismissRemovedNotice()
    else MembershipNoticeStore.dismiss(context.applicationContext)
}

/** Persistent banner for Home and Devices & sync. Stays until dismissed. */
@Composable
internal fun MembershipNoticeBanner(notice: MembershipNotice, onDismiss: () -> Unit, modifier: Modifier = Modifier,
    onOpenDevices: (() -> Unit)? = null) {
    val date = remember(notice.at) {
        if (notice.at > 0) DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(notice.at)) else null
    }
    val removed = notice.kind == MembershipNotice.Kind.REMOVED
    val message = if (removed) buildString {
        append("This device was removed from the sync group")
        if (notice.by.isNotBlank()) append(" by ").append(notice.by)
        if (date != null) append(" on ").append(date)
        append(". Your items stay on this device.")
    } else buildString {
        append("This device left the sync group")
        if (date != null) append(" on ").append(date)
        append(". Your items stay on this device and no longer sync.")
        if (notice.pendingLeave) append(" The managing device is told the next time it can be reached.")
    }
    Column(modifier) {
        StatusBanner(kind = if (removed) StatusKind.WARNING else StatusKind.INFO,
            title = if (removed) "Removed from sync" else "Left the sync group", message = message,
            actionLabel = "Dismiss", onAction = onDismiss)
        if (onOpenDevices != null) TextButton(onClick = onOpenDevices, modifier = Modifier.heightIn(min = 40.dp)) {
            Text("Open Devices & sync")
        }
    }
}

/**
 * Turns membership changes into notices wherever the user is: incoming transfer offers, acceptance, a completed
 * handoff, and a confirmed local leave. Skips the first value so unlocking does not replay old state.
 */
@Composable
internal fun MembershipNoticesEffect(viewModel: VaultViewModel) {
    val context = LocalContext.current
    val transfer by viewModel.authorityTransfer.collectAsStateWithLifecycle()
    val managerId by viewModel.syncManagerDeviceId.collectAsStateWithLifecycle()
    val local by viewModel.localSyncDevice.collectAsStateWithLifecycle()
    val members by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val activePeers = members.count { it.status == MemberStatus.ACTIVE.name }
    var previousTransfer by remember { mutableStateOf<AuthorityTransferState?>(null) }
    var previousManager by remember { mutableStateOf<String?>(null) }
    var primed by remember { mutableStateOf(false) }
    LaunchedEffect(transfer, managerId, local?.deviceId) {
        val me = local?.deviceId
        if (primed && me != null) {
            val name = { id: String? -> members.firstOrNull { it.deviceId == id }?.displayName ?: "the other device" }
            val before = previousTransfer
            when {
                transfer != null && before == null && transfer?.targetDeviceId == me ->
                    viewModel.notify("${name(managerId)} offered this device the managing role. Open Devices & sync to accept.", StatusKind.INFO)
                transfer?.accepted == true && before?.accepted == false && managerId == me ->
                    viewModel.notify("${name(transfer?.targetDeviceId)} accepted. Complete the transfer to finish.", StatusKind.SUCCESS)
                transfer == null && before != null && managerId == previousManager && managerId != me && before.targetDeviceId == me ->
                    viewModel.notify("The managing-role offer was cancelled.", StatusKind.INFO)
            }
            if (managerId != null && previousManager != null && managerId != previousManager && managerId == me)
                viewModel.notify("This device now manages the sync group.", StatusKind.SUCCESS)
        }
        previousTransfer = transfer
        previousManager = managerId
        primed = true
    }
    LaunchedEffect(activePeers) {
        val requested = MembershipNoticeStore.leaveRequestedAt
        if (requested > 0 && activePeers == 0) {
            MembershipNoticeStore.leaveRequestedAt = 0L
            MembershipNoticeStore.record(context.applicationContext,
                MembershipNotice(MembershipNotice.Kind.LEFT, "", System.currentTimeMillis()))
        } else if (requested > 0 && System.currentTimeMillis() - requested > 120_000) MembershipNoticeStore.leaveRequestedAt = 0L
    }
}

/**
 * Best guess at a device's platform from its name.
 * TODO(sync): use a real platform field when membership records carry one (SPEC3 lists android|windows|unknown).
 */
internal fun devicePlatformIcon(device: SyncMembershipEntity): ImageVector {
    val name = device.displayName.lowercase()
    val desktop = listOf("windows", " pc", "desktop", "laptop", "surface", "nuvori for windows").any { name.contains(it) } ||
        name.startsWith("pc") || name.startsWith("desktop-") || name.startsWith("laptop-")
    return if (desktop) NuvoriIcons.Monitor else NuvoriIcons.Phone
}

/** Steps of the managing-role handoff. */
internal enum class TransferStep { OFFER, ACCEPT, COMPLETE }

/** 1 Offer → 2 Accept → 3 Complete, with the current step highlighted and done steps checked. */
@Composable
internal fun TransferStepper(current: TransferStep, modifier: Modifier = Modifier) {
    val labels = listOf("Offer" to "Managing device", "Accept" to "Other device", "Complete" to "Managing device")
    Row(modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { index, (label, who) ->
            val done = index < current.ordinal
            val active = index == current.ordinal
            val scheme = MaterialTheme.colorScheme
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(28.dp).background(
                    when { done -> scheme.primary; active -> scheme.primaryContainer; else -> scheme.surfaceVariant }, CircleShape),
                    contentAlignment = Alignment.Center) {
                    if (done) Icon(NuvoriIcons.Check, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(16.dp))
                    else Text("${index + 1}", style = MaterialTheme.typography.labelLarge,
                        color = if (active) scheme.onPrimaryContainer else scheme.onSurfaceVariant)
                }
                Text(label + if (done) ", done" else if (active) ", now" else "",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active || done) scheme.onSurface else scheme.onSurfaceVariant, maxLines = 1)
                Text(who, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

/** A tiny "Managing device" badge. */
@Composable
internal fun ManagingBadge(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier.background(scheme.primaryContainer, NuvoriShapes.Chip).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(NuvoriIcons.Shield, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(4.dp))
        Text("Managing device", style = MaterialTheme.typography.labelSmall, color = scheme.onPrimaryContainer,
            fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
