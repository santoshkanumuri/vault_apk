package com.privatevault.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircleOutline
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Meaning of a status message. Colors and icons follow the meaning, never the screen. */
enum class StatusKind { SUCCESS, INFO, PROGRESS, WARNING, ERROR, NEUTRAL }

/** A one-off confirmation or problem report. The id keeps repeated identical events distinct. */
data class UserNotice(val text: String, val kind: StatusKind = StatusKind.INFO, val id: Long = nextNoticeId()) {
    private companion object {
        private var counter = 0L
        @Synchronized fun nextNoticeId(): Long = ++counter
    }
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

internal val StatusKind.icon: ImageVector
    get() = when (this) {
        StatusKind.SUCCESS -> Icons.Outlined.CheckCircleOutline
        StatusKind.INFO, StatusKind.NEUTRAL -> Icons.Outlined.Info
        StatusKind.PROGRESS -> Icons.Outlined.Sync
        StatusKind.WARNING -> Icons.Outlined.WarningAmber
        StatusKind.ERROR -> Icons.Outlined.ErrorOutline
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
        color = colors.container, contentColor = colors.content, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(kind.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
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
    Surface(modifier, color = colors.container, contentColor = colors.content, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

/** Snackbar colored by the notice's meaning, with a matching icon. */
@Composable
internal fun NoticeSnackbar(data: SnackbarData, kind: StatusKind) {
    val colors = statusColors(kind)
    Snackbar(
        modifier = Modifier.padding(12.dp),
        containerColor = colors.container,
        contentColor = colors.content,
        shape = RoundedCornerShape(14.dp),
        dismissAction = if (data.visuals.withDismissAction) {
            { TextButton(onClick = data::dismiss) { Text("Dismiss", color = colors.accent) } }
        } else null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(kind.icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Text(data.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
