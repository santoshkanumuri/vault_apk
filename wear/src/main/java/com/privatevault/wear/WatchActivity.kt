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
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
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
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.privatevault.app.watch.WatchSync
import com.privatevault.app.security.Totp
import com.privatevault.app.watch.WatchAccount
import com.privatevault.app.watch.WatchSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class WatchActivity : ComponentActivity() {
    private lateinit var store: WatchStore
    private var snapshot by mutableStateOf<WatchSnapshot?>(null)
    private var secure by mutableStateOf(false)
    private val updateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { reload() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        store = WatchStore.get(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(
                primary = Color(0xFF78D47B), background = Color(0xFF050705),
                surface = Color(0xFF192019), onBackground = Color(0xFFF0F4ED),
                onSurface = Color(0xFFF0F4ED),
            )) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground) {
                    WatchCodes(snapshot, secure, ::copyCode)
                }
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
                    try { items.filter { it.uri.path == WatchSync.SNAPSHOT_PATH }.forEach {
                        it.data?.let(store::apply)
                    } } finally { items.release() }
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
    }

    private fun copyCode(account: WatchAccount) {
        if (!secure) return
        val guard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (guard.isKeyguardLocked) { reload(); return }
        val code = Totp.code(account.secret, account.algorithm, account.digits, account.period)
        val clip = ClipData.newPlainText("Authenticator code", code)
        if (Build.VERSION.SDK_INT >= 33)
            clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        Toast.makeText(this, "Code copied", Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun WatchCodes(snapshot: WatchSnapshot?, secure: Boolean, copy: (WatchAccount) -> Unit) {
    var group by remember(snapshot?.vaultId) { mutableStateOf<String?>(null) }
    val accounts = snapshot?.accounts.orEmpty()
    val letters = accounts.groupingBy(::watchLetter).eachCount().toList()
        .sortedWith(compareBy({ it.first == "#" }, { it.first }))
    LaunchedEffect(letters) { if (group != "All" && letters.none { it.first == group }) group = null }
    when {
        !secure || snapshot == null || accounts.isEmpty() -> WatchEmptyState(secure, snapshot)
        group == null -> WatchLetterIndex(accounts.size, letters) { group = it }
        else -> {
            BackHandler { group = null }
            WatchCodeList(group!!, accounts.filter { group == "All" || watchLetter(it) == group }, copy) { group = null }
        }
    }
}

private fun watchLetter(account: WatchAccount): String = account.name.trim().firstOrNull()?.uppercaseChar()
    ?.takeIf { it in 'A'..'Z' }?.toString() ?: "#"

@Composable
private fun WatchEmptyState(secure: Boolean, snapshot: WatchSnapshot?) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        WatchBrand()
        Text(when {
            !secure -> "Set a screen lock and unlock your watch to view codes."
            snapshot == null -> "Open Nuvori on your phone, then choose Watch codes to connect."
            else -> "No codes on this watch. Sync from your phone."
        }, color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun WatchBrand() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Image(painterResource(R.drawable.nuvori_logo_transparent), contentDescription = null, modifier = Modifier.size(42.dp))
        Column {
            Text("Nuvori", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("Authenticator", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = .72f))
        }
    }
}

@Composable
private fun WatchLetterIndex(count: Int, letters: List<Pair<String, Int>>, open: (String) -> Unit) {
    val options = listOf("All" to count) + letters
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    val threshold = with(LocalDensity.current) { 28.dp.toPx() }
    val accessibilityEnabled = (LocalContext.current.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        .isTouchExplorationEnabled
    var selected by remember(letters) { mutableIntStateOf(0) }
    var dialMoved by remember { mutableStateOf(false) }
    var dialTick by remember { mutableIntStateOf(0) }
    var dialPixels by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(selected, dialTick, dialMoved, accessibilityEnabled) {
        progress = 0f
        if (dialMoved && !accessibilityEnabled) {
            repeat(20) { delay(100); progress = (it + 1) / 20f }
            open(options[selected].first)
            dialMoved = false
        }
    }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onRotaryScrollEvent { event ->
                dialPixels += event.verticalScrollPixels
                while (abs(dialPixels) >= threshold) {
                    val next = (selected + if (dialPixels > 0) 1 else -1).coerceIn(options.indices)
                    if (next != selected) {
                        selected = next
                        dialMoved = true
                        scope.launch { listState.animateScrollToItem(next + 2) }
                    }
                    dialPixels += if (dialPixels > 0) -threshold else threshold
                }
                if (dialMoved) dialTick++
                true
            }
            .pointerInput(Unit) { awaitPointerEventScope { while (true) {
                if (awaitPointerEvent().changes.any { it.pressed }) dialMoved = false
            } } }
            .focusRequester(focus).focusable().testTag("letterIndex"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { WatchBrand() } }
        item { Text("Find a code", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = .72f)) }
        itemsIndexed(options, key = { _, item -> item.first }) { index, (letter, total) ->
            val active = dialMoved && selected == index
            Card(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                .clickable(onClickLabel = "Open $letter codes", role = Role.Button) { open(letter) }
                .semantics { contentDescription = "$letter, $total ${if (total == 1) "code" else "codes"}" },
                colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 9.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(letter, style = MaterialTheme.typography.titleLarge, color = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurface)
                        Text("$total", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    if (active && !accessibilityEnabled) {
                        Text("Pause to open", style = MaterialTheme.typography.labelSmall)
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun WatchCodeList(group: String, accounts: List<WatchAccount>, copy: (WatchAccount) -> Unit, back: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var seconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1_000) }
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { seconds = System.currentTimeMillis() / 1_000; delay(1_000) }
        }
    }
    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(group) { focus.requestFocus() }
    LazyColumn(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .onRotaryScrollEvent { event ->
                scope.launch { listState.scrollBy(event.verticalScrollPixels) }
                true
            }
            .focusRequester(focus).focusable().testTag("codeList"),
        state = listState,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { TextButton(onClick = back, modifier = Modifier.heightIn(min = 48.dp)) { Text("‹ Letters") } }
        item { Text(if (group == "All") "All codes" else "$group codes", style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
        items(accounts.sortedWith(compareBy({ it.name.lowercase() }, { it.account.lowercase() })), key = { it.id }) { account ->
            Card(Modifier.fillMaxWidth().clickable(onClickLabel = "Copy ${account.name} code", role = Role.Button) { copy(account) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(account.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (account.account.isNotBlank()) Text(account.account, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .72f))
                    val code = Totp.code(account.secret, account.algorithm, account.digits, account.period, seconds)
                    Text(code.chunked(if (account.digits == 8) 4 else 3).joinToString(" "),
                        fontSize = if (account.digits == 8) 24.sp else 28.sp, fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace, maxLines = 1, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                    LinearProgressIndicator(progress = (account.period - seconds % account.period).toFloat() / account.period,
                        modifier = Modifier.fillMaxWidth())
                    Text("${account.period - seconds % account.period}s  ·  Tap to copy", style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                }
            }
        }
    }
}
