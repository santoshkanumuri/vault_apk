package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.VaultEntry

@Composable
internal fun AutofillAccountRow(entry: VaultEntry, action: String, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            NuvoriLogo(Modifier.size(36.dp).clearAndSetSemantics { })
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(entry.primaryValue.ifBlank { entry.title }, style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (entry.primaryValue.isNotBlank() && entry.title != entry.primaryValue) {
                    Text(entry.title, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                // Fixed length: neither the password nor its length belongs in the picker.
                Text("••••••••••", Modifier.clearAndSetSemantics { },
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
