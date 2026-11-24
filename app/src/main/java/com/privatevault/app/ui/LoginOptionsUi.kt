package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.OutlinedButton
import com.privatevault.app.controls.TextButton
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
import kotlinx.coroutines.launch
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
internal fun AutofillPreference(refreshCopy: () -> Unit = {}, onUsageCleared: () -> Unit = {}) {
    val context = LocalContext.current
    val usageScope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE) }
    var keyboardSuggestions by remember { mutableStateOf(AutofillPreferences.keyboardSuggestions(context)) }
    var unlockedProfiles by remember { mutableStateOf(preferences.getBoolean("autofill_unlocked_profiles", false)) }
    var copyLinkedCode by remember { mutableStateOf(AutofillPreferences.copyLinkedCode(context)) }
    var offerLockedProfiles by remember { mutableStateOf(AutofillPreferences.offerLockedProfiles(context)) }
    var offerCards by remember { mutableStateOf(AutofillPreferences.offerCards(context)) }
    var signInReminders by remember { mutableStateOf(AutofillPreferences.signInReminders(context)) }
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
                Text(stringResource(R.string.linked_native_apps_and_exact_https_sites),
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
                listOf(false to "Account picker", true to "Keyboard suggestions").forEach { (keyboard, label) ->
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
            Text(if (keyboardSuggestions) "After unlocking, your logins appear in the keyboard. Tap the field again if the keyboard closed. Choose another login opens the account picker and requires unlock again. Keyboards without inline support show an Android suggestion menu. Codes and password generation use the picker."
                else "After unlocking, Nuvori shows the logins for this app or site right away. Tap one to fill it, or search all your logins.",
                style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            SettingsSwitchRow("Copy linked code after filling", copyLinkedCode, { enabled ->
                copyLinkedCode = enabled
                AutofillPreferences.setCopyLinkedCode(context, enabled)
            }, description = "Off by default. After you pick a login, Nuvori fills its linked authenticator code on the next screen when the app or site has a code field Nuvori recognizes. Turn this on as a fallback to also put the code on the clipboard for 30 seconds. Your keyboard and the app in front can read the clipboard.")
            HorizontalDivider()
            // The "Most used" mark comes from this phone's own fill counts (ItemUsageStore). They never sync.
            Text(stringResource(R.string.logins_you_fill_most_on_this_phone_come),
                style = MaterialTheme.typography.bodySmall)
            SettingsSecondaryButton("Clear Most used", icon = NuvoriIcons.History, onClick = {
                usageScope.launch {
                    val cleared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.privatevault.app.security.ItemUsageStore.clear(context) }.isSuccess
                    }
                    if (cleared) onUsageCleared()
                }
            })
        }
        SettingsSection("Notices") {
            SettingsSwitchRow("Remind me how I signed in", signInReminders, { enabled ->
                signInReminders = enabled
                AutofillPreferences.setSignInReminders(context, enabled)
            }, description = "On by default. When Nuvori for Windows remembered that you used \"Continue with Google\" or another provider on a site, Autofill there shows \"Last time: Google · your account\". It never fills a password. Tapping it fills only that account, into an empty account field you are in.")
        }
        SettingsSection("Everyday details") {
            SettingsSwitchRow("Offer to fill cards", offerCards, { enabled ->
                offerCards = enabled
                AutofillPreferences.setOfferCards(context, enabled)
            }, description = "On by default. Payment forms in apps and supported browsers show \"Unlock to fill a card\". After you unlock, you pick a card, and Nuvori fills its number, name and expiry. The security code fills only while its switch is on, and needs your fingerprint if Nuvori was unlocked over a minute ago.")
            SettingsSwitchRow("Offer to fill contact details", offerLockedProfiles, { enabled ->
                offerLockedProfiles = enabled
                AutofillPreferences.setOfferLockedProfiles(context, enabled)
            }, description = "On by default. Address and contact forms show \"Unlock to fill your details\". After you unlock, you choose which saved details to fill. The suggestion itself shows none of your details.")
            SettingsSwitchRow("Offer saved details without vault unlock", unlockedProfiles, { enabled ->
                unlockedProfiles = enabled
                preferences.edit().putBoolean("autofill_unlocked_profiles", enabled).apply()
                if (enabled) refreshCopy()
                else com.privatevault.app.autofill.UnlockedProfileStore(context).clear()
            }, description = "Off by default. When on, email, phone, name and address profiles get a separate device-encrypted copy, and forms fill them without unlocking. When off, Nuvori asks you to unlock first. Profiles sync through the vault, then the copy refreshes here after this device unlocks.")
            StatusBanner(kind = if (unlockedProfiles) StatusKind.WARNING else StatusKind.INFO,
                message = "Anyone using your unlocked phone can choose these details without the vault password. Passwords and authenticator codes still require unlock.")
        }
        SettingsSection("Browsers and in-app pages") {
            Text(stringResource(R.string.in_chrome_or_brave_enable_autofill_using),
                style = MaterialTheme.typography.bodyMedium)
            SettingsSecondaryButton("Open browser autofill settings", icon = Icons.Outlined.Language, onClick = {
                val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).addCategory(Intent.CATEGORY_DEFAULT)
                    .addCategory(Intent.CATEGORY_APP_BROWSER).addCategory(Intent.CATEGORY_PREFERENCE)
                runCatching { context.startActivity(Intent.createChooser(intent, "Browser autofill settings")) }
            })
            Text(stringResource(R.string.embedded_app_pages), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.in_app_webviews_require_you_to_choose_a),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun LoginOptions(apps: String, signatures: String, authenticatorId: String, codes: List<VaultEntry>,
    onApps: (String, String) -> Unit, onCode: (String) -> Unit, onGenerated: (String) -> Unit) {
    val context = LocalContext.current
    var showApps by remember { mutableStateOf(false) }
    var showCodes by remember { mutableStateOf(false) }
    var showGenerator by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { showGenerator = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.generate_password)) }
        OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text("Autofill apps (${linkedAppPackages(apps).size})") }
        Text(stringResource(R.string.only_authorize_apps_you_trust_selecting), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { showCodes = true }, modifier = Modifier.fillMaxWidth()) {
            Text(codes.find { it.id == authenticatorId }?.let { "Authenticator: ${it.title} · ${it.primaryValue}" } ?: "Link an authenticator")
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (showGenerator) PasswordGeneratorDialog(onDismiss = { showGenerator = false }) { onGenerated(it); showGenerator = false }
    if (showApps) CodeAppLinksPicker(apps, { showApps = false }) { selected ->
        val old = signatures.lineSequence().filter { it.contains('=') }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val identities = linkedAppPackages(selected).map { name -> name to (appSigningIdentity(context, name) ?: old[name]) }
        if (identities.any { it.second.isNullOrBlank() }) error = "Couldn't verify one of these apps. Install it before authorizing autofill."
        else { onApps(selected, identities.joinToString("\n") { "${it.first}=${it.second}" }); error = null; showApps = false }
    }
    if (showCodes) AlertDialog(onDismissRequest = { showCodes = false }, title = { Text(stringResource(R.string.link_existing_authenticator)) },
        text = { LazyColumn(Modifier.heightIn(max = 360.dp)) {
            item { Text(stringResource(R.string.the_code_stays_in_codes_and_is_shown_wit)) }
            item { TextButton(onClick = { onCode(""); showCodes = false }) { Text(stringResource(R.string.no_linked_authenticator)) } }
            items(codes, key = { it.id }) { code -> TextButton(onClick = { onCode(code.id); showCodes = false }) { Text("${code.title} · ${code.primaryValue}") } }
            if (codes.isEmpty()) item { Text(stringResource(R.string.add_an_authenticator_in_codes_first)) }
        } }, confirmButton = { TextButton(onClick = { showCodes = false }) { Text(stringResource(R.string.close)) } })
}
