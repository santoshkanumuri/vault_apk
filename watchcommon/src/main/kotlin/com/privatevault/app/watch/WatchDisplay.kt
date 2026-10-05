package com.privatevault.app.watch

import java.text.DateFormat
import java.util.Date

/** How close a code is to changing. The watch and phone color their countdowns by it. */
enum class CodeUrgency { CALM, SOON, EXPIRING }

/** Display rules for codes on small screens. Pure functions so they run in plain JVM tests. */
object WatchDisplay {
    /** Above this many accounts the watch opens a letter index first; smaller lists open straight to the codes. */
    const val DIRECT_LIST_LIMIT = 8
    /** In the last seconds of a period the next code is shown too, so a slow typist is not caught out. */
    const val NEXT_CODE_SECONDS = 10
    const val RECENT_LIMIT = 4

    /** "123 456" or "1234 5678": groups that are easy to read aloud and retype. */
    fun groupCode(code: String): String = code.chunked(if (code.length == 8) 4 else 3).joinToString(" ")

    fun remainingSeconds(period: Int, epochSeconds: Long): Int {
        require(period > 0)
        return (period - Math.floorMod(epochSeconds, period.toLong())).toInt()
    }

    fun urgency(remaining: Int): CodeUrgency = when {
        remaining <= 5 -> CodeUrgency.EXPIRING
        remaining <= NEXT_CODE_SECONDS -> CodeUrgency.SOON
        else -> CodeUrgency.CALM
    }

    fun showNextCode(remaining: Int): Boolean = remaining <= NEXT_CODE_SECONDS

    /** Most recent first, without duplicates, capped. [id] moves to the front. */
    fun recordRecent(recent: List<String>, id: String): List<String> =
        (listOf(id) + recent.filter { it != id }).take(RECENT_LIMIT)

    /** "Synced just now", "Synced 5 min ago", "Synced 3 h ago", or a short date. Null when nothing was ever received. */
    fun syncedLabel(nowMs: Long, syncedAtMs: Long, prefix: String = "Synced"): String? {
        if (syncedAtMs <= 0) return null
        val elapsed = (nowMs - syncedAtMs).coerceAtLeast(0)
        val minutes = elapsed / 60_000
        return when {
            minutes < 1 -> "$prefix just now"
            minutes < 60 -> "$prefix $minutes min ago"
            minutes < 24 * 60 -> "$prefix ${minutes / 60} h ago"
            else -> "$prefix " + DateFormat.getDateInstance(DateFormat.SHORT).format(Date(syncedAtMs))
        }
    }
}
