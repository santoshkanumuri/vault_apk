package com.privatevault.app.security

/**
 * Backoff after wrong master passwords (security review L-03). The first [FREE_ATTEMPTS] tries are free; after that
 * each failure waits twice as long as the one before, from 30 seconds up to [MAX_DELAY_MILLIS]. Times are wall-clock
 * (they must survive a restart); a clock moved back counts as no time passed.
 */
internal object UnlockThrottle {
    const val FREE_ATTEMPTS = 5
    const val MAX_DELAY_MILLIS = 15 * 60_000L

    /** The wait that [failures] wrong passwords in a row impose before the next try. */
    fun delayAfter(failures: Int): Long {
        if (failures < FREE_ATTEMPTS) return 0L
        val step = (failures - FREE_ATTEMPTS).coerceAtMost(10)
        return (30_000L shl step).coerceAtMost(MAX_DELAY_MILLIS)
    }

    /** Milliseconds left before another try, after [failures] in a row, the last at [lastFailureAt]. */
    fun waitMillis(failures: Int, lastFailureAt: Long, now: Long): Long {
        val delay = delayAfter(failures)
        if (delay == 0L) return 0L
        val elapsed = now - lastFailureAt
        if (elapsed < 0) return delay
        return (delay - elapsed).coerceAtLeast(0L)
    }

    /** "45 seconds", "1 minute", "15 minutes": a wait rounded up. */
    fun describe(millis: Long): String {
        val seconds = ((millis + 999) / 1000).coerceAtLeast(1)
        if (seconds < 60) return if (seconds == 1L) "1 second" else "$seconds seconds"
        val minutes = (seconds + 59) / 60
        return if (minutes == 1L) "1 minute" else "$minutes minutes"
    }
}
