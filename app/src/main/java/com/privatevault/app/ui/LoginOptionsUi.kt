package com.privatevault.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.privatevault.app.autofill.AutofillPreferences
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.*

@Composable
internal fun AutofillPreference(refreshCopy: () -> Unit = {}) {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE) }
    var keyboardSuggestions by remember { mutableStateOf(preferences.getBoolean("autofill_keyboard_suggestions", true)) }
    var unlockedProfiles by remember { mutableStateOf(preferences.getBoolean("autofill_unlocked_profiles", false)) }
    var copyLinkedCode by remember { mutableStateOf(AutofillPreferences.copyLinkedCode(context)) }
    val autofillEnabled = rememberAutofillServiceEnabled()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingsSection("Autofill service") {
            if (autofillEnabled) {
                StatusBanner(kind = StatusKind.SUCCESS, title = "Nuvori is your Autofill service",
                    message = "Linked native apps and exact HTTPS sites in verified Chrome and Brave releases can offer logins. Supported browser forms can offer Save after you unlock and confirm.")
            } else {
                StatusBanner(kind = StatusKind.WARNING, title = "Nuvori is not your Autofill service",
                    message = "Choose Nuvori in Android to fill saved logins in supported apps and websites. This replaces your current Autofill provider.")
                SettingsPrimaryButton("Choose Nuvori autofill", icon = NuvoriIcons.Password, onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
                })
                Text("Linked native apps and exact HTTPS sites in verified Chrome and Brave releases can offer logins. Supported browser forms can offer Save after you unlock and confirm. The Vault codes tile still works with another provider.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            SettingsSection("Passwords and passkeys",
                description = "Modern Android apps use Credential Manager. Enable Nuvori there for passwords and passkeys as well.") {
                SettingsSecondaryButton("Enable passwords and passkeys", icon = NuvoriIcons.Passkey, onClick = {
                    runCatching { androidx.credentials.CredentialManager.create(context).createSettingsPendingIntent().send() }
                })
            }
        }
        SettingsSection("How logins appear", description = "Choose what you see after unlocking a login suggestion.") {
            Column(Modifier.selectableGroup()) {
                listOf(true to "Keyboard suggestions", false to "Account picker").forEach { (keyboard, label) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                        selected = keyboardSuggestions == keyboard, role = Role.RadioButton,
                        onClick = {
                            keyboardSuggestions = keyboard
                            preferences.edit().putBoolean("autofill_keyboard_suggestions", keyboard).apply()
                        }), verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = keyboardSuggestions == keyboard, onClick = null)
                        Text(label, Modifier.padding(start = 12.dp))
                    }
                }
            }
            Text(if (keyboardSuggestions) "Choose linked accounts in your keyboard. Choose another login opens the account picker and requires unlock again. Keyboards without inline support show an Android suggestion menu. Codes and password generation use the picker."
                else "After unlocking, Nuvori opens the full account picker for search, selection and linking.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsSwitchRow("Copy linked code after filling", copyLinkedCode, { enabled ->
                copyLinkedCode = enabled
                AutofillPreferences.setCopyLinkedCode(context, enabled)
            }, description = "When you pick a login in the account picker, Nuvori copies its linked authenticator code so you can paste it on the next screen. The copy is marked sensitive.")
        }
        SettingsSection("Everyday details") {
            SettingsSwitchRow("Offer saved details without vault unlock", unlockedProfiles, { enabled ->
                unlockedProfiles = enabled
                preferences.edit().putBoolean("autofill_unlocked_profiles", enabled).apply()
                if (enabled) refreshCopy()
                else com.privatevault.app.autofill.UnlockedProfileStore(context).clear()
            }, description = "Off by default. When on, email, phone, name and address profiles get a separate device-encrypted copy for Autofill. They sync through the vault, then refresh here after this device unlocks.")
            StatusBanner(kind = if (unlockedProfiles) StatusKind.WARNING else StatusKind.INFO,
                message = "Anyone using your unlocked phone can choose these details without the vault password. Passwords and authenticator codes still require unlock.")
        }
        SettingsSection("Browsers and in-app pages") {
            Text("In Chrome or Brave, enable autofill using another service in the browser's settings. The browser must expose Android autofill fields. HTTP pages, mismatched subdomains, ambiguous forms and unverified browsers are rejected.",
                style = MaterialTheme.typography.bodyMedium)
            SettingsSecondaryButton("Open browser autofill settings", icon = Icons.Outlined.Language, onClick = {
                val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_APP_BROWSER).addCategory(Intent.CATEGORY_PREFERENCE)
                runCatching { context.startActivity(Intent.createChooser(intent, "Browser autofill settings")) }
            })
            Text("Embedded app pages", style = MaterialTheme.typography.titleSmall)
            Text("In-app WebViews require you to choose a login after unlocking. The containing app can read filled credentials; approve only apps you trust. A website link does not authorize an embedded app. If nothing appears, open the page in Chrome or Brave, or copy from the Vault codes tile.",
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun LoginOptions(apps: String, signatures: String, authenticatorId: String, codes: List<VaultEntry>,
    onApps: (String, String) -> Unit, onCode: (String) -> Unit, generate: () -> Unit) {
    val context = LocalContext.current
    var showApps by remember { mutableStateOf(false) }
    var showCodes by remember { mutableStateOf(false) }
    var confirmGenerate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { confirmGenerate = true }, modifier = Modifier.fillMaxWidth()) { Text("Generate 24-character password") }
        OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Autofill apps (${linkedAppPackages(apps).size})") }
        Text("Only authorize apps you trust. Selecting Done approves the installed apps' current signing identities. Suggestions based on recent app usage do not authorize autofill.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showCodes = true }, modifier = Modifier.fillMaxWidth()) {
            Text(codes.find { it.id == authenticatorId }?.let { "Authenticator: ${it.title} · ${it.primaryValue}" } ?: "Link an authenticator")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (confirmGenerate) AlertDialog(onDismissRequest = { confirmGenerate = false }, title = { Text("Generate a new password?") },
        text = { Text("This replaces the password in this form. It does not change the password at the service. Nothing is saved until you save the login.") },
        confirmButton = { TextButton(onClick = { generate(); confirmGenerate = false }) { Text("Generate") } },
        dismissButton = { TextButton(onClick = { confirmGenerate = false }) { Text("Cancel") } })
    if (showApps) CodeAppLinksPicker(apps, { showApps = false }) { selected ->
        val old = signatures.lineSequence().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val identities = linkedAppPackages(selected).map { name -> name to (appSigningIdentity(context, name) ?: old[name]) }
        if (identities.any { it.second.isNullOrBlank() }) error = "Could not verify one of these apps. Install it before authorizing autofill."
        else { onApps(selected, identities.joinToString("\n") { "${it.first}=${it.second}" }); error = null; showApps = false }
    }
    if (showCodes) AlertDialog(onDismissRequest = { showCodes = false }, title = { Text("Link existing authenticator") },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text("The code stays in Codes and is shown with this login. Deleting this login will not delete the authenticator.") }
            item { TextButton(onClick = { onCode(""); showCodes = false }) { Text("No linked authenticator") } }
            items(codes, key = { it.id }) { code -> TextButton(onClick = { onCode(code.id); showCodes = false }) { Text("${code.title} · ${code.primaryValue}") } }
            if (codes.isEmpty()) item { Text("Add an authenticator in Codes first.") }
        } }, confirmButton = { TextButton(onClick = { showCodes = false }) { Text("Close") } })
}
