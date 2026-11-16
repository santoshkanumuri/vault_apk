package com.privatevault.app

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Card colors are stored and synced as an opaque ARGB number (0xFFRRGGBB), the same field every
 * Nuvori version reads, so any color picked here shows on other devices without a format change.
 * Pure Kotlin, so the picker's math is testable without Android.
 */
internal object CardColorMath {
    private const val OPAQUE = 0xFF000000L

    /** "#1A2B3C", "1a2b3c" or the short "#ABC". Null for anything else. */
    fun parseHex(text: String): Long? {
        val digits = text.trim().removePrefix("#")
        val full = when (digits.length) {
            3 -> digits.map { "$it$it" }.joinToString("")
            6 -> digits
            else -> return null
        }
        if (!full.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return OPAQUE or full.toLong(16)
    }

    fun toHex(color: Long): String = "#%06X".format(color and 0xFFFFFF)

    /** Hue in degrees (0..360), saturation and value in 0..1, to an opaque ARGB color. */
    fun hsvToArgb(hue: Float, saturation: Float, value: Float): Long {
        val h = ((hue % 360f) + 360f) % 360f / 60f
        val s = saturation.coerceIn(0f, 1f)
        val v = value.coerceIn(0f, 1f)
        val c = v * s
        val x = c * (1 - abs(h % 2 - 1))
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun channel(part: Float) = ((part + m) * 255f).roundToInt().coerceIn(0, 255).toLong()
        return OPAQUE or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
    }

    /** [hue 0..360, saturation 0..1, value 0..1]. A gray has hue 0. */
    fun argbToHsv(color: Long): FloatArray {
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val delta = max - minOf(r, g, b)
        val hue = when {
            delta == 0f -> 0f
            max == r -> 60f * (((g - b) / delta) % 6f)
            max == g -> 60f * ((b - r) / delta + 2f)
            else -> 60f * ((r - g) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return floatArrayOf(hue, if (max == 0f) 0f else delta / max, max)
    }

    /** Colors on the user's other cards, most used first, for one-tap reuse. */
    fun usedColors(cardColors: List<Long>, limit: Int = 10): List<Long> =
        cardColors.map { OPAQUE or (it and 0xFFFFFF) }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key })
            .map { it.key }.take(limit)
}
