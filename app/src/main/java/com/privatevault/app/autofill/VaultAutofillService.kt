package com.privatevault.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.*
import android.view.autofill.AutofillId
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.httpsOrigin
import com.privatevault.app.security.trustedBrowser
import com.privatevault.app.security.VaultKeyManager
import java.util.UUID

/**
 * [embeddedSite] is the https site an app's WebView claims to show. A picker hint only: it never authorizes a fill,
 * never feeds keyboard suggestions or same-site fills, and is never stored as a link. [origin] stays null for apps.
 */
/**
 * [unverified]: Nuvori could not confirm the site (a browser it does not recognize, a non-https page, or a WebView with
 * frames or several sites). The picker still lists every login, and fills one only after the user confirms it for
 * this app; it never links a login here.
 */
internal data class LoginFillRequest(val packageName: String, val identity: String,
    val username: AutofillId?, val password: AutofillId?, val otp: AutofillId?, val expiresAt: Long, val origin: String? = null,
    val newPasswords: List<AutofillId> = emptyList(), val embeddedWebView: Boolean = false,
    val previousUsername: AutofillId? = null, val pinPassword: Boolean = false, val embeddedSite: String? = null,
    val unverified: Boolean = false)

private const val SAVE_USERNAME_ID = "save_username_id"
private const val SAVE_ORIGIN = "save_origin"
private const val SAVE_PACKAGE = "save_package"
private const val SAVE_IDENTITY = "save_identity"
/** Marks a node in or under a non-https document while a save reads values. Never equal to an https origin. */
private const val UNTRUSTED_ORIGIN = "untrusted:"

internal fun LoginFillRequest.saveClientState(): Bundle? {
    val id = username ?: previousUsername ?: return null
    val website = origin ?: return null
    return Bundle().apply {
        putParcelable(SAVE_USERNAME_ID, id)
        putString(SAVE_ORIGIN, website)
        putString(SAVE_PACKAGE, packageName)
        putString(SAVE_IDENTITY, identity)
    }
}

private const val SUGGESTED_PACKAGE = "suggested_package"
private const val SUGGESTED_IDENTITY = "suggested_identity"
private const val SUGGESTED_ORIGIN = "suggested_origin"
private const val SUGGESTED_EMBEDDED = "suggested_embedded"
private const val SUGGESTED_AT = "suggested_at"
private const val SUGGESTED_PROCESS = "suggested_process"

/**
 * Client state of a keyboard-suggestion response: the save state plus where and when its datasets were built. Android
 * hands it back with the dataset the user picks, so the next code screen can offer that login's code. No account data.
 */
internal fun LoginFillRequest.suggestionClientState(now: Long): Bundle = (saveClientState() ?: Bundle()).apply {
    val stamp = KeyboardSuggestionStamp(packageName, identity, origin, embeddedWebView, now)
    putString(SUGGESTED_PACKAGE, stamp.packageName)
    putString(SUGGESTED_IDENTITY, stamp.identity)
    stamp.origin?.let { putString(SUGGESTED_ORIGIN, it) }
    putBoolean(SUGGESTED_EMBEDDED, stamp.embeddedWebView)
    putLong(SUGGESTED_AT, stamp.at)
    putString(SUGGESTED_PROCESS, stamp.process)
}

private fun Bundle.suggestionStamp(): KeyboardSuggestionStamp? {
    if (!containsKey(SUGGESTED_AT)) return null
    return KeyboardSuggestionStamp(getString(SUGGESTED_PACKAGE) ?: return null, getString(SUGGESTED_IDENTITY) ?: return null,
        getString(SUGGESTED_ORIGIN), getBoolean(SUGGESTED_EMBEDDED), getLong(SUGGESTED_AT), getString(SUGGESTED_PROCESS) ?: return null)
}

@Suppress("DEPRECATION")
internal fun Bundle.previousUsernameFor(packageName: String, identity: String, origin: String?): AutofillId? {
    if (origin == null || getString(SAVE_ORIGIN) != origin || getString(SAVE_PACKAGE) != packageName ||
        getString(SAVE_IDENTITY) != identity) return null
    return if (Build.VERSION.SDK_INT >= 33) getParcelable(SAVE_USERNAME_ID, AutofillId::class.java)
        else getParcelable(SAVE_USERNAME_ID)
}

internal fun LoginFillRequest.saveInfo(): SaveInfo? {
    val savedPassword = newPasswords.firstOrNull() ?: password ?: return null
    if (otp != null) return null
    // A native form without a username field still saves its password; the review screen asks which account it belongs to.
    // A lone numeric PIN (app lock, card PIN) is not offered: it is rarely a login and would prompt on every entry.
    if (origin == null && username == null && previousUsername == null && newPasswords.isEmpty())
        return if (pinPassword) null else SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(savedPassword))
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
    val canSave = if (origin != null) username != null || newPasswords.isNotEmpty()
        else username != null && password != null
    if (!canSave && previousUsername == null) return null
    return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
        (listOfNotNull(username ?: previousUsername, savedPassword) + newPasswords.drop(1)).toTypedArray())
        .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
}

internal fun LoginFillRequest.delayedUsernameSave(): SaveInfo? =
    if (origin != null && username != null && password == null && newPasswords.isEmpty() && otp == null)
        SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, arrayOf(username))
            .setFlags(SaveInfo.FLAG_DELAY_SAVE).build() else null

/** Only field IDs and verified destination identity. No form contents or vault data. */
internal object PendingLoginFills {
    private val requests = mutableMapOf<String, LoginFillRequest>()
    @Synchronized fun put(request: LoginFillRequest): String {
        requests.entries.removeAll { it.value.expiresAt <= SystemClock.elapsedRealtime() }
        if (requests.size >= 8) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = request }
    }
    @Synchronized fun take(token: String?): LoginFillRequest? = requests.remove(token)?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }
    @Synchronized fun remove(token: String) { requests.remove(token) }
}

/**
 * A contact form waiting for the profile picker: field ids and kinds plus the verified destination ([origin] only for a
 * trusted browser). No profile values and no form contents.
 */
internal data class ProfileFillRequest(val packageName: String, val identity: String, val origin: String?,
    val fields: Map<AutofillId, ProfileField>, val expiresAt: Long, val embeddedWebView: Boolean = false,
    /** State or country drop-downs: their entries, to pick the one matching the saved address. */
    val options: Map<AutofillId, List<String>> = emptyMap())

/** One-use tokens: at most [capacity] waiting requests (oldest dropped first), each taken once and only before it expires. */
internal open class PendingProfileRequests(private val capacity: Int = 8) {
    private val requests = LinkedHashMap<String, ProfileFillRequest>()
    @Synchronized fun put(request: ProfileFillRequest, now: Long = SystemClock.elapsedRealtime()): String {
        requests.entries.removeAll { it.value.expiresAt <= now }
        while (requests.size >= capacity) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = request }
    }
    @Synchronized fun take(token: String?, now: Long = SystemClock.elapsedRealtime()): ProfileFillRequest? =
        token?.let(requests::remove)?.takeIf { it.expiresAt > now }
    @Synchronized fun remove(token: String) { requests.remove(token) }
    @Synchronized fun size(): Int = requests.size
}

internal object PendingProfileFills : PendingProfileRequests()

internal data class NativeLoginFields(val username: AutofillId?, val password: AutofillId?, val otp: AutofillId?, val origin: String? = null,
    val newPasswords: List<AutofillId> = emptyList(), val embeddedWebView: Boolean = false, val pinPassword: Boolean = false,
    val usernameGuessed: Boolean = false, val embeddedSite: String? = null)

/** Thin adapter: the rules live in [classifyLoginForm], which runs on plain nodes so it can be unit tested. */
internal fun nativeLoginFields(structure: AssistStructure, browser: Boolean = false, lenient: Boolean = false): NativeLoginFields? {
    val tree = formTree(structure) ?: return null
    val form = classifyLoginForm(tree.roots, browser, lenient) ?: return null
    return NativeLoginFields(form.username?.let { tree.ids[it] }, form.password?.let { tree.ids[it] },
        form.otp?.let { tree.ids[it] }, form.origin, form.newPasswords.map { tree.ids[it] }, form.embeddedWebView, form.pinPassword, form.usernameGuessed,
        form.embeddedSite)
}

/** The field the user is in, as the structure reports it, or null when none is marked focused. */
internal fun focusedAutofillId(structure: AssistStructure): AutofillId? {
    var count = 0
    fun find(node: AssistStructure.ViewNode, depth: Int): AutofillId? {
        if (++count > 2000 || depth > 40) return null
        if (node.isFocused && node.autofillId != null && node.autofillType != android.view.View.AUTOFILL_TYPE_NONE) return node.autofillId
        for (index in 0 until node.childCount) find(node.getChildAt(index), depth + 1)?.let { return it }
        return null
    }
    for (index in 0 until structure.windowNodeCount) find(structure.getWindowNodeAt(index).rootViewNode, 0)?.let { return it }
    return null
}

/** Intent extra of a save review: the site an app's WebView claimed. A default name only, never a link or website. */
internal const val SAVE_EMBEDDED_SITE = "login_save_embedded_site"

class VaultAutofillService : AutofillService() {
    /**
     * A code screen after a login the user picked from the keyboard strip. Android reports that pick in the fill event
     * history with the dataset id (the login id) and the client state written when the strip was built. Only a stamp
     * from this process, for this app and signing identity, within the code window counts. The picker re-checks the
     * destination and the linked code after unlock ([com.privatevault.app.security.pickerFillAllowed]).
     */
    @Suppress("DEPRECATION")
    private fun rememberKeyboardSignIn(packageName: String, identity: String) {
        val event = runCatching { fillEventHistory?.events }.getOrNull()
            ?.lastOrNull { it.type == FillEventHistory.Event.TYPE_DATASET_SELECTED } ?: return
        val stamp = runCatching { event.clientState?.suggestionStamp() }.getOrNull() ?: return
        keyboardSignIn(event.datasetId, stamp, packageName, identity, SystemClock.elapsedRealtime())?.let(RecentLoginFills::remember)
    }

    @Suppress("DEPRECATION")
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) return
        val response = runCatching {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return@runCatching null
            val destination = structure.activityComponent?.packageName ?: return@runCatching null
            if (destination == packageName) return@runCatching null
            val identity = appSigningIdentity(this, destination) ?: return@runCatching null
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.invalid"))
            val browser = trustedBrowser(destination, identity)
            // A browser Nuvori does not recognize can report any site, so nothing there is matched or saved by site.
            // The user can still fill any login there, as in an app, after confirming it (the "unverified" fallback).
            val unknownBrowser = !browser && packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo.packageName == destination }
            val strict = if (unknownBrowser) null else nativeLoginFields(structure, browser)
            // Nothing verified to offer, but the user is in a login field (a non-https page, frames in a WebView, say).
            val fallback = if (strict == null) nativeLoginFields(structure, browser = false, lenient = true) else null
            val unverified = fallback != null
            val fields = strict ?: fallback
            val inlineRequest = if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest else null
            // One form can hold a login, a card and an address (roboform.com's test form, many sign-up pages). Offer the
            // login only in its own fields; in a card or address field, offer the card or the address.
            val focused = focusedAutofillId(structure)
            val loginIds = listOfNotNull(fields?.username, fields?.password, fields?.otp) + fields?.newPasswords.orEmpty()
            val elsewhere = focused != null && fields != null && focused !in loginIds
            val loginForm = !elsewhere && (fields?.password != null || fields?.otp != null || !fields?.newPasswords.isNullOrEmpty())
            if (!loginForm) {
                // A checkout can hold card and address fields: each kind gets its own dataset, and Android shows the
                // one that covers the focused field.
                val datasets = ArrayList<Dataset>()
                val cleanups = ArrayList<() -> Unit>()
                val store = UnlockedProfileStore(this)
                val vault = VaultKeyManager(this).isInitialized
                if (!vault) store.clear()
                if (vault && AutofillPreferences.offerCards(this))
                    lockedCardDataset(structure, browser && !unknownBrowser, destination, identity, inlineRequest, cleanups, unknownBrowser)?.let(datasets::add)
                val profileForm = if (unknownBrowser) null else profileForm(structure, browser)
                val profileIds = profileForm?.fields
                if (profileIds != null) {
                    val copy = store.read()
                    val fromCopy = profileDatasets(this, profileIds, copy, inlineRequest, profileForm.options)
                    datasets += fromCopy
                    // Fallback only: without a device copy, offer an unlock that leads to the profile picker.
                    if (fromCopy.isEmpty() && copy.isEmpty() && vault && AutofillPreferences.offerLockedProfiles(this) &&
                        lockedProfileOfferWorthwhile(profileIds.values, loginForm))
                        lockedProfileDataset(structure, browser, destination, identity, inlineRequest, cleanups)?.let(datasets::add)
                }
                if (datasets.isNotEmpty()) {
                    cancellationSignal.setOnCancelListener { cleanups.forEach { it() } }
                    return@runCatching FillResponse.Builder().apply { datasets.forEach { addDataset(it) } }.build()
                }
            }
            if (fields == null || elsewhere) return@runCatching null
            // The Vault codes tile can suggest this app's codes without Usage access. Package and time only, in memory.
            RecentAutofillApps.record(destination, browser, packageName, SystemClock.elapsedRealtime())
            val previousUsername = if (browser) request.clientState?.previousUsernameFor(destination, identity, fields.origin) else null
            val fill = LoginFillRequest(destination, identity, fields.username, fields.password, fields.otp,
                SystemClock.elapsedRealtime() + 120_000, fields.origin, fields.newPasswords, fields.embeddedWebView || unverified,
                previousUsername, fields.pinPassword, fields.embeddedSite, unverified = unverified)
            if (fields.otp != null) rememberKeyboardSignIn(destination, identity)
            val keyboard = AutofillPreferences.keyboardSuggestions(this) && fields.otp == null && fields.newPasswords.isEmpty()
            val token = PendingLoginFills.put(fill)
            cancellationSignal.setOnCancelListener { PendingLoginFills.remove(token) }
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.autofill.$token")
                .putExtra("login_fill_token", token).putExtra("keyboard_suggestions", keyboard)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val sender = pendingIntent.intentSender
            val description = if (fields.otp != null) "Unlock Nuvori to choose a code" else "Unlock Nuvori to choose a login"
            val presentation = unlockMenuPresentation(this, description)
            val dataset = Dataset.Builder(presentation).setAuthentication(sender)
            val inline = unlockInlinePresentation(this, inlineRequest, pendingIntent,
                if (fields.otp != null) "Unlock to choose code" else "Unlock to fill", description)
            val fieldIds = listOfNotNull(fields.username, fields.password, fields.otp) + fields.newPasswords
            fieldIds.forEach { dataset.presentField(it, null, presentation, inline) }
            val response = FillResponse.Builder()
            if (keyboard) response.requireUnlock(fieldIds, sender, presentation, inline)
            else response.addDataset(dataset.build())
            // An unverified page never offers to save: the login would be tied to whatever the page claimed.
            if (!unverified) {
                (fill.saveInfo() ?: fill.delayedUsernameSave())?.let(response::setSaveInfo)
                fill.saveClientState()?.let(response::setClientState)
            }
            response.build()
        }.getOrNull()
        if (!cancellationSignal.isCanceled) callback.onSuccess(response)
    }

    /**
     * "Unlock to fill your details": one authenticated dataset over the form's profile fields. It carries no values;
     * the picker fills only after unlock and a tap on one profile, and re-checks the destination first.
     */
    @Suppress("DEPRECATION")
    private fun lockedProfileDataset(structure: AssistStructure, browser: Boolean, destination: String, identity: String,
        inlineRequest: android.view.inputmethod.InlineSuggestionsRequest?, cleanups: MutableList<() -> Unit>): Dataset? {
        val form = lockedProfileForm(structure, browser) ?: return null
        val fill = ProfileFillRequest(destination, identity, form.destination.origin, form.fields,
            SystemClock.elapsedRealtime() + 120_000, form.destination.embeddedWebView, form.options)
        val token = PendingProfileFills.put(fill)
        cleanups += { PendingProfileFills.remove(token) }
        val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.profile.$token").putExtra(PROFILE_FILL_TOKEN, token)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val description = "Unlock Nuvori to fill your details"
        val menu = unlockMenuPresentation(this, description, "Unlock to fill your details")
        val inline = unlockInlinePresentation(this, inlineRequest, pendingIntent, "Unlock to fill your details", description)
        val dataset = Dataset.Builder(menu).setAuthentication(pendingIntent.intentSender)
        form.fields.keys.forEach { dataset.presentField(it, null, menu, inline) }
        return dataset.build()
    }

    /**
     * "Unlock to fill a card": one authenticated dataset over the form's card fields. It carries no card data; the
     * picker fills only after unlock and a tap on one card, re-checks the destination, and adds the security code
     * only when the user leaves that switch on.
     */
    @Suppress("DEPRECATION")
    private fun lockedCardDataset(structure: AssistStructure, browser: Boolean, destination: String, identity: String,
        inlineRequest: android.view.inputmethod.InlineSuggestionsRequest?, cleanups: MutableList<() -> Unit>,
        unknownBrowser: Boolean = false): Dataset? {
        // Verified first; otherwise the "unverified" fallback (no origin; the picker says the app receives the card).
        val strict = if (unknownBrowser) null else cardFormFields(structure, browser)
        val (fields, form) = strict ?: cardFormFields(structure, browser = false, lenient = true) ?: return null
        if (fields.isEmpty()) return null
        val unverified = strict == null
        val fill = CardFillRequest(destination, identity, form.origin, fields, SystemClock.elapsedRealtime() + 120_000,
            form.embeddedWebView || unverified, unverified)
        val token = PendingCardFills.put(fill)
        cleanups += { PendingCardFills.remove(token) }
        val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.card.$token").putExtra(CARD_FILL_TOKEN, token)
        val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val description = "Unlock Nuvori to fill a card"
        val menu = unlockMenuPresentation(this, description, "Unlock to fill a card")
        val inline = unlockInlinePresentation(this, inlineRequest, pendingIntent, "Unlock to fill a card", description)
        val dataset = Dataset.Builder(menu).setAuthentication(pendingIntent.intentSender)
        fields.keys.forEach { dataset.presentField(it, null, menu, inline) }
        return dataset.build()
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        var pending: LoginSaveRequest? = null
        var token: String? = null
        try {
            val structure = request.fillContexts.asReversed().map { it.structure }.firstOrNull { candidate ->
                val candidatePackage = candidate.activityComponent?.packageName ?: return@firstOrNull false
                val candidateIdentity = appSigningIdentity(this, candidatePackage) ?: return@firstOrNull false
                val candidateBrowser = trustedBrowser(candidatePackage, candidateIdentity)
                nativeLoginFields(candidate, candidateBrowser)?.let { it.password != null || it.newPasswords.isNotEmpty() } == true
            } ?: error("Missing login form")
            val destination = structure.activityComponent?.packageName ?: error("Missing app")
            val identity = appSigningIdentity(this, destination) ?: error("Missing identity")
            val browser = trustedBrowser(destination, identity)
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.invalid"))
            if (!browser && packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo.packageName == destination }) error("Unsupported browser")
            val fields = nativeLoginFields(structure, browser) ?: error("Unsupported form")
            val savedPassword = fields.newPasswords.firstOrNull() ?: fields.password
            check(savedPassword != null && fields.otp == null)
            val origin = fields.origin
            if (browser) requireNotNull(origin)
            val previousUsername = if (browser) request.clientState?.previousUsernameFor(destination, identity, origin) else null
            val values = mutableMapOf<AutofillId, CharSequence>()
            // [here]: the node's own https origin as the classifier sees it (a frame never inherits one; nothing in or
            // under a non-https document has one). A browser save never reads a value from another origin.
            fun visit(node: AssistStructure.ViewNode, inherited: String?) {
                val tag = node.htmlInfo?.tag?.lowercase(java.util.Locale.ROOT)
                var here = if (inherited == UNTRUSTED_ORIGIN || (tag != "iframe" && tag != "frame")) inherited else null
                if (here != UNTRUSTED_ORIGIN && !node.webDomain.isNullOrBlank()) here =
                    (if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null) ?: UNTRUSTED_ORIGIN
                if ((!browser || here == origin) && (node.autofillId == fields.username || node.autofillId == previousUsername ||
                        node.autofillId == savedPassword || node.autofillId in fields.newPasswords)) {
                    node.autofillValue?.takeIf { it.isText }?.textValue?.let { values[requireNotNull(node.autofillId)] = it }
                }
                for (i in 0 until node.childCount) visit(node.getChildAt(i), here)
            }
            for (context in request.fillContexts) {
                if (context.structure.activityComponent?.packageName != destination) continue
                if (browser && nativeLoginFields(context.structure, true)?.origin != origin) continue
                for (i in 0 until context.structure.windowNodeCount) visit(context.structure.getWindowNodeAt(i).rootViewNode, null)
            }
            val selected = request.clientState?.takeIf {
                browser && it.getString("selected_origin") == origin && it.getString("selected_browser") == destination
            }?.getString("selected_username")
            // No readable account: the review screen asks for it. A blank field counts as missing.
            val formUsername = values[fields.username]?.toString()?.takeIf { it.isNotBlank() }
            val username = listOf(formUsername, values[previousUsername]?.toString(), selected)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            // A guessed account box (the field before a lone password) may hold a server or workspace name: confirm it first.
            val confirmed = formUsername == null || !fields.usernameGuessed
            val password = requireNotNull(values[savedPassword])
            check(fields.newPasswords.all { values[it]?.toString() == password.toString() })
            require(username.length <= 1024 && password.isNotEmpty() && password.length <= 4096)
            pending = LoginSaveRequest(destination, identity, origin, username, CharArray(password.length) { password[it] },
                SystemClock.elapsedRealtime() + 120_000, usernameConfirmed = confirmed)
            token = PendingLoginSaves.put(pending)
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.save.$token").putExtra("login_save_token", token)
            // The WebView's claimed site names a new login. It is never saved as its website: that would let browsers fill it.
            if (!browser && fields.embeddedWebView) fields.embeddedSite?.let { intent.putExtra(SAVE_EMBEDDED_SITE, it) }
            val sender = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).intentSender
            callback.onSuccess(sender)
        } catch (_: Exception) {
            token?.let(PendingLoginSaves::remove)
            pending?.clear()
            callback.onFailure("Could not prepare this login. Save it manually in Nuvori.")
        }
    }
}
