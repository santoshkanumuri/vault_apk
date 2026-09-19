package com.privatevault.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.*

@Composable
internal fun AutofillPreference() {
    val context = LocalContext.current
    Text("Password autofill", style = MaterialTheme.typography.titleSmall)
    Text("Selecting Private Vault replaces your current Autofill provider. Supports linked native apps and exact HTTPS websites in verified Chrome and Brave releases. Supported browser login forms can offer Save; unlock and confirm before creating or updating a login. The code tile still works if you keep another provider.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE, Uri.parse("package:${context.packageName}")))
    }, modifier = Modifier.fillMaxWidth()) { Text("Choose Private Vault autofill") }
    Text("In Chrome or Brave, enable autofill using another service in the browser's settings. Support depends on the browser exposing Android autofill fields. HTTP pages, mismatched subdomains, ambiguous forms and unverified browsers are rejected.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(onClick = {
        val intent = Intent(Intent.ACTION_APPLICATION_PREFERENCES).addCategory(Intent.CATEGORY_DEFAULT)
            .addCategory(Intent.CATEGORY_APP_BROWSER).addCategory(Intent.CATEGORY_PREFERENCE)
        runCatching { context.startActivity(Intent.createChooser(intent, "Browser autofill settings")) }
    }, modifier = Modifier.fillMaxWidth()) { Text("Open browser autofill settings") }
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
