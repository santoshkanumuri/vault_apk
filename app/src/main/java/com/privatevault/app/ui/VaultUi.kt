@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class
)

package com.privatevault.app

import com.privatevault.app.security.decodePhoto
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material3.Switch
import com.privatevault.app.nfc.cardNetwork
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.VaultGroup
import com.privatevault.app.data.VaultPhoto
import com.privatevault.app.security.PasswordCrypto
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.Calendar
import java.util.Locale

internal val VaultColors = darkColorScheme(
    primary = Color(0xFF43E6A8),
    onPrimary = Color(0xFF002117),
    secondary = Color(0xFFF2C778),
    tertiary = Color(0xFFC8B2F2),
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF23262E),
    onBackground = Color(0xFFF2F2F4),
    onSurface = Color(0xFFF2F2F4),
    error = Color(0xFFFFB4AB)
)

internal val VaultLightColors = lightColorScheme(
    primary = Color(0xFF006B4D),
    onPrimary = Color.White,
    secondary = Color(0xFF795900),
    tertiary = Color(0xFF65508D),
    background = Color(0xFFF8FAF9),
    surface = Color(0xFFF8FAF9),
    surfaceVariant = Color(0xFFE7EDE9),
    onBackground = Color(0xFF17221D),
    onSurface = Color(0xFF17221D),
    onSurfaceVariant = Color(0xFF414B45),
    error = Color(0xFFAD4248)
)

@Composable
private fun DeleteButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, label: String = "Delete") {
    Button(onClick = onClick, modifier = modifier, enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD4248), contentColor = Color.White)) {
        Text(label)
    }
}

private enum class VaultTab(val label: String, val glyph: String, val type: EntryType?) {
    HOME("Home", "H", null),
    CARDS("Cards", "▣", EntryType.CARD),
    QUESTIONS("Questions", "?", EntryType.QUESTION),
    NOTES("Notes", "N", EntryType.NOTE),
    PASSWORDS("Passwords", "●", EntryType.PASSWORD),
    AUTHENTICATOR("Codes", "", EntryType.AUTHENTICATOR),
    MORE("More", "", null)
}

private val navigationTabs = listOf(VaultTab.HOME, VaultTab.CARDS, VaultTab.PASSWORDS, VaultTab.AUTHENTICATOR, VaultTab.MORE)


@Composable
fun PrivateVaultApp(
    viewModel: VaultViewModel,
    biometricAvailable: Boolean,
    onBiometricUnlock: () -> Unit,
    onEnableDailyBiometric: () -> Unit,
    onBiometricAction: (() -> Unit) -> Unit,
    onCopySecret: (String, String) -> Unit
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val requestDailyBiometric by viewModel.requestDailyBiometric.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showPrivacy by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let { snackbar.showSnackbar(it); viewModel.clearMessage() }
    }
    LaunchedEffect(requestDailyBiometric) {
        if (requestDailyBiometric) onEnableDailyBiometric()
    }
    val lightMode by viewModel.lightMode.collectAsStateWithLifecycle()
    MaterialTheme(colorScheme = if (lightMode) VaultLightColors else VaultColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize().pointerInput(status) {
                awaitPointerEventScope { while (true) { awaitPointerEvent(); viewModel.touch() } }
            }) {
                AnimatedContent(targetState = status, label = "vault state") { state ->
                    when (state) {
                        VaultStatus.NeedsSetup -> SetupScreen(viewModel)
                        is VaultStatus.Locked -> UnlockScreen(
                            viewModel, state,
                            biometricAvailable && state.canUseBiometric,
                            onBiometricUnlock
                        )
                        VaultStatus.Unlocked -> VaultHome(viewModel, onCopySecret, onBiometricAction)
                    }
                }
                SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
                if (status !is VaultStatus.Unlocked) TextButton(onClick = { showPrivacy = true }, modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()) { Text("Privacy policy") }
                if (showPrivacy) PrivacyPolicyDialog { showPrivacy = false }
            }
        }
    }
}

@Composable
private fun SetupScreen(viewModel: VaultViewModel) {
    var password by remember { mutableStateOf("") }
    var enableNfc by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf("") }
    val valid = password.length >= PasswordCrypto.MIN_PASSWORD_LENGTH && password == confirm
    CenteredAuthCard("Create your private vault", "Your master password cannot be recovered.") {
        SecretField("Master password", password) { password = it }
        SecretField("Confirm password", confirm) { confirm = it }
        Text("Use at least ${PasswordCrypto.MIN_PASSWORD_LENGTH} characters. A long phrase is easier to remember.", style = MaterialTheme.typography.bodySmall)
        NfcPreference(enableNfc, viewModel.nfcSupported) { enableNfc = it }
        Button(onClick = { viewModel.setup(password.toCharArray(), enableNfc); password = ""; confirm = "" }, enabled = valid, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Create vault")
        }
    }
}

@Composable
private fun UnlockScreen(viewModel: VaultViewModel, state: VaultStatus.Locked, canUseBiometric: Boolean, biometric: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var usePassword by remember(canUseBiometric) { mutableStateOf(!canUseBiometric) }
    LaunchedEffect(canUseBiometric) { if (canUseBiometric) biometric() }
    val reason = when (state.reason) {
        LockReason.STARTUP -> if (state.canUseBiometric) "Use fingerprint, or enter the master password to start a new 24-hour session." else "Enter the master password to start a 24-hour biometric session."
        LockReason.INACTIVITY -> "The vault locked after one minute of inactivity. ${if (canUseBiometric) "Use fingerprint to continue." else "Enter the master password."}"
        LockReason.SCREEN_OFF -> "The vault locked when the screen turned off. ${if (canUseBiometric) "Use fingerprint to continue." else "Enter the master password."}"
        LockReason.BACKGROUND -> "The vault locked when you left the app. ${if (canUseBiometric) "Use fingerprint to continue." else "Enter the master password."}"
    }
    CenteredAuthCard("Private Vault", reason) {
        if (canUseBiometric) {
            OutlinedButton(onClick = { password = ""; biometric() }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Use fingerprint")
            }
        }
        if (usePassword) {
            SecretField("Master password", password) { password = it }
            Button(onClick = { viewModel.unlock(password.toCharArray()); password = "" }, enabled = password.isNotEmpty(), modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Unlock")
            }
        } else {
            Text("If the fingerprint prompt does not appear, tap Use fingerprint to try again.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
            OutlinedButton(onClick = { usePassword = true }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Use master password") }
        }
    }
}

@Composable
private fun CenteredAuthCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().padding(24.dp), contentAlignment = Alignment.Center) {
        Card(Modifier.widthIn(max = 460.dp).fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("PRIVATE VAULT", color = MaterialTheme.colorScheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
                Text(subtitle, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                content()
            }
        }
    }
}

@Composable
private fun VaultHome(viewModel: VaultViewModel, onCopySecret: (String, String) -> Unit, onBiometricAction: (() -> Unit) -> Unit) {
    val restoreSummary by viewModel.restoreSummary.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<VaultEntry?>(null) }
    var addType by remember { mutableStateOf<EntryType?>(null) }
    var showGroups by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showQuickAdd by remember { mutableStateOf(false) }
    val tab = VaultTab.entries[tabIndex]
    val screenTitle = when (tab) {
        VaultTab.HOME -> "Private Vault"
        VaultTab.CARDS -> "Card Wallet"
        VaultTab.QUESTIONS -> "Security Questions"
        VaultTab.PASSWORDS -> "Passwords"
        VaultTab.NOTES -> "Notes"
        VaultTab.AUTHENTICATOR -> "Authenticator"
        VaultTab.MORE -> "More"
    }
    val shown = entries
        .filter { item -> tab.type == null || item.entry.type == tab.type }
        .filter { item -> tab == VaultTab.CARDS || search.isBlank() || searchableText(item).contains(search, true) }
        .let { list ->
            if (tab == VaultTab.CARDS) list.sortedBy { it.entry.sortOrder } else list
        }
    val selected = entries.firstOrNull { it.entry.id == selectedId }
    val selectEntry: (String) -> Unit = { id -> selectedId = id; viewModel.markOpened(id) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = { if (!wide) VaultNavigation(tabIndex) { tabIndex = it; selectedId = null } }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if (wide) VaultRail(tabIndex) { tabIndex = it; selectedId = null }
                Column(Modifier.weight(1f).fillMaxHeight().statusBarsPadding()) {
                    VaultToolbar(
                        screenTitle, search, { search = it },
                        showSearch = tab != VaultTab.CARDS && tab != VaultTab.MORE,
                        onAdd = { if (tab.type == null) showQuickAdd = true else addType = tab.type!! }
                    )
                    if (tab == VaultTab.MORE) {
                        MoreScreen(groups.size, entries, { showGroups = true }, { tabIndex = VaultTab.QUESTIONS.ordinal }, { tabIndex = VaultTab.NOTES.ordinal }, { showSettings = true })
                    } else if (tab == VaultTab.HOME) {
                        Dashboard(shown, selectEntry, { showQuickAdd = true }, Modifier.fillMaxSize())
                    } else if (wide) {
                        Row(Modifier.fillMaxSize()) {
                            EntryCollection(tab, shown, selectedId, selectEntry, onCopySecret, onBiometricAction, Modifier.weight(.9f))
                            VerticalDivider(Modifier.fillMaxHeight().width(1.dp))
                            if (selected != null) EntryDetail(selected, viewModel, onCopySecret, onBiometricAction, { editing = selected.entry }, { selectedId = null }, Modifier.weight(1.1f))
                            else EmptyDetail(Modifier.weight(1.1f))
                        }
                    } else {
                        EntryCollection(tab, shown, selectedId, selectEntry, onCopySecret, onBiometricAction, Modifier.fillMaxSize())
                    }
                }
            }
        }
        if (!wide && selected != null) {
            Dialog(onDismissRequest = { selectedId = null }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    EntryDetail(selected, viewModel, onCopySecret, onBiometricAction, { editing = selected.entry }, { selectedId = null }, Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding())
                }
            }
        }
    }
    if (addType != null || editing != null) {
        val editorType = addType ?: editing!!.type
        val editorGroups = entries.firstOrNull { it.entry.id == editing?.id }?.groups?.map { it.id }?.toSet().orEmpty()
        if (editorType == EntryType.AUTHENTICATOR) AuthenticatorEditor(editing, groups, editorGroups, viewModel,
            onDismiss = { editing = null; addType = null },
            onSave = { entry, ids -> viewModel.saveEntry(entry, ids); editing = null; addType = null })
        else EntryEditor(editing, editorType, groups, editorGroups, viewModel,
            onDismiss = { editing = null; addType = null },
            onSave = { entry, ids -> viewModel.saveEntry(entry, ids); editing = null; addType = null })
    }
    if (showGroups) GroupManager(
        groups = groups,
        entries = entries,
        viewModel = viewModel,
        copy = onCopySecret,
        authenticate = onBiometricAction,
        openEntry = { id -> showGroups = false; selectEntry(id) },
        close = { showGroups = false }
    )
    if (showSettings) SettingsDialog(viewModel, { showSettings = false })
    restoreSummary?.let { summary ->
        AlertDialog(properties = wideDialogProperties, onDismissRequest = viewModel::cancelRestore,
            title = { Text("Backup ready to restore") },
            text = { Text("$summary Your current vault password stays the same. Fingerprint access must be enabled again.") },
            confirmButton = { Button(onClick = viewModel::confirmRestore) { Text("Replace vault") } },
            dismissButton = { TextButton(onClick = viewModel::cancelRestore) { Text("Cancel") } })
    }
    if (showQuickAdd) AlertDialog(
        modifier = wideDialogModifier,
        properties = wideDialogProperties,
        onDismissRequest = { showQuickAdd = false },
        title = { Text("Quick add") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                EntryType.entries.forEach { type ->
                    FilledTonalButton(
                        onClick = { addType = type; showQuickAdd = false },
                        modifier = Modifier.fillMaxWidth().height(52.dp)
                    ) { Text("Add ${type.label()}") }
                }
            }
        },
        confirmButton = {}
    )
}

@Composable
private fun VaultToolbar(title: String, search: String, onSearch: (String) -> Unit, showSearch: Boolean, onAdd: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("VAULT STATUS · SECURE", color = MaterialTheme.colorScheme.primary, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
                Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.semantics { heading() })
            }
            IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) { Icon(Icons.Outlined.Add, "Add") }
        }
        if (showSearch) OutlinedTextField(
            search,
            onSearch,
            placeholder = { Text("Search $title") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().height(50.dp)
        )
    }
}

@Composable
private fun Dashboard(entries: List<EntryWithDetails>, select: (String) -> Unit, quickAdd: () -> Unit, modifier: Modifier) {
    val cards = entries.filter { it.entry.type == EntryType.CARD }
    val passwords = entries.count { it.entry.type == EntryType.PASSWORD }
    val questions = entries.count { it.entry.type == EntryType.QUESTION }
    val notes = entries.count { it.entry.type == EntryType.NOTE }
    val favorites = entries.filter { it.entry.favorite }.sortedByDescending { it.entry.updatedAt }.take(5)
    val expiring = cards.filter { expiryState(it.entry.tertiaryValue) != ExpiryState.OK }.sortedBy { expirySortKey(it.entry.tertiaryValue) }
    val recent = entries.filter { it.entry.lastOpenedAt > 0 }.sortedByDescending { it.entry.lastOpenedAt }.take(5)
    LazyColumn(modifier, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Cards", cards.size, Color(0xFF43E6A8), Modifier.weight(1f))
                    StatCard("Passwords", passwords, Color(0xFF7DB7FF), Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatCard("Questions", questions, Color(0xFFF2C778), Modifier.weight(1f))
                    StatCard("Notes", notes, Color(0xFFC8B2F2), Modifier.weight(1f))
                }
            }
        }
        item { Button(onClick = quickAdd, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("＋  Quick add") } }
        if (favorites.isNotEmpty()) item { DashboardSection("Favorites", favorites, select) }
        if (expiring.isNotEmpty()) item { DashboardSection("Needs attention", expiring, select, showExpiry = true) }
        if (recent.isNotEmpty()) item { DashboardSection("Recently opened", recent, select) }
        if (favorites.isEmpty() && recent.isEmpty()) item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Your dashboard is ready", fontWeight = FontWeight.SemiBold)
                    Text("Open entries or mark favorites and they will appear here.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .7f))
                }
            }
        }
    }
}

@Composable
private fun StatCard(label: String, count: Int, accent: Color, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = .13f))) {
        Column(Modifier.padding(14.dp)) {
            Text(count.toString(), style = MaterialTheme.typography.headlineSmall, color = if (MaterialTheme.colorScheme.background.luminance() > .5f) MaterialTheme.colorScheme.onSurface else accent, fontWeight = FontWeight.Bold)
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
private fun DashboardSection(title: String, entries: List<EntryWithDetails>, select: (String) -> Unit, showExpiry: Boolean = false) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        entries.forEach { item ->
            Card(
                Modifier.fillMaxWidth().clickable { select(item.entry.id) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(item.entry.color)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.entry.title, fontWeight = FontWeight.SemiBold)
                        Text(item.entry.type.label().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .62f))
                    }
                    if (showExpiry) Text(expiryState(item.entry.tertiaryValue).label, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun VaultNavigation(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
        navigationTabs.forEach { tab ->
            val index = if (tab == VaultTab.MORE && VaultTab.entries[selected] in listOf(VaultTab.QUESTIONS, VaultTab.NOTES)) selected else tab.ordinal
            NavigationBarItem(selected == index, { onSelect(tab.ordinal) }, icon = { VaultTabIcon(tab) }, label = { Text(tab.label) })
        }
    }
}

@Composable
private fun VaultRail(selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail(Modifier.fillMaxHeight().statusBarsPadding(), containerColor = MaterialTheme.colorScheme.background) {
        Spacer(Modifier.height(20.dp))
        navigationTabs.forEach { tab ->
            val index = if (tab == VaultTab.MORE && VaultTab.entries[selected] in listOf(VaultTab.QUESTIONS, VaultTab.NOTES)) selected else tab.ordinal
            NavigationRailItem(selected == index, { onSelect(tab.ordinal) }, icon = { VaultTabIcon(tab) }, label = { Text(tab.label) })
        }
    }
}

@Composable
private fun VaultTabIcon(tab: VaultTab) {
    val icon = when (tab) {
        VaultTab.HOME -> Icons.Outlined.Home
        VaultTab.CARDS -> Icons.Outlined.CreditCard
        VaultTab.QUESTIONS -> Icons.Outlined.QuestionAnswer
        VaultTab.PASSWORDS -> Icons.Outlined.Key
        VaultTab.NOTES -> Icons.AutoMirrored.Outlined.Notes
        VaultTab.AUTHENTICATOR -> Icons.Outlined.Timer
        VaultTab.MORE -> Icons.Outlined.MoreHoriz
    }
    Icon(icon, contentDescription = tab.label)
}

@Composable
private fun EntryCollection(
    tab: VaultTab,
    entries: List<EntryWithDetails>,
    selectedId: String?,
    select: (String) -> Unit,
    copy: (String, String) -> Unit,
    authenticate: (() -> Unit) -> Unit,
    modifier: Modifier
) {
    if (entries.isEmpty()) {
        Box(modifier.padding(24.dp), contentAlignment = Alignment.Center) {
            Text("No ${tab.label.lowercase()} yet. Tap Add to create one.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
        }
    } else if (tab.type == EntryType.CARD) {
        CardStack(entries, select, copy, authenticate, modifier)
    } else {
        LazyColumn(modifier, contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries, key = { it.entry.id }) { item ->
                if (item.entry.type == EntryType.AUTHENTICATOR) TotpTile(item.entry, copy, { select(item.entry.id) })
                else EntryRow(item, item.entry.id == selectedId) { select(item.entry.id) }
            }
        }
    }
}

@Composable
private fun CardStack(cards: List<EntryWithDetails>, select: (String) -> Unit, copy: (String, String) -> Unit, authenticate: (() -> Unit) -> Unit, modifier: Modifier) {
    var spread by rememberSaveable { mutableStateOf(false) }
    val scroll = rememberScrollState()
    LaunchedEffect(spread) { scroll.scrollTo(0) }
    val animation = spring<androidx.compose.ui.unit.Dp>(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessLow)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (spread) "Scroll through all ${cards.size} full cards." else "Scroll through the stack or tap Expand.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f)
            )
            TextButton(onClick = { spread = !spread }, modifier = Modifier.height(42.dp)) { Text(if (spread) "Collapse" else "Expand") }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val cardWidth = maxWidth.coerceAtMost(560.dp)
            val cardHeight = cardWidth / CARD_ASPECT_RATIO
            val stackedSpacing = 46.dp
            val expandedSpacing = cardHeight + 12.dp
            val targetHeight = cardHeight + (if (spread) expandedSpacing else stackedSpacing) * (cards.size - 1).coerceAtLeast(0)
            val containerHeight by animateDpAsState(targetHeight, animationSpec = animation, label = "wallet height")
            Box(
                Modifier.fillMaxSize().verticalScroll(scroll)
            ) {
                Box(Modifier.fillMaxWidth().height(containerHeight)) {
                    cards.indices.forEach { index ->
                        val offset by animateDpAsState(
                            targetValue = (if (spread) expandedSpacing else stackedSpacing) * index,
                            animationSpec = animation,
                            label = "card offset"
                        )
                        CardFace(
                            cards[index].entry,
                            Modifier
                                .align(Alignment.TopCenter)
                                .offset(y = offset)
                                .width(cardWidth)
                                .aspectRatio(CARD_ASPECT_RATIO),
                            copy,
                            authenticate
                        ) { select(cards[index].entry.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardFace(
    entry: VaultEntry,
    modifier: Modifier,
    copy: ((String, String) -> Unit)? = null,
    authenticate: ((() -> Unit) -> Unit)? = null,
    onClick: () -> Unit
) {
    val base = Color(entry.color)
    val ink = cardInk(base)
    var cvvVisible by remember(entry.id) { mutableStateOf(false) }
    LaunchedEffect(cvvVisible) { if (cvvVisible) { kotlinx.coroutines.delay(15_000); cvvVisible = false } }
    Card(modifier.clickable(onClick = onClick).semantics { contentDescription = "Open ${entry.title} ${entry.cardKind.label()} card" }, shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = base, contentColor = ink), border = if (base.luminance() < .03f) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .18f)) else null, elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(
            Modifier.fillMaxSize().padding(18.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.title, modifier = Modifier.weight(1f).padding(end = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Column(Modifier.widthIn(max = 120.dp), horizontalAlignment = Alignment.End) {
                    Text(entry.cardKind.label().uppercase(), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
                }
            }
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(formatCardNumber(entry.primaryValue), Modifier.weight(1f), fontSize = 19.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    if (copy != null && entry.primaryValue.isNotBlank()) CardCopyAction("Copy card number") { copy("Card number", entry.primaryValue) }
                }
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("CARDHOLDER", fontSize = 9.sp, color = ink, letterSpacing = .8.sp)
                        Text(entry.secondaryValue.ifBlank { "Name on card" }, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("EXPIRES", fontSize = 9.sp, color = ink, letterSpacing = .8.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.tertiaryValue.ifBlank { "MM/YY" })
                            if (copy != null && entry.tertiaryValue.isNotBlank()) CardCopyAction("Copy expiry date") { copy("Expiry", entry.tertiaryValue) }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("CVV", fontSize = 9.sp, color = ink, letterSpacing = .8.sp)
                    Spacer(Modifier.width(8.dp))
                    Text(if (cvvVisible) entry.fourthValue.ifBlank { "—" } else "•••", fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (authenticate != null && entry.fourthValue.isNotBlank()) {
                        CompactCardAction(if (cvvVisible) "Hide" else "View") {
                            if (cvvVisible) cvvVisible = false else authenticate { cvvVisible = true }
                        }
                        CardCopyAction("Authenticate to copy CVV") { authenticate { copy?.invoke("CVV", entry.fourthValue) } }
                    }
                    NetworkLogo(entry.network.ifBlank { cardNetwork(entry.primaryValue) }, Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

@Composable
private fun CardCopyAction(description: String, action: () -> Unit) {
    IconButton(onClick = action, modifier = Modifier.size(40.dp)) {
        Icon(Icons.Outlined.ContentCopy, contentDescription = description, modifier = Modifier.size(16.dp), tint = LocalContentColor.current)
    }
}

@Composable
private fun CompactCardAction(label: String, action: () -> Unit) {
    TextButton(onClick = action, colors = ButtonDefaults.textButtonColors(contentColor = LocalContentColor.current), contentPadding = PaddingValues(horizontal = 7.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EntryRow(item: EntryWithDetails, selected: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick).semantics { contentDescription = "Open ${item.entry.title}" }, colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Color(item.entry.color)), contentAlignment = Alignment.Center) {
                Text(when (item.entry.type) { EntryType.QUESTION -> "?"; EntryType.NOTE -> "N"; else -> "●" }, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(item.entry.title, fontWeight = FontWeight.SemiBold)
                Text(when (item.entry.type) { EntryType.PASSWORD, EntryType.AUTHENTICATOR -> item.entry.primaryValue; EntryType.QUESTION -> item.entry.primaryValue; EntryType.NOTE -> item.entry.notes; EntryType.CARD -> maskCard(item.entry.primaryValue) }, maxLines = 1, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .65f))
            }
            if (item.groups.isNotEmpty()) Text(item.groups.first().name, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun EntryDetail(item: EntryWithDetails, viewModel: VaultViewModel, copy: (String, String) -> Unit, authenticate: (() -> Unit) -> Unit, edit: () -> Unit, close: () -> Unit, modifier: Modifier) {
    var revealed by remember(item.entry.id) { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var selectedPhoto by remember { mutableStateOf<VaultPhoto?>(null) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        viewModel.externalFlowActive = false
        if (uri != null) viewModel.addPhoto(item.entry.id, uri) else viewModel.touch()
    }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        viewModel.finishCamera(item.entry.id, success)
    }
    fun launchCamera() {
        viewModel.prepareCamera()?.let { uri ->
            runCatching { takePhoto.launch(uri) }.onFailure { viewModel.externalFlowActive = false; viewModel.clearCamera() }
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() }
    val cameraContext = androidx.compose.ui.platform.LocalContext.current
    Column(modifier.background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(item.entry.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                Text(item.entry.type.label().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            TextButton(onClick = { viewModel.toggleFavorite(item.entry) }, modifier = Modifier.height(48.dp)) { Text(if (item.entry.favorite) "★ Favorite" else "☆ Favorite") }
            TextButton(onClick = close, modifier = Modifier.height(48.dp)) { Text("Close") }
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (item.entry.type == EntryType.CARD) {
                item {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CardFace(item.entry, Modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(CARD_ASPECT_RATIO), copy, authenticate) {}
                    }
                }
                val expiry = expiryState(item.entry.tertiaryValue)
                if (expiry != ExpiryState.OK) item {
                    Surface(color = MaterialTheme.colorScheme.error.copy(alpha = .16f), shape = RoundedCornerShape(14.dp)) {
                        Text(expiry.label, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(14.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
                item { SectionTitle("Card") }
                item { PlainRow("Card type", item.entry.cardKind.label()) }
                item { SecretRow("Card number", item.entry.primaryValue, revealed.contains("primary"), { revealed = toggle(revealed, "primary") }, copy) }
                item { PlainRow("Name on card", item.entry.secondaryValue, copy) }
                item { PlainRow("Expiry", item.entry.tertiaryValue, copy) }
                item { SectionTitle("Security") }
                item {
                    SecretRow(
                        "CVV",
                        item.entry.fourthValue,
                        revealed.contains("fourth"),
                        {
                            if ("fourth" in revealed) revealed = revealed - "fourth"
                            else authenticate { revealed = revealed + "fourth" }
                        },
                        { label, value -> authenticate { copy(label, value) } }
                    )
                }
            } else if (item.entry.type == EntryType.AUTHENTICATOR) {
                item { TotpTile(item.entry, copy, {}) }
                item { PlainRow("Account", item.entry.primaryValue) }
                item { PlainRow("Code settings", "${item.entry.totpDigits} digits · ${item.entry.totpPeriod} seconds · ${item.entry.totpAlgorithm}") }
            } else if (item.entry.type == EntryType.PASSWORD) {
                item { SectionTitle("Login") }
                item { PlainRow("Username", item.entry.primaryValue, copy) }
                item { SectionTitle("Security") }
                item { SecretRow("Password", item.entry.secondaryValue, revealed.contains("secondary"), { revealed = toggle(revealed, "secondary") }, copy) }
            } else if (item.entry.type == EntryType.QUESTION) {
                item { SectionTitle("Security question") }
                item { PlainRow("Question", item.entry.primaryValue) }
                item { SecretRow("Answer", item.entry.secondaryValue, revealed.contains("secondary"), { revealed = toggle(revealed, "secondary") }, copy) }
            } else {
                item { SectionTitle("Note") }
                item { PlainRow("Contents", item.entry.notes) }
            }
            if (item.groups.isNotEmpty()) {
                item { SectionTitle("Groups") }
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { item.groups.forEach { FilterChip(true, {}, { Text(it.name) }) } } }
            }
            if (item.photos.isNotEmpty()) {
                item { SectionTitle("Photos") }
                item {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(item.photos.sortedByDescending { it.isCover }, key = { it.id }) { photo ->
                            EncryptedPhoto(photo, viewModel) { selectedPhoto = photo }
                        }
                    }
                }
            }
            if (item.entry.type != EntryType.NOTE && item.entry.notes.isNotBlank()) {
                item { SectionTitle("Notes") }
                item { PlainRow("Additional details", item.entry.notes) }
            }
            if (item.entry.tags.isNotBlank()) {
                item { SectionTitle("Tags") }
                item { Text(item.entry.tags, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f)) }
            }
        }
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 8.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { viewModel.externalFlowActive = true; pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f).height(50.dp)) { Text("＋ Add photo") }
                    OutlinedButton(onClick = {
                        if (androidx.core.content.ContextCompat.checkSelfPermission(cameraContext, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) launchCamera()
                        else cameraPermission.launch(android.Manifest.permission.CAMERA)
                    }, modifier = Modifier.weight(1f).height(50.dp)) { Text("Camera") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = edit, modifier = Modifier.weight(1f).height(48.dp)) { Text("Edit") }
                    if (item.entry.type != EntryType.AUTHENTICATOR) OutlinedButton(onClick = { viewModel.duplicateEntry(item); close() }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Duplicate") }
                    DeleteButton(onClick = { confirmDelete = true }, modifier = Modifier.weight(1f).height(48.dp))
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(
        modifier = wideDialogModifier,
        properties = wideDialogProperties,
        onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${item.entry.title}?") },
        text = { Text("This removes the entry and its encrypted photos. It does not delete its groups.") },
        confirmButton = { DeleteButton(onClick = { viewModel.deleteEntry(item); confirmDelete = false; close() }) },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
    )
    selectedPhoto?.let { photo -> PhotoViewer(photo, viewModel, { selectedPhoto = null }) }
}

@Composable
private fun EncryptedPhoto(photo: VaultPhoto, viewModel: VaultViewModel, open: () -> Unit) {
    val bytes = remember(photo.id, photo.encryptedThumbnailFileName) { viewModel.loadThumbnail(photo) }
    val bitmap = remember(bytes) { bytes?.let { runCatching { decodePhoto(it, 480) }.getOrNull() } }
    DisposableEffect(bytes) { onDispose { bytes?.fill(0) } }
    if (bitmap != null) {
        Box(Modifier.size(150.dp, 112.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = open)) {
            androidx.compose.foundation.Image(bitmap.asImageBitmap(), "Open attached vault photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (photo.isCover) Surface(Modifier.align(Alignment.TopStart).padding(6.dp), color = Color.Black.copy(alpha = .72f), contentColor = Color.White, shape = RoundedCornerShape(8.dp)) {
                Text("Cover", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun PhotoViewer(photo: VaultPhoto, viewModel: VaultViewModel, close: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    var cropping by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(com.privatevault.app.security.PhotoCrop()) }
    var edgeControls by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val bytes = remember(photo.id, revision) { viewModel.loadPhoto(photo) }
    val bitmap = remember(bytes) { bytes?.let { runCatching { decodePhoto(it, 4096) }.getOrNull() } }
    DisposableEffect(bytes) { onDispose { bytes?.fill(0) } }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val transformState = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 5f)
        offset += pan
    }
    val scope = rememberCoroutineScope()
    fun transform(rotation: Float = 0f, region: com.privatevault.app.security.PhotoCrop? = null) {
        busy = true
        val job = viewModel.transformPhoto(photo, rotation, region)
        scope.launch { job.join(); revision++; scale = 1f; offset = androidx.compose.ui.geometry.Offset.Zero; cropping = false; busy = false }
    }
    val back: () -> Unit = { if (!busy) { if (cropping) cropping = false else close() } }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (cropping) "Crop photo" else "Photo", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    TextButton(onClick = back, enabled = !busy, modifier = Modifier.height(48.dp)) { Text(if (cropping) "Cancel" else "Close") }
                }
                if (cropping) Text("Drag the edges or corners. Drag inside to move. Save replaces this vault photo.", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
                Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    if (bitmap != null && cropping) PhotoCropEditor(bitmap, crop, { if (!busy) crop = it }, Modifier.fillMaxSize().padding(20.dp))
                    else if (bitmap != null) androidx.compose.foundation.Image(
                        bitmap.asImageBitmap(),
                        "Vault photo",
                        Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y).transformable(transformState),
                        contentScale = ContentScale.Fit
                    )
                }
                if (cropping && edgeControls) CropEdgeControls(crop) { if (!busy) crop = it }
                FlowRow(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (cropping) {
                        TextButton(onClick = { crop = com.privatevault.app.security.PhotoCrop() }, enabled = !busy, modifier = Modifier.height(48.dp)) { Text("Reset") }
                        TextButton(onClick = { edgeControls = !edgeControls }, enabled = !busy, modifier = Modifier.height(48.dp)) { Text(if (edgeControls) "Hide sliders" else "Adjust edges") }
                        Button(onClick = { transform(region = crop) }, enabled = !busy, modifier = Modifier.height(48.dp)) { Text(if (busy) "Saving…" else "Save crop") }
                    } else {
                        OutlinedButton(onClick = { transform(rotation = 90f) }, enabled = !busy && bitmap != null, modifier = Modifier.height(48.dp)) { Text("Rotate") }
                        OutlinedButton(onClick = { crop = com.privatevault.app.security.PhotoCrop(); cropping = true }, enabled = !busy && bitmap != null, modifier = Modifier.height(48.dp)) { Text("Crop") }
                        FilledTonalButton(onClick = { viewModel.setCoverPhoto(photo) }, enabled = !busy && !photo.isCover, modifier = Modifier.height(48.dp)) { Text(if (photo.isCover) "Cover photo" else "Set as cover") }
                        DeleteButton(onClick = { viewModel.deletePhoto(photo); close() }, enabled = !busy, modifier = Modifier.height(48.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text.uppercase(), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, letterSpacing = .8.sp)
}

@Composable
private fun PlainRow(label: String, value: String, copy: ((String, String) -> Unit)? = null) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(14.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value.ifBlank { "Not set" }, Modifier.weight(1f))
            if (copy != null && value.isNotBlank()) TextButton(onClick = { copy(label, value) }, modifier = Modifier.height(44.dp)) { Text("Copy") }
        }
    }
}

@Composable
private fun SecretRow(label: String, value: String, revealed: Boolean, toggle: () -> Unit, copy: (String, String) -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = {}, onLongClick = toggle)
            .padding(14.dp)
            .semantics { contentDescription = "$label. Hold to reveal." }
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (revealed) value else "••••••••", Modifier.weight(1f))
            TextButton(onClick = toggle, modifier = Modifier.height(44.dp)) { Text(if (revealed) "Hide" else "Reveal") }
            if (value.isNotBlank()) TextButton(onClick = { copy(label, value) }, modifier = Modifier.height(44.dp)) { Text("Copy") }
        }
    }
}

@Composable private fun EmptyDetail(modifier: Modifier) = Box(modifier, contentAlignment = Alignment.Center) { Text("Select an entry to see its details.", color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f)) }

@Composable
private fun EntryEditor(existing: VaultEntry?, type: EntryType, groups: List<VaultGroup>, initialGroups: Set<String>, viewModel: VaultViewModel, onDismiss: () -> Unit, onSave: (VaultEntry, Set<String>) -> Unit) {
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var primary by remember { mutableStateOf(existing?.primaryValue.orEmpty()) }
    var secondary by remember { mutableStateOf(existing?.secondaryValue.orEmpty()) }
    var tertiary by remember { mutableStateOf(existing?.tertiaryValue.orEmpty()) }
    var fourth by remember { mutableStateOf(existing?.fourthValue.orEmpty()) }
    var cardKind by remember { mutableStateOf(existing?.cardKind ?: CardKind.CREDIT) }
    var network by remember { mutableStateOf(existing?.network.orEmpty()) }
    val nfcEnabled by viewModel.nfcEnabled.collectAsStateWithLifecycle()
    val scanning by viewModel.nfcScanning.collectAsStateWithLifecycle()
    val scanned by viewModel.nfcResult.collectAsStateWithLifecycle()
    DisposableEffect(Unit) { onDispose { viewModel.cancelNfcScan() } }
    LaunchedEffect(scanned) {
        scanned?.let { card ->
            primary = card.number
            secondary = card.holder
            tertiary = card.expiry
            fourth = ""
            network = card.network
            if (title.isBlank()) title = card.network
            viewModel.consumeNfcResult()
        }
    }
    var notes by remember { mutableStateOf(existing?.notes.orEmpty()) }
    var tags by remember { mutableStateOf(existing?.tags.orEmpty()) }
    var selectedGroups by remember { mutableStateOf(initialGroups) }
    val colors = remember(existing?.id) {
        val counts = viewModel.entries.value.filter { it.entry.type == EntryType.CARD }.groupingBy { it.entry.color }.eachCount()
        colorsByUsage(counts)
    }
    var color by remember { mutableStateOf(existing?.color ?: colors.first().value) }
    val valid = title.isNotBlank() && when (type) { EntryType.CARD -> primary.isNotBlank(); EntryType.QUESTION -> primary.isNotBlank() && secondary.isNotBlank(); EntryType.PASSWORD -> secondary.isNotBlank(); EntryType.NOTE -> notes.isNotBlank(); EntryType.AUTHENTICATOR -> false }

    AlertDialog(modifier = wideDialogModifier, properties = wideDialogProperties, onDismissRequest = onDismiss, title = { Text(if (existing == null) "Add ${type.label()}" else "Edit ${type.label()}") }, text = {
        LazyColumn(Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { OutlinedTextField(title, { title = it }, label = { Text(when (type) { EntryType.CARD -> "Card label"; EntryType.NOTE -> "Note title"; else -> "Service" }) }, modifier = Modifier.fillMaxWidth()) }
            when (type) {
                EntryType.CARD -> {
                    item {
                        if (nfcEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(onClick = viewModel::requestNfcScan, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Scan card with NFC") }
                                Text("Scanning replaces card fields. Review them before saving. CVV must be entered manually.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Card type", style = MaterialTheme.typography.labelLarge)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                CardKind.entries.forEach { kind ->
                                    FilterChip(selected = cardKind == kind, onClick = { cardKind = kind }, label = { Text(kind.label()) })
                                }
                            }
                        }
                    }
                    item { OutlinedTextField(primary, { primary = it.filter(Char::isDigit).take(19); network = cardNetwork(primary) }, label = { Text("Card number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(network, { network = it.take(40) }, label = { Text("Network") }, placeholder = { Text("Visa, Mastercard, RuPay...") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(secondary, { secondary = it }, label = { Text("Name on card") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(tertiary, { tertiary = it.take(7) }, label = { Text("Expiry date") }, placeholder = { Text("MM/YY") }, modifier = Modifier.fillMaxWidth()) }
                    item { OutlinedTextField(fourth, { fourth = it.filter(Char::isDigit).take(4) }, label = { Text("CVV") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()) }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Card color", style = MaterialTheme.typography.labelLarge)
                            LazyRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(colors, key = { it.value }) { option ->
                                    val selected = color == option.value
                                    Box(
                                        Modifier.size(52.dp)
                                            .selectable(selected = selected, role = Role.RadioButton, onClick = { color = option.value })
                                            .semantics { contentDescription = option.name }
                                            .padding(4.dp)
                                            .border(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else .3f), CircleShape)
                                            .padding(4.dp)
                                            .background(Color(option.value), CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (selected) Icon(Icons.Outlined.Check, contentDescription = null, tint = cardInk(Color(option.value)), modifier = Modifier.size(20.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                EntryType.PASSWORD -> {
                    item { OutlinedTextField(primary, { primary = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth()) }
                    item { SecretField("Password", secondary) { secondary = it } }
                }
                EntryType.QUESTION -> {
                    item { OutlinedTextField(primary, { primary = it }, label = { Text("Security question") }, modifier = Modifier.fillMaxWidth()) }
                    item { SecretField("Answer", secondary) { secondary = it } }
                }
                EntryType.NOTE, EntryType.AUTHENTICATOR -> Unit
            }
            item { OutlinedTextField(notes, { notes = it }, label = { Text(if (type == EntryType.NOTE) "Note" else "Notes") }, minLines = if (type == EntryType.NOTE) 7 else 2, modifier = Modifier.fillMaxWidth()) }
            item { OutlinedTextField(tags, { tags = it }, label = { Text("Tags") }, placeholder = { Text("Travel, business, banking") }, modifier = Modifier.fillMaxWidth()) }
            if (groups.isNotEmpty()) item {
                Column { Text("Groups", style = MaterialTheme.typography.labelMedium); groups.forEach { group ->
                    Row(Modifier.fillMaxWidth().clickable { selectedGroups = toggle(selectedGroups, group.id) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(group.id in selectedGroups, { selectedGroups = toggle(selectedGroups, group.id) }); Text(group.name)
                    }
                } }
            }
        }
    }, confirmButton = {
        Button(onClick = {
            onSave((existing ?: VaultEntry(type = type, title = title)).copy(title = title.trim(), primaryValue = primary.trim(), secondaryValue = if (type == EntryType.CARD) secondary.trim() else secondary, tertiaryValue = tertiary.trim(), fourthValue = fourth.trim(), cardKind = cardKind, network = network.trim(), notes = notes.trim(), tags = tags.trim(), color = color), selectedGroups)
        }, enabled = valid) { Text("Save") }
    }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
    if (scanning) AlertDialog(
        properties = wideDialogProperties,
        onDismissRequest = viewModel::cancelNfcScan,
        title = { Text("Hold your card against the phone") },
        text = { Text("Keep the contactless card still against the back of your phone. Only readable details will be filled in; nothing is saved until you tap Save.") },
        confirmButton = { TextButton(onClick = viewModel::cancelNfcScan) { Text("Cancel scan") } }
    )
}

@Composable
private fun GroupManager(
    groups: List<VaultGroup>, entries: List<EntryWithDetails>, viewModel: VaultViewModel,
    copy: (String, String) -> Unit, authenticate: (() -> Unit) -> Unit,
    openEntry: (String) -> Unit, close: () -> Unit
) {
    var selectedId by remember { mutableStateOf<String?>(null) }
    var search by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var managingLinks by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var links by remember { mutableStateOf(setOf<String>()) }
    var confirmDelete by remember { mutableStateOf(false) }
    val selected = groups.firstOrNull { it.id == selectedId }
    val linked = entries.filter { item -> item.groups.any { it.id == selectedId } }
    val back: () -> Unit = { if (selectedId != null) selectedId = null else close() }
    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        BackHandler { back() }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = back) { Text(if (selected == null) "Close" else "Back") }
                    Text(selected?.name ?: "Groups", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 2)
                    IconButton(onClick = { name = selected?.name.orEmpty(); notes = selected?.notes.orEmpty(); editing = true }) {
                        Icon(if (selected == null) Icons.Outlined.Add else Icons.Outlined.Settings, if (selected == null) "Add group" else "Edit group")
                    }
                }
                if (selected == null) {
                    OutlinedTextField(search, { search = it }, label = { Text("Find a group") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), singleLine = true)
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item { Text("Keep each bank or service together: cards, logins, codes, questions and notes.", style = MaterialTheme.typography.bodyMedium) }
                        if (groups.isEmpty()) item { Text("Tap + to create your first group.") }
                        items(groups.filter { it.name.contains(search, true) || it.notes.contains(search, true) }, key = { it.id }) { group ->
                            val members = entries.filter { item -> item.groups.any { it.id == group.id } }
                            Card(Modifier.fillMaxWidth().clickable { selectedId = group.id }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                                        Spacer(Modifier.width(12.dp))
                                        Text(group.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(members.size.toString(), color = MaterialTheme.colorScheme.primary)
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        EntryType.entries.forEach { type ->
                                            val count = members.count { it.entry.type == type }
                                            if (count > 0) Text("$count " + type.label(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                        }
                                    }
                                    if (group.notes.isNotBlank()) Text(group.notes, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        members.filter { it.entry.type == EntryType.CARD }.take(6).forEach {
                                            Box(Modifier.size(width = 36.dp, height = 18.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(3.dp)).background(Color(it.entry.color), RoundedCornerShape(3.dp)))
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        item {
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("${linked.size} linked entries", style = MaterialTheme.typography.titleMedium)
                                    if (selected.notes.isNotBlank()) Text(selected.notes)
                                    OutlinedButton(onClick = { links = linked.map { it.entry.id }.toSet(); managingLinks = true }) { Text("Manage linked entries") }
                                }
                            }
                        }
                        if (linked.isEmpty()) item { Text("Link entries to bring this group's details together.") }
                        EntryType.entries.forEach { type ->
                            val matching = linked.filter { it.entry.type == type }
                            if (matching.isNotEmpty()) {
                                item { SectionTitle(type.label() + " · " + matching.size) }
                                items(matching, key = { it.entry.id }) { item -> GroupEntryDetails(item, copy, authenticate) { openEntry(item.entry.id) } }
                            }
                        }
                        item { DeleteButton(onClick = { confirmDelete = true }, label = "Delete group", modifier = Modifier.fillMaxWidth()) }
                    }
                }
            }
        }
    }
    if (editing) AlertDialog(properties = wideDialogProperties, onDismissRequest = { editing = false },
        title = { Text(if (selected == null) "New group" else "Edit group") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("Group notes") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
        } },
        confirmButton = { Button(enabled = name.isNotBlank(), onClick = {
            if (selected == null) viewModel.addGroup(name, notes) else viewModel.editGroup(selected, name, notes)
            editing = false
        }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } })
    if (managingLinks && selected != null) AlertDialog(properties = wideDialogProperties, onDismissRequest = { managingLinks = false },
        title = { Text("Link entries") },
        text = { LazyColumn {
            items(entries, key = { it.entry.id }) { item ->
                Row(Modifier.fillMaxWidth().clickable { links = toggle(links, item.entry.id) }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(item.entry.id in links, null)
                    Column { Text(item.entry.title); Text(item.entry.type.label(), style = MaterialTheme.typography.labelSmall) }
                }
            }
        } },
        confirmButton = { Button(onClick = { viewModel.setGroupEntries(selected, links); managingLinks = false }) { Text("Save links") } },
        dismissButton = { TextButton(onClick = { managingLinks = false }) { Text("Cancel") } })
    if (confirmDelete && selected != null) AlertDialog(properties = wideDialogProperties, onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${selected.name}?") }, text = { Text("Only this group and its notes are deleted. Linked entries remain in your vault.") },
        confirmButton = { DeleteButton(onClick = { viewModel.deleteGroup(selected); selectedId = null; confirmDelete = false }) },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
private fun GroupEntryDetails(
    item: EntryWithDetails,
    copy: (String, String) -> Unit,
    authenticate: (() -> Unit) -> Unit,
    open: () -> Unit
) {
    var revealed by remember(item.entry.id) { mutableStateOf(setOf<String>()) }
    val entry = item.entry

    if (entry.type == EntryType.CARD) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CardFace(
                    entry,
                    Modifier.widthIn(max = 560.dp).fillMaxWidth().aspectRatio(CARD_ASPECT_RATIO),
                    copy,
                    authenticate,
                    open
                )
            }
            if (entry.notes.isNotBlank()) PlainRow("Notes", entry.notes)
            if (entry.tags.isNotBlank()) PlainRow("Tags", entry.tags)
            TextButton(onClick = open, modifier = Modifier.align(Alignment.End).height(44.dp)) { Text("Open full details") }
        }
        return
    }

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            when (entry.type) {
                EntryType.PASSWORD -> {
                    PlainRow("Username", entry.primaryValue, copy)
                    SecretRow("Password", entry.secondaryValue, "password" in revealed, { revealed = toggle(revealed, "password") }, copy)
                }
                EntryType.QUESTION -> {
                    PlainRow("Question", entry.primaryValue)
                    SecretRow("Answer", entry.secondaryValue, "answer" in revealed, { revealed = toggle(revealed, "answer") }, copy)
                }
                EntryType.NOTE -> PlainRow("Contents", entry.notes)
                EntryType.AUTHENTICATOR -> TotpTile(entry, copy, open)
                EntryType.CARD -> Unit
            }
            if (entry.type != EntryType.NOTE && entry.notes.isNotBlank()) PlainRow("Notes", entry.notes)
            if (entry.tags.isNotBlank()) PlainRow("Tags", entry.tags)
            if (item.photos.isNotEmpty()) Text("${item.photos.size} ${if (item.photos.size == 1) "photo" else "photos"}", color = MaterialTheme.colorScheme.primary)
            TextButton(onClick = open, modifier = Modifier.align(Alignment.End).height(44.dp)) { Text("Open full details") }
        }
    }
}

@Composable
private fun SettingsDialog(viewModel: VaultViewModel, close: () -> Unit) {
    var showPrivacy by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val lightMode by viewModel.lightMode.collectAsStateWithLifecycle()
    val nfcEnabled by viewModel.nfcEnabled.collectAsStateWithLifecycle()
    var action by remember { mutableStateOf<String?>(null) }
    var password by remember { mutableStateOf("") }
    var current by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var pendingBackupPassword by remember { mutableStateOf<CharArray?>(null) }
    DisposableEffect(Unit) { onDispose { pendingBackupPassword?.fill('\u0000'); pendingBackupPassword = null } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        viewModel.externalFlowActive = false
        val pass = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && pass != null) viewModel.exportBackup(uri, pass) else { pass?.fill('\u0000'); viewModel.touch() }
    }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.externalFlowActive = false
        val pass = pendingBackupPassword
        pendingBackupPassword = null
        if (uri != null && pass != null) viewModel.restoreBackup(uri, pass) else { pass?.fill('\u0000'); viewModel.touch() }
    }

    AlertDialog(modifier = wideDialogModifier, properties = wideDialogProperties, onDismissRequest = close, title = { Text("Settings") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Light mode", Modifier.weight(1f))
                Switch(checked = lightMode, onCheckedChange = viewModel::setLightMode,
                    modifier = Modifier.semantics { contentDescription = "Light mode" })
            }
            NfcPreference(nfcEnabled, viewModel.nfcSupported, viewModel::setNfcEnabled)
            CodeAppDetectionPreference()
            TextButton(onClick = { showPrivacy = true }) { Text("Privacy policy") }
            OutlinedButton(onClick = { requestVaultCodesTile(context) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text("Add Vault codes tile") }
            Text("Open authenticator codes from Quick Settings after unlocking. Your password autofill app stays unchanged.", style = MaterialTheme.typography.bodySmall)
            FilledTonalButton(onClick = { action = "export" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Export encrypted backup") }
            FilledTonalButton(onClick = { action = "restore" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Restore encrypted backup") }
            OutlinedButton(onClick = { action = "password" }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Change master password") }
            Text("A cloud file provider may upload an encrypted backup outside this app.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = close) { Text("Close") } })

    if (showPrivacy) PrivacyPolicyDialog { showPrivacy = false }
    if (action == "export" || action == "restore") AlertDialog(
        modifier = wideDialogModifier,
        properties = wideDialogProperties,
        onDismissRequest = { action = null; password = "" },
        title = { Text(if (action == "export") "Protect backup" else "Replace this vault?") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (action == "export") "This backup will always require the current master password." else "The backup will be validated first. You will review its contents before confirming replacement.")
            SecretField(if (action == "export") "Current master password" else "Backup password", password) { password = it }
        } },
        confirmButton = { Button(onClick = {
            pendingBackupPassword = password.toCharArray(); password = ""
            viewModel.externalFlowActive = true
            if (action == "export") export.launch("private-vault.pvault") else restore.launch(arrayOf("application/octet-stream", "application/zip", "*/*"))
            action = null
        }, enabled = password.isNotEmpty()) { Text(if (action == "export") "Choose location" else "Choose and validate") } },
        dismissButton = { TextButton(onClick = { action = null; password = "" }) { Text("Cancel") } }
    )

    if (action == "password") AlertDialog(
        modifier = wideDialogModifier,
        properties = wideDialogProperties,
        onDismissRequest = { action = null }, title = { Text("Change master password") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SecretField("Current password", current) { current = it }
            SecretField("New password", replacement) { replacement = it }
            Text("Existing backup files keep their original passwords.", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { Button(onClick = { viewModel.changePassword(current.toCharArray(), replacement.toCharArray()); current = ""; replacement = ""; action = null }, enabled = current.isNotEmpty() && replacement.length >= PasswordCrypto.MIN_PASSWORD_LENGTH) { Text("Change") } },
        dismissButton = { TextButton(onClick = { action = null }) { Text("Cancel") } }
    )
}

@Composable
private fun SecretField(label: String, value: String, onValue: (String) -> Unit) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValue, label = { Text(label) }, singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth()
    )
}

private fun EntryType.label() = when (this) { EntryType.CARD -> "card"; EntryType.QUESTION -> "security question"; EntryType.PASSWORD -> "password"; EntryType.NOTE -> "note"; EntryType.AUTHENTICATOR -> "authenticator" }
private fun CardKind.label() = when (this) { CardKind.CREDIT -> "Credit"; CardKind.DEBIT -> "Debit" }
private val wideDialogModifier = Modifier.widthIn(max = 680.dp).fillMaxWidth().padding(horizontal = 16.dp)
private val wideDialogProperties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)
internal data class CardColor(val name: String, val value: Long)
// Selected from https://colorhunt.co/palettes/retro, with pure white added.
internal val cardColors = listOf(
    CardColor("White", 0xFFFFFFFFL),
    CardColor("Cream", 0xFFF5EBDDL),
    CardColor("Coral", 0xFFF2765EL),
    CardColor("Denim", 0xFF315B8CL),
    CardColor("Cocoa", 0xFF413333L),
    CardColor("Salmon", 0xFFFF7F50L),
    CardColor("Gold", 0xFFFFD166L),
    CardColor("Mint", 0xFF06D6A0L),
    CardColor("Ocean", 0xFF118AB2L),
    CardColor("Lilac", 0xFF9564DDL),
    CardColor("Lemon", 0xFFE4DA72L),
    CardColor("Pearl", 0xFFEEEEEEL),
    CardColor("Plum", 0xFF722F99L),
    CardColor("Silver", 0xFFC5C1C1L),
    CardColor("Sand", 0xFFFEE7C8L),
    CardColor("Rose", 0xFFFF9292L),
    CardColor("Vermilion", 0xFFDF301CL),
    CardColor("Amber", 0xFFFF9100L),
    CardColor("Ivory", 0xFFFFF1D1L),
    CardColor("Aqua", 0xFF00B7CDL),
    CardColor("Azure", 0xFF007DCCL),
    CardColor("Saffron", 0xFFFFB900L),
    CardColor("Berry", 0xFFD10056L),
    CardColor("Honey", 0xFFFFEA93L),
    CardColor("Moss", 0xFF8DB355L),
    CardColor("Midnight", 0xFF0B1849L),
    CardColor("Forest", 0xFF124D1CL),
    CardColor("Mustard", 0xFFE4B028L),
    CardColor("Linen", 0xFFEBEDE3L),
    CardColor("Teal", 0xFF215E61L),
    CardColor("Black", 0xFF000000L),
    CardColor("Charcoal", 0xFF202020L),
    CardColor("Graphite", 0xFF484848L),
    CardColor("Gray", 0xFF808080L)
)

internal fun colorsByUsage(counts: Map<Long, Int>): List<CardColor> =
    cardColors.sortedBy { counts[it.value] ?: 0 }

private fun cardInk(background: Color): Color {
    val dark = Color.Black
    val darkContrast = (background.luminance() + .05f) / (dark.luminance() + .05f)
    val whiteContrast = 1.05f / (background.luminance() + .05f)
    return if (darkContrast >= whiteContrast) dark else Color.White
}

@Composable
private fun NfcPreference(enabled: Boolean, supported: Boolean, change: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("NFC card import", Modifier.weight(1f))
            Switch(checked = enabled, onCheckedChange = change, enabled = supported,
                modifier = Modifier.semantics { contentDescription = "Enable NFC card import" })
        }
        Text(if (supported) "Optional. Off means no card scanning. When on, tap Scan card in the card form. No CVV or payments."
            else "This phone has no NFC reader. You can still enter cards manually.",
            style = MaterialTheme.typography.bodySmall)
    }
}
private const val CARD_ASPECT_RATIO = 1.586f
private fun maskCard(number: String): String {
    val digits = number.filter(Char::isDigit)
    return if (digits.length <= 4) "•••• ${digits}" else "•••• •••• •••• ${digits.takeLast(4)}"
}
private fun formatCardNumber(number: String): String {
    val digits = number.filter(Char::isDigit)
    return if (digits.isBlank()) "Card number" else digits.chunked(4).joinToString(" ")
}
private fun <T> toggle(values: Set<T>, value: T): Set<T> = if (value in values) values - value else values + value

private fun searchableText(item: EntryWithDetails): String = buildString {
    append(item.entry.title)
    append(' ')
    append(item.entry.tags)
    append(' ')
    append(item.entry.notes)
    item.groups.forEach { append(' '); append(it.name) }
}

private fun groupSummary(entries: List<EntryWithDetails>): String {
    val cards = entries.count { it.entry.type == EntryType.CARD }
    val questions = entries.count { it.entry.type == EntryType.QUESTION }
    val passwords = entries.count { it.entry.type == EntryType.PASSWORD }
    val notes = entries.count { it.entry.type == EntryType.NOTE }
    return "$cards ${if (cards == 1) "card" else "cards"} · $questions ${if (questions == 1) "question" else "questions"} · $passwords ${if (passwords == 1) "password" else "passwords"} · $notes ${if (notes == 1) "note" else "notes"}"
}

private enum class ExpiryState(val label: String) {
    OK("Valid"), SOON("Expires soon"), EXPIRED("Expired")
}

private fun expirySortKey(value: String): Long {
    val parts = value.trim().split('/', '-', ' ')
    val month = parts.getOrNull(0)?.toIntOrNull() ?: return Long.MAX_VALUE
    var year = parts.getOrNull(1)?.toIntOrNull() ?: return Long.MAX_VALUE
    if (year < 100) year += 2000
    if (month !in 1..12) return Long.MAX_VALUE
    return Calendar.getInstance(Locale.US).apply {
        clear()
        set(year, month, 1)
        add(Calendar.MILLISECOND, -1)
    }.timeInMillis
}

private fun expiryState(value: String): ExpiryState {
    val expiry = expirySortKey(value)
    if (expiry == Long.MAX_VALUE) return ExpiryState.OK
    val now = System.currentTimeMillis()
    return when {
        expiry < now -> ExpiryState.EXPIRED
        expiry - now <= 90L * 24 * 60 * 60 * 1000 -> ExpiryState.SOON
        else -> ExpiryState.OK
    }
}
