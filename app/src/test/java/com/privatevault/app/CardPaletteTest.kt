package com.privatevault.app

import org.junit.Assert.*
import org.junit.Test

class CardPaletteTest {
    @Test fun uniqueColorsIncludingWhiteAndDarkNeutrals() {
        assertEquals(34, cardColors.size)
        assertEquals(34, cardColors.map { it.value }.distinct().size)
        assertTrue(cardColors.any { it.value == 0xFFFFFFFFL })
        assertTrue(cardColors.any { it.value == 0xFF000000L })
        assertTrue(cardColors.any { it.value == 0xFF808080L })
    }

    @Test fun unusedFirstAndUsedLeastOftenFirst() {
        val first = cardColors[0]
        val second = cardColors[1]
        val sorted = colorsByUsage(mapOf(first.value to 3, second.value to 1))
        assertEquals(cardColors.drop(2), sorted.dropLast(2))
        assertEquals(listOf(second, first), sorted.takeLast(2))
    }

    @Test fun allUsedStillSortsByCountAndKeepsTiesStable() {
        val counts = cardColors.associate { it.value to 1 }.toMutableMap()
        counts[cardColors[0].value] = 4
        assertEquals(cardColors.drop(1) + cardColors[0], colorsByUsage(counts))
    }
}
