package com.privatevault.app

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.privatevault.app.appearance.AppearancePalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class AppearanceThemeTest {
    // Copied unchanged from the fixed schemes that nuvoriColorScheme replaced.
    private val oldDark = darkColorScheme(
        primary = Color(0xFF78D47B), onPrimary = Color(0xFF09210B), primaryContainer = Color(0xFF173B1B),
        onPrimaryContainer = Color(0xFFB9F3B9), secondary = Color(0xFFB8CCB5), onSecondary = Color(0xFF243424),
        secondaryContainer = Color(0xFF334832), onSecondaryContainer = Color(0xFFD4E8D0), tertiary = Color(0xFFC6CBB8),
        background = Color(0xFF050705), surface = Color(0xFF050705), surfaceVariant = Color(0xFF192019),
        onBackground = Color(0xFFF0F4ED), onSurface = Color(0xFFF0F4ED), onSurfaceVariant = Color(0xFFBEC9BB),
        outline = Color(0xFF879184), outlineVariant = Color(0xFF2A332A), surfaceContainerLowest = Color(0xFF050705),
        surfaceContainerLow = Color(0xFF0A0D0A), surfaceContainer = Color(0xFF0C100C), surfaceContainerHigh = Color(0xFF131913),
        surfaceContainerHighest = Color(0xFF192019), error = Color(0xFFFFB4AB))
    private val oldLight = lightColorScheme(
        primary = Color(0xFF2E7034), onPrimary = Color.White, primaryContainer = Color(0xFFB9F3B9),
        onPrimaryContainer = Color(0xFF08210A), secondary = Color(0xFF536451), onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6E8D2), onSecondaryContainer = Color(0xFF111F11), tertiary = Color(0xFF5D6253),
        background = Color(0xFFF8FAF5), surface = Color(0xFFF8FAF5), surfaceVariant = Color(0xFFE7EDE4),
        onBackground = Color(0xFF181D17), onSurface = Color(0xFF181D17), onSurfaceVariant = Color(0xFF424940),
        outline = Color(0xFF727970), outlineVariant = Color(0xFFD3DBD0), surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFFFFF), surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFEEF2EB),
        surfaceContainerHighest = Color(0xFFE7EDE4), error = Color(0xFFB3261E))

    private fun assertSame(expected: ColorScheme, actual: ColorScheme) {
        fun fields(s: ColorScheme) = mapOf(
            "primary" to s.primary, "onPrimary" to s.onPrimary, "primaryContainer" to s.primaryContainer,
            "onPrimaryContainer" to s.onPrimaryContainer, "secondary" to s.secondary, "onSecondary" to s.onSecondary,
            "secondaryContainer" to s.secondaryContainer, "onSecondaryContainer" to s.onSecondaryContainer,
            "tertiary" to s.tertiary, "background" to s.background, "surface" to s.surface,
            "surfaceVariant" to s.surfaceVariant, "onBackground" to s.onBackground, "onSurface" to s.onSurface,
            "onSurfaceVariant" to s.onSurfaceVariant, "outline" to s.outline, "outlineVariant" to s.outlineVariant,
            "surfaceContainerLowest" to s.surfaceContainerLowest, "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainer" to s.surfaceContainer, "surfaceContainerHigh" to s.surfaceContainerHigh,
            "surfaceContainerHighest" to s.surfaceContainerHighest, "error" to s.error)
        val want = fields(expected)
        fields(actual).forEach { (name, color) -> assertEquals(name, want.getValue(name), color) }
    }

    @Test fun defaultDarkSchemeKeepsTodaysColors() = assertSame(oldDark, nuvoriColorScheme("green", "sage", true))

    @Test fun defaultLightSchemeKeepsTodaysColors() = assertSame(oldLight, nuvoriColorScheme("green", "sage", false))

    @Test fun vaultSchemesAreTheDefaultSchemes() {
        assertSame(oldDark, VaultColors)
        assertSame(oldLight, VaultLightColors)
    }

    @Test fun everyPairMapsTonesAndUnknownIdsUseTheDefault() {
        for (a in AppearancePalette.accents) for (s in AppearancePalette.surfaces) for (dark in listOf(true, false)) {
            val scheme = nuvoriColorScheme(a.id, s.id, dark)
            val tones = if (dark) a.dark else a.light
            val surface = if (dark) s.dark else s.light
            assertEquals(Color(tones.primary), scheme.primary)
            assertEquals(Color(tones.onPrimaryContainer), scheme.onPrimaryContainer)
            assertEquals(Color(surface.background), scheme.surface)
            assertEquals(Color(surface.containerHighest), scheme.surfaceVariant)
            assertEquals(Color(if (dark) surface.background else surface.containerLow), scheme.surfaceContainerLowest)
        }
        assertSame(nuvoriColorScheme("green", "sage", true), nuvoriColorScheme("nope", "nope", true))
        assertNotEquals(nuvoriColorScheme("green", "sage", true).primary, nuvoriColorScheme(AppearancePalette.accents[3].id, "sage", true).primary)
    }
}
