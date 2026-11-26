package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.TextButton
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.nfc.cardNetwork

/** The card's network for display: the saved one, or the one its number shows. */
internal fun displayNetwork(entry: VaultEntry): String = entry.network.trim().ifEmpty { cardNetwork(entry.primaryValue) }

/** "Visa •••• 1111 · 08/29". Never more than the last four digits. */
internal fun cardSummary(entry: VaultEntry): String {
    val last4 = entry.primaryValue.filter(Char::isDigit).takeLast(4)
    return listOfNotNull(
        listOfNotNull(displayNetwork(entry).ifEmpty { null }, last4.takeIf { it.length == 4 }?.let { "•••• $it" }).joinToString(" ").ifEmpty { null },
        entry.tertiaryValue.trim().ifEmpty { null }?.let(::displayExpiry),
    ).joinToString(" · ").ifEmpty { if (entry.cardKind.name == "DEBIT") "Debit card" else "Credit card" }
}

/** The expiry as "MM/YY" however it was typed ("0829", "8/2029"); unreadable text is shown as saved. */
internal fun displayExpiry(saved: String): String =
    com.privatevault.app.autofill.parseExpiry(saved.trim())?.let { (month, year) ->
        String.format(java.util.Locale.ROOT, "%02d/%02d", month, year % 100) } ?: saved

/** Cards matching [query] by name, network, holder or last four digits. Favorites stay first. */
internal fun filterCards(cards: List<VaultEntry>, query: String): List<VaultEntry> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return cards
    return cards.filter { card ->
        listOf(card.title, displayNetwork(card), card.secondaryValue, card.primaryValue.filter(Char::isDigit).takeLast(4))
            .any { it.lowercase().contains(q) }
    }
}

/**
 * The card picker after unlock. [asksForCode]: the form has a security-code field, so the switch shows; the code is
 * filled only while [includeCode] is on. [embeddedWarning] shows when an app's WebView asks: the app receives the card.
 */
@Composable
internal fun ColumnScope.CardFillContent(cards: List<VaultEntry>, asksForCode: Boolean, includeCode: Boolean,
    onIncludeCode: (Boolean) -> Unit, busy: Boolean, embeddedWarning: String?, onFill: (VaultEntry) -> Unit, onOpenNuvori: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(cards, query) { filterCards(cards, query) }
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        embeddedWarning?.let { item(key = "embedded-browser") { StatusBanner(StatusKind.WARNING, it, title = stringResource(R.string.embedded_browser)) } }
        if (cards.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PickerSectionHeader(stringResource(R.string.no_cards_saved_yet))
                    PickerHint(stringResource(R.string.add_a_card_in_nuvori_nuvori_can_then_fil))
                }
            }
        } else {
            if (cards.size > 6) item(key = "search") {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text(stringResource(R.string.search_cards)) }, leadingIcon = { Icon(NuvoriIcons.Search, contentDescription = null) })
            }
            if (asksForCode) item(key = "code") {
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.also_fill_the_security_code), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.asks_for_your_fingerprint_or_face_if_nuv),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = includeCode, onCheckedChange = onIncludeCode, enabled = !busy)
                }
            }
            item(key = "header") { PickerSectionHeader(if (query.isBlank()) "Your cards" else "${shown.size} matching") }
            items(shown, key = { it.id }) { card -> CardChoiceRow(card, !busy) { onFill(card) } }
            item(key = "hint") { PickerHint(stringResource(R.string.choose_a_card_only_the_card_fields_this)) }
        }
        item(key = "open-nuvori") {
            TextButton(onClick = onOpenNuvori, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (cards.isEmpty()) "Open Nuvori to add a card" else "Open Nuvori to manage cards")
            }
        }
    }
}

@Composable
private fun CardChoiceRow(card: VaultEntry, enabled: Boolean, onClick: () -> Unit) {
    val title = card.title.ifBlank { "Card" }
    val summary = cardSummary(card)
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .semantics { role = Role.Button; contentDescription = "Fill $title, $summary" }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            MiniCard(card)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(stringResource(R.string.fill), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}

/** The card in its own color, with its last four digits: the same look as the wallet. */
@Composable
internal fun MiniCard(card: VaultEntry) {
    val base = Color(card.color)
    val ink = inkFor(base)
    val shape = RoundedCornerShape(7.dp)
    Box(Modifier.size(width = 56.dp, height = 36.dp).background(base, shape)
        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (base.luminance() > .9f || base.luminance() < .03f) .25f else 0f), shape)
        .padding(horizontal = 6.dp, vertical = 4.dp)) {
        Box(Modifier.size(width = 11.dp, height = 8.dp).background(Color(0xFFD9BC72), RoundedCornerShape(2.dp)).align(Alignment.TopStart))
        Text(card.primaryValue.filter(Char::isDigit).takeLast(4), color = ink, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
            fontFamily = FontFamily.Monospace, modifier = Modifier.align(Alignment.BottomEnd))
    }
}
