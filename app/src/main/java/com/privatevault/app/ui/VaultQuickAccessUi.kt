package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.matchingCodeEntries

@Composable
internal fun ColumnScope.VaultQuickAccessContent(
    entries: List<VaultEntry>, suggestedApp: String?, copy: (VaultEntry, Boolean) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(1) }
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
            Tab(selected = selectedTab == index, onClick = { selectedTab = index; search = "" }, text = { Text(label) })
        }
    }
    OutlinedTextField(search, { search = it },
        label = { Text(if (passwords) "Search passwords" else "Search TOTP accounts") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (passwords) item {
            Text("Copy, then paste in your app. To fill and link a login, return to its sign-in field and choose Nuvori Autofill.",
                style = MaterialTheme.typography.bodySmall)
        }
        if (suggested.isNotEmpty() && search.isBlank()) item {
            Text(if (showAll) "All TOTP accounts" else "Suggested TOTP accounts for your previous app",
                style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show suggested" else "Show all") }
        }
        if (matches.isEmpty()) item {
            Text(if (accounts.isEmpty()) {
                if (passwords) "No passwords saved yet. Add one in Nuvori." else "No TOTP accounts yet. Add one in Nuvori."
            } else "No matching accounts.")
        }
        items(matches, key = { it.id }) { entry ->
            if (passwords) Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    if (entry.primaryValue.isNotEmpty()) Text(entry.primaryValue,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (entry.primaryValue.isNotEmpty()) OutlinedButton(onClick = { copy(entry, true) },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Copy username for ${entry.title}" }) {
                            Text("Copy username")
                        }
                        Button(onClick = { copy(entry, false) }, enabled = entry.secondaryValue.isNotEmpty(),
                            modifier = Modifier.weight(1f).semantics { contentDescription = "Copy password for ${entry.title}" }) {
                            Text("Copy password")
                        }
                    }
                }
            } else TotpTile(entry, { _, _ -> copy(entry, false) }, {}, initiallyMasked = true)
        }
    }
}
