package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.matchingCodeEntries

/**
 * Remembers which tab the Quick Settings picker was left on. Only that screen provides one, so
 * previews and tests always start on the codes tab. The tab index is not sensitive.
 */
internal class QuickAccessTabStore(val initialTab: Int, val save: (Int) -> Unit)

internal val LocalQuickAccessTabStore = staticCompositionLocalOf<QuickAccessTabStore?> { null }

@Composable
internal fun ColumnScope.VaultQuickAccessContent(
    entries: List<VaultEntry>, suggestedApp: String?, copy: (VaultEntry, Boolean) -> Unit,
) {
    val tabStore = LocalQuickAccessTabStore.current
    val keyboard = LocalSoftwareKeyboardController.current
    var selectedTab by remember { mutableIntStateOf(tabStore?.initialTab?.coerceIn(0, 1) ?: 1) }
    var search by remember { mutableStateOf("") }
    var showAll by remember { mutableStateOf(false) }
    val passwords = selectedTab == 0
    val accounts = entries.filter { it.type == if (passwords) EntryType.PASSWORD else EntryType.AUTHENTICATOR }
    val suggested = if (passwords) emptyList() else matchingCodeEntries(accounts, suggestedApp)
    val filtered = if (search.isBlank() && !showAll && suggested.isNotEmpty()) suggested else accounts
    val matches = filtered.filter { entry ->
        listOf(entry.title, entry.primaryValue, if (passwords) entry.tertiaryValue else "")
            .any { it.contains(search, ignoreCase = true) }
    }
    TabRow(selectedTabIndex = selectedTab) {
        listOf("Passwords", "TOTP").forEachIndexed { index, label ->
            Tab(selected = selectedTab == index, onClick = {
                selectedTab = index; search = ""
                tabStore?.save?.invoke(index)
            }, text = { Text(label) })
        }
    }
    OutlinedTextField(search, { search = it },
        label = { Text(if (passwords) "Search passwords" else "Search TOTP accounts") },
        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
        trailingIcon = if (search.isEmpty()) null else {
            { IconButton(onClick = { search = "" }) { Icon(Icons.Outlined.Close, contentDescription = "Clear search") } }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (passwords) item {
            Text("Copy, then paste in your app. To fill and link a login, return to its sign-in field and choose Nuvori Autofill.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (suggested.isNotEmpty() && search.isBlank()) item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                StatusChip(if (showAll) StatusKind.NEUTRAL else StatusKind.INFO,
                    if (showAll) "All TOTP accounts" else "Suggested for your last app")
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show suggested" else "Show all") }
            }
        }
        if (matches.isEmpty()) item {
            Text(if (accounts.isEmpty()) {
                if (passwords) "No passwords saved yet. Add one in Nuvori." else "No TOTP accounts yet. Add one in Nuvori."
            } else "No matching accounts.")
        }
        items(matches, key = { it.id }) { entry ->
            if (passwords) PasswordCopyCard(entry, copy)
            else TotpTile(entry, { _, _ -> copy(entry, false) }, {}, initiallyMasked = true)
        }
    }
}

@Composable
private fun PasswordCopyCard(entry: VaultEntry, copy: (VaultEntry, Boolean) -> Unit) {
    val site = accountSiteLabel(entry)?.takeIf { it != entry.title }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (entry.primaryValue.isNotEmpty()) OutlinedButton(onClick = { copy(entry, true) },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Copy username for ${entry.title}" }) {
                    Text("Copy username", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Button(onClick = { copy(entry, false) }, enabled = entry.secondaryValue.isNotEmpty(),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Copy password for ${entry.title}" }) {
                    Text("Copy password", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
