package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.privatevault.app.data.*
import com.privatevault.app.security.Totp
import com.privatevault.app.security.decodePhoto
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TotpTile(entry: VaultEntry, copy: (String, String) -> Unit, open: () -> Unit, initiallyMasked: Boolean = false) {
    var revealed by remember(entry.id) { mutableStateOf(!initiallyMasked) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lifecycle, entry.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { while (true) { seconds = System.currentTimeMillis() / 1000; delay(250) } }
            finally { seconds = null; if (initiallyMasked) revealed = false }
        }
    }
    val current = seconds
    val code = remember(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod, current?.div(entry.totpPeriod.coerceAtLeast(1))) {
        if (current == null) null else runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod, current) }.getOrNull()
    }
    Card(if (initiallyMasked) Modifier.fillMaxWidth() else Modifier.fillMaxWidth().clickable(onClick = open)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            if (entry.primaryValue.isNotBlank()) Text(entry.primaryValue, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (revealed) code ?: "••• •••" else "••• •••", Modifier.weight(1f), style = MaterialTheme.typography.headlineMedium)
                if (initiallyMasked) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
                IconButton(enabled = code != null, onClick = {
                    // Recompute on tap so a code at a time-step boundary isn't copied stale.
                    if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, entry.totpPeriod) }
                            .onSuccess { copy("Authenticator code", it) }
                    }
                }) { Icon(Icons.Outlined.ContentCopy, "Copy authenticator code") }
            }
            if (current != null && code != null) {
                val remaining = entry.totpPeriod - current % entry.totpPeriod
                LinearProgressIndicator(progress = remaining.toFloat() / entry.totpPeriod, modifier = Modifier.fillMaxWidth())
                Text("New code in ${remaining}s", style = MaterialTheme.typography.labelSmall)
            } else if (current != null) Text("Check this authenticator's setup key and settings.", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
internal fun MoreScreen(groupCount: Int, entries: List<EntryWithDetails>, groups: () -> Unit, questions: () -> Unit, notes: () -> Unit, settings: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Your vault", style = MaterialTheme.typography.titleMedium) }
        item { MoreItem("Groups", "$groupCount groups · linked accounts in one place", groups) }
        item { MoreItem("Security questions", "${entries.count { it.entry.type == EntryType.QUESTION }} saved questions", questions) }
        item { MoreItem("Notes", "${entries.count { it.entry.type == EntryType.NOTE }} private notes", notes) }
        item { MoreItem("Settings", "Appearance, NFC, encrypted backup and restore", settings) }
    }
}

@Composable
private fun MoreItem(title: String, subtitle: String, open: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = open)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun AuthenticatorEditor(existing: VaultEntry?, groups: List<VaultGroup>, initialGroups: Set<String>, viewModel: VaultViewModel, onDismiss: () -> Unit, onSave: (VaultEntry, Set<String>) -> Unit) {
    var issuer by remember { mutableStateOf(existing?.title.orEmpty()) }
    var account by remember { mutableStateOf(existing?.primaryValue.orEmpty()) }
    var secret by remember { mutableStateOf(existing?.secondaryValue.orEmpty()) }
    var algorithm by remember { mutableStateOf(existing?.totpAlgorithm ?: "SHA1") }
    var digits by remember { mutableStateOf((existing?.totpDigits ?: 6).toString()) }
    var period by remember { mutableStateOf((existing?.totpPeriod ?: 30).toString()) }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var linkedApps by remember { mutableStateOf(existing?.linkedApps.orEmpty()) }
    var linkApps by remember { mutableStateOf(false) }
    var selectedGroups by remember { mutableStateOf(initialGroups) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanner by remember { mutableStateOf(false) }
    var advanced by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    fun acceptQr(text: String) {
        runCatching { Totp.parse(text) }.onSuccess {
            issuer = it.issuer.ifBlank { it.account }; account = it.account; secret = it.secret
            algorithm = it.algorithm; digits = it.digits.toString(); period = it.period.toString(); error = null
        }.onFailure { error = "This is not a supported TOTP setup QR. You can enter the setup key manually." }
        scanner = false
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanner = true else error = "Camera permission is needed to scan. You can import a QR image or enter the key."
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.externalFlowActive = false
        if (uri != null) scope.launch {
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val chunk = ByteArray(8192)
                        try {
                            while (true) {
                                val n = input.read(chunk); if (n < 0) break
                                require(output.size() + n <= 20 * 1024 * 1024)
                                output.write(chunk, 0, n)
                            }
                            output.toByteArray()
                        } finally { chunk.fill(0) }
                    }
                    try {
                        val bitmap = decodePhoto(bytes, 2048)
                        try { readQr(bitmap) ?: error("No QR") } finally { bitmap.recycle() }
                    } finally { bytes.fill(0) }
                }
            }.onSuccess { acceptQr(it) }.onFailure { error = "Could not read that QR image. Try scanning it or entering the setup key." }
        }
    }
    val valid = issuer.isNotBlank() && runCatching { Totp.validate(secret, algorithm, digits.toInt(), period.toInt()) }.isSuccess
    AlertDialog(
        properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn),
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().padding(12.dp),
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add authenticator" else "Edit authenticator") },
        text = {
            LazyColumn(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { Text("Scan the setup QR, then enter a generated code on the website to finish enrollment. Your phone's automatic date and time should be enabled.") }
                item { OutlinedButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanner = true
                    else permission.launch(Manifest.permission.CAMERA)
                }, modifier = Modifier.fillMaxWidth()) { Text("Scan setup QR") } }
                item { OutlinedButton(onClick = { viewModel.externalFlowActive = true; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) { Text("Import QR image") } }
                item { Text("Imported originals remain outside the vault. The scanner does not save camera images.", style = MaterialTheme.typography.bodySmall) }
                item { OutlinedTextField(issuer, { issuer = it }, label = { Text("Service") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(account, { account = it }, label = { Text("Account") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedTextField(secret, { secret = it }, label = { Text("Setup key") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
                item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Hide code settings" else "Code settings") } }
                if (advanced) {
                    item { OutlinedTextField(algorithm, { algorithm = it.uppercase(java.util.Locale.ROOT) }, label = { Text("Algorithm: SHA1, SHA256, SHA512") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(digits, { digits = it }, label = { Text("Digits: 6–8") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(period, { period = it }, label = { Text("Interval in seconds") }, modifier = Modifier.fillMaxWidth()) }
                }
                item { OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()) }
                item { OutlinedButton(onClick = { linkApps = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Linked apps (${com.privatevault.app.security.linkedAppPackages(linkedApps).size})")
                } }
                if (groups.isNotEmpty()) item { Text("Link to groups", style = MaterialTheme.typography.titleSmall) }
                items(groups, key = { it.id }) { group ->
                    Row(Modifier.fillMaxWidth().clickable { selectedGroups = if (group.id in selectedGroups) selectedGroups - group.id else selectedGroups + group.id }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group.id in selectedGroups, null); Text(group.name)
                    }
                }
                error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            }
        },
        confirmButton = { Button(enabled = valid, onClick = {
            onSave((existing ?: VaultEntry(type = EntryType.AUTHENTICATOR, title = issuer)).copy(title = issuer.trim(), primaryValue = account.trim(), secondaryValue = Totp.normalizeSecret(secret), totpAlgorithm = algorithm, totpDigits = digits.toInt(), totpPeriod = period.toInt(), notes = notes, linkedApps = linkedApps), selectedGroups)
            secret = ""
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (scanner) QrScanner(onResult = ::acceptQr, close = { scanner = false })
    if (linkApps) CodeAppLinksPicker(linkedApps, dismiss = { linkApps = false }) { linkedApps = it; linkApps = false }
}
