package com.privatevault.app.security

internal val backgroundTimeouts = listOf(0L, 10_000L, 30_000L, 60_000L, 300_000L)
internal val inactivityTimeouts = listOf(60_000L, 300_000L, 900_000L, 1_800_000L)
/** Every unlock, 1 day or 1 week. Security review L-04: fingerprint access never lasts longer than a week. */
internal val masterPasswordIntervals = listOf(0L, 86_400_000L, 604_800_000L)
internal const val MAX_MASTER_PASSWORD_INTERVAL_MS = 604_800_000L

/**
 * A stored interval brought within [masterPasswordIntervals]. 2.3.x offered 1 month; settings and
 * backups that still hold it get 1 week.
 */
internal fun capMasterPasswordInterval(ms: Long): Long = when {
    ms in masterPasswordIntervals -> ms
    ms <= 0L -> 0L
    ms >= MAX_MASTER_PASSWORD_INTERVAL_MS -> MAX_MASTER_PASSWORD_INTERVAL_MS
    else -> 86_400_000L
}

/**
 * How long a file, photo or camera screen that Nuvori opened counts as time in the app. The vault
 * still locks at once when the screen turns off, and it locks when this limit ends.
 */
internal const val EXTERNAL_FLOW_LIMIT_MS = 600_000L

/**
 * When the vault locks after the app goes to the background at [now]. A screen that Nuvori opened
 * ([externalFlow]) keeps the vault open up to [EXTERNAL_FLOW_LIMIT_MS], or longer only when the
 * inactivity deadline is later. Any other app switch never extends [inactivityDeadline].
 */
internal fun backgroundLockDeadline(now: Long, inactivityDeadline: Long, externalFlow: Boolean, timeout: Long = 10_000L): Long =
    if (externalFlow) maxOf(inactivityDeadline, now + EXTERNAL_FLOW_LIMIT_MS) else minOf(now + timeout, inactivityDeadline)

internal fun biometricSessionValid(
    nowWall: Long, nowElapsed: Long, bootCount: Int,
    createdWall: Long, createdElapsed: Long, createdBootCount: Int,
    lastSeenWall: Long, duration: Long
): Boolean = duration in masterPasswordIntervals && duration > 0 &&
    bootCount == createdBootCount && nowWall >= createdWall && nowWall >= lastSeenWall &&
    nowElapsed >= createdElapsed && nowWall - createdWall < duration && nowElapsed - createdElapsed < duration
