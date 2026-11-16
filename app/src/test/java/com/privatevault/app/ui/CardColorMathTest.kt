package com.privatevault.app

import com.privatevault.app.CardColorMath.argbToHsv
import com.privatevault.app.CardColorMath.hsvToArgb
import com.privatevault.app.CardColorMath.parseHex
import com.privatevault.app.CardColorMath.toHex
import com.privatevault.app.CardColorMath.usedColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardColorMathTest {
    @Test fun hexParsesFullShortAndBareForms() {
        assertEquals(0xFF2E7034L, parseHex("#2E7034"))
        assertEquals(0xFF2E7034L, parseHex("2e7034"))
        assertEquals(0xFFAABBCCL, parseHex("#abc"))
        assertEquals(0xFF000000L, parseHex(" #000000 "))
    }

    @Test fun badHexIsRejected() {
        for (text in listOf("", "#", "#12345", "#1234567", "#GGGGGG", "12 345", "#12-456")) assertNull(text, parseHex(text))
    }

    @Test fun hexIsAlwaysSixUppercaseDigits() {
        assertEquals("#08704A", toHex(0xFF08704AL))
        assertEquals("#000000", toHex(0xFF000000L))
        assertEquals("#FFFFFF", toHex(0xFFFFFFFFL))
    }

    @Test fun primariesConvertExactly() {
        assertEquals(0xFFFF0000L, hsvToArgb(0f, 1f, 1f))
        assertEquals(0xFF00FF00L, hsvToArgb(120f, 1f, 1f))
        assertEquals(0xFF0000FFL, hsvToArgb(240f, 1f, 1f))
        assertEquals(0xFFFF0000L, hsvToArgb(360f, 1f, 1f))
        assertEquals(0xFFFFFFFFL, hsvToArgb(200f, 0f, 1f))
        assertEquals(0xFF000000L, hsvToArgb(200f, 1f, 0f))
    }

    @Test fun everyStoredColorSurvivesARoundTrip() {
        // The old 34 presets plus edge values: picking a color from its own HSV changes nothing.
        val samples = cardColors.map { it.value } + listOf(0xFF010203L, 0xFFFEFDFCL, 0xFF7F7F7FL, 0xFF08704AL)
        for (color in samples) {
            val (h, s, v) = argbToHsv(color).let { Triple(it[0], it[1], it[2]) }
            assertEquals(toHex(color), toHex(hsvToArgb(h, s, v)))
        }
    }

    @Test fun graysHaveNoSaturation() {
        val hsv = argbToHsv(0xFF808080L)
        assertEquals(0f, hsv[1], 0.0001f)
        assertEquals(128 / 255f, hsv[2], 0.0001f)
    }

    @Test fun usedColorsListsMostUsedFirstWithoutDuplicates() {
        val cards = listOf(0xFF315B8CL, 0xFF08704AL, 0xFF315B8CL, 0xFFF5EBDDL, 0xFF315B8CL, 0xFF08704AL)
        assertEquals(listOf(0xFF315B8CL, 0xFF08704AL, 0xFFF5EBDDL), usedColors(cards))
        assertEquals(2, usedColors(cards, limit = 2).size)
    }
}
