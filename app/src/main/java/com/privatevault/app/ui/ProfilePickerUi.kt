package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.privatevault.app.autofill.ProfileField
import com.privatevault.app.autofill.autofillProfile
import com.privatevault.app.autofill.profileFillSummary
import com.privatevault.app.autofill.profileFillValues
import com.privatevault.app.data.VaultEntry

/** A saved contact-details entry and the labels of what it fills in this form. Never its values. */
internal class ProfileChoice(val entry: VaultEntry, val summary: String)

/** Entries that fill at least one requested field, in the order given. */
internal fun profileChoices(entries: List<VaultEntry>, kinds: Collection<ProfileField>): List<ProfileChoice> {
    val requested = kinds.distinct().withIndex().associate { (index, kind) -> index to kind }
    return entries.mapNotNull { entry ->
        val profile = entry.autofillProfile() ?: return@mapNotNull null
        val filled = profileFillValues(profile, requested).keys.map(requested::getValue)
        if (filled.isEmpty()) null else ProfileChoice(entry, profileFillSummary(filled))
    }
}

/**
 * The profile picker after unlock. [embeddedWarning] is shown when an app's WebView asks: the app receives what is filled.
 * [hasProfiles] tells "nothing saved yet" apart from "nothing saved fits this form".
 */
@Composable
internal fun ColumnScope.ProfileFillContent(choices: List<ProfileChoice>, hasProfiles: Boolean, busy: Boolean,
    embeddedWarning: String?, onFill: (VaultEntry) -> Unit, onOpenNuvori: () -> Unit) {
    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        embeddedWarning?.let { item(key = "embedded-browser") { StatusBanner(StatusKind.WARNING, it, title = "Embedded browser") } }
        if (choices.isEmpty()) {
            item(key = "empty") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    PickerSectionHeader(if (hasProfiles) "No saved details fit this form" else "No contact details saved yet")
                    PickerHint(if (hasProfiles) "Your saved Autofill details have nothing this form asks for. Add the missing details in Nuvori."
                        else "Add an Autofill details entry with your name, email, phone or address in Nuvori. Nuvori can then fill it here.")
                }
            }
        } else {
            item(key = "header") { PickerSectionHeader("Saved details") }
            items(choices, key = { it.entry.id }) { choice -> ProfileChoiceRow(choice, !busy) { onFill(choice.entry) } }
            item(key = "hint") { PickerHint("Choose details to fill. Only the fields this form asks for are filled.") }
        }
        item(key = "open-nuvori") {
            TextButton(onClick = onOpenNuvori, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(if (hasProfiles) "Open Nuvori to manage details" else "Open Nuvori to add one")
            }
        }
    }
}

@Composable
private fun ProfileChoiceRow(choice: ProfileChoice, enabled: Boolean, onClick: () -> Unit) {
    val title = choice.entry.title.ifBlank { "Contact details" }
    Card(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .semantics { role = Role.Button; contentDescription = "Fill details from $title: ${choice.summary}" }) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AccountLetterAvatar(title)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(choice.summary, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("Fill", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
    }
}
