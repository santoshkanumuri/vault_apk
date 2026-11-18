package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.matchingCodeEntries

/**
 * Remembers which category the Quick copy picker was left on. Only that screen provides one, so
 * previews and tests always start on the codes category. The index is not sensitive.
 */
internal class QuickAccessTabStore(val initialTab: Int, val save: (Int) -> Unit)

internal val LocalQuickAccessTabStore = staticCompositionLocalOf<QuickAccessTabStore?> { null }

/** The Quick copy categories. The ordinal is the saved tab index: 0 and 1 keep their meaning from older versions. */
internal enum class QuickCategory(val label: String, val type: EntryType, val searchLabel: String, val empty: String) {
    PASSWORDS("Passwords", EntryType.PASSWORD, "Search passwords", "No passwords saved yet. Add one in Nuvori."),
    CODES("TOTP", EntryType.AUTHENTICATOR, "Search TOTP accounts", "No TOTP accounts yet. Add one in Nuvori."),
    CARDS("Cards", EntryType.CARD, "Search cards", "No cards saved yet. Add one in Nuvori."),
    QUESTIONS("Questions", EntryType.QUESTION, "Search questions", "No security questions saved yet. Add one in Nuvori."),
}

/** Items of [category] that match [search]. Secrets (passwords, answers, card numbers beyond the last four) are never searched. */
internal fun quickCopyMatches(entries: List<VaultEntry>, category: QuickCategory, search: String): List<VaultEntry> {
    val items = entries.filter { it.type == category.type }
    if (category == QuickCategory.CARDS) {
        return filterCards(items.sortedWith(compareByDescending<VaultEntry> { it.favorite }.thenByDescending { it.lastOpenedAt }), search)
    }
    val query = search.trim()
    if (query.isEmpty()) return items
    return items.filter { entry ->
        when (category) {
            QuickCategory.PASSWORDS -> listOf(entry.title, entry.primaryValue, entry.tertiaryValue)
            QuickCategory.QUESTIONS -> listOf(entry.title, entry.primaryValue)
            else -> listOf(entry.title, entry.primaryValue)
        }.any { it.contains(query, ignoreCase = true) }
    }
}

@Composable
internal fun ColumnScope.VaultQuickAccessContent(
    entries: List<VaultEntry>, suggestedApp: String?, notificationsOn: Boolean = false,
    copy: (VaultEntry, QuickField) -> Unit,
) {
    val tabStore = LocalQuickAccessTabStore.current
    val keyboard = LocalSoftwareKeyboardController.current
    var category by remember {
        mutableStateOf(QuickCategory.entries[(tabStore?.initialTab ?: QuickCategory.CODES.ordinal).coerceIn(0, QuickCategory.entries.size - 1)])
    }
    var search by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val accounts = entries.filter { it.type == category.type }
    val suggested = if (category == QuickCategory.CODES) matchingCodeEntries(accounts, suggestedApp) else emptyList()
    val pool = if (search.isBlank() && !showAll && suggested.isNotEmpty()) suggested else entries
    val matches = quickCopyMatches(pool, category, search)
    CategoryButtons(category, entries) { selected ->
        category = selected; search = ""
        tabStore?.save?.invoke(selected.ordinal)
    }
    OutlinedTextField(search, { search = it },
        label = { Text(category.searchLabel) },
        leadingIcon = { Icon(NuvoriIcons.Search, contentDescription = null) },
        trailingIcon = if (search.isEmpty()) null else {
            { IconButton(onClick = { search = "" }) { Icon(NuvoriIcons.Close, contentDescription = "Clear search") } }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (category == QuickCategory.PASSWORDS || category == QuickCategory.CARDS) item(key = "hint") {
            PickerHint(if (notificationsOn) "Tap a field to copy it. The other fields stay in your notifications for 2 minutes, so you don't need to open this again."
                else "Tap a field to copy it. Turn on Nuvori notifications to copy the other fields from your notifications without opening this again.")
        }
        if (suggested.isNotEmpty() && search.isBlank()) item(key = "suggested") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(if (showAll) StatusKind.NEUTRAL else StatusKind.INFO,
                    if (showAll) "All TOTP accounts" else "Suggested for your last app")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show suggested" else "Show all") }
            }
        }
        if (matches.isEmpty()) item(key = "empty") {
            Text(if (accounts.isEmpty()) category.empty else "No matching accounts.")
        }
        items(matches, key = { it.id }) { entry ->
            when (category) {
                QuickCategory.PASSWORDS -> PasswordCopyCard(entry, entries, copy)
                QuickCategory.CODES -> TotpTile(entry, { _, _ -> copy(entry, QuickField.CODE) }, {}, initiallyMasked = true)
                QuickCategory.CARDS -> CardCopyCard(entry, copy)
                QuickCategory.QUESTIONS -> QuestionCopyCard(entry, copy)
            }
        }
        if (category == QuickCategory.PASSWORDS && accounts.isNotEmpty()) item(key = "autofill-hint") {
            PickerHint("To fill and link a login instead, return to its sign-in field and choose Nuvori Autofill.")
        }
    }
}

/** Four equal buttons: one tap switches the category. Each shows how many items it holds. */
@Composable
private fun CategoryButtons(selected: QuickCategory, entries: List<VaultEntry>, onSelect: (QuickCategory) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val counts = remember(entries) { entries.groupingBy { it.type }.eachCount() }
    Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        QuickCategory.entries.forEach { category ->
            val isSelected = category == selected
            val count = counts[category.type] ?: 0
            Surface(
                selected = isSelected,
                onClick = { if (!isSelected) onSelect(category) },
                modifier = Modifier.weight(1f).heightIn(min = 60.dp)
                    .semantics { role = Role.Tab; contentDescription = "${category.label}, $count" },
                shape = RoundedCornerShape(14.dp),
                color = if (isSelected) scheme.primaryContainer else scheme.surfaceVariant.copy(alpha = .55f),
                contentColor = if (isSelected) scheme.onPrimaryContainer else scheme.onSurfaceVariant,
            ) {
                Column(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(category.type.icon, contentDescription = null, modifier = Modifier.size(20.dp))
                    Text(category.label, style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
    }
}

/** Spoken name of a field in a button's description: "Copy card number for Visa". */
private val QuickField.spoken: String get() = if (this == QuickField.CVV) "CVV" else clipLabel.lowercase()

/** One button per field. Up to three share a row; four make a 2 by 2 grid so each target stays wide. */
@Composable
private fun CopyButtons(entry: VaultEntry, fields: List<QuickField>, copy: (VaultEntry, QuickField) -> Unit) {
    if (fields.isEmpty()) {
        Text("Nothing to copy. Add details in Nuvori.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val rows = if (fields.size > 3) fields.chunked(2) else listOf(fields)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { field ->
                    FilledTonalButton(onClick = { copy(entry, field) }, contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                            .semantics { contentDescription = "Copy ${field.spoken} for ${entry.title}" }) {
                        Text(field.button, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun PasswordCopyCard(entry: VaultEntry, all: List<VaultEntry>, copy: (VaultEntry, QuickField) -> Unit) {
    val site = accountSiteLabel(entry)?.takeIf { it != entry.title }
    val fields = remember(entry, all) { quickCopyValues(entry, all).keys.toList() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountLetterAvatar(entry.title)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.primaryValue.isNotEmpty()) Text(entry.primaryValue,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (site != null) Text(site, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CopyButtons(entry, fields, copy)
        }
    }
}

@Composable
private fun CardCopyCard(entry: VaultEntry, copy: (VaultEntry, QuickField) -> Unit) {
    val fields = remember(entry) { quickCopyValues(entry, emptyList()).keys.toList() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MiniCard(entry)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title.ifBlank { "Card" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cardSummary(entry), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            CopyButtons(entry, fields, copy)
        }
    }
}

@Composable
private fun QuestionCopyCard(entry: VaultEntry, copy: (VaultEntry, QuickField) -> Unit) {
    val fields = remember(entry) { quickCopyValues(entry, emptyList()).keys.toList() }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AccountLetterAvatar(entry.title)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.primaryValue.isNotBlank()) Text(entry.primaryValue, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
            CopyButtons(entry, fields, copy)
        }
    }
}
