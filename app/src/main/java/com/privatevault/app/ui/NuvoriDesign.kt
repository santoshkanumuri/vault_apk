package com.privatevault.app

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.LocalIndication
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatevault.app.data.EntryType
import kotlin.math.abs

// Design tokens shared with the Windows app (see SPEC.md): 8dp controls and chips, 12dp cards,
// 16dp sheets, 1dp hairlines and flat surfaces.
internal object NuvoriShapes {
    val Control = RoundedCornerShape(8.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Card = RoundedCornerShape(12.dp)
    val Sheet = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
    val Dialog = RoundedCornerShape(16.dp)
    val CardRadius = 12.dp
}

internal val ColorScheme.isLight: Boolean get() = background.luminance() > .5f

/** Divider and card border color ("hairline" on Windows). */
internal val ColorScheme.hairline: Color get() = outlineVariant

/** Raised flat surface for cards and grouped lists ("surface" on Windows). */
internal val ColorScheme.raised: Color get() = surfaceContainer

internal data class Tint(val container: Color, val content: Color)

private val darkTints = listOf(
    Tint(Color(0xFF173B1B), Color(0xFFB9F3B9)), Tint(Color(0xFF152D43), Color(0xFFBFE0FF)),
    Tint(Color(0xFF3A2A00), Color(0xFFFFDF99)), Tint(Color(0xFF2E2440), Color(0xFFDCCBFF)),
    Tint(Color(0xFF3B1E2C), Color(0xFFFFC4DD)), Tint(Color(0xFF13332F), Color(0xFFA8EEE0)),
)
private val lightTints = listOf(
    Tint(Color(0xFFD7F5DB), Color(0xFF0B3D16)), Tint(Color(0xFFDCEBFF), Color(0xFF0F2F4F)),
    Tint(Color(0xFFFFF0CC), Color(0xFF4A3000)), Tint(Color(0xFFECE3FF), Color(0xFF2C1A55)),
    Tint(Color(0xFFFFE1EC), Color(0xFF4F1530)), Tint(Color(0xFFD3F3EC), Color(0xFF0D3A32)),
)

@Composable
internal fun tint(index: Int): Tint {
    val list = if (MaterialTheme.colorScheme.isLight) lightTints else darkTints
    return list[((index % list.size) + list.size) % list.size]
}

/** Same index as the Windows `hashIndex(title.toLocaleLowerCase(), 6)`, so an item looks the same on both apps. */
internal fun avatarTintIndex(title: String): Int {
    var hash = 0
    title.lowercase().forEach { hash = hash * 31 + it.code }
    return (abs(hash.toLong()) % 6).toInt()
}

/** First letter of the first word, ignoring a leading scheme (matches Windows `monogram`). */
internal fun monogram(title: String): String {
    val words = title.trim().replace(Regex("^https?://", RegexOption.IGNORE_CASE), "")
        .split(Regex("[\\s._@-]+")).filter { it.isNotEmpty() }
    val first = words.firstOrNull() ?: return "?"
    return String(Character.toChars(first.codePointAt(0))).uppercase()
}

/** Category colors, same mapping as the Windows `cat-*` classes. */
internal val EntryType.tintIndex: Int get() = when (this) {
    EntryType.PASSWORD -> 1
    EntryType.CARD -> 0
    EntryType.AUTHENTICATOR -> 5
    EntryType.QUESTION -> 2
    EntryType.AUTOFILL -> 4
    EntryType.NOTE -> 3
}

internal val EntryType.icon: ImageVector get() = when (this) {
    EntryType.PASSWORD -> NuvoriIcons.Password
    EntryType.CARD -> NuvoriIcons.Card
    EntryType.AUTHENTICATOR -> NuvoriIcons.Code
    EntryType.QUESTION -> NuvoriIcons.Question
    EntryType.AUTOFILL -> NuvoriIcons.Autofill
    EntryType.NOTE -> NuvoriIcons.Note
}

/** Plural section name used on tiles and in the navigation. */
internal val EntryType.sectionLabel: String get() = when (this) {
    EntryType.PASSWORD -> "Passwords"
    EntryType.CARD -> "Cards"
    EntryType.AUTHENTICATOR -> "Codes"
    EntryType.QUESTION -> "Questions"
    EntryType.AUTOFILL -> "Autofill"
    EntryType.NOTE -> "Notes"
}

/** Singular, capitalized name for an item of this type. */
internal val EntryType.itemLabel: String get() = when (this) {
    EntryType.PASSWORD -> "Password"
    EntryType.CARD -> "Card"
    EntryType.AUTHENTICATOR -> "Code"
    EntryType.QUESTION -> "Security question"
    EntryType.AUTOFILL -> "Autofill details"
    EntryType.NOTE -> "Note"
}

/** Letter avatar: a tinted rounded square with the item's initial, or an icon. Decorative. */
@Composable
internal fun NuvoriAvatar(title: String, modifier: Modifier = Modifier, size: Dp = 36.dp,
    icon: ImageVector? = null, tintOverride: Tint? = null) {
    val colors = tintOverride ?: tint(avatarTintIndex(title))
    Box(modifier.size(size).clip(RoundedCornerShape(size / 4)).background(colors.container)
        .clearAndSetSemantics { }, contentAlignment = Alignment.Center) {
        if (icon != null) Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(size / 2))
        else Text(monogram(title), color = colors.content, fontWeight = FontWeight.SemiBold,
            fontSize = (size.value * .42f).sp, maxLines = 1)
    }
}

/** A tinted icon square for a category or action. */
@Composable
internal fun TintedIcon(icon: ImageVector, tint: Tint, modifier: Modifier = Modifier, size: Dp = 32.dp) {
    Box(modifier.size(size).clip(RoundedCornerShape(8.dp)).background(tint.container), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = tint.content, modifier = Modifier.size(size * .56f))
    }
}

/** Flat card with a hairline border. */
@Composable
internal fun HairlineCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = NuvoriShapes.Card, color = MaterialTheme.colorScheme.raised,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.hairline), content = content)
}

/** Section title with an optional trailing text action. */
@Composable
internal fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold)
        if (action != null && onAction != null) TextButton(onClick = onAction) { Text(action) }
    }
}

// Motion

/** True when the system "Remove animations" setting is on. Compose already scales durations; this drops movement. */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
}

internal object NuvoriMotion {
    const val SCREEN_MS = 210
    const val FADE_OUT_MS = 120
    const val PRESS_MS = 100
    val EaseOut = CubicBezierEasing(.2f, 0f, 0f, 1f)
    val SlideDistance = 28.dp

    fun <T> screen(reduce: Boolean, delay: Int = 0): FiniteAnimationSpec<T> =
        if (reduce) snap() else tween(SCREEN_MS, delay, EaseOut)

    /** Forward = slide in from the end and fade; back = the reverse. */
    fun enter(forward: Boolean, distancePx: Int, reduce: Boolean): EnterTransition =
        if (reduce) fadeIn(snap()) else
            slideInHorizontally(tween(SCREEN_MS, easing = EaseOut)) { if (forward) distancePx else -distancePx } +
                fadeIn(tween(SCREEN_MS, easing = EaseOut))

    fun exit(forward: Boolean, distancePx: Int, reduce: Boolean): ExitTransition =
        if (reduce) fadeOut(snap()) else
            slideOutHorizontally(tween(SCREEN_MS, easing = EaseOut)) { if (forward) -distancePx else distancePx } +
                fadeOut(tween(FADE_OUT_MS, easing = LinearEasing))

    fun <S> sharedAxis(scope: AnimatedContentTransitionScope<S>, forward: Boolean, distancePx: Int, reduce: Boolean): ContentTransform =
        with(scope) { enter(forward, distancePx, reduce) togetherWith exit(forward, distancePx, reduce) }

    /** Bottom navigation switches use fade-through: no direction between peers. */
    fun <S> fadeThrough(scope: AnimatedContentTransitionScope<S>, reduce: Boolean): ContentTransform = with(scope) {
        if (reduce) fadeIn(snap()) togetherWith fadeOut(snap())
        else fadeIn(tween(SCREEN_MS, delayMillis = 60, easing = EaseOut)) togetherWith fadeOut(tween(90, easing = LinearEasing))
    }
}

@Composable
internal fun slideDistancePx(): Int = with(LocalDensity.current) { NuvoriMotion.SlideDistance.roundToPx() }

/** Scales a tile slightly while pressed (0.97, 100ms). Reads the animation in the draw phase only. */
internal fun Modifier.pressScale(interaction: MutableInteractionSource, pressedScale: Float = .97f): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val scale = animateFloatAsState(if (pressed && !reduce) pressedScale else 1f, tween(NuvoriMotion.PRESS_MS), label = "press")
    graphicsLayer { scaleX = scale.value; scaleY = scale.value }
}

/** Click and optional long-press with ripple and press feedback. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
internal fun Modifier.tappable(onClickLabel: String? = null, role: Role? = Role.Button, onLongClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null, pressedScale: Float = .97f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    this.pressScale(interaction, pressedScale)
        .combinedClickable(interactionSource = interaction, indication = LocalIndication.current, role = role,
            onClickLabel = onClickLabel, onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick)
}

// Grouped lists: rows that sit inside one 12dp card with hairline dividers, like the Windows lists.

internal fun segmentShape(index: Int, count: Int, radius: Dp = NuvoriShapes.CardRadius): Shape {
    val top = if (index == 0) radius else 0.dp
    val bottom = if (index == count - 1) radius else 0.dp
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** Draws one segment of a grouped card: background, outer hairline border and a divider above non-first rows. */
internal fun Modifier.groupedSegment(index: Int, count: Int, container: Color, hairline: Color,
    radius: Dp = NuvoriShapes.CardRadius, dividerInset: Dp = 0.dp): Modifier = drawWithCache {
    val r = radius.toPx()
    val stroke = 1.dp.toPx()
    val first = index == 0
    val last = index == count - 1
    // Extend past the clipped edges so only the borders that belong to this segment are visible.
    val top = if (first) 0f else -2 * r
    val bottom = if (last) size.height else size.height + 2 * r
    val fill = Path().apply { addRoundRect(RoundRect(0f, top, size.width, bottom, CornerRadius(r))) }
    val outline = Path().apply {
        addRoundRect(RoundRect(stroke / 2, top + stroke / 2, size.width - stroke / 2, bottom - stroke / 2, CornerRadius(r)))
    }
    val inset = dividerInset.toPx()
    onDrawBehind {
        clipRect {
            drawPath(fill, container)
            drawPath(outline, hairline, style = Stroke(stroke))
            if (!first) drawLine(hairline, Offset(inset, stroke / 2), Offset(size.width, stroke / 2), stroke)
        }
    }
}

/** Countdown ring for authenticator codes. `fraction` is the share of the period left. */
@Composable
internal fun CountdownRing(fraction: Float, seconds: Long, color: Color, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val track = MaterialTheme.colorScheme.hairline
    val reduce = rememberReduceMotion()
    // Smooth between the 250ms ticks, but never animate backwards across a period boundary.
    val animated by animateFloatAsState(fraction, if (reduce) snap() else tween(250, easing = LinearEasing), label = "ring")
    val shown = if (fraction > animated + .5f) fraction else animated
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val width = 2.5.dp.toPx()
            val inset = width / 2
            val arcSize = Size(this.size.width - width, this.size.height - width)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(width))
            drawArc(color, -90f, 360f * shown.coerceIn(0f, 1f), false, Offset(inset, inset), arcSize,
                style = Stroke(width, cap = StrokeCap.Round))
        }
        Text(seconds.toString(), fontSize = (size.value * .36f).sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(top = 1.dp))
    }
}

/** Groups an authenticator code for reading: 123 456, 1234 5678. */
internal fun formatTotp(code: String): String = when (code.length) {
    6 -> code.substring(0, 3) + " " + code.substring(3)
    7 -> code.substring(0, 3) + " " + code.substring(3)
    8 -> code.substring(0, 4) + " " + code.substring(4)
    else -> code
}

/** Time-of-day greeting, same thresholds as Windows. */
internal fun greeting(hour: Int): String = when {
    hour < 5 -> "Good evening"
    hour < 12 -> "Good morning"
    hour < 18 -> "Good afternoon"
    else -> "Good evening"
}

internal fun relativeTimeShort(then: Long, now: Long): String {
    val seconds = ((now - then) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} h ago"
        else -> "${seconds / 86_400} d ago"
    }
}
