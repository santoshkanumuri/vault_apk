package com.privatevault.app

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContactPage
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.privatevault.app.data.*
import com.privatevault.app.security.Totp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
internal fun TotpTile(entry: VaultEntry, copy: (String, String) -> Unit, open: () -> Unit, initiallyMasked: Boolean = false) {
    var revealed by remember(entry.id) { mutableStateOf(!initiallyMasked) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var nowMs by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(lifecycle, entry.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try { while (true) { nowMs = System.currentTimeMillis(); delay(250) } }
            finally { nowMs = null; if (initiallyMasked) revealed = false }
        }
    }
    val current = nowMs?.div(1000)
    val period = entry.totpPeriod.coerceAtLeast(1)
    val code = remember(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period, current?.div(period)) {
        if (current == null) null else runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period, current) }.getOrNull()
    }
    // Recompute on tap so a code at a time-step boundary isn't copied stale.
    val copyCurrent: () -> Unit = {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            runCatching { Totp.code(entry.secondaryValue, entry.totpAlgorithm, entry.totpDigits, period) }
                .onSuccess { copy("Authenticator code", it) }
        }
    }
    val scheme = MaterialTheme.colorScheme
    val periodMs = period * 1000L
    val remainingMs = nowMs?.let { periodMs - it % periodMs }
    val seconds = remainingMs?.let { (it + 999) / 1000 }
    // Warn before the code rolls over so a slow paste doesn't fail.
    val accent = when {
        seconds == null -> scheme.primary
        seconds <= 2 -> statusColors(StatusKind.ERROR).accent
        seconds <= 5 -> statusColors(StatusKind.WARNING).accent
        else -> scheme.primary
    }
    // The masked card copies without revealing the code; the plain card opens its entry.
    Surface(Modifier.fillMaxWidth().clip(NuvoriShapes.Card)
        .tappable(onClickLabel = if (initiallyMasked) "Copy code" else null, pressedScale = .985f,
            onClick = if (initiallyMasked) copyCurrent else open),
        shape = NuvoriShapes.Card, color = scheme.raised, border = BorderStroke(1.dp, scheme.hairline)) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            NuvoriAvatar(entry.title, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(entry.title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (entry.favorite) FavoriteMark()
                }
                if (entry.primaryValue.isNotBlank()) Text(entry.primaryValue, style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (revealed) code?.let(::formatTotp) ?: "••• •••" else "••• •••",
                    fontSize = 26.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp,
                    color = if (revealed && seconds != null && seconds <= 5) accent else scheme.onSurface)
                if (current != null && code == null)
                    Text("Check this authenticator's setup key and settings.", color = scheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (remainingMs != null && seconds != null && code != null)
                    CountdownRing(remainingMs.toFloat() / periodMs, seconds, accent, Modifier.padding(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (initiallyMasked) TextButton(onClick = { revealed = !revealed }) { Text(if (revealed) "Hide" else "Show") }
                    IconButton(enabled = code != null, onClick = copyCurrent, modifier = Modifier.size(44.dp)) {
                        Icon(NuvoriIcons.Copy, "Copy authenticator code", Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoreScreen(groupCount: Int, entries: List<EntryWithDetails>, groups: () -> Unit, questions: () -> Unit, notes: () -> Unit,
    autofill: () -> Unit, settings: () -> Unit, devices: () -> Unit = {}, passkeys: () -> Unit = {}, help: () -> Unit = {},
    lock: (() -> Unit)? = null, passkeyCount: Int = 0, listState: LazyListState? = null) {
    val state = listState ?: rememberLazyListState()
    val vault = listOf(
        MoreRow("Notes", "${entries.count { it.entry.type == EntryType.NOTE }} private notes", EntryType.NOTE.icon, tint(EntryType.NOTE.tintIndex), notes),
        MoreRow("Security questions", "${entries.count { it.entry.type == EntryType.QUESTION }} saved questions", EntryType.QUESTION.icon, tint(EntryType.QUESTION.tintIndex), questions),
        MoreRow("Autofill details", "${entries.count { it.entry.type == EntryType.AUTOFILL }} saved profiles", EntryType.AUTOFILL.icon, tint(EntryType.AUTOFILL.tintIndex), autofill),
        MoreRow("Passkeys", if (passkeyCount == 0) "Website sign-in without passwords" else "$passkeyCount saved", NuvoriIcons.Passkey, tint(0), passkeys),
        MoreRow("Groups", "$groupCount groups · linked accounts in one place", NuvoriIcons.Folder, tint(1), groups),
    )
    val manage = listOfNotNull(
        MoreRow("Devices & sync", "Pair a phone or PC and check sync", NuvoriIcons.Devices, tint(0), devices),
        MoreRow("Settings", "Security, autofill, backup and appearance", NuvoriIcons.Settings, tint(0), settings),
        MoreRow("Help", "Answers and shortcuts", NuvoriIcons.Question, tint(0), help),
        lock?.let { MoreRow("Lock vault", "Lock now. Unlock with fingerprint or password.", NuvoriIcons.Lock, tint(0), it) },
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        LazyColumn(Modifier.widthIn(max = 720.dp).fillMaxSize(), state = state,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp)) {
            item { MoreCaption("Your vault") }
            itemsIndexed(vault, key = { _, row -> row.title }) { index, row -> MoreItem(row, index, vault.size) }
            item { Spacer(Modifier.height(20.dp)) }
            item { MoreCaption("Manage Nuvori") }
            itemsIndexed(manage, key = { _, row -> "manage-" + row.title }) { index, row -> MoreItem(row, index, manage.size) }
        }
    }
}

private data class MoreRow(val title: String, val subtitle: String, val icon: ImageVector, val tint: Tint, val open: () -> Unit)

@Composable
private fun MoreCaption(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp).semantics { heading() })
}

@Composable
private fun MoreItem(row: MoreRow, index: Int, count: Int) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().groupedSegment(index, count, scheme.raised, scheme.hairline, dividerInset = 62.dp)
        .clip(segmentShape(index, count)).tappable(pressedScale = .985f, onClick = row.open)
        .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TintedIcon(row.icon, row.tint, size = 34.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(row.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            Text(row.subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
        }
        Icon(NuvoriIcons.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun AuthenticatorEditor(existing: VaultEntry?, groups: List<VaultGroup>, initialGroups: Set<String>, viewModel: VaultViewModel, onDismiss: () -> Unit, onSave: (VaultEntry, Set<String>, List<DraftPhoto>) -> Unit) {
    val photoDraft = rememberPhotoDraftEditor(viewModel)
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
            runCatching { readQrImage(context, uri) }
                .onSuccess { acceptQr(it) }.onFailure { error = "Could not read that QR image. Try scanning it or entering the setup key." }
        }
    }
    val valid = issuer.isNotBlank() && runCatching { Totp.validate(secret, algorithm, digits.toInt(), period.toInt()) }.isSuccess
    BackHandler(onBack = onDismiss)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding().imePadding(), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    BackIcon(onDismiss)
                    Text(if (existing == null) "Add authenticator" else "Edit authenticator",
                        Modifier.weight(1f), style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(enabled = valid && !photoDraft.busy, onClick = {
                        onSave((existing ?: VaultEntry(type = EntryType.AUTHENTICATOR, title = issuer)).copy(title = issuer.trim(), primaryValue = account.trim(), secondaryValue = Totp.normalizeSecret(secret), totpAlgorithm = algorithm, totpDigits = digits.toInt(), totpPeriod = period.toInt(), notes = notes, linkedApps = linkedApps), selectedGroups, photoDraft.takeForSave())
                        secret = ""
                    }, modifier = Modifier.size(48.dp)) { Icon(NuvoriIcons.Check, contentDescription = "Save authenticator") }
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                item { PhotoDraftControls(photoDraft, viewModel) }
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
            }
    }
    if (scanner) QrScanner(onResult = ::acceptQr, close = { scanner = false })
    if (linkApps) CodeAppLinksPicker(linkedApps, dismiss = { linkApps = false }) { linkedApps = it; linkApps = false }
}
