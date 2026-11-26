package com.privatevault.wear

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Build
import android.os.PersistableBundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.SwipeToDismissBox
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.privatevault.app.appearance.AccentTones
import com.privatevault.app.appearance.AppearancePalette
import com.privatevault.app.watch.WatchSync
import com.privatevault.app.watch.parseWatchAppearance
import com.privatevault.app.security.Totp
import com.privatevault.app.watch.WatchAccount
import com.privatevault.app.watch.WatchDisplay
import com.privatevault.app.watch.CodeUrgency
import com.privatevault.app.watch.WatchSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

class WatchActivity : ComponentActivity() {
    private lateinit var store: WatchStore
    private var snapshot by mutableStateOf<WatchSnapshot?>(null)
    private var secure by mutableStateOf(false)
    private var syncedAt by mutableLongStateOf(0L)
    private var recentIds by mutableStateOf<List<String>>(emptyList())
    private var accentId by mutableStateOf<String?>(null)
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = WatchStore.get(this)
        setContent {
            WatchTheme(AppearancePalette.accent(accentId).dark) {
                WatchCodes(snapshot, secure, syncedAt, recentIds, onOpened = ::markUsed, copy = ::copyCode)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(WatchSyncService.ACTION_UPDATED)
        ContextCompat.registerReceiver(this, updateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        reload()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val items = Tasks.await(Wearable.getDataClient(this@WatchActivity).dataItems, 10, TimeUnit.SECONDS)
                    try {
                        items.filter { it.uri.path == WatchSync.SNAPSHOT_PATH }.forEach { it.data?.let(store::apply) }
                        // A queued appearance item. No item means the phone removed it, so the default applies.
                        store.setAccent(items.firstOrNull { it.uri.path == WatchSync.APPEARANCE_PATH }
                            ?.data?.let(::parseWatchAppearance))
                    } finally { items.release() }
                }
            }
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) reload()
        }
    }
    override fun onStop() { unregisterReceiver(updateReceiver); snapshot = null; super.onStop() }

    private fun reload() {
        val guard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        secure = store.isSecure() && !guard.isKeyguardLocked
        snapshot = if (secure) store.read() else null
        syncedAt = if (secure) store.syncedAt() else 0L
        recentIds = if (secure) store.recentIds() else emptyList()
        accentId = store.accent()
    }

    private fun markUsed(account: WatchAccount) {
        if (!secure) return
        store.markUsed(account.id)
        recentIds = store.recentIds()
    }

    private fun copyCode(account: WatchAccount) {
        if (!secure) return
        val guard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (guard.isKeyguardLocked) { reload(); return }
        val code = Totp.code(account.secret, account.algorithm, account.digits, account.period)
        val clip = ClipData.newPlainText("Authenticator code", code)
        if (Build.VERSION.SDK_INT >= 33)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        val clipboard = applicationContext.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(clip)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ clearCopiedCode(clipboard, code) }, CLIP_CLEAR_MILLIS)
        Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
    }
}

/** How long a copied code stays on the watch clipboard (security review M-02). */
private const val CLIP_CLEAR_MILLIS = 30_000L

/**
 * Clears a copied code unless something else was copied since. A clipboard this app can't read (it is not in front)
 * is cleared too: on a watch that costs little, and a second factor shouldn't linger.
 */
private fun clearCopiedCode(clipboard: ClipboardManager, code: String) {
    runCatching {
        val current = clipboard.primaryClip
        val text = current?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString()
        if (current != null && text != code) return
        clipboard.clearPrimaryClip()
    }
}

private const val RECENT = "Recent"
private val CodeSoon = Color(0xFFFFC94D)
private val CodeExpiring = Color(0xFFFFB4AB)
private val WatchBackground = Color(0xFF050705)
private val WatchSurface = Color(0xFF192019)
private val WatchText = Color(0xFFF0F4ED)

/** Cards and chips use the Nuvori 8dp control corner (NuvoriShapes.Control on the phone). */
private val WatchControlShape = RoundedCornerShape(8.dp)

/** Wear Material theme in the accent chosen on the phone. */
@Composable
internal fun WatchTheme(tones: AccentTones = AppearancePalette.accent(null).dark, content: @Composable () -> Unit) {
    MaterialTheme(colors = Colors(
        primary = Color(tones.primary), primaryVariant = Color(tones.primaryContainer), onPrimary = Color(tones.onPrimary),
        background = WatchBackground, surface = WatchSurface,
        onBackground = WatchText, onSurface = WatchText, onSurfaceVariant = WatchText.copy(alpha = .72f),
    ), content = content)
}

@Composable
internal fun WatchCodes(snapshot: WatchSnapshot?, secure: Boolean, syncedAt: Long = 0L, recentIds: List<String> = emptyList(),
    onOpened: (WatchAccount) -> Unit = {}, copy: (WatchAccount) -> Unit) {
    var group by remember(snapshot?.vaultId) { mutableStateOf<String?>(null) }
    var openId by remember(snapshot?.vaultId) { mutableStateOf<String?>(null) }
    val accounts = snapshot?.accounts.orEmpty()
    val sorted = remember(accounts) { accounts.sortedWith(compareBy({ it.name.lowercase() }, { it.account.lowercase() })) }
    val recent = remember(accounts, recentIds) { recentIds.mapNotNull { id -> accounts.firstOrNull { it.id == id } } }
    val letters = accounts.groupingBy(::watchLetter).eachCount().toList()
        .sortedWith(compareBy({ it.first == "#" }, { it.first }))
    LaunchedEffect(letters, recent) {
        if (group == RECENT && recent.isEmpty() || group != "All" && group != RECENT && letters.none { it.first == group }) group = null
    }
    val opened = openId?.let { id -> accounts.firstOrNull { it.id == id } }
    val open: (WatchAccount) -> Unit = { onOpened(it); openId = it.id }
    if (!secure || snapshot == null || accounts.isEmpty()) {
        WatchScreen(null) { WatchEmptyState(secure, snapshot, syncedAt) }
        return
    }
    // The list the detail screen returns to. `background` is true while it shows behind a swipe.
    val listScreen: @Composable (background: Boolean) -> Unit = { background ->
        when {
            // A short list is quicker to scroll than to search by letter.
            accounts.size <= WatchDisplay.DIRECT_LIST_LIMIT -> WatchCodeList(null, sorted, syncedAt, open)
            group == null -> WatchLetterIndex(accounts.size, recent.size, letters, syncedAt) { group = it }
            else -> {
                val current = group!!
                val shown = when (current) {
                    "All" -> sorted
                    RECENT -> recent
                    else -> sorted.filter { watchLetter(it) == current }
                }
                SwipeBack(stringResource(R.string.letter, current.toString()), handleBack = !background, onBack = { group = null },
                    background = { WatchLetterIndex(accounts.size, recent.size, letters, syncedAt) { group = it } }) {
                    WatchCodeList(current, shown, syncedAt, open)
                }
            }
        }
    }
    if (opened != null) SwipeBack(stringResource(R.string.code, opened.id.toString()), handleBack = true, onBack = { openId = null },
        background = { listScreen(true) }) { WatchCodeDetail(opened, copy) }
    else listScreen(false)
}

/**
 * Swipe right (or the back button) returns to [background]. Without this the system swipe closes the
 * whole app instead of going back one screen.
 */
@Composable
private fun SwipeBack(contentKey: String, handleBack: Boolean, onBack: () -> Unit,
    background: @Composable () -> Unit, content: @Composable () -> Unit) {
    key(contentKey) {
        val state = rememberSwipeToDismissBoxState()
        SwipeToDismissBox(onDismissed = onBack, state = state, backgroundKey = "$contentKey:background", contentKey = contentKey) { isBackground ->
            if (isBackground) background() else content()
        }
    }
    // Registered after the background so the screen in front owns the back button.
    BackHandler(enabled = handleBack, onBack = onBack)
}

/** Time at the top, a scroll indicator at the edge, and the screen's background. */
@Composable
private fun WatchScreen(listState: ScalingLazyListState?, content: @Composable () -> Unit) {
    Scaffold(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
        timeText = { TimeText() },
        positionIndicator = listState?.let { state -> { PositionIndicator(scalingLazyListState = state) } },
    ) { content() }
}

private fun watchLetter(account: WatchAccount): String = account.name.trim().firstOrNull()?.uppercaseChar()
    ?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

/** Round screens clip their corners, so list rows keep a side margin proportional to the display. */
@Composable
private fun watchContentPadding(): PaddingValues {
    val configuration = LocalConfiguration.current
    return if (configuration.isScreenRound) PaddingValues(horizontal = (configuration.screenWidthDp * .08f).dp, vertical = 24.dp)
    else PaddingValues(horizontal = 10.dp, vertical = 24.dp)
}

private fun urgencyColor(remaining: Int, calm: Color): Color = when (WatchDisplay.urgency(remaining)) {
    CodeUrgency.CALM -> calm
    CodeUrgency.SOON -> CodeSoon
    CodeUrgency.EXPIRING -> CodeExpiring
}

/** Seconds since the epoch, ticking only while the screen is in front. */
@Composable
private fun rememberEpochSeconds(): Long {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { seconds = System.currentTimeMillis() / 1_000; delay(1_000) }
        }
    }
    return seconds
}

@Composable
private fun WatchEmptyState(secure: Boolean, snapshot: WatchSnapshot?, syncedAt: Long) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colors.background).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        WatchBrand()
        Text(when {
            !secure -> "Set a screen lock and unlock your watch to view codes."
            snapshot == null -> "On your phone, open Nuvori > Settings > Watch codes to connect."
            else -> "No codes on this watch yet. Codes you add on your phone appear here."
        }, color = MaterialTheme.colors.onBackground, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
        if (secure && snapshot != null) WatchSyncedLabel(syncedAt, Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun WatchBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.nuvori_logo_transparent), contentDescription = null, modifier = Modifier.size(32.dp))
        Column {
            Text(stringResource(R.string.nuvori), style = MaterialTheme.typography.title2, color = MaterialTheme.colors.primary)
            Text(stringResource(R.string.authenticator), style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onBackground.copy(alpha = .72f))
        }
    }
}

/** When the phone last sent a new copy, so a stale list is easy to spot. */
@Composable
private fun WatchSyncedLabel(syncedAt: Long, modifier: Modifier = Modifier) {
    val label = WatchDisplay.syncedLabel(System.currentTimeMillis(), syncedAt) ?: return
    Text(label, style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center,
        color = MaterialTheme.colors.onBackground.copy(alpha = .6f), modifier = modifier.fillMaxWidth())
}

/** Letters to jump to. The crown only scrolls; a tap opens a letter. */
@Composable
private fun WatchLetterIndex(count: Int, recentCount: Int, letters: List<Pair<String, Int>>, syncedAt: Long, open: (String) -> Unit) {
    val options = (if (recentCount > 0) listOf(RECENT to recentCount) else emptyList()) + listOf("All" to count) + letters
    val listState = rememberScalingLazyListState()
    WatchScreen(listState) {
        ScalingLazyColumn(
            Modifier.fillMaxSize().testTag("letterIndex"),
            state = listState,
            contentPadding = watchContentPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { WatchBrand() } }
            item {
                Column(Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.find_a_code), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
                        color = MaterialTheme.colors.onBackground.copy(alpha = .72f))
                    WatchSyncedLabel(syncedAt)
                }
            }
            items(options, key = { it.first }) { (letter, total) ->
                Chip(
                    label = { Text(letter, style = MaterialTheme.typography.title2, modifier = Modifier.weight(1f)) },
                    secondaryLabel = null,
                    icon = null,
                    onClick = { open(letter) },
                    colors = ChipDefaults.secondaryChipColors(backgroundColor = MaterialTheme.colors.surface),
                    shape = WatchControlShape,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .semantics { contentDescription = "$letter, $total ${if (total == 1) "code" else "codes"}" },
                )
            }
        }
    }
}

@Composable
private fun WatchCodeList(group: String?, accounts: List<WatchAccount>, syncedAt: Long, open: (WatchAccount) -> Unit) {
    val seconds = rememberEpochSeconds()
    val listState = rememberScalingLazyListState()
    WatchScreen(listState) {
        ScalingLazyColumn(
            Modifier.fillMaxSize().testTag("codeList"),
            state = listState,
            contentPadding = watchContentPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (group == null) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { WatchBrand() } }
            else item { Text(when (group) { "All" -> "All codes"; RECENT -> "Recent codes"; else -> "$group codes" },
                style = MaterialTheme.typography.title2, color = MaterialTheme.colors.primary,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
            items(accounts, key = { it.id }) { account -> WatchCodeRow(account, seconds) { open(account) } }
            item { WatchSyncedLabel(syncedAt, Modifier.padding(top = 4.dp)) }
        }
    }
}

/** A thin countdown bar (Wear Material has no linear progress indicator). */
@Composable
private fun CountdownBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(color.copy(alpha = .24f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

@Composable
private fun WatchCodeRow(account: WatchAccount, seconds: Long, open: () -> Unit) {
    val remaining = WatchDisplay.remainingSeconds(account.period, seconds)
    val code = remember(account, seconds / account.period) {
        Totp.code(account.secret, account.algorithm, account.digits, account.period, seconds)
    }
    val accent = urgencyColor(remaining, MaterialTheme.colors.primary)
    Card(onClick = open, modifier = Modifier.fillMaxWidth(),
        backgroundPainter = ColorPainter(MaterialTheme.colors.surface), contentColor = MaterialTheme.colors.onSurface,
        shape = WatchControlShape) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(account.name, style = MaterialTheme.typography.title3, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (account.account.isNotBlank()) Text(account.account, style = MaterialTheme.typography.caption2, maxLines = 1,
                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colors.onSurface.copy(alpha = .72f))
            Text(WatchDisplay.groupCode(code), fontSize = if (account.digits == 8) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountdownBar(remaining.toFloat() / account.period, accent, Modifier.weight(1f))
                Text("${remaining}s", style = MaterialTheme.typography.caption2, color = accent)
            }
        }
    }
}

/** One code, as large as the screen allows, with a countdown ring around the edge and the next code near the end. */
@Composable
private fun WatchCodeDetail(account: WatchAccount, copy: (WatchAccount) -> Unit) {
    val seconds = rememberEpochSeconds()
    val haptics = LocalHapticFeedback.current
    val remaining = WatchDisplay.remainingSeconds(account.period, seconds)
    val code = remember(account, seconds / account.period) {
        Totp.code(account.secret, account.algorithm, account.digits, account.period, seconds)
    }
    val next = if (WatchDisplay.showNextCode(remaining)) remember(account, seconds / account.period) {
        Totp.code(account.secret, account.algorithm, account.digits, account.period, seconds + account.period)
    } else null
    val accent = urgencyColor(remaining, MaterialTheme.colors.primary)
    val grouped = WatchDisplay.groupCode(code)
    // The countdown ring takes the screen edge, so this screen has no time text.
    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background).testTag("codeDetail"), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(progress = remaining.toFloat() / account.period, indicatorColor = accent, strokeWidth = 4.dp,
            modifier = Modifier.fillMaxSize().padding(3.dp).semantics { contentDescription = "$remaining seconds left" })
        Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(account.name, style = MaterialTheme.typography.title3, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center)
            if (account.account.isNotBlank()) Text(account.account, style = MaterialTheme.typography.caption2, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onBackground.copy(alpha = .72f))
            Text(grouped, fontSize = if (account.digits == 8) 28.sp else 34.sp, fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace, maxLines = 1, color = accent, textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 4.dp).semantics { contentDescription = "Code $grouped" })
            Text(if (next != null) "Next ${WatchDisplay.groupCode(next)}" else "${remaining}s left",
                style = MaterialTheme.typography.caption1, fontFamily = if (next != null) FontFamily.Monospace else null,
                color = MaterialTheme.colors.onBackground.copy(alpha = .8f))
            Chip(
                label = { Text(stringResource(R.string.copy_code)) },
                onClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); copy(account) },
                colors = ChipDefaults.primaryChipColors(),
                shape = WatchControlShape,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}
