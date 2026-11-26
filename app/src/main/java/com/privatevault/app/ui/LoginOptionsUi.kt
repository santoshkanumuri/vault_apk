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
        SettingsSection(stringResource(R.string.autofill_service)) {
            if (autofillEnabled) {
                StatusBanner(kind = StatusKind.SUCCESS, title = stringResource(R.string.nuvori_is_your_autofill_service),
                    message = stringResource(R.string.linked_native_apps_and_exact_https_sites_2))
            } else {
                StatusBanner(kind = StatusKind.WARNING, title = stringResource(R.string.nuvori_is_not_your_autofill_service),
                    message = stringResource(R.string.choose_nuvori_in_android_to_fill_saved_l))
                SettingsPrimaryButton(stringResource(R.string.choose_nuvori_autofill), icon = NuvoriIcons.Password, onClick = {
                    context.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
                })
                Text(stringResource(R.string.linked_native_apps_and_exact_https_sites),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            SettingsSection(stringResource(R.string.passwords_and_passkeys),
                description = stringResource(R.string.modern_android_apps_use_credential_manag)) {
                SettingsSecondaryButton(stringResource(R.string.enable_passwords_and_passkeys), icon = NuvoriIcons.Passkey, onClick = {
                    runCatching { androidx.credentials.CredentialManager.create(context).createSettingsPendingIntent().send() }
                })
            }
        }
        SettingsSection(stringResource(R.string.how_logins_appear), description = stringResource(R.string.choose_what_you_see_after_unlocking_a_lo)) {
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
            SettingsSwitchRow(stringResource(R.string.copy_linked_code_after_filling), copyLinkedCode, { enabled ->
                copyLinkedCode = enabled
                AutofillPreferences.setCopyLinkedCode(context, enabled)
            }, description = stringResource(R.string.off_by_default_after_you_pick_a_login_nu))
            HorizontalDivider()
            // The "Most used" mark comes from this phone's own fill counts (ItemUsageStore). They never sync.
            Text(stringResource(R.string.logins_you_fill_most_on_this_phone_come),
                style = MaterialTheme.typography.bodySmall)
            SettingsSecondaryButton(stringResource(R.string.clear_most_used), icon = NuvoriIcons.History, onClick = {
                usageScope.launch {
                    val cleared = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        runCatching { com.privatevault.app.security.ItemUsageStore.clear(context) }.isSuccess
                    }
                    if (cleared) onUsageCleared()
                }
            })
        }
        SettingsSection(stringResource(R.string.notices)) {
            SettingsSwitchRow(stringResource(R.string.remind_me_how_i_signed_in), signInReminders, { enabled ->
                signInReminders = enabled
                AutofillPreferences.setSignInReminders(context, enabled)
            }, description = stringResource(R.string.on_by_default_when_nuvori_for_windows_re))
        }
        SettingsSection(stringResource(R.string.everyday_details)) {
            SettingsSwitchRow(stringResource(R.string.offer_to_fill_cards), offerCards, { enabled ->
                offerCards = enabled
                AutofillPreferences.setOfferCards(context, enabled)
            }, description = stringResource(R.string.on_by_default_payment_forms_in_apps_and))
            SettingsSwitchRow(stringResource(R.string.offer_to_fill_contact_details), offerLockedProfiles, { enabled ->
                offerLockedProfiles = enabled
                AutofillPreferences.setOfferLockedProfiles(context, enabled)
            }, description = stringResource(R.string.on_by_default_address_and_contact_forms))
            SettingsSwitchRow(stringResource(R.string.offer_saved_details_without_vault_unlock), unlockedProfiles, { enabled ->
                unlockedProfiles = enabled
                preferences.edit().putBoolean("autofill_unlocked_profiles", enabled).apply()
                if (enabled) refreshCopy()
                else com.privatevault.app.autofill.UnlockedProfileStore(context).clear()
            }, description = stringResource(R.string.off_by_default_when_on_email_phone_name))
            StatusBanner(kind = if (unlockedProfiles) StatusKind.WARNING else StatusKind.INFO,
                message = stringResource(R.string.anyone_using_your_unlocked_phone_can_cho))
        }
        SettingsSection(stringResource(R.string.browsers_and_in_app_pages)) {
            Text(stringResource(R.string.in_chrome_or_brave_enable_autofill_using),
                style = MaterialTheme.typography.bodyMedium)
            SettingsSecondaryButton(stringResource(R.string.open_browser_autofill_settings), icon = Icons.Outlined.Language, onClick = {
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
        OutlinedButton(onClick = { showApps = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.autofill_apps, (linkedAppPackages(apps).size).toString())) }
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
