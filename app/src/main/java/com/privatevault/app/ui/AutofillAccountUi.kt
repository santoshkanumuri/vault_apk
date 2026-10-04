package com.privatevault.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.httpsOrigin

// Mid-dark hues keep white letters readable on both light and dark surfaces.
private val accountAvatarPalette = listOf(
    Color(0xFF1565C0), Color(0xFF2E7D32), Color(0xFF7B1FA2), Color(0xFFBF360C),
    Color(0xFFAD1457), Color(0xFF00796B), Color(0xFF3949AB), Color(0xFF5D4037),
)

/** Same title, same color on every screen and every launch. */
internal fun accountAvatarColor(seed: String): Color =
    accountAvatarPalette[(seed.trim().lowercase().hashCode() and Int.MAX_VALUE) % accountAvatarPalette.size]

/** Decorative: the title is always read from the text next to it. */
@Composable
internal fun AccountLetterAvatar(title: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val letter = title.trim().firstOrNull { it.isLetterOrDigit() }?.uppercaseChar()?.toString() ?: "•"
    Box(modifier.size(size).clip(CircleShape).background(accountAvatarColor(title)).clearAndSetSemantics { },
        contentAlignment = Alignment.Center) {
        Text(letter, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

/** The saved login's website without the scheme, or null when it is not a usable HTTPS address. */
internal fun accountSiteLabel(entry: VaultEntry): String? =
    (httpsOrigin(entry.tertiaryValue) ?: httpsOrigin("https://${entry.tertiaryValue.trim()}"))?.removePrefix("https://")

/** One saved login in the account picker. The password is never shown, spoken or measured. */
@Composable
internal fun AutofillAccountRow(entry: VaultEntry, action: String, codeLinked: Boolean = false, onClick: () -> Unit) {
    val account = entry.primaryValue.ifBlank { entry.title }
    val showTitle = entry.primaryValue.isNotBlank() && entry.title != entry.primaryValue
    val site = accountSiteLabel(entry)?.takeIf { it != entry.title }
    val description = buildString {
        append(action).append(": ").append(account)
        if (showTitle) append(", ").append(entry.title)
        if (codeLinked) append(", authenticator code linked")
    }
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().semantics { role = Role.Button; contentDescription = description }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountLetterAvatar(entry.title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(account, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (showTitle) Text(entry.title, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (site != null) Text(site, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (codeLinked) StatusChip(StatusKind.INFO, "Code linked", Modifier.padding(top = 4.dp))
            }
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}
