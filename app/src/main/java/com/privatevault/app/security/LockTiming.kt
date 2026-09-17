package com.privatevault.app.security

internal fun backgroundLockDeadline(now: Long, inactivityDeadline: Long, externalFlow: Boolean): Long =
    if (externalFlow) inactivityDeadline else minOf(now + 10_000L, inactivityDeadline)
