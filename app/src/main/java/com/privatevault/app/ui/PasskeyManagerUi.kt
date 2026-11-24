package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.Button
import com.privatevault.app.controls.OutlinedButton
import com.privatevault.app.controls.TextButton
import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.privatevault.app.data.PasskeyEventKind
import com.privatevault.app.data.PasskeyHistoryEvent
import com.privatevault.app.data.PasskeyInfo
import com.privatevault.app.data.PasskeyOrigin
import com.privatevault.app.passkeys.PasskeyLedger
import com.privatevault.app.security.UsageSnapshot
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** Everything the passkeys page shows, read once per refresh. No private keys. */
internal data class PasskeyPageData(
    val passkeys: List<PasskeyInfo> = emptyList(),
    val origins: Map<String, PasskeyOrigin> = emptyMap(),
    /** The whole ledger, newest first, including lines for deleted passkeys. */
    val history: List<PasskeyHistoryEvent> = emptyList(),
    val usage: UsageSnapshot = UsageSnapshot.EMPTY,
)

private fun dateTime(at: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(at))

/** A small PNG from the vault, decoded once. Null when it is not a readable image. */
@Composable
private fun rememberPng(bytes: ByteArray?): ImageBitmap? = remember(bytes) {
    bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }.getOrNull() }
}

/** The icon of an installed app, or null. */
@Composable
internal fun rememberInstalledAppIcon(packageName: String?): ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        packageName?.takeIf { it.isNotBlank() }?.let {
            runCatching { context.packageManager.getApplicationIcon(it).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
}

/**
 * The passkey's face: the stored icon of the app that created it, else the icon of an installed app from its history,
 * else the site's letter avatar. Decorative: the row says the site and the app in text.
 */
@Composable
private fun PasskeyIcon(rpId: String, stored: ByteArray?, packageName: String?, size: Dp = 40.dp) {
    val image = rememberPng(stored) ?: rememberInstalledAppIcon(packageName)
    if (image != null) Image(image, contentDescription = null, modifier = Modifier.size(size).clip(RoundedCornerShape(size / 4)))
    else NuvoriAvatar(rpId, size = size, icon = NuvoriIcons.Passkey, site = rpId)
}

/**
 * "Saved passkeys" as a manager: one row per passkey with its app icon, site, account and last use. A tap opens the
 * details: names, dates, where it came from, key facts and its own history. [onDelete] asks before deleting.
 */
@Composable
internal fun SavedPasskeysSection(data: PasskeyPageData, onDelete: (PasskeyInfo) -> Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = remember(data.passkeys, search) {
        val query = search.trim()
        if (query.isEmpty()) data.passkeys
        else data.passkeys.filter { listOf(it.rpId, it.username, it.displayName).any { text -> text.contains(query, ignoreCase = true) } }
    }
    val sites = remember(data.passkeys) { data.passkeys.map { it.rpId }.distinct().size }
    SettingsSection("Saved passkeys", description = when {
        data.passkeys.isEmpty() -> null
        else -> "${data.passkeys.size} ${if (data.passkeys.size == 1) "passkey" else "passkeys"} for $sites ${if (sites == 1) "site or app" else "sites and apps"}. Tap one for details."
    }) {
        if (data.passkeys.isEmpty()) Text(stringResource(R.string.no_passkeys_saved_yet))
        if (data.passkeys.size > 4) OutlinedTextField(search, { search = it }, label = { Text(stringResource(R.string.search_passkeys)) },
            leadingIcon = { Icon(NuvoriIcons.Search, contentDescription = null) },
            trailingIcon = if (search.isEmpty()) null else {
                { IconButton(onClick = { search = "" }) { Icon(NuvoriIcons.Close, contentDescription = "Clear search") } }
            },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        if (data.passkeys.isNotEmpty() && shown.isEmpty()) Text(stringResource(R.string.no_passkeys_match_this_search),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Several accounts on one site: the one this device uses most carries the badge.
        val mostUsed = remember(data.passkeys, data.usage) {
            data.passkeys.groupBy { it.rpId }.values.mapNotNull { group -> data.usage.mostUsed(group.map { it.id }) }.toSet()
        }
        shown.forEachIndexed { index, passkey ->
            if (index > 0) HorizontalDivider()
            key(passkey.id) {
                PasskeyManagerRow(passkey, data, expanded = open == passkey.id, mostUsed = passkey.id in mostUsed,
                    onToggle = { open = if (open == passkey.id) null else passkey.id }, onDelete = { onDelete(passkey) })
            }
        }
    }
}

@Composable
private fun PasskeyManagerRow(passkey: PasskeyInfo, data: PasskeyPageData, expanded: Boolean, mostUsed: Boolean,
    onToggle: () -> Unit, onDelete: () -> Unit) {
    val events = remember(passkey.id, data.history) { PasskeyLedger.eventsFor(passkey.id, data.history) }
    val origin = data.origins[passkey.id]
    val use = data.usage.use(passkey.id)
    val lastSignIn = remember(events) { PasskeyLedger.signIns(events).firstOrNull() }
    val lastUsed = listOfNotNull(use?.lastUsedAt, lastSignIn?.at).maxOrNull()
    val now = remember { System.currentTimeMillis() }
    val account = passkey.username.ifBlank { passkey.displayName.ifBlank { "Unnamed account" } }
    val summary = "Added ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(passkey.createdAt))}" +
        (lastUsed?.let { " · Used ${relativeTime(it, now)}" } ?: " · Not used here yet")
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(Modifier.fillMaxWidth().clip(NuvoriShapes.Card).clickable(onClick = onToggle)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "${passkey.rpId}, $account. $summary" + if (mostUsed) ", $MOST_USED_SPOKEN" else ""
                stateDescription = if (expanded) "Details shown" else "Details hidden"
            }
            .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PasskeyIcon(passkey.rpId, origin?.icon, origin?.appPackage ?: events.firstOrNull { it.appPackage.isNotBlank() }?.appPackage)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(passkey.rpId, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(account, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (mostUsed) MostUsedBadge()
                Text(summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(if (expanded) NuvoriIcons.ChevronUp else NuvoriIcons.ChevronDown, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(expanded) {
            PasskeyDetails(passkey, origin, events, use?.count ?: 0, lastUsed, onDelete)
        }
    }
}

@Composable
private fun PasskeyDetails(passkey: PasskeyInfo, origin: PasskeyOrigin?, events: List<PasskeyHistoryEvent>, uses: Int,
    lastUsed: Long?, onDelete: () -> Unit) {
    var allHistory by rememberSaveable(passkey.id) { mutableStateOf(false) }
    val change = remember(events) { PasskeyLedger.lastChange(events) }
    val addedFrom = remember(origin, events) { PasskeyLedger.addedFrom(origin, events) }
    val fingerprint = remember(passkey.publicKey) { PasskeyLedger.publicKeyFingerprint(passkey.publicKey) }
    Column(Modifier.fillMaxWidth().padding(start = 52.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DetailLine("Website or app", passkey.rpId)
        DetailLine("Account", passkey.username.ifBlank { "Not given" })
        if (passkey.displayName.isNotBlank() && passkey.displayName != passkey.username) DetailLine("Display name", passkey.displayName)
        DetailLine("Added", dateTime(passkey.createdAt))
        if (addedFrom != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (origin != null) PasskeyIcon(passkey.rpId, origin.icon, origin.appPackage, size = 24.dp)
            DetailLine("Added from", addedFrom + (origin?.appPackage?.takeIf { it != origin.appLabel }?.let { " ($it)" } ?: ""))
        }
        change?.takeIf { it.at != passkey.createdAt || it.eventKind != PasskeyEventKind.ADDED_BEFORE_HISTORY }?.let {
            DetailLine("Last changed", "${dateTime(it.at)} · ${PasskeyLedger.describe(it)}")
        }
        DetailLine("Last used", lastUsed?.let { dateTime(it) + if (uses > 0) " · $uses ${if (uses == 1) "time" else "times"} on this device" else "" }
            ?: "Not used on this device yet")
        DetailLine("Key", "ES256 (ECDSA P-256 with SHA-256) · stays in your encrypted vault")
        if (fingerprint != null) DetailLine("Public key fingerprint", fingerprint, mono = true)
        DetailLine("Credential ID", PasskeyLedger.shortId(passkey.id), mono = true)
        DetailLine("User handle", PasskeyLedger.shortId(passkey.userHandle), mono = true)
        Text(stringResource(R.string.stored_encrypted_with_your_vault_include),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (events.isNotEmpty()) {
            Text(stringResource(R.string.history_on_this_device), style = MaterialTheme.typography.labelLarge)
            val visible = if (allHistory) events else events.take(5)
            visible.forEach { LedgerLine(it, showPasskey = false) }
            if (events.size > 5) TextButton(onClick = { allHistory = !allHistory }) {
                Text(if (allHistory) "Show less" else "Show all ${events.size}")
            }
        }
        OutlinedButton(onClick = onDelete, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.heightIn(min = 48.dp)) {
            Icon(NuvoriIcons.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
            Text(stringResource(R.string.delete_passkey))
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String, mono: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer {
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = if (mono) FontFamily.Monospace else null)
        }
    }
}

/** One ledger line: what happened and when. [showPasskey] adds the site and account, for the page-wide ledger. */
@Composable
private fun LedgerLine(event: PasskeyHistoryEvent, showPasskey: Boolean) {
    val kind = event.eventKind
    val status = when (kind) {
        PasskeyEventKind.DELETED, PasskeyEventKind.DELETED_ON_OTHER_DEVICE -> StatusKind.ERROR
        PasskeyEventKind.USED -> StatusKind.NEUTRAL
        PasskeyEventKind.CREATED, PasskeyEventKind.IMPORTED, PasskeyEventKind.RESTORED -> StatusKind.SUCCESS
        else -> StatusKind.INFO
    }
    val text = PasskeyLedger.describe(event)
    val who = listOf(event.rpId, event.username).filter(String::isNotBlank).joinToString(" · ")
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusIcon(status, statusColors(status).accent, Modifier.padding(top = 2.dp), size = 16.dp)
        Column(Modifier.weight(1f)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Text((if (showPasskey && who.isNotBlank()) "$who · " else "") + dateTime(event.at),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * "Passkey history": the ledger for every passkey, newest first, including ones since deleted. Kept on this device
 * only. [onClear] asks first; it empties the ledger and keeps the passkeys.
 */
@Composable
internal fun PasskeyHistorySection(history: List<PasskeyHistoryEvent>, onClear: () -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    var changesOnly by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val filtered = remember(history, changesOnly) { if (changesOnly) history.filter { it.eventKind?.isChange == true } else history }
    SettingsSection("Passkey history",
        description = "When each passkey was added, synced, used or deleted. Kept encrypted on this device only; not synced or backed up.") {
        if (history.isEmpty()) {
            Text(stringResource(R.string.nothing_yet_new_passkeys_sign_ins_and_de),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingsSection
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !changesOnly, onClick = { changesOnly = false }, label = { Text(stringResource(R.string.everything)) })
            FilterChip(selected = changesOnly, onClick = { changesOnly = true }, label = { Text(stringResource(R.string.changes_only)) })
        }
        val visible = if (all) filtered else filtered.take(10)
        visible.forEach { LedgerLine(it, showPasskey = true) }
        if (filtered.size > 10) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${filtered.size}") }
        SettingsSecondaryButton("Clear history", onClick = { confirm = true }, icon = NuvoriIcons.Delete)
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text(stringResource(R.string.clear_passkey_history)) },
        text = { Text(stringResource(R.string.this_removes_the_history_list_on_this_de)) },
        confirmButton = { Button(onClick = { confirm = false; onClear() }) { Text(stringResource(R.string.clear_history)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.cancel)) } })
}

/** The date and time to show for a request: time only today, otherwise the date too. */
internal fun requestTime(at: Long, now: Long = System.currentTimeMillis()): String {
    val day = java.util.Calendar.getInstance().apply { timeInMillis = now }
    val then = java.util.Calendar.getInstance().apply { timeInMillis = at }
    val sameDay = day.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) &&
        day.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR)
    return if (sameDay) "Today " + DateFormat.getTimeInstance(DateFormat.SHORT, Locale.getDefault()).format(Date(at))
        else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault()).format(Date(at))
}
