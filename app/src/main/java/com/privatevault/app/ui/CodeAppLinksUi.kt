package com.privatevault.app

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.privatevault.app.security.linkedAppPackages
import com.privatevault.app.security.usageAccessGranted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun CodeAppDetectionPreference() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vault_preferences", android.content.Context.MODE_PRIVATE) }
    var enabled by remember { mutableStateOf(prefs.getBoolean("code_app_detection", false)) }
    var granted by remember { mutableStateOf(usageAccessGranted(context)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) granted = usageAccessGranted(context) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Suggest codes for the previous app", Modifier.weight(1f))
        Switch(enabled, { enabled = it; prefs.edit().putBoolean("code_app_detection", it).apply() },
            modifier = Modifier.semantics { contentDescription = "Suggest codes for the previous app" })
    }
    if (enabled) {
        Text("Usage access lets the vault inspect recent app activity when you open the tile. No usage history is stored. Browsers do not identify the website.", style = MaterialTheme.typography.bodySmall)
        if (!granted) OutlinedButton(onClick = {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}")))
        }, modifier = Modifier.fillMaxWidth()) { Text("Allow Usage access") }
        else Text("Usage access allowed", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun CodeAppLinksPicker(value: String, dismiss: () -> Unit, save: (String) -> Unit) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf(linkedAppPackages(value)) }
    var search by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val installed = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }.distinctBy { it.first }
            (installed + selected.filter { name -> installed.none { it.first == name } }.map { it to "$it (not installed)" }).sortedBy { it.second.lowercase() }
        }
        loading = false
    }
    AlertDialog(onDismissRequest = dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth().padding(12.dp),
        title = { Text("Linked apps") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Choose the apps that should suggest this account. You can link several accounts to one app.")
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text("Search apps") }, modifier = Modifier.fillMaxWidth())
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(apps.filter { it.first.contains(search, true) || it.second.contains(search, true) }, key = { it.first }) { (name, label) ->
                        Row(Modifier.fillMaxWidth().toggleable(name in selected, role = Role.Checkbox, onValueChange = {
                            selected = if (it) selected + name else selected - name
                        }).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(name in selected, null)
                            Column(Modifier.weight(1f)) {
                                Text(label)
                                Text(name, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { save(selected.sorted().joinToString("\n")) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
