package com.privatevault.app

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Meaning of a status message. Colors and icons follow the meaning, never the screen. */
enum class StatusKind { SUCCESS, INFO, PROGRESS, WARNING, ERROR, NEUTRAL }

/**
 * A one-off confirmation or problem report. The id keeps repeated identical events distinct.
 * An optional action (for example Undo) is offered on the snackbar; it must be cheap and safe to run later.
 */
data class UserNotice(
    val text: String,
    val kind: StatusKind = StatusKind.INFO,
    val id: Long = nextNoticeId(),
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
) {
    private companion object {
        private var counter = 0L
        @Synchronized fun nextNoticeId(): Long = ++counter
    }
}

/** Snackbar visuals that carry the notice meaning, so a queued snackbar always keeps its own color. */
internal class NoticeVisuals(
    override val message: String,
    val kind: StatusKind,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
) : SnackbarVisuals

/** How long a notice stays: errors until dismissed, warnings and long text longer, confirmations briefly. */
internal fun UserNotice.visuals(): NoticeVisuals {
    val persistent = kind == StatusKind.ERROR || kind == StatusKind.WARNING || text.length > 90
    return NoticeVisuals(
        message = text, kind = kind, actionLabel = actionLabel,
        withDismissAction = persistent && actionLabel == null,
        duration = when {
            kind == StatusKind.ERROR -> SnackbarDuration.Indefinite
            persistent || actionLabel != null -> SnackbarDuration.Long
            else -> SnackbarDuration.Short
        })
}

internal data class StatusColors(val container: Color, val content: Color, val accent: Color)

@Composable
internal fun statusColors(kind: StatusKind): StatusColors {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > .5f
    return when (kind) {
        StatusKind.SUCCESS -> if (light) StatusColors(Color(0xFFD7F5DB), Color(0xFF0B3D16), Color(0xFF1E7A33))
            else StatusColors(Color(0xFF12321A), Color(0xFFC8F5CF), Color(0xFF7DDB86))
        StatusKind.INFO, StatusKind.PROGRESS -> if (light) StatusColors(Color(0xFFDCEBFF), Color(0xFF0F2F4F), Color(0xFF225F99))
            else StatusColors(Color(0xFF152D43), Color(0xFFDAEDFF), Color(0xFF9DCEFF))
        StatusKind.WARNING -> if (light) StatusColors(Color(0xFFFFF0CC), Color(0xFF4A3000), Color(0xFF8A5300))
            else StatusColors(Color(0xFF3A2A00), Color(0xFFFFE3A3), Color(0xFFFFC94D))
        StatusKind.ERROR -> if (light) StatusColors(Color(0xFFFFDAD6), Color(0xFF410002), Color(0xFFB3261E))
            else StatusColors(Color(0xFF410E0B), Color(0xFFFFDAD6), Color(0xFFFFB4AB))
        StatusKind.NEUTRAL -> StatusColors(scheme.surfaceVariant, scheme.onSurfaceVariant, scheme.outline)
    }
}

/** Lucide status glyphs, shared with Windows. */
internal val StatusKind.icon: ImageVector
    get() = when (this) {
        StatusKind.SUCCESS -> NuvoriIcons.Success
        StatusKind.INFO, StatusKind.NEUTRAL -> NuvoriIcons.Info
        StatusKind.PROGRESS -> NuvoriIcons.Progress
        StatusKind.WARNING -> NuvoriIcons.Warning
        StatusKind.ERROR -> NuvoriIcons.Error
    }

/** The status icon. Progress spins unless animations are off. Decorative: text always accompanies it. */
@Composable
internal fun StatusIcon(kind: StatusKind, tint: Color, modifier: Modifier = Modifier, size: Dp = 20.dp) {
    if (kind == StatusKind.PROGRESS && !rememberReduceMotion()) {
        val rotation = rememberInfiniteTransition(label = "progress")
            .animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "spin")
        Icon(kind.icon, contentDescription = null, tint = tint,
            modifier = modifier.size(size).graphicsLayer { rotationZ = rotation.value })
    } else Icon(kind.icon, contentDescription = null, tint = tint, modifier = modifier.size(size))
}

/** Inline status for a screen section. Announced politely to accessibility services. */
@Composable
internal fun StatusBanner(
    kind: StatusKind,
    message: String,
    modifier: Modifier = Modifier,
    title: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = statusColors(kind)
    Surface(modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        color = colors.container, contentColor = colors.content, shape = NuvoriShapes.Card) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatusIcon(kind, colors.accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                title?.let { Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
                Text(message, style = MaterialTheme.typography.bodySmall)
                if (actionLabel != null && onAction != null) TextButton(onClick = onAction) {
                    Text(actionLabel, color = colors.accent)
                }
            }
        }
    }
}

/** Compact status label for list rows and cards. */
@Composable
internal fun StatusChip(kind: StatusKind, label: String, modifier: Modifier = Modifier) {
    val colors = statusColors(kind)
    Surface(modifier, color = colors.container, contentColor = colors.content, shape = NuvoriShapes.Chip) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(kind, colors.accent, size = 14.dp)
            Text(label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** Snackbar colored by the notice's meaning, with a matching icon and an optional action such as Undo. */
@Composable
internal fun NoticeSnackbar(data: SnackbarData, kind: StatusKind = data.noticeKind) {
    val colors = statusColors(kind)
    Snackbar(
        modifier = Modifier.padding(12.dp),
        containerColor = colors.container,
        contentColor = colors.content,
        shape = NuvoriShapes.Card,
        action = data.visuals.actionLabel?.let { label ->
            { TextButton(onClick = data::performAction) { Text(label, color = colors.accent, fontWeight = FontWeight.SemiBold) } }
        },
        dismissAction = if (data.visuals.withDismissAction) {
            { TextButton(onClick = data::dismiss) { Text("Dismiss", color = colors.accent) } }
        } else null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StatusIcon(kind, colors.accent)
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Meaning of a snackbar shown through [NoticeVisuals]; plain visuals count as information. */
internal val SnackbarData.noticeKind: StatusKind get() = (visuals as? NoticeVisuals)?.kind ?: StatusKind.INFO
