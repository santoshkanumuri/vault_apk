package com.privatevault.app.security

import kotlin.math.ceil
import kotlin.math.floor

data class PhotoCrop(val left: Float = 0f, val top: Float = 0f, val right: Float = 1f, val bottom: Float = 1f) {
    init {
        require(left >= 0f && top >= 0f && right <= 1f && bottom <= 1f && left < right && top < bottom)
    }

    fun pixels(width: Int, height: Int): IntArray {
        require(width > 0 && height > 0)
        val x = floor(left * width).toInt().coerceIn(0, width - 1)
        val y = floor(top * height).toInt().coerceIn(0, height - 1)
        val endX = ceil(right * width).toInt().coerceIn(x + 1, width)
        val endY = ceil(bottom * height).toInt().coerceIn(y + 1, height)
        return intArrayOf(x, y, endX - x, endY - y)
    }
}
