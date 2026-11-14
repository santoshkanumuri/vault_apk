package com.privatevault.app.appearance

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The generated Kotlin palette must equal the shared JSON (the Windows app and extension read the same file), and
 * every accent x background x mode must stay readable. Thresholds match scripts/appearance/check.py.
 */
class AppearancePaletteTest {
    private val json: JsonObject = JsonParser.parseString(
        requireNotNull(javaClass.getResource("/appearance/nuvori-appearance-v1.json")).readText()).asJsonObject

    private fun hex(color: Long) = "#%06x".format(color and 0xFFFFFF)

    @Test fun generatedPaletteMatchesTheSharedFile() {
        assertEquals(json["version"].asInt, AppearancePalette.VERSION)
        assertEquals(json["defaults"].asJsonObject["accent"].asString, AppearancePalette.DEFAULT_ACCENT)
        assertEquals(json["defaults"].asJsonObject["surface"].asString, AppearancePalette.DEFAULT_SURFACE)
        val accents = json["accents"].asJsonArray.map { it.asJsonObject }
        assertEquals(accents.map { it["id"].asString }, AppearancePalette.accents.map { it.id })
        accents.zip(AppearancePalette.accents).forEach { (source, accent) ->
            assertEquals(source["name"].asString, accent.name)
            assertEquals(source["swatch"].asString, hex(accent.swatch))
            for ((mode, tones) in listOf("dark" to accent.dark, "light" to accent.light)) {
                val s = source[mode].asJsonObject
                assertEquals(listOf("primary", "onPrimary", "primaryContainer", "onPrimaryContainer").map { s[it].asString },
                    listOf(tones.primary, tones.onPrimary, tones.primaryContainer, tones.onPrimaryContainer).map(::hex))
            }
        }
        val surfaces = json["surfaces"].asJsonArray.map { it.asJsonObject }
        assertEquals(surfaces.map { it["id"].asString }, AppearancePalette.surfaces.map { it.id })
        surfaces.zip(AppearancePalette.surfaces).forEach { (source, surface) ->
            assertEquals(source["name"].asString, surface.name)
            for ((mode, t) in listOf("dark" to surface.dark, "light" to surface.light)) {
                val s = source[mode].asJsonObject
                val keys = listOf("background", "containerLow", "container", "containerHigh", "containerHighest", "hover", "text",
                    "muted", "subtle", "outline", "hairline", "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer", "tertiary")
                val values = listOf(t.background, t.containerLow, t.container, t.containerHigh, t.containerHighest, t.hover, t.text,
                    t.muted, t.subtle, t.outline, t.hairline, t.secondary, t.onSecondary, t.secondaryContainer, t.onSecondaryContainer, t.tertiary)
                assertEquals(keys.map { s[it].asString }, values.map(::hex))
            }
        }
    }

    @Test fun defaultsAreTodaysGreenTheme() {
        val green = AppearancePalette.accent(null)
        assertEquals("green", green.id)
        assertEquals(0xFF78D47B, green.dark.primary)
        assertEquals(0xFF2E7034, green.light.primary)
        assertEquals(0xFF050705, AppearancePalette.surface(null).dark.background)
        assertEquals(0xFFF8FAF5, AppearancePalette.surface(null).light.background)
    }

    @Test fun unknownIdsFallBackToTheDefaults() {
        assertEquals("green", AppearancePalette.accent("ultraviolet-from-a-newer-build").id)
        assertEquals("sage", AppearancePalette.surface("").id)
    }

    @Test fun everyCombinationIsReadable() {
        val failures = mutableListOf<String>()
        fun need(label: String, a: Long, b: Long, minimum: Double) {
            val ratio = contrast(a, b)
            if (ratio < minimum) failures += "$label %.2f < $minimum (${hex(a)} on ${hex(b)})".format(ratio)
        }
        for (surface in AppearancePalette.surfaces) for (dark in listOf(true, false)) {
            val t = if (dark) surface.dark else surface.light
            val where = "${surface.id}/${if (dark) "dark" else "light"}"
            for (bg in listOf(t.background, t.container, t.containerHighest)) {
                need("$where text", t.text, bg, 7.0); need("$where muted", t.muted, bg, 4.5)
            }
            for (bg in listOf(t.background, t.container)) {
                need("$where subtle", t.subtle, bg, 4.5); need("$where outline", t.outline, bg, 3.0)
                need("$where secondary", t.secondary, bg, 4.5)
            }
            need("$where onSecondary", t.onSecondary, t.secondary, 4.5)
            need("$where onSecondaryContainer", t.onSecondaryContainer, t.secondaryContainer, 7.0)
            for (accent in AppearancePalette.accents) {
                val a = if (dark) accent.dark else accent.light
                val pair = "$where+${accent.id}"
                for (bg in listOf(t.background, t.container, t.containerHigh)) need("$pair primary", a.primary, bg, 4.5)
                for (bg in listOf(t.containerHighest, t.hover)) need("$pair primary on raised", a.primary, bg, 3.0)
                need("$pair onPrimary", a.onPrimary, a.primary, 4.5)
                need("$pair onPrimaryContainer", a.onPrimaryContainer, a.primaryContainer, 7.0)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun luminance(color: Long): Double {
        fun channel(shift: Int): Double {
            val c = ((color shr shift) and 0xFF) / 255.0
            return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
    }

    private fun contrast(a: Long, b: Long): Double {
        val x = luminance(a); val y = luminance(b)
        return (max(x, y) + 0.05) / (min(x, y) + 0.05)
    }
}
