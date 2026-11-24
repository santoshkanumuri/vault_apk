package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.Button
import com.privatevault.app.controls.FilledTonalButton
import com.privatevault.app.controls.OutlinedButton
import com.privatevault.app.controls.TextButton
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SignInMethod

/** White ink on dark brand colors, near-black on light ones; the same rule as Windows `ProviderMark`. */
internal fun signInMarkInk(rgb: Int): Int = if (SiteIconLogic.glyphIsWhite(rgb)) 0xFFFFFFFF.toInt() else 0xFF111111.toInt()

/**
 * The provider's mark (Simple Icons, CC0) or its letter tile, in its brand color. Decorative: the text next to it
 * names the provider. Near-black tiles get a hairline so they stay visible in dark mode.
 */
@Composable
internal fun ProviderMark(provider: String, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val mark = SignInMarks.of(provider)
    val shape = RoundedCornerShape(size / 4)
    val ink = Color(signInMarkInk(mark.rgb))
    var box = modifier.size(size).clip(shape).background(Color(0xFF000000.toInt() or mark.rgb))
    if (SiteIconLogic.luminance(mark.rgb) < 0.02 || SiteIconLogic.needsBorder(mark.rgb))
        box = box.border(1.dp, MaterialTheme.colorScheme.hairline, shape)
    Box(box.clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        val path = mark.path
        if (path != null) {
            val glyph = remember(path) { SiteIcons.path(path) }
            Canvas(Modifier.size(size * .6f)) {
                val k = this.size.minDimension / 24f
                withTransform({ scale(k, k, pivot = Offset.Zero) }) { drawPath(glyph, ink) }
            }
        } else Text(mark.letter ?: provider.take(1).uppercase(), color = ink, fontWeight = FontWeight.Bold,
            fontSize = (size.value * .44f).sp, maxLines = 1)
    }
}

/** The same mark as a bitmap of [px] square pixels, for autofill suggestions (they take no Compose content). */
internal fun signInMarkBitmap(provider: String, px: Int): Bitmap {
    val mark = SignInMarks.of(provider)
    val bitmap = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() or mark.rgb }
    val radius = px / 4f
    canvas.drawRoundRect(RectF(0f, 0f, px.toFloat(), px.toFloat()), radius, radius, fill)
    val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = signInMarkInk(mark.rgb) }
    val path = mark.path
    if (path != null) {
        // The Compose parser, like the tile: Simple Icons paths use compact arc flags ("0 00-4.8"), which
        // androidx.core's PathParser rejects (Discord threw ArrayIndexOutOfBounds inside the autofill service).
        val glyph = SiteIcons.path(path).asAndroidPath()
        val scale = px * .6f / 24f
        canvas.save()
        canvas.translate(px * .2f, px * .2f)
        canvas.scale(scale, scale)
        canvas.drawPath(glyph, ink)
        canvas.restore()
    } else {
        val letter = mark.letter ?: provider.take(1).uppercase()
        ink.textSize = px * (if (letter.length > 1) .40f else .48f)
        ink.isFakeBoldText = true
        ink.textAlign = Paint.Align.CENTER
        val bounds = android.graphics.Rect().also { ink.getTextBounds(letter, 0, letter.length, it) }
        canvas.drawText(letter, px / 2f, px / 2f + bounds.height() / 2f, ink)
    }
    return bitmap
}

/** One remembered method: mark, "GitHub · octocat", last use and count, and an optional forget action. */
@Composable
private fun SignInMethodRow(method: SignInMethod, now: Long, onForget: (() -> Unit)?, compact: Boolean = false) {
    val name = SignInMarks.name(method.provider)
    val uses = if (method.useCount == 1L) "1 time" else "${method.useCount} times"
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 44.dp else 56.dp)
        .padding(horizontal = if (compact) 0.dp else 16.dp, vertical = if (compact) 4.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ProviderMark(method.provider, size = if (compact) 28.dp else 32.dp)
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row {
                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                if (method.account.isNotEmpty()) Text(" · ${method.account}", style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                else Text(" · account not known", style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("Last used ${relativeTime(method.lastUsedAt, now)}" + if (compact) "" else " · $uses",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        if (onForget != null) IconButton(onClick = onForget, modifier = Modifier.size(48.dp)) {
            Icon(NuvoriIcons.Delete, contentDescription = "Forget $name on ${method.site}",
                tint = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * Sign-ins: which "Continue with <provider>" button, and which account, the user used on each site. Nuvori for
 * Windows remembers them through the browser extension; sync brings them here. Grouped by site, with provider chips,
 * an account filter and a site search that matches any page of a site. Forgetting one deletes it on every device.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SignInMethodsPage(viewModel: VaultViewModel) {
    val items by viewModel.signInMethods.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var provider by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("") }
    var forget by remember { mutableStateOf<SignInMethod?>(null) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); now = System.currentTimeMillis() } }
    val groups = remember(items, provider, account, query) { SignInMethodsLogic.groupBySite(items, provider, account, query) }
    val providers = remember(items) { SignInMethodsLogic.providersOf(items) }
    val accounts = remember(items) { SignInMethodsLogic.accountsOf(items) }

    Text(stringResource(R.string.when_you_sign_in_to_a_site_with_google_a),
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (items.isEmpty()) {
        HairlineCard(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TintedIcon(NuvoriIcons.LogIn, tint(EntryType.PASSWORD.tintIndex), size = 48.dp)
                Text(stringResource(R.string.no_sign_in_methods_yet), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                Text(stringResource(R.string.on_your_pc_sign_in_to_a_site_with_contin),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
    } else {
        NuvoriSearchField(query, { query = it }, "Search by site")
        if (providers.size > 1) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(provider.isEmpty(), { provider = "" }, { Text(stringResource(R.string.all)) }, shape = NuvoriShapes.Chip)
            providers.forEach { id ->
                val count = items.count { it.provider == id }
                FilterChip(provider == id, { provider = if (provider == id) "" else id }, { Text("${SignInMarks.name(id)} $count") },
                    leadingIcon = { ProviderMark(id, size = 18.dp) }, shape = NuvoriShapes.Chip,
                    modifier = Modifier.semantics { contentDescription = "${SignInMarks.name(id)}, $count" })
            }
        }
        if (accounts.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            val label = when (account) {
                "" -> "All accounts"
                SignInMethodsLogic.NO_ACCOUNT -> "Account not known"
                else -> account
            }
            Box {
                OutlinedButton(onClick = { open = true }, shape = NuvoriShapes.Control,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Account filter: $label" }) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 240.dp))
                    Spacer(Modifier.width(6.dp))
                    Icon(NuvoriIcons.ChevronDown, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.all_accounts)) }, onClick = { account = ""; open = false })
                    accounts.forEach { value -> DropdownMenuItem(text = { Text(value) }, onClick = { account = value; open = false }) }
                    if (items.any { it.account.isEmpty() }) DropdownMenuItem(text = { Text(stringResource(R.string.account_not_known)) },
                        onClick = { account = SignInMethodsLogic.NO_ACCOUNT; open = false })
                }
            }
        }
        if (groups.isEmpty()) {
            HairlineCard(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TintedIcon(NuvoriIcons.Search, tint(0), size = 48.dp)
                    Text(stringResource(R.string.no_matches), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.try_another_site_or_clear_the_filters), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    FilledTonalButton(onClick = { query = ""; provider = ""; account = "" }, shape = NuvoriShapes.Control) { Text(stringResource(R.string.clear_filters)) }
                }
            }
        } else {
            val methods = groups.sumOf { it.methods.size }
            Text("${groups.size} ${if (groups.size == 1) "site" else "sites"} · $methods ${if (methods == 1) "sign-in method" else "sign-in methods"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            groups.forEach { group ->
                HairlineCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            NuvoriAvatar(group.site, size = 28.dp, site = group.site)
                            Text(group.site, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.hairline)
                        group.methods.forEachIndexed { index, method ->
                            if (index > 0) HorizontalDivider(Modifier.padding(start = 60.dp), color = MaterialTheme.colorScheme.hairline)
                            SignInMethodRow(method, now, onForget = { forget = method })
                        }
                    }
                }
            }
        }
    }
    Text(stringResource(R.string.kept_in_your_encrypted_vault_nuvori_neve),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    forget?.let { method ->
        val who = if (method.account.isEmpty()) "" else " (${method.account})"
        AlertDialog(onDismissRequest = { forget = null },
            title = { Text("Forget ${SignInMarks.name(method.provider)}$who on ${method.site}?") },
            text = { Text(stringResource(R.string.nuvori_stops_reminding_you_about_it_on_a)) },
            confirmButton = {
                Button(onClick = { viewModel.deleteSignInMethod(method); forget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFAD4248), contentColor = Color.White)) { Text(stringResource(R.string.forget)) }
            },
            dismissButton = { TextButton(onClick = { forget = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

/**
 * A login's detail screen: the sign-in methods remembered for its site (same registrable domain). Nothing when there
 * are none, so logins without them look as before.
 */
@Composable
internal fun LoginSignInMethods(viewModel: VaultViewModel, loginUrl: String, modifier: Modifier = Modifier, openAll: (() -> Unit)? = null) {
    val items by viewModel.signInMethods.collectAsStateWithLifecycle()
    val methods = remember(items, loginUrl) { SignInMethodsLogic.forLogin(items, loginUrl) }
    if (methods.isEmpty()) return
    val now = remember(methods) { System.currentTimeMillis() }
    HairlineCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.also_signs_in_with), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { heading() })
            methods.take(4).forEach { SignInMethodRow(it, now, onForget = null, compact = true) }
            if (openAll != null) TextButton(onClick = openAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.all_sign_in_methods)) }
        }
    }
}

/**
 * The account picker's sign-in reminder: "Last time: GitHub · octocat" for this site, above the logins. Information
 * only: it has no action and fills nothing.
 */
@Composable
internal fun SignInReminderNote(method: SignInMethod) {
    val description = com.privatevault.app.autofill.SignInReminders.note(method)
    Surface(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description },
        color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = NuvoriShapes.Card) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ProviderMark(method.provider, size = 28.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(com.privatevault.app.autofill.SignInReminders.title(method), style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.you_may_not_need_a_password_here_use_tha), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
