package com.privatevault.app.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Same cases as src/lib/generator.test.mjs in Nuvori for Windows. */
class PasswordGeneratorTest {
    private val wordFile = listOf(File("src/main/assets/eff_large_wordlist.txt"), File("app/src/main/assets/eff_large_wordlist.txt")).first { it.isFile }
    private val words = wordFile.readLines().filter(String::isNotBlank)

    @Test fun theWordListIsTheEffLargeListTheSameFileAsOnWindows() {
        val hash = MessageDigest.getInstance("SHA-256").digest(wordFile.readBytes()).joinToString("") { "%02x".format(it) }
        assertEquals("6d557f0693958fb5e650b68b5bee585eb82cf4da32965505c789e924743bc522", hash)
        assertEquals(PasswordGenerator.WORD_COUNT, words.size)
        assertEquals(words.size, words.toSet().size)
        assertEquals("abacus", words.first())
        assertEquals("zoom", words.last())
    }

    @Test fun passwordsHaveTheLengthAndOneCharacterOfEachChosenSet() {
        repeat(200) {
            val password = PasswordGenerator.password(GeneratorSettings())
            assertEquals(20, password.length)
            assertTrue(password, password.any(Char::isLowerCase) && password.any(Char::isUpperCase) && password.any(Char::isDigit))
            assertTrue(password, password.any { it in "!@#$%^&*-_=+?" })
            assertFalse("similar characters are left out by default: $password", password.any { it in "lIO01" })
        }
        val digitsOnly = GeneratorSettings(length = 8, uppercase = false, lowercase = false, symbols = false, avoidSimilar = false)
        assertTrue(PasswordGenerator.password(digitsOnly).matches(Regex("[0-9]{8}")))
        assertEquals(500, List(500) { PasswordGenerator.password(GeneratorSettings()) }.toSet().size)
    }

    @Test fun passphrasesUseListWordsTheSeparatorCapitalsAndOneDigit() {
        val settings = GeneratorSettings(mode = GeneratorMode.PASSPHRASE, words = 6, separator = ".")
        repeat(200) {
            val parts = PasswordGenerator.passphrase(settings, words).split(".")
            assertEquals(6, parts.size)
            assertEquals(1, parts.count { it.last().isDigit() })
            parts.map { it.trimEnd(Char::isDigit) }.forEach { word ->
                assertTrue(word, word.lowercase() in words)
                assertTrue(word, word.first().isUpperCase())
            }
        }
        val plain = PasswordGenerator.passphrase(settings.copy(capitalize = false, includeNumber = false, separator = " "), words)
        assertTrue(plain, plain.matches(Regex("[a-z-]+( [a-z-]+){5}")))
        assertThrows(IllegalArgumentException::class.java) { PasswordGenerator.passphrase(settings, listOf("too", "short")) }
    }

    @Test fun storedSettingsAreKeptInsideTheirLimits() {
        val bad = GeneratorSettings(length = 9999, words = -2, separator = "<", uppercase = false, lowercase = false, digits = false, symbols = false).normalized()
        assertEquals(64, bad.length)
        assertEquals(3, bad.words)
        assertEquals("-", bad.separator)
        assertTrue("one character set always stays on", bad.lowercase)
        assertEquals(GeneratorSettings(), GeneratorSettings().normalized())
    }

    @Test fun strengthEstimatesMatchTheChoices() {
        // 20 characters from 70: a-z without l (25), A-Z without I and O (24), 2-9 (8), 13 symbols. 122 bits.
        assertEquals(122, PasswordGenerator.entropyBits(GeneratorSettings()))
        assertEquals(70, PasswordGenerator.entropyBits(GeneratorSettings(mode = GeneratorMode.PASSPHRASE)))
        assertEquals(38, PasswordGenerator.entropyBits(GeneratorSettings(mode = GeneratorMode.PASSPHRASE, words = 3, includeNumber = false)))
        assertEquals("Weak", PasswordGenerator.strengthLabel(38))
        assertEquals("Strong", PasswordGenerator.strengthLabel(70))
        assertEquals("Very strong", PasswordGenerator.strengthLabel(122))
    }
}
