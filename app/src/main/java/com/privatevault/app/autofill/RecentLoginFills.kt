package com.privatevault.app.autofill

/** How long the code screen after a login may offer that login's linked code. */
internal const val CODE_AFTER_LOGIN_MS = 180_000L

/** How long the last app that asked Nuvori to fill stays the tile's suggestion. */
internal const val RECENT_AUTOFILL_APP_MS = 300_000L

/**
 * The login the picker just filled and where. Ids and the destination only: no password, setup key or code.
 * Times are [android.os.SystemClock.elapsedRealtime] values, passed in so tests need no clock.
 */
internal data class RecentLoginFill(val entryId: String, val packageName: String, val identity: String,
    val origin: String?, val embeddedWebView: Boolean, val expiresAt: Long)

/** One slot in process memory. Process death loses it; the code screen then shows the normal list. */
internal object RecentLoginFills {
    private var slot: RecentLoginFill? = null
    /** A newer fill always replaces the older one: only the last sign-in can lead to a code screen. */
    @Synchronized fun remember(fill: RecentLoginFill) { slot = fill }
    @Synchronized fun peek(now: Long): RecentLoginFill? {
        if (slot?.let { it.expiresAt <= now } == true) slot = null
        return slot
    }
    /** Single use: the code for that sign-in was filled. */
    @Synchronized fun consume(entryId: String) { if (slot?.entryId == entryId) slot = null }
    @Synchronized fun clear() { slot = null }
}

/** Package and time only. Never a browser (its package says nothing about the site) and never Nuvori itself. */
internal data class AutofillAppVisit(val packageName: String, val at: Long)

internal fun recentAutofillApp(visit: AutofillAppVisit?, now: Long): String? =
    visit?.takeIf { now >= it.at && now - it.at <= RECENT_AUTOFILL_APP_MS }?.packageName

/** The last app whose login or code form reached Nuvori's autofill service. A tile suggestion only, never a trust signal. */
internal object RecentAutofillApps {
    private var last: AutofillAppVisit? = null
    @Synchronized fun record(packageName: String, browser: Boolean, ownPackage: String, now: Long) {
        if (browser || packageName.isBlank() || packageName == ownPackage) return
        last = AutofillAppVisit(packageName, now)
    }
    @Synchronized fun recent(now: Long): String? = recentAutofillApp(last, now) ?: run { last = null; null }
    @Synchronized fun clear() { last = null }
}

/** Marks a stamp written by this process. A restarted process cannot vouch for an older keyboard strip. */
private val PROCESS_STAMP: String = java.util.UUID.randomUUID().toString()

/** Where and when a keyboard-suggestion strip was built ([at] is elapsedRealtime). Kept in its client state; no account data. */
internal data class KeyboardSuggestionStamp(val packageName: String, val identity: String, val origin: String?,
    val embeddedWebView: Boolean, val at: Long, val process: String = PROCESS_STAMP)

/**
 * The login picked from a keyboard strip, as a code-step record, or null. The dataset id is the login id (the picker
 * dataset is not a login). The stamp must come from this process, within the code window, for the same package and
 * signing identity as the code request. The record keeps the stamp's own origin: the picker matches it to the code
 * page with [com.privatevault.app.security.codeAfterLoginMatches] and checks the linked code after unlock.
 */
internal fun keyboardSignIn(datasetId: String?, stamp: KeyboardSuggestionStamp, packageName: String, identity: String,
    now: Long): RecentLoginFill? {
    if (datasetId.isNullOrBlank() || datasetId == PICKER_DATASET_ID || stamp.process != PROCESS_STAMP ||
        now < stamp.at || now - stamp.at > CODE_AFTER_LOGIN_MS || identity.isBlank() ||
        stamp.packageName != packageName || stamp.identity != identity) return null
    return RecentLoginFill(datasetId, packageName, identity, stamp.origin, stamp.embeddedWebView, now + CODE_AFTER_LOGIN_MS)
}

/** Dataset id of the "Choose another login" entry in a keyboard strip. */
internal const val PICKER_DATASET_ID = "nuvori-picker"
