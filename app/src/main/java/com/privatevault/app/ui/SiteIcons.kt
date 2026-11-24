package com.privatevault.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.pow

/** One built-in icon: title, brand color (0xRRGGBB) and an SVG path for a 24x24 viewBox. */
internal class SiteIcon(val title: String, val rgb: Int, val pathData: String)

/** The built-in icon set (v1 format, see assets/site_icons.json). */
internal class SiteIconSet(val icons: List<SiteIcon>, val domains: Map<String, Int>) {
    /** First candidate key that is in `domains` wins, so `co.uk` can never match (it is never a key). */
    fun lookup(site: String?): SiteIcon? {
        for (key in SiteIconLogic.lookupKeys(site)) domains[key]?.let { i -> return icons.getOrNull(i) }
        return null
    }
}

/** Pure helpers, free of Android so unit tests run on the JVM. */
internal object SiteIconLogic {
    /** The host of a login URL or passkey rpId, or null when no icon should be tried. */
    fun hostOf(input: String?): String? {
        var s = input?.trim()?.lowercase() ?: return null
        if (s.isEmpty()) return null
        val scheme = s.indexOf("://")
        if (scheme >= 0) {
            val name = s.substring(0, scheme)
            if (name != "https" && name != "http") return null
            s = s.substring(scheme + 3)
        } else if (s.startsWith("android:")) return null
        s = s.substringBefore('/').substringBefore('?').substringBefore('#')
        s = s.substringAfterLast('@')
        if (s.startsWith("[")) return null // IPv6 literal
        s = s.substringBefore(':').trimEnd('.')
        if (s.isEmpty() || s == "localhost" || !s.contains('.')) return null
        val parts = s.split('.')
        if (parts.any { it.isEmpty() }) return null
        if (parts.all { p -> p.all { it in '0'..'9' } }) return null // IPv4
        return s
    }

    /** `www.bbc.co.uk` -> `www.bbc.co.uk`, `bbc.co.uk`, `co.uk`. Empty when there is no usable host. */
    fun lookupKeys(input: String?): List<String> {
        val labels = (hostOf(input) ?: return emptyList()).split('.')
        return (0..labels.size - 2).map { labels.drop(it).joinToString(".") }
    }

    private fun channel(v: Int): Double {
        val c = v / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    /** WCAG relative luminance of 0xRRGGBB. */
    fun luminance(rgb: Int): Double =
        0.2126 * channel((rgb shr 16) and 0xFF) + 0.7152 * channel((rgb shr 8) and 0xFF) + 0.0722 * channel(rgb and 0xFF)

    /** White glyph on dark brand colors, near-black on light ones. */
    fun glyphIsWhite(rgb: Int): Boolean = luminance(rgb) < 0.4

    /** Very light tiles get a hairline so they do not vanish on a light background. */
    fun needsBorder(rgb: Int): Boolean = luminance(rgb) > 0.85

    /** Parses the v1 file. Returns null for a different version or bad data. */
    fun parse(json: String): SiteIconSet? = try {
        val root = MiniJson(json).readValue() as? Map<*, *>
        if ((root?.get("v") as? Double)?.toInt() != 1) null else {
            val icons = (root["icons"] as List<*>).map { row ->
                val r = row as List<*>
                SiteIcon(r[0] as String, (r[1] as String).toInt(16), r[2] as String)
            }
            val domains = (root["domains"] as Map<*, *>).entries.associate { (k, v) -> k as String to (v as Double).toInt() }
            SiteIconSet(icons, domains)
        }
    } catch (_: Exception) { null }
}

/** Minimal JSON reader (strings, numbers, arrays, objects, literals). org.json is stubbed in JVM unit tests. */
private class MiniJson(private val s: String) {
    private var i = 0
    private fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
    fun readValue(): Any? {
        ws()
        return when (val c = s[i]) {
            '{' -> {
                i++
                val m = HashMap<String, Any?>()
                ws()
                if (s[i] == '}') i++ else while (true) {
                    ws()
                    val k = readString()
                    ws()
                    i++ // ':'
                    m[k] = readValue()
                    ws()
                    if (s[i++] == '}') break
                }
                m
            }
            '[' -> {
                i++
                val l = ArrayList<Any?>()
                ws()
                if (s[i] == ']') i++ else while (true) {
                    l.add(readValue())
                    ws()
                    if (s[i++] == ']') break
                }
                l
            }
            '"' -> readString()
            't' -> { i += 4; true }
            'f' -> { i += 5; false }
            'n' -> { i += 4; null }
            else -> {
                val st = i
                while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
                if (st == i) throw IllegalArgumentException("bad json at $i: $c")
                s.substring(st, i).toDouble()
            }
        }
    }

    private fun readString(): String {
        i++ // opening quote
        val sb = StringBuilder()
        while (true) {
            val c = s[i++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> when (val e = s[i++]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'u' -> { sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4 }
                    else -> sb.append(e)
                }
                else -> sb.append(c)
            }
        }
    }
}

/** Loads the asset once, off the main thread, and keeps it in memory. Also holds the parsed-path cache. */
internal object SiteIcons {
    private val mutex = Mutex()
    @Volatile private var set: SiteIconSet? = null
    @Volatile private var failed = false

    /** The icon set from memory, or null if not loaded yet or the asset is unreadable. */
    fun loaded(): SiteIconSet? = set

    suspend fun load(context: Context): SiteIconSet? {
        set?.let { return it }
        if (failed) return null
        return mutex.withLock {
            set ?: withContext(Dispatchers.IO) {
                try {
                    val text = context.applicationContext.assets.open("site_icons.json").use { it.readBytes().toString(Charsets.UTF_8) }
                    SiteIconLogic.parse(text)
                } catch (_: Exception) { null }
            }.also { if (it == null) failed = true else set = it }
        }
    }

    private val paths = object : LinkedHashMap<String, Path>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Path>?) = size > 128
    }

    fun path(d: String): Path = synchronized(paths) {
        paths.getOrPut(d) { PathParser().parsePathString(d).toPath() }
    }
}

/** The "Website icons" preference. Default on. Kept in the same preferences file as the other appearance settings. */
internal object SiteIconSettings {
    private const val KEY = "site_icons"
    private var state by mutableStateOf<Boolean?>(null)

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("vault_preferences", Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = state ?: prefs(context).getBoolean(KEY, true).also { state = it }

    fun set(context: Context, on: Boolean) {
        if (prefs(context).edit().putBoolean(KEY, on).commit()) state = on
    }
}

/** The icon for [site] when the setting is on and the set is loaded; null means "draw the letter". */
@Composable
internal fun rememberSiteIcon(site: String?): SiteIcon? {
    if (site.isNullOrBlank() || SiteIconLogic.hostOf(site) == null) return null
    val context = LocalContext.current
    if (!SiteIconSettings.enabled(context)) return null
    val loaded by produceState(SiteIcons.loaded(), context) { value = SiteIcons.load(context) }
    return remember(loaded, site) { loaded?.lookup(site) }
}

/**
 * The icon another device captured for [site] and shared through sync, when the setting is on and the vault is
 * open. Null means "try the built-in icon". Reads [SyncedSiteIconCache.generation], so a newly synced icon shows.
 */
@Composable
internal fun rememberSyncedSiteIcon(site: String?): androidx.compose.ui.graphics.ImageBitmap? {
    if (site.isNullOrBlank() || SiteIconLogic.hostOf(site) == null) return null
    if (!SiteIconSettings.enabled(LocalContext.current)) return null
    val generation = com.privatevault.app.sync.SyncedSiteIconCache.generation
    return remember(site, generation) { com.privatevault.app.sync.SyncedSiteIconCache.lookup(site) }
}

internal fun SiteIcon.tileColor(): Color = Color(0xFF000000.toInt() or rgb)
internal fun SiteIcon.glyphColor(): Color = if (SiteIconLogic.glyphIsWhite(rgb)) Color.White else Color(0xFF111111)
