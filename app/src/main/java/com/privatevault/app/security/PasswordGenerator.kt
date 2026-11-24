package com.privatevault.app.security

import android.content.Context
import androidx.core.content.edit
import java.security.SecureRandom
import kotlin.math.floor
import kotlin.math.ln

/*
 * Password and passphrase generator. Nuvori for Windows has the same rules and limits
 * (src/lib/generator.ts): keep the two in step. The word list is the EFF large word list,
 * 7,776 words (assets/eff_large_wordlist.txt, see THIRD_PARTY_NOTICES.md).
 */

enum class GeneratorMode { PASSWORD, PASSPHRASE }

data class GeneratorSettings(
    val mode: GeneratorMode = GeneratorMode.PASSWORD,
    /** Password mode. */
    val length: Int = 20,
    val uppercase: Boolean = true,
    val lowercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    /** Leaves out characters that are easy to confuse: l, I, O, 0 and 1. */
    val avoidSimilar: Boolean = true,
    /** Passphrase mode. */
    val words: Int = 5,
    val separator: String = "-",
    val capitalize: Boolean = true,
    /** Adds one digit to the end of one random word. */
    val includeNumber: Boolean = true,
) {
    /** Every value inside its limits, and at least one character set on. */
    fun normalized(): GeneratorSettings {
        val clamped = copy(
            length = length.coerceIn(PasswordGenerator.LENGTH_RANGE),
            words = words.coerceIn(PasswordGenerator.WORDS_RANGE),
            separator = separator.takeIf { it in PasswordGenerator.SEPARATORS } ?: "-",
        )
        return if (!uppercase && !lowercase && !digits && !symbols) clamped.copy(lowercase = true) else clamped
    }
}

object PasswordGenerator {
    val LENGTH_RANGE = 8..64
    val WORDS_RANGE = 3..10
    val SEPARATORS = listOf("-", ".", "_", " ")
    const val WORD_COUNT = 7776

    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%^&*-_=+?"
    private const val SIMILAR = "lIO01"

    private fun characterSets(settings: GeneratorSettings): List<String> {
        fun strip(set: String) = if (settings.avoidSimilar) set.filterNot { it in SIMILAR } else set
        return listOfNotNull(
            strip(LOWER).takeIf { settings.lowercase },
            strip(UPPER).takeIf { settings.uppercase },
            strip(DIGITS).takeIf { settings.digits },
            SYMBOLS.takeIf { settings.symbols },
        )
    }

    /** A random password: at least one character from each chosen set, then a shuffle. */
    fun password(input: GeneratorSettings, random: SecureRandom = SecureRandom()): String {
        val settings = input.normalized()
        val sets = characterSets(settings)
        val all = sets.joinToString("")
        val chars = sets.map { it[random.nextInt(it.length)] }.toMutableList()
        while (chars.size < settings.length) chars += all[random.nextInt(all.length)]
        // Fisher-Yates; SecureRandom.nextInt(bound) is uniform.
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            chars[i] = chars[j].also { chars[j] = chars[i] }
        }
        return chars.joinToString("")
    }

    /** Words picked at random from [wordList], joined by the separator. */
    fun passphrase(input: GeneratorSettings, wordList: List<String>, random: SecureRandom = SecureRandom()): String {
        require(wordList.size >= 1024) { "The word list is missing" }
        val settings = input.normalized()
        val words = MutableList(settings.words) {
            val word = wordList[random.nextInt(wordList.size)]
            if (settings.capitalize) word.replaceFirstChar(Char::uppercaseChar) else word
        }
        if (settings.includeNumber) {
            val index = random.nextInt(words.size)
            words[index] = words[index] + random.nextInt(10)
        }
        return words.joinToString(settings.separator)
    }

    fun generate(context: Context, settings: GeneratorSettings): String =
        if (settings.mode == GeneratorMode.PASSPHRASE) passphrase(settings, WordList.load(context)) else password(settings)

    /** One tap: a new password or passphrase with the settings last used on this device. */
    fun generateWithSavedSettings(context: Context): String = generate(context, GeneratorSettingsStore(context).read())

    /**
     * Bits of randomness in a result, rounded down. A password counts every position from the full
     * pool. A passphrase counts its words, plus the digit and its position.
     */
    fun entropyBits(input: GeneratorSettings, wordCount: Int = WORD_COUNT): Int {
        val settings = input.normalized()
        fun log2(value: Double) = ln(value) / ln(2.0)
        val bits = if (settings.mode == GeneratorMode.PASSPHRASE) {
            settings.words * log2(wordCount.toDouble()) +
                if (settings.includeNumber) log2(10.0) + log2(settings.words.toDouble()) else 0.0
        } else {
            settings.length * log2(characterSets(settings).sumOf { it.length }.toDouble())
        }
        return floor(bits).toInt()
    }

    /** Below 50 bits is weak for a website password; 80 and above resists any offline attack. */
    fun strengthLabel(bits: Int): String = when {
        bits < 50 -> "Weak"
        bits < 65 -> "Fair"
        bits < 80 -> "Strong"
        else -> "Very strong"
    }
}

/** The EFF large word list, read once from the app's assets. */
object WordList {
    @Volatile private var words: List<String>? = null

    fun load(context: Context): List<String> = words ?: synchronized(this) {
        words ?: context.applicationContext.assets.open("eff_large_wordlist.txt").bufferedReader().useLines { lines ->
            lines.filter(String::isNotBlank).toList()
        }.also {
            check(it.size == PasswordGenerator.WORD_COUNT) { "The word list is damaged" }
            words = it
        }
    }
}

/** The last generator settings on this device. They are not secret and are not synced. */
class GeneratorSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("password_generator", Context.MODE_PRIVATE)

    fun read(): GeneratorSettings {
        val defaults = GeneratorSettings()
        return GeneratorSettings(
            mode = if (prefs.getString("mode", null) == "passphrase") GeneratorMode.PASSPHRASE else GeneratorMode.PASSWORD,
            length = prefs.getInt("length", defaults.length),
            uppercase = prefs.getBoolean("uppercase", defaults.uppercase),
            lowercase = prefs.getBoolean("lowercase", defaults.lowercase),
            digits = prefs.getBoolean("digits", defaults.digits),
            symbols = prefs.getBoolean("symbols", defaults.symbols),
            avoidSimilar = prefs.getBoolean("avoid_similar", defaults.avoidSimilar),
            words = prefs.getInt("words", defaults.words),
            separator = prefs.getString("separator", defaults.separator) ?: defaults.separator,
            capitalize = prefs.getBoolean("capitalize", defaults.capitalize),
            includeNumber = prefs.getBoolean("include_number", defaults.includeNumber),
        ).normalized()
    }

    fun save(input: GeneratorSettings) {
        val settings = input.normalized()
        prefs.edit {
            putString("mode", if (settings.mode == GeneratorMode.PASSPHRASE) "passphrase" else "password")
            putInt("length", settings.length)
            putBoolean("uppercase", settings.uppercase)
            putBoolean("lowercase", settings.lowercase)
            putBoolean("digits", settings.digits)
            putBoolean("symbols", settings.symbols)
            putBoolean("avoid_similar", settings.avoidSimilar)
            putInt("words", settings.words)
            putString("separator", settings.separator)
            putBoolean("capitalize", settings.capitalize)
            putBoolean("include_number", settings.includeNumber)
        }
    }
}
