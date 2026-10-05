package com.privatevault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.privatevault.app.autofill.autofillProfile
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.Totp

/** Last four digits with a mask, never the full number. */
internal fun maskedCardEnding(number: String): String {
    val digits = number.filter(Char::isDigit)
    return if (digits.isEmpty()) "" else "•••• ${digits.takeLast(4)}"
}

/**
 * One calm line under an item's title. Never includes passwords, answers, CVVs or full card numbers.
 * A login without a username (for example a password-only save from Windows) says so instead of going blank.
 */
internal fun entrySubtitle(item: EntryWithDetails, includeType: Boolean = false, includeFolder: Boolean = true): String {
    val entry = item.entry
    val detail: List<String> = when (entry.type) {
        EntryType.PASSWORD -> listOf(entry.primaryValue.ifBlank { "No username" },
            accountSiteLabel(entry)?.takeIf { !it.equals(entry.title, ignoreCase = true) }.orEmpty())
        EntryType.CARD -> listOf(if (entry.cardKind == CardKind.DEBIT) "Debit" else "Credit",
            entry.network.ifBlank { com.privatevault.app.nfc.cardNetwork(entry.primaryValue) }, maskedCardEnding(entry.primaryValue))
        EntryType.AUTHENTICATOR -> listOf(entry.primaryValue)
        EntryType.QUESTION -> listOf(entry.primaryValue)
        EntryType.NOTE -> listOf(entry.notes.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(80))
        EntryType.AUTOFILL -> listOf(entry.autofillProfile()?.let { profile ->
            listOf(profile.name, profile.email, profile.phone, profile.address1, profile.city).firstOrNull { it.isNotBlank() }
        }.orEmpty())
    }
    val folder = if (includeFolder) item.groups.firstOrNull { it.folderType != null }?.name.orEmpty() else ""
    return (listOfNotNull(if (includeType) entry.type.itemLabel else null) + detail + folder)
        .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Copy shortcuts offered on long-press. Labels name the field; values never appear in the menu. */
internal fun copyActions(entry: VaultEntry, copy: (String, String) -> Unit, authenticate: (() -> Unit) -> Unit): List<Pair<String, () -> Unit>> =
    buildList {
        when (entry.type) {
            EntryType.PASSWORD -> {
                if (entry.primaryValue.isNotBlank()) add("Copy username" to { copy("Username", entry.primaryValue) })
                if (entry.secondaryValue.isNotEmpty()) add("Copy password" to { copy("Password", entry.secondaryValue) })
                if (entry.tertiaryValue.isNotBlank()) add("Copy website" to { copy("Website", entry.tertiaryValue) })
            }
            EntryType.CARD -> {
                if (entry.primaryValue.isNotBlank()) add("Copy card number" to { copy("Card number", entry.primaryValue) })
                if (entry.tertiaryValue.isNotBlank()) add("Copy expiry" to { copy("Expiry", entry.tertiaryValue) })
                if (entry.secondaryValue.isNotBlank()) add("Copy name on card" to { copy("Name on card", entry.secondaryValue) })
                if (entry.fourthValue.isNotBlank()) add("Copy CVV" to { authenticate { copy("CVV", entry.fourthValue) } })
            }
            EntryType.AUTHENTICATOR -> add("Copy code" to {
                runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod) }
                    .onSuccess { copy("Authenticator code", it) }
            })
            EntryType.QUESTION -> if (entry.secondaryValue.isNotEmpty()) add("Copy answer" to { copy("Answer", entry.secondaryValue) })
            EntryType.NOTE -> if (entry.notes.isNotBlank()) add("Copy note" to { copy("Note", entry.notes) })
            EntryType.AUTOFILL -> entry.autofillProfile()?.let { profile ->
                if (profile.name.isNotBlank()) add("Copy name" to { copy("Name", profile.name) })
                if (profile.email.isNotBlank()) add("Copy email" to { copy("Email", profile.email) })
                if (profile.phone.isNotBlank()) add("Copy mobile number" to { copy("Mobile number", profile.phone) })
            }
        }
    }

/** A tiny card in the card's own color, used where other items show a letter avatar. */
@Composable
internal fun MiniCardSwatch(color: Long, modifier: Modifier = Modifier, width: Dp = 36.dp) {
    val base = Color(color)
    val ink = if (base.luminance() > .5f) Color.Black.copy(alpha = .55f) else Color.White.copy(alpha = .7f)
    Box(modifier.width(width).heightIn(min = width * .63f, max = width * .63f).clip(RoundedCornerShape(5.dp))
        .background(base).border(1.dp, MaterialTheme.colorScheme.hairline, RoundedCornerShape(5.dp))
        .clearAndSetSemantics { }) {
        Box(Modifier.padding(start = width * .14f, top = width * .2f).size(width * .2f, width * .15f)
            .clip(RoundedCornerShape(2.dp)).background(ink))
    }
}

/** Leading visual for an item: card swatch for cards, otherwise a letter avatar in the shared tint. */
@Composable
internal fun EntryLeading(entry: VaultEntry, size: Dp = 36.dp) {
    if (entry.type == EntryType.CARD) Box(Modifier.size(size), contentAlignment = Alignment.Center) { MiniCardSwatch(entry.color, width = size) }
    else NuvoriAvatar(entry.title, size = size)
}

@Composable
internal fun FavoriteMark(modifier: Modifier = Modifier, size: Dp = 14.dp) {
    Icon(NuvoriIcons.StarFilled, contentDescription = null, tint = statusColors(StatusKind.WARNING).accent,
        modifier = modifier.size(size))
}

/**
 * The standard item row: avatar, title with favorite star, one-line subtitle and a chevron, drawn as one
 * segment of a grouped 12dp card. Never shows secrets.
 */
@Composable
internal fun EntryListRow(
    item: EntryWithDetails,
    index: Int,
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    includeType: Boolean = false,
    subtitle: String = entrySubtitle(item, includeType),
    trailing: String? = null,
    trailingColor: Color? = null,
    contentDescription: String? = null,
    onLongClick: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primaryContainer.copy(alpha = .55f) else scheme.raised
    val description = contentDescription ?: "Open ${item.entry.type.legacyLabel()}: ${item.entry.title}"
    Row(
        modifier.fillMaxWidth()
            .groupedSegment(index, count, container, scheme.hairline)
            .clip(segmentShape(index, count))
            .tappable(onLongClick = onLongClick, onLongClickLabel = if (onLongClick != null) "Copy options" else null,
                pressedScale = .985f, onClick = onClick)
            .semantics { this.contentDescription = description }
            .heightIn(min = 60.dp)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EntryLeading(item.entry)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.entry.title.ifBlank { "Untitled" }, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (item.entry.favorite) FavoriteMark()
            }
            if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, Modifier.padding(start = 8.dp), color = trailingColor ?: scheme.error,
            style = MaterialTheme.typography.labelMedium, maxLines = 1)
        Icon(NuvoriIcons.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant.copy(alpha = .7f),
            modifier = Modifier.padding(start = 4.dp).size(18.dp))
    }
}

/** Lower-case type name kept for existing accessibility labels ("Open password: Mail"). */
internal fun EntryType.legacyLabel(): String = when (this) {
    EntryType.CARD -> "card"; EntryType.QUESTION -> "security question"; EntryType.PASSWORD -> "password"
    EntryType.NOTE -> "note"; EntryType.AUTHENTICATOR -> "authenticator"; EntryType.AUTOFILL -> "autofill profile"
}

@Suppress("unused")
private val unusedBorder = BorderStroke(1.dp, Color.Transparent)
