@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.privatevault.app

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.Totp
import com.privatevault.app.sync.DeviceSyncPhase
import com.privatevault.app.sync.MemberStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Locale

// ---------------------------------------------------------------------------------------------
// Sync state for Home, and acknowledgement of a user-started sync.
// ---------------------------------------------------------------------------------------------

/** Remembers that the user asked for a sync, so its finish or failure can be acknowledged. */
internal object SyncRequests {
    private val state = MutableStateFlow(0L)
    val requestedAt = state.asStateFlow()
    fun request() { state.value = System.currentTimeMillis() }
    fun clear() { state.value = 0L }
}

/** Shows a notice when a user-started sync finishes or fails. Automatic syncs stay quiet. */
@Composable
internal fun SyncAcknowledgementEffect(viewModel: VaultViewModel) {
    val requested by SyncRequests.requestedAt.collectAsStateWithLifecycle()
    val status by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    var sawActivity by remember(requested) { mutableStateOf(false) }
    LaunchedEffect(requested, status.phase) {
        if (requested == 0L) return@LaunchedEffect
        when (status.phase) {
            DeviceSyncPhase.SEARCHING, DeviceSyncPhase.FOUND, DeviceSyncPhase.CONNECTING,
            DeviceSyncPhase.TRANSFERRING -> sawActivity = true
            DeviceSyncPhase.CHECKED -> if (sawActivity) {
                SyncRequests.clear(); viewModel.notify("Sync complete · everything is up to date", StatusKind.SUCCESS)
            }
            DeviceSyncPhase.RECEIVED -> if (sawActivity) {
                SyncRequests.clear(); viewModel.notify("Synced · some changes are still waiting to apply", StatusKind.WARNING)
            }
            DeviceSyncPhase.ATTENTION -> {
                SyncRequests.clear(); viewModel.notify("Sync didn't finish. ${status.detail}".trim(), StatusKind.ERROR)
            }
            else -> Unit
        }
    }
    LaunchedEffect(requested) {
        if (requested == 0L) return@LaunchedEffect
        delay(90_000)
        if (SyncRequests.requestedAt.value == requested) {
            SyncRequests.clear()
            viewModel.notify("Still looking for your devices. Sync keeps trying in the background.", StatusKind.INFO)
        }
    }
}

internal data class HomeSync(
    val pairedDevices: Int = 0,
    val phase: DeviceSyncPhase = DeviceSyncPhase.OFF,
    val detail: String = "",
    val lastChecked: Long = 0L,
    val waiting: String? = null,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val transferOfferedHere: Boolean = false,
    val transferReadyToComplete: Boolean = false,
    val managerName: String = "",
)

/** Collects what Home needs to know about sync. Disk reads for timestamps happen off the main thread. */
@Composable
internal fun rememberHomeSync(viewModel: VaultViewModel): HomeSync {
    val status by viewModel.deviceSyncStatus.collectAsStateWithLifecycle()
    val members by viewModel.pairedDevices.collectAsStateWithLifecycle()
    val conflicts by viewModel.syncConflicts.collectAsStateWithLifecycle()
    val rejected by viewModel.rejectedSyncChanges.collectAsStateWithLifecycle()
    val transfer by viewModel.authorityTransfer.collectAsStateWithLifecycle()
    val managerId by viewModel.syncManagerDeviceId.collectAsStateWithLifecycle()
    val local by viewModel.localSyncDevice.collectAsStateWithLifecycle()
    val active = members.filter { it.status == MemberStatus.ACTIVE.name }
    val ids = active.map { it.deviceId }
    val details by produceState(0L to (null as String?), status.phase, ids) {
        value = withContext(Dispatchers.IO) {
            val last = runCatching { ids.maxOfOrNull { viewModel.deviceSyncContact(it).second } ?: 0L }.getOrDefault(0L)
            val waiting = if (ids.isEmpty()) null else runCatching { viewModel.deviceSyncQueueStatus() }.getOrNull()
            last to waiting
        }
    }
    val me = local?.deviceId
    return HomeSync(
        pairedDevices = active.size, phase = status.phase, detail = status.detail,
        lastChecked = details.first, waiting = details.second, conflicts = conflicts.size, rejected = rejected,
        transferOfferedHere = transfer?.let { it.targetDeviceId == me && !it.accepted } == true,
        transferReadyToComplete = transfer?.accepted == true && managerId != null && managerId == me,
        managerName = (members + listOfNotNull(local)).firstOrNull { it.deviceId == managerId }?.displayName.orEmpty(),
    )
}

internal data class AttentionItem(val kind: StatusKind, val title: String, val detail: String? = null, val onClick: () -> Unit)

internal data class CalmLine(val kind: StatusKind, val text: String)

private fun syncAttention(sync: HomeSync, now: Long, openDevices: () -> Unit): List<AttentionItem> = buildList {
    if (sync.transferOfferedHere) add(AttentionItem(StatusKind.INFO, "Accept managing role?",
        "${sync.managerName.ifBlank { "The managing device" }} offered this device the role", openDevices))
    if (sync.transferReadyToComplete) add(AttentionItem(StatusKind.SUCCESS, "Complete the managing-role transfer",
        "The other device accepted", openDevices))
    if (sync.pairedDevices == 0) return@buildList
    if (sync.conflicts > 0) add(AttentionItem(StatusKind.ERROR,
        "${sync.conflicts} sync ${if (sync.conflicts == 1) "conflict" else "conflicts"} to review", null, openDevices))
    if (sync.rejected > 0) add(AttentionItem(StatusKind.WARNING,
        "${sync.rejected} ${if (sync.rejected == 1) "change" else "changes"} couldn't apply", "Retry from Devices & sync", openDevices))
    when {
        sync.phase == DeviceSyncPhase.ATTENTION -> add(AttentionItem(StatusKind.ERROR, "Sync needs attention",
            sync.detail.takeIf { it.isNotBlank() }, openDevices))
        sync.phase == DeviceSyncPhase.RECEIVED -> add(AttentionItem(StatusKind.WARNING, "Changes are waiting to apply", null, openDevices))
        sync.waiting != null && sync.phase == DeviceSyncPhase.CHECKED -> add(AttentionItem(StatusKind.WARNING, sync.waiting, null, openDevices))
        sync.lastChecked > 0 && now - sync.lastChecked > 3L * 24 * 60 * 60 * 1000 ->
            add(AttentionItem(StatusKind.WARNING, "Devices haven't synced in ${(now - sync.lastChecked) / 86_400_000} days",
                "Open Nuvori on your other device on the same Wi-Fi", openDevices))
    }
}

private fun calmLine(sync: HomeSync, itemCount: Int, now: Long): CalmLine = when {
    sync.pairedDevices == 0 -> CalmLine(StatusKind.SUCCESS, "Unlocked · $itemCount ${if (itemCount == 1) "item" else "items"} on this phone")
    sync.phase == DeviceSyncPhase.TRANSFERRING || sync.phase == DeviceSyncPhase.CONNECTING ->
        CalmLine(StatusKind.PROGRESS, "Syncing with your devices…")
    sync.phase == DeviceSyncPhase.SEARCHING || sync.phase == DeviceSyncPhase.FOUND ->
        CalmLine(StatusKind.PROGRESS, "Looking for your devices…")
    sync.phase == DeviceSyncPhase.PAUSED -> CalmLine(StatusKind.NEUTRAL, "Automatic sync is paused")
    sync.phase == DeviceSyncPhase.WAITING -> CalmLine(StatusKind.NEUTRAL, "Waiting for Wi-Fi to sync")
    sync.phase == DeviceSyncPhase.OFF -> CalmLine(StatusKind.NEUTRAL, "Device sync is off")
    sync.lastChecked > 0 -> CalmLine(StatusKind.SUCCESS, "Everything's in sync · ${relativeTimeShort(sync.lastChecked, now)}")
    else -> CalmLine(StatusKind.SUCCESS, "Everything's in sync")
}

// ---------------------------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------------------------

/** The shared search field: flat, 12dp, with a Lucide search glyph and a clear button. */
@Composable
internal fun NuvoriSearchField(value: String, onValue: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier) {
    val keyboard = LocalSoftwareKeyboardController.current
    val scheme = MaterialTheme.colorScheme
    OutlinedTextField(
        value, onValue,
        placeholder = { Text(placeholder, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(NuvoriIcons.Search, contentDescription = null, modifier = Modifier.size(20.dp)) },
        trailingIcon = if (value.isNotEmpty()) {{
            IconButton(onClick = { onValue("") }, modifier = Modifier.size(48.dp)) {
                Icon(NuvoriIcons.Close, contentDescription = "Clear search", modifier = Modifier.size(20.dp))
            }
        }} else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        shape = NuvoriShapes.Card,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = scheme.raised, unfocusedContainerColor = scheme.raised,
            unfocusedBorderColor = scheme.hairline, focusedBorderColor = scheme.primary,
            unfocusedLeadingIconColor = scheme.onSurfaceVariant, focusedLeadingIconColor = scheme.onSurface),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
    )
}

/** Greeting, item count, lock, and the prominent search entry. */
@Composable
internal fun HomeHeader(itemCount: Int, search: String, onSearch: (String) -> Unit, onLock: () -> Unit, modifier: Modifier = Modifier) {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NuvoriLogo(Modifier.size(32.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(greeting(hour), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                Text("$itemCount ${if (itemCount == 1) "item" else "items"} in your vault",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onLock, modifier = Modifier.size(48.dp)) {
                Icon(NuvoriIcons.Lock, contentDescription = "Lock vault", modifier = Modifier.size(22.dp))
            }
        }
        NuvoriSearchField(search, onSearch, "Search vault", Modifier.padding(end = 8.dp))
    }
}

// ---------------------------------------------------------------------------------------------
// Home content
// ---------------------------------------------------------------------------------------------

@Composable
internal fun HomeScreen(
    entries: List<EntryWithDetails>,
    open: (String) -> Unit,
    openCategory: (EntryType) -> Unit,
    modifier: Modifier,
    passkeyCount: Int = 0,
    openPasskeys: () -> Unit = {},
    groupCount: Int = 0,
    openGroups: () -> Unit = {},
    sync: HomeSync = HomeSync(),
    membershipNotice: MembershipNotice? = null,
    dismissMembershipNotice: () -> Unit = {},
    listState: LazyListState? = null,
    openDevices: () -> Unit = {},
    copy: (String, String) -> Unit = { _, _ -> },
    authenticate: (() -> Unit) -> Unit = { it() },
    onAdd: () -> Unit = {},
    onSyncNow: () -> Unit = {},
    onScanImport: () -> Unit = {},
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = System.currentTimeMillis() } }
    val quick = remember(entries) {
        val favorites = entries.filter { it.entry.favorite && it.entry.type != EntryType.AUTHENTICATOR }
            .sortedBy { it.entry.title.lowercase(Locale.ROOT) }
        val recent = entries.filter { it.entry.lastOpenedAt > 0 && !it.entry.favorite && it.entry.type != EntryType.AUTHENTICATOR }
            .sortedByDescending { it.entry.lastOpenedAt }
        (favorites + recent).take(12)
    }
    val codes = remember(entries) {
        val all = entries.filter { it.entry.type == EntryType.AUTHENTICATOR }.map { it.entry }
        val favorites = all.filter { it.favorite }
        (favorites + all.filter { !it.favorite }.sortedByDescending { it.lastOpenedAt }).take(4)
    }
    val expiring = remember(entries) {
        entries.filter { it.entry.type == EntryType.CARD && cardExpiryKind(it.entry.tertiaryValue) != null }
    }
    val attention = buildList {
        addAll(syncAttention(sync, now, openDevices))
        expiring.take(3).forEach { item ->
            val kind = cardExpiryKind(item.entry.tertiaryValue) ?: return@forEach
            add(AttentionItem(kind, "${item.entry.title} ${if (kind == StatusKind.ERROR) "has expired" else "expires soon"}",
                "Card ending ${item.entry.primaryValue.filter(Char::isDigit).takeLast(4)}") { open(item.entry.id) })
        }
    }
    val counts = remember(entries) { EntryType.entries.associateWith { type -> entries.count { it.entry.type == type } } }
    val state = listState ?: androidx.compose.foundation.lazy.rememberLazyListState()
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 760.dp).fillMaxWidth(), state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)) {
            item(key = "status") {
                Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    membershipNotice?.let { MembershipNoticeBanner(it, onDismiss = dismissMembershipNotice, onOpenDevices = openDevices) }
                    if (attention.isEmpty()) CalmStatusLine(calmLine(sync, entries.size, now))
                    else attention.forEach { AttentionRow(it) }
                }
            }
            item(key = "quick") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Quick access")
                    if (quick.isEmpty()) EmptyHint(NuvoriIcons.Star, "Star an item or open one, and it shows up here.")
                    else LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(end = 4.dp)) {
                        items(quick, key = { it.entry.id }) { item ->
                            QuickAccessCard(item, { open(item.entry.id) }, copy, authenticate, Modifier.animateItemPlacement())
                        }
                    }
                }
            }
            if (codes.isNotEmpty()) item(key = "codes") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Codes", action = "All codes", onAction = { openCategory(EntryType.AUTHENTICATOR) })
                    LiveCodes(codes, copy, open)
                }
            }
            item(key = "categories") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Your vault")
                    CategoryGrid(listOf(
                        CategoryTile("Passwords", counts[EntryType.PASSWORD] ?: 0, EntryType.PASSWORD.icon, tint(EntryType.PASSWORD.tintIndex)) { openCategory(EntryType.PASSWORD) },
                        CategoryTile("Cards", counts[EntryType.CARD] ?: 0, EntryType.CARD.icon, tint(EntryType.CARD.tintIndex)) { openCategory(EntryType.CARD) },
                        CategoryTile("Codes", counts[EntryType.AUTHENTICATOR] ?: 0, EntryType.AUTHENTICATOR.icon, tint(EntryType.AUTHENTICATOR.tintIndex)) { openCategory(EntryType.AUTHENTICATOR) },
                        CategoryTile("Notes", counts[EntryType.NOTE] ?: 0, EntryType.NOTE.icon, tint(EntryType.NOTE.tintIndex)) { openCategory(EntryType.NOTE) },
                        CategoryTile("Questions", counts[EntryType.QUESTION] ?: 0, EntryType.QUESTION.icon, tint(EntryType.QUESTION.tintIndex)) { openCategory(EntryType.QUESTION) },
                        CategoryTile("Autofill", counts[EntryType.AUTOFILL] ?: 0, EntryType.AUTOFILL.icon, tint(EntryType.AUTOFILL.tintIndex)) { openCategory(EntryType.AUTOFILL) },
                        CategoryTile("Passkeys", passkeyCount, NuvoriIcons.Passkey, neutralTint(), openPasskeys),
                        CategoryTile("Groups", groupCount, NuvoriIcons.Folder, neutralTint(), openGroups),
                    ))
                }
            }
            item(key = "actions") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionHeader("Quick actions")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val syncing = sync.phase == DeviceSyncPhase.TRANSFERRING || sync.phase == DeviceSyncPhase.CONNECTING
                        QuickActionTile(NuvoriIcons.Add, "Add", Modifier.weight(1f), onClick = onAdd)
                        QuickActionTile(NuvoriIcons.Sync, if (syncing) "Syncing" else "Sync now", Modifier.weight(1f),
                            busy = syncing, onClick = onSyncNow)
                        QuickActionTile(NuvoriIcons.Scan, "Scan or import", Modifier.weight(1f), onClick = onScanImport)
                    }
                }
            }
        }
    }
}

/** Kept for existing callers and tests; Home is now [HomeScreen]. */
@Composable
internal fun Dashboard(entries: List<EntryWithDetails>, select: (String) -> Unit,
    openCategory: (EntryType) -> Unit, modifier: Modifier, passkeyCount: Int = 0,
    openPasskeys: () -> Unit = {}, groupCount: Int = 0, openGroups: () -> Unit = {}) =
    HomeScreen(entries, select, openCategory, modifier, passkeyCount, openPasskeys, groupCount, openGroups)

/** Expired cards are errors; cards expiring within 90 days are warnings. */
internal fun cardExpiryKind(value: String): StatusKind? {
    val parts = value.trim().split('/', '-', ' ')
    val month = parts.getOrNull(0)?.toIntOrNull() ?: return null
    var year = parts.getOrNull(1)?.toIntOrNull() ?: return null
    if (year < 100) year += 2000
    if (month !in 1..12) return null
    val end = Calendar.getInstance(Locale.US).apply { clear(); set(year, month, 1); add(Calendar.MILLISECOND, -1) }.timeInMillis
    val now = System.currentTimeMillis()
    return when {
        end < now -> StatusKind.ERROR
        end - now <= 90L * 24 * 60 * 60 * 1000 -> StatusKind.WARNING
        else -> null
    }
}

@Composable
private fun neutralTint() = Tint(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun CalmStatusLine(line: CalmLine) {
    val colors = statusColors(line.kind)
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusIcon(line.kind, colors.accent, size = 16.dp)
        Text(line.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun AttentionRow(item: AttentionItem) {
    val colors = statusColors(item.kind)
    Surface(Modifier.fillMaxWidth().clip(NuvoriShapes.Card).tappable(onClick = item.onClick),
        color = colors.container, contentColor = colors.content, shape = NuvoriShapes.Card) {
        Row(Modifier.heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusIcon(item.kind, colors.accent)
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                item.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Icon(NuvoriIcons.ChevronRight, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun EmptyHint(icon: ImageVector, text: String) {
    HairlineCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Compact item card: tap opens, long-press offers copy shortcuts. */
@Composable
private fun QuickAccessCard(item: EntryWithDetails, open: () -> Unit, copy: (String, String) -> Unit,
    authenticate: (() -> Unit) -> Unit, modifier: Modifier = Modifier) {
    var menu by remember { mutableStateOf(false) }
    val actions = remember(item.entry) { copyActions(item.entry, copy, authenticate) }
    val scheme = MaterialTheme.colorScheme
    Box(modifier) {
        Surface(Modifier.width(156.dp).clip(NuvoriShapes.Card)
            .tappable(onLongClick = if (actions.isNotEmpty()) {{ menu = true }} else null,
                onLongClickLabel = "Copy options", onClick = open)
            .semantics { contentDescription = "Open ${item.entry.type.legacyLabel()}: ${item.entry.title}" },
            shape = NuvoriShapes.Card, color = scheme.raised,
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.hairline)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    EntryLeading(item.entry, size = 32.dp)
                    Spacer(Modifier.weight(1f))
                    if (item.entry.favorite) FavoriteMark()
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.entry.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(entrySubtitle(item, includeType = false, includeFolder = false).ifBlank { item.entry.type.itemLabel },
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; open() },
                leadingIcon = { Icon(NuvoriIcons.ChevronRight, null, Modifier.size(18.dp)) })
            actions.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() },
                    leadingIcon = { Icon(NuvoriIcons.Copy, null, Modifier.size(18.dp)) })
            }
        }
    }
}

/** Up to four live codes with countdown rings. One clock drives them all. Tap copies; long-press opens. */
@Composable
private fun LiveCodes(codes: List<VaultEntry>, copy: (String, String) -> Unit, open: (String) -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { nowMs = System.currentTimeMillis(); delay(250) }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= 560.dp) 4 else 2
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            codes.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { entry -> androidx.compose.runtime.key(entry.id) { LiveCodeTile(entry, nowMs, copy, { open(entry.id) }, Modifier.weight(1f)) } }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun LiveCodeTile(entry: VaultEntry, nowMs: Long, copy: (String, String) -> Unit, open: () -> Unit, modifier: Modifier) {
    val period = entry.totpPeriod.coerceAtLeast(1)
    val periodMs = period * 1000L
    val step = nowMs / periodMs
    val code = remember(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period, step) {
        runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period, nowMs / 1000) }.getOrNull()
    }
    val remainingMs = periodMs - nowMs % periodMs
    val seconds = (remainingMs + 999) / 1000
    val accent = when {
        seconds <= 2 -> statusColors(StatusKind.ERROR).accent
        seconds <= 5 -> statusColors(StatusKind.WARNING).accent
        else -> MaterialTheme.colorScheme.primary
    }
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.clip(NuvoriShapes.Card)
        .tappable(onClickLabel = "Copy code", onLongClickLabel = "Open", onLongClick = open, onClick = {
            runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period) }
                .onSuccess { copy("Authenticator code", it) }
        })
        .semantics { contentDescription = "Copy code for ${entry.title}" },
        shape = NuvoriShapes.Card, color = scheme.raised, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.hairline)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(entry.title.ifBlank { "Code" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                CountdownRing(remainingMs.toFloat() / periodMs, seconds, accent, size = 24.dp)
            }
            Text(code?.let(::formatTotp) ?: "––– –––", fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace, letterSpacing = 1.sp, maxLines = 1,
                color = if (seconds <= 5) accent else scheme.onSurface)
        }
    }
}

private data class CategoryTile(val label: String, val count: Int, val icon: ImageVector, val tint: Tint, val onClick: () -> Unit)

@Composable
private fun CategoryGrid(tiles: List<CategoryTile>) {
    val hairline = MaterialTheme.colorScheme.hairline
    HairlineCard(Modifier.fillMaxWidth()) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val columns = when {
                maxWidth >= 640.dp -> tiles.size
                maxWidth >= 280.dp -> 4
                else -> 2
            }
            Column {
                tiles.chunked(columns).forEachIndexed { rowIndex, row ->
                    if (rowIndex > 0) HorizontalDivider(color = hairline)
                    Row(Modifier.fillMaxWidth().height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
                        row.forEachIndexed { index, tile ->
                            if (index > 0) VerticalDivider(color = hairline)
                            CategoryCell(tile, Modifier.weight(1f))
                        }
                        repeat(columns - row.size) {
                            VerticalDivider(color = hairline)
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryCell(tile: CategoryTile, modifier: Modifier) {
    Column(modifier.tappable(pressedScale = .96f, onClick = tile.onClick)
        .semantics(mergeDescendants = true) { contentDescription = "${tile.label}, ${tile.count}" }
        .heightIn(min = 64.dp).padding(horizontal = 4.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        TintedIcon(tile.icon, tile.tint, size = 28.dp)
        Text(tile.count.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            lineHeight = 20.sp)
        Text(tile.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun QuickActionTile(icon: ImageVector, label: String, modifier: Modifier, busy: Boolean = false, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier.clip(NuvoriShapes.Card).tappable(onClick = onClick), shape = NuvoriShapes.Card,
        color = scheme.raised, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.hairline)) {
        Column(Modifier.heightIn(min = 76.dp).padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (busy) StatusIcon(StatusKind.PROGRESS, scheme.primary, size = 22.dp)
            else Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Sheets
// ---------------------------------------------------------------------------------------------

/** Pick what to add. Every item type, with its shared icon and color. */
@Composable
internal fun AddTypeSheet(onDismiss: () -> Unit, onPick: (EntryType) -> Unit) {
    val types = listOf(
        EntryType.PASSWORD to "Website or app sign-in",
        EntryType.CARD to "Credit or debit card",
        EntryType.AUTHENTICATOR to "Two-step verification code",
        EntryType.NOTE to "Private text",
        EntryType.QUESTION to "Question and answer",
        EntryType.AUTOFILL to "Name, email, phone, address",
    )
    NuvoriSheet("Add to your vault", onDismiss) {
        types.forEachIndexed { index, (type, hint) ->
            SheetRow(type.icon, tint(type.tintIndex), type.itemLabel, hint, index, types.size) { onPick(type) }
        }
    }
}

/** Scanning and import shortcuts. */
@Composable
internal fun ScanImportSheet(onDismiss: () -> Unit, scanCode: () -> Unit, importCodes: () -> Unit, importPasswords: () -> Unit,
    restoreBackup: () -> Unit) {
    val rows = listOf(
        Triple(NuvoriIcons.Scan, "Scan a setup QR", "Add a two-step code with the camera") to scanCode,
        Triple(NuvoriIcons.Code, "Import authenticator codes", "From Google Authenticator or OneAuth") to importCodes,
        Triple(NuvoriIcons.Import, "Import passwords", "Browser or password manager export") to importPasswords,
        Triple(NuvoriIcons.History, "Restore a backup", "Encrypted .pvault file") to restoreBackup,
    )
    NuvoriSheet("Scan or import", onDismiss) {
        rows.forEachIndexed { index, (row, action) ->
            SheetRow(row.first, tint(if (index == 0) 0 else 1), row.second, row.third, index, rows.size, action)
        }
    }
}

@Composable
private fun NuvoriSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = NuvoriShapes.Sheet, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 12.dp).semantics { heading() })
            content()
        }
    }
}

@Composable
private fun SheetRow(icon: ImageVector, tint: Tint, title: String, hint: String, index: Int, count: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().groupedSegment(index, count, scheme.raised, scheme.hairline)
        .clip(segmentShape(index, count)).tappable(pressedScale = .985f, onClick = onClick)
        .heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TintedIcon(icon, tint, size = 36.dp)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
        Icon(NuvoriIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}
