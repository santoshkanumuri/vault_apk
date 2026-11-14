package com.privatevault.app.security

import android.content.Context
import android.content.pm.PackageManager
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.EntryType
import com.privatevault.app.autofill.RecentLoginFill
import java.security.MessageDigest
import java.security.SecureRandom

internal fun appSigningIdentity(context: Context, packageName: String): String? = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val certificates = info.signingInfo?.apkContentsSigners ?: return null
    if (certificates.isEmpty()) return null
    certificates.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
}.getOrNull()

internal fun loginAuthorized(entry: VaultEntry, packageName: String, identity: String): Boolean =
    entry.type == EntryType.PASSWORD && identity.isNotBlank() &&
        entry.autofillSignatures.lineSequence().any { it == "$packageName=$identity" }

internal fun loginAuthorizedForDestination(entry: VaultEntry, packageName: String, identity: String, origin: String?): Boolean =
    if (origin == null) loginAuthorized(entry, packageName, identity)
    else entry.type == EntryType.PASSWORD && trustedBrowser(packageName, identity) &&
        httpsOrigin(origin) == origin && (httpsOrigin(entry.tertiaryValue) == origin ||
            entry.autofillOrigins.lineSequence().any { it == origin })

/** The https origins a login is saved for: its website (an https URL or a bare host) and its linked origins. Never http. */
internal fun savedLoginOrigins(entry: VaultEntry): List<String> {
    val website = entry.tertiaryValue.trim()
    val primary = httpsOrigin(website)
        ?: website.takeIf { it.isNotEmpty() && "://" !in it }?.let { httpsOrigin("https://$it") }
    return (listOfNotNull(primary) + entry.autofillOrigins.lineSequence().mapNotNull { httpsOrigin(it.trim()) }).distinct()
}

/**
 * The saved origin that makes [entry] a same-site login for [origin], or null. Same site means an equal registrable
 * domain under the full Public Suffix List (private section included, so a.github.io and b.github.io differ) and
 * a different exact origin. Only a verified browser with an exact https origin qualifies; apps and embedded
 * WebViews never do. This is not an authorization: it never feeds keyboard suggestions, and the picker fills
 * such a login once, after an explicit tap, without linking it.
 */
internal fun sameSiteSavedOrigin(
    entry: VaultEntry,
    packageName: String,
    identity: String,
    origin: String?,
    embeddedWebView: Boolean,
): String? {
    if (origin == null || embeddedWebView || entry.type != EntryType.PASSWORD || entry.secondaryValue.isEmpty() ||
        !trustedBrowser(packageName, identity) || httpsOrigin(origin) != origin ||
        loginAuthorizedForDestination(entry, packageName, identity, origin)) return null
    val site = registrableDomain(origin) ?: return null
    return savedLoginOrigins(entry).firstOrNull { it != origin && registrableDomain(it) == site }
}

/** Why the picker may fill a login. [pickerFillAllowed] re-checks it right before every fill. */
internal enum class PickerFillGrant {
    /** The login is linked to this exact app identity or https origin. */
    LINKED,
    /** One tap on a same-site login in the unlocked picker. Fills once; writes no link. */
    SAME_SITE_ONCE,
    /** "Fill once" in the confirmation dialog that names the destination. Fills once; writes no link. */
    CONFIRMED_ONCE,
    /** The linked code for the login just filled here ([codeAfterLoginMatches]). Code fields only; writes no link. */
    CODE_AFTER_LOGIN,
}

/**
 * True when a code request comes from where [remembered] was just filled. Same package and signing identity always.
 * A website also needs a verified browser and an equal registrable domain: code pages often sit on another
 * subdomain, but a.github.io and b.github.io are different sites. Apps and embedded WebViews match on package and
 * identity only (an embedded page's origin is chosen by the app). A website never matches an app, and the reverse.
 */
internal fun codeAfterLoginMatches(
    remembered: RecentLoginFill?,
    packageName: String,
    identity: String,
    origin: String?,
    embeddedWebView: Boolean,
    now: Long,
): Boolean {
    if (remembered == null || remembered.expiresAt <= now || identity.isBlank() ||
        remembered.packageName != packageName || remembered.identity != identity) return false
    val website = origin != null && !embeddedWebView
    if (website != (remembered.origin != null && !remembered.embeddedWebView)) return false
    if (!website) return true
    val requested = origin ?: return false
    val filled = remembered.origin ?: return false
    if (!trustedBrowser(packageName, identity) || httpsOrigin(requested) != requested || httpsOrigin(filled) != filled) return false
    val site = registrableDomain(requested) ?: return false
    return registrableDomain(filled) == site
}

/**
 * The trust check for a picker fill. A one-time grant re-proves its own condition here instead of skipping the
 * check: same site again for [PickerFillGrant.SAME_SITE_ONCE], and for [PickerFillGrant.CONFIRMED_ONCE] the
 * dialog must still show this entry ([confirmedEntryId]) and a website request must come from a verified browser.
 * [PickerFillGrant.CODE_AFTER_LOGIN] needs a code request ([otpRequest]), the unexpired [rememberedLogin] for this
 * entry at a matching destination, and the entry's linked authenticator in the vault ([linkedCodeExists]).
 */
internal fun pickerFillAllowed(
    entry: VaultEntry,
    packageName: String,
    identity: String,
    origin: String?,
    embeddedWebView: Boolean,
    grant: PickerFillGrant,
    confirmedEntryId: String? = null,
    otpRequest: Boolean = false,
    rememberedLogin: RecentLoginFill? = null,
    now: Long = 0L,
    linkedCodeExists: Boolean = false,
): Boolean {
    val authorized = loginAuthorizedForDestination(entry, packageName, identity, origin)
    return when (grant) {
        PickerFillGrant.LINKED -> authorized
        PickerFillGrant.SAME_SITE_ONCE -> authorized ||
            sameSiteSavedOrigin(entry, packageName, identity, origin, embeddedWebView) != null
        PickerFillGrant.CONFIRMED_ONCE -> entry.type == EntryType.PASSWORD && entry.secondaryValue.isNotEmpty() &&
            confirmedEntryId == entry.id &&
            (origin == null || (trustedBrowser(packageName, identity) && httpsOrigin(origin) == origin))
        // Never a username or password: only the code field of the screen that follows this login's fill.
        PickerFillGrant.CODE_AFTER_LOGIN -> otpRequest && entry.type == EntryType.PASSWORD &&
            entry.linkedAuthenticatorId.isNotEmpty() && linkedCodeExists && rememberedLogin?.entryId == entry.id &&
            codeAfterLoginMatches(rememberedLogin, packageName, identity, origin, embeddedWebView, now)
    }
}

internal fun authorizedPasswordSuggestions(
    entries: List<VaultEntry>,
    packageName: String,
    identity: String,
    origin: String?,
): List<VaultEntry> = entries
    .filter { it.type == EntryType.PASSWORD && it.secondaryValue.isNotEmpty() &&
        loginAuthorizedForDestination(it, packageName, identity, origin) }
    .sortedWith(compareByDescending<VaultEntry> { it.favorite }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.primaryValue })

internal data class LoginSuggestionLabel(val title: String, val subtitle: String, val contentDescription: String)

internal fun loginSuggestionLabel(entry: VaultEntry): LoginSuggestionLabel {
    require(entry.type == EntryType.PASSWORD && entry.secondaryValue.isNotEmpty())
    val account = entry.primaryValue.ifBlank { entry.title }
    return LoginSuggestionLabel(account, "•••••••• · ${entry.title}", "Fill $account from Nuvori. Password hidden.")
}

internal fun trustedFillFocus(hasWindowFocus: Boolean, confirmedDialogAction: Boolean, generatedPassword: Boolean): Boolean =
    hasWindowFocus || confirmedDialogAction || generatedPassword

internal fun linkLoginToDestination(
    entry: VaultEntry,
    packageName: String,
    identity: String,
    origin: String?,
): VaultEntry {
    require(entry.type == EntryType.PASSWORD && entry.secondaryValue.isNotEmpty())
    return if (origin == null) {
        require(packageName.isNotBlank() && identity.isNotBlank() && '\n' !in packageName && '=' !in packageName && '\n' !in identity)
        entry.copy(autofillSignatures = (entry.autofillSignatures.lineSequence().filter(String::isNotBlank) +
            "$packageName=$identity").distinct().joinToString("\n"))
    } else {
        require(httpsOrigin(origin) == origin)
        entry.copy(autofillOrigins = (entry.autofillOrigins.lineSequence().filter(String::isNotBlank) + origin)
            .distinct().joinToString("\n"))
    }
}

internal fun generateLoginPassword(length: Int = 24): String {
    require(length in 16..64)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%*-_=+?"
    val random = SecureRandom()
    return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
}
