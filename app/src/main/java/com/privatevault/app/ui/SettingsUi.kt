package com.privatevault.app

import android.content.Context
import android.view.autofill.AutofillManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Contactless
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Shared building blocks for the Settings hub and its pages.

/** Labels for the "after leaving the app" lock choices. The hub shows the selected one. */
internal val backgroundLockChoices = listOf(
    0L to "Immediately", 10_000L to "10 seconds", 30_000L to "30 seconds", 60_000L to "1 minute", 300_000L to "5 minutes"
)

private fun autoLockStatus(backgroundTimeoutMs: Long): String = when (backgroundTimeoutMs) {
    0L -> "Locks at once"
    10_000L -> "Locks in 10 s"
    30_000L -> "Locks in 30 s"
    60_000L -> "Locks in 1 min"
    300_000L -> "Locks in 5 min"
    else -> "Custom lock"
}

private fun autofillServiceEnabled(context: Context): Boolean =
    context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true

/** True while Nuvori is the selected Android Autofill service. Checked again every time the screen resumes. */
@Composable
internal fun rememberAutofillServiceEnabled(): Boolean {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(autofillServiceEnabled(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) enabled = autofillServiceEnabled(context)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return enabled
}

/** Facts the hub shows as status chips. Nothing here is secret. */
internal data class SettingsHubSummary(
    val backgroundTimeoutMs: Long,
    val pairedDevices: Int,
    val syncNeedsAttention: Boolean,
    val watchSyncEnabled: Boolean,
    val passkeys: Int,
    val lightMode: Boolean,
    val nfcEnabled: Boolean,
    val nfcSupported: Boolean,
    val transferPending: Boolean = false,
    val membershipNotice: MembershipNotice? = null,
)

@Composable
internal fun SettingsHub(summary: SettingsHubSummary, open: (String) -> Unit) {
    val autofillEnabled = rememberAutofillServiceEnabled()
    val membershipNotice = summary.membershipNotice
    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        SettingsHubGroup("Security") {
            SettingsHubRow("Security", "Lock now, auto-lock and master password", NuvoriIcons.Lock,
                listOf(StatusKind.NEUTRAL to autoLockStatus(summary.backgroundTimeoutMs))) { open("Security") }
        }
        SettingsHubGroup("Autofill and passkeys") {
            SettingsHubRow("Autofill and codes", "Fill logins and open codes quickly", NuvoriIcons.Password,
                listOf(if (autofillEnabled) StatusKind.SUCCESS to "On" else StatusKind.WARNING to "Off")) { open("Autofill and codes") }
            SettingsHubDivider()
            SettingsHubRow("Passkeys", "Website sign-in and encrypted backups", NuvoriIcons.Passkey,
                listOf(StatusKind.NEUTRAL to if (summary.passkeys == 0) "None saved" else "${summary.passkeys} saved")) { open("Passkeys") }
        }
        SettingsHubGroup("Sync") {
            val pairedChip = if (summary.pairedDevices == 0) StatusKind.NEUTRAL to "Not paired"
                else StatusKind.NEUTRAL to "${summary.pairedDevices} ${if (summary.pairedDevices == 1) "device" else "devices"}"
            SettingsHubRow("Devices & sync", "Pair a phone or a Windows PC", NuvoriIcons.Devices,
                listOfNotNull(pairedChip,
                    if (summary.syncNeedsAttention) StatusKind.ERROR to "Needs attention" else null,
                    if (summary.transferPending) StatusKind.INFO to "Role transfer" else null,
                    when (membershipNotice?.kind) {
                        MembershipNotice.Kind.REMOVED -> StatusKind.WARNING to "Removed"
                        MembershipNotice.Kind.LEFT -> StatusKind.NEUTRAL to "Left group"
                        null -> null
                    })) { open("Android devices") }
            SettingsHubDivider()
            SettingsHubRow("Watch codes", "Show authenticator codes on Wear OS", NuvoriIcons.Watch,
                listOf(if (summary.watchSyncEnabled) StatusKind.SUCCESS to "On" else StatusKind.NEUTRAL to "Off")) { open("Watch codes") }
        }
        SettingsHubGroup("Backup and data") {
            SettingsHubRow("Backup and import", "Encrypted backups and password exports", NuvoriIcons.Import) { open("Backup and import") }
            SettingsHubDivider()
            SettingsHubRow("Import authenticator codes", "Move codes from another app", NuvoriIcons.Code) { open("Import authenticator codes") }
        }
        SettingsHubGroup("App") {
            SettingsHubRow("Appearance", "Light or black background", if (summary.lightMode) NuvoriIcons.Sun else NuvoriIcons.Moon,
                listOf(StatusKind.NEUTRAL to if (summary.lightMode) "Light" else "Dark")) { open("Appearance") }
            SettingsHubDivider()
            SettingsHubRow("Cards and NFC", "Optional contactless card scanning", NuvoriIcons.Nfc,
                listOf(when {
                    !summary.nfcSupported -> StatusKind.NEUTRAL to "No NFC"
                    summary.nfcEnabled -> StatusKind.SUCCESS to "NFC on"
                    else -> StatusKind.NEUTRAL to "NFC off"
                })) { open("Cards and NFC") }
        }
        SettingsHubGroup("Support") {
            SettingsHubRow("Help", "Answers and shortcuts for common tasks", NuvoriIcons.Question) { open("Help") }
            SettingsHubDivider()
            SettingsHubRow("About", "Privacy and security limits", NuvoriIcons.Info) { open("About") }
        }
    }
}

@Composable
private fun SettingsHubGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        HairlineCard(Modifier.fillMaxWidth()) { Column(content = rows) }
    }
}

@Composable
private fun SettingsHubDivider() {
    HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.hairline)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsHubRow(title: String, description: String, icon: ImageVector,
    chips: List<Pair<StatusKind, String>> = emptyList(), onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).tappable(pressedScale = .985f, onClick = onClick)
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsIconBadge(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                chips.forEach { (kind, label) -> StatusChip(kind, label) }
            }
            Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(NuvoriIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** Tinted icon square used at the start of hub rows and device cards (Windows `row-icon`). */
@Composable
internal fun SettingsIconBadge(icon: ImageVector, modifier: Modifier = Modifier) {
    Box(modifier.size(36.dp).background(MaterialTheme.colorScheme.primaryContainer, NuvoriShapes.Control),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp))
    }
}

/** A page card: heading, a short explanation, then the actions. */
@Composable
internal fun SettingsSection(title: String, modifier: Modifier = Modifier, description: String? = null,
    content: @Composable ColumnScope.() -> Unit) {
    HairlineCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            if (description != null) Text(description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** A plain heading for a group of cards that are not wrapped in a card themselves. */
@Composable
internal fun SettingsHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
}

/** The whole row toggles, so the label is part of the switch for screen readers. */
@Composable
internal fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, description: String? = null, enabled: Boolean = true) {
    Row(modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, enabled = enabled,
        role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (description != null) Text(description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun SettingsButtonLabel(text: String, icon: ImageVector?) {
    if (icon != null) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    }
    Text(text)
}

private fun Modifier.settingsButtonSize(fill: Boolean): Modifier =
    (if (fill) fillMaxWidth() else this).heightIn(min = 48.dp)

/** The main action of a card. Filled. */
@Composable
internal fun SettingsPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true, fill: Boolean = true) {
    Button(onClick = onClick, modifier = modifier.settingsButtonSize(fill), enabled = enabled, shape = NuvoriShapes.Control) {
        SettingsButtonLabel(text, icon)
    }
}

/** A supporting action. Outlined. */
@Composable
internal fun SettingsSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true, fill: Boolean = true) {
    OutlinedButton(onClick = onClick, modifier = modifier.settingsButtonSize(fill), enabled = enabled, shape = NuvoriShapes.Control) {
        SettingsButtonLabel(text, icon)
    }
}

/** An action that removes data or trust. Outlined and error-colored. */
@Composable
internal fun SettingsDangerButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, enabled: Boolean = true, fill: Boolean = true) {
    val error = MaterialTheme.colorScheme.error
    OutlinedButton(onClick = onClick, modifier = modifier.settingsButtonSize(fill), enabled = enabled, shape = NuvoriShapes.Control,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = error),
        border = BorderStroke(1.dp, error.copy(alpha = if (enabled) .7f else .24f))) {
        SettingsButtonLabel(text, icon)
    }
}
