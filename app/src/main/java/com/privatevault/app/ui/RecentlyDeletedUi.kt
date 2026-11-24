package com.privatevault.app

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.PasswordHistoryEntry
import com.privatevault.app.data.TrashedEntry

private fun ago(at: Long, now: Long = System.currentTimeMillis()): String =
    // Android prints "0 minutes ago" for the first minute.
    if (now - at < DateUtils.MINUTE_IN_MILLIS) "just now"
    else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()

private fun EntryType.trashLabel(): String = when (this) {
    EntryType.PASSWORD -> "Login"
    EntryType.CARD -> "Card"
    EntryType.AUTHENTICATOR -> "Authenticator code"
    EntryType.NOTE -> "Note"
    EntryType.QUESTION -> "Security question"
    EntryType.AUTOFILL -> "Autofill details"
}

/**
 * A login's earlier passwords, newest first (up to 10). This phone keeps them when the password
 * changes here or on a paired device. They are not synced. Collapsed until tapped.
 */
@Composable
internal fun PreviousPasswords(viewModel: VaultViewModel, entryId: String, updatedAt: Long, copy: (String, String) -> Unit) {
    var list by remember(entryId) { mutableStateOf(emptyList<PasswordHistoryEntry>()) }
    var open by remember(entryId) { mutableStateOf(false) }
    var revealed by remember(entryId) { mutableStateOf(emptySet<Long>()) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(entryId, updatedAt) { list = viewModel.passwordHistory(entryId) }
    if (list.isEmpty()) return
    HairlineCard(Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 8.dp)
                .tappable(pressedScale = .99f) { open = !open }
                .semantics { contentDescription = "Previous passwords, ${list.size}. ${if (open) "Tap to hide" else "Tap to show"}" },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(NuvoriIcons.History, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Previous passwords", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text("${list.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(if (open) NuvoriIcons.ChevronUp else NuvoriIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
            if (open) {
                list.forEach { old ->
                    HorizontalDivider(color = MaterialTheme.colorScheme.hairline)
                    val shown = old.id in revealed
                    Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text("Replaced ${ago(old.replacedAt)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(if (shown) old.password else "••••••••", style = MaterialTheme.typography.bodyLarge,
                                fontFamily = if (shown) FontFamily.Monospace else null)
                        }
                        IconButton(onClick = { revealed = if (shown) revealed - old.id else revealed + old.id }, modifier = Modifier.size(48.dp)) {
                            Icon(if (shown) NuvoriIcons.EyeOff else NuvoriIcons.Eye, contentDescription = if (shown) "Hide previous password" else "Reveal previous password", modifier = Modifier.size(20.dp))
                        }
                        IconButton(onClick = { copy("Previous password", old.password) }, modifier = Modifier.size(48.dp)) {
                            Icon(NuvoriIcons.Copy, contentDescription = "Copy previous password", modifier = Modifier.size(20.dp))
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.hairline)
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kept on this phone only, up to 10.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    TextButton(onClick = { confirmClear = true }) { Text("Clear") }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text("Clear previous passwords?") },
        text = { Text("This phone forgets the earlier passwords of this login. The current password stays.") },
        confirmButton = { TextButton(onClick = { confirmClear = false; viewModel.clearPasswordHistory(entryId) { list = emptyList() } }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } })
}

/**
 * Settings > Recently deleted: entries deleted on this phone or on a paired device, kept here for
 * 30 days. A restore makes a new entry that syncs to the other devices.
 */
@Composable
internal fun RecentlyDeletedPage(viewModel: VaultViewModel) {
    val items by viewModel.trash.collectAsStateWithLifecycle()
    var forget by remember { mutableStateOf<TrashedEntry?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { viewModel.loadTrash() }
    SettingsSection("Recently deleted",
        description = "Entries deleted on this phone or on a paired device stay here for ${TrashedEntry.DAYS} days. A restored entry syncs to your other devices. Photos and passkeys are not kept.") {
        if (items.isEmpty()) Text("Nothing deleted recently.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        items.forEachIndexed { index, item ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.hairline)
            val now = System.currentTimeMillis()
            val days = ((item.expiresAt - now + 86_399_999L) / 86_400_000L).coerceAtLeast(0)
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(item.title.ifBlank { "Untitled" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${item.type.trashLabel()} · deleted ${if (item.deletedElsewhere) "on another device " else ""}${ago(item.deletedAt, now)} · " +
                        if (days <= 1) "deleted for good tomorrow" else "$days days left",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { viewModel.restoreTrashed(item) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Restore") }
                    TextButton(onClick = { forget = item }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text("Delete for good", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (items.isNotEmpty()) SettingsDangerButton("Empty Recently deleted", onClick = { confirmEmpty = true }, icon = NuvoriIcons.Delete)
    }
    forget?.let { item ->
        AlertDialog(onDismissRequest = { forget = null },
            title = { Text("Delete ${item.title.ifBlank { "this entry" }} for good?") },
            text = { Text("This phone forgets its copy. You can't undo this.") },
            confirmButton = { TextButton(onClick = { forget = null; viewModel.forgetTrashed(item) }) { Text("Delete for good", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { forget = null }) { Text("Cancel") } })
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty Recently deleted?") },
        text = { Text("This phone forgets all ${items.size} deleted entries. You can't undo this.") },
        confirmButton = { TextButton(onClick = { confirmEmpty = false; viewModel.emptyTrash() }) { Text("Empty", color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } })
}
