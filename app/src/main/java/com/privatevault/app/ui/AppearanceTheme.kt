package com.privatevault.app

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.privatevault.app.appearance.AppearancePalette
import com.privatevault.app.appearance.AppearanceStore

/** The one place that turns an accent id and a background id into a Material color scheme. Unknown ids use the default. */
internal fun nuvoriColorScheme(accentId: String, surfaceId: String, dark: Boolean): ColorScheme {
    val accent = AppearancePalette.accent(accentId).let { if (dark) it.dark else it.light }
    val surface = AppearancePalette.surface(surfaceId).let { if (dark) it.dark else it.light }
    val bg = Color(surface.background)
    val text = Color(surface.text)
    val lowest = if (dark) bg else Color(surface.containerLow)
    return if (dark) darkColorScheme(
        primary = Color(accent.primary), onPrimary = Color(accent.onPrimary),
        primaryContainer = Color(accent.primaryContainer), onPrimaryContainer = Color(accent.onPrimaryContainer),
        secondary = Color(surface.secondary), onSecondary = Color(surface.onSecondary),
        secondaryContainer = Color(surface.secondaryContainer), onSecondaryContainer = Color(surface.onSecondaryContainer),
        tertiary = Color(surface.tertiary),
        background = bg, surface = bg, surfaceVariant = Color(surface.containerHighest),
        onBackground = text, onSurface = text, onSurfaceVariant = Color(surface.muted),
        outline = Color(surface.outline), outlineVariant = Color(surface.hairline),
        surfaceContainerLowest = lowest, surfaceContainerLow = Color(surface.containerLow),
        surfaceContainer = Color(surface.container), surfaceContainerHigh = Color(surface.containerHigh),
        surfaceContainerHighest = Color(surface.containerHighest),
        error = Color(0xFFFFB4AB)
    ) else lightColorScheme(
        primary = Color(accent.primary), onPrimary = Color(accent.onPrimary),
        primaryContainer = Color(accent.primaryContainer), onPrimaryContainer = Color(accent.onPrimaryContainer),
        secondary = Color(surface.secondary), onSecondary = Color(surface.onSecondary),
        secondaryContainer = Color(surface.secondaryContainer), onSecondaryContainer = Color(surface.onSecondaryContainer),
        tertiary = Color(surface.tertiary),
        background = bg, surface = bg, surfaceVariant = Color(surface.containerHighest),
        onBackground = text, onSurface = text, onSurfaceVariant = Color(surface.muted),
        outline = Color(surface.outline), outlineVariant = Color(surface.hairline),
        surfaceContainerLowest = lowest, surfaceContainerLow = Color(surface.containerLow),
        surfaceContainer = Color(surface.container), surfaceContainerHigh = Color(surface.containerHigh),
        surfaceContainerHighest = Color(surface.containerHighest),
        error = Color(0xFFB3261E)
    )
}

internal val VaultColors = nuvoriColorScheme(AppearancePalette.DEFAULT_ACCENT, AppearancePalette.DEFAULT_SURFACE, dark = true)
internal val VaultLightColors = nuvoriColorScheme(AppearancePalette.DEFAULT_ACCENT, AppearancePalette.DEFAULT_SURFACE, dark = false)

/** Material theme in the accent and background the user chose. The store is readable before unlock. */
@Composable
internal fun NuvoriTheme(dark: Boolean, content: @Composable () -> Unit) {
    val context: Context = LocalContext.current
    val stored by AppearanceStore.flow(context).collectAsState()
    val scheme = androidx.compose.runtime.remember(stored.appearance, dark) {
        nuvoriColorScheme(stored.appearance.accent, stored.appearance.surface, dark)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
