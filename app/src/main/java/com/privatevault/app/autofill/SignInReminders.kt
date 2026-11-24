package com.privatevault.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.privatevault.app.R
import com.privatevault.app.SignInMarks
import com.privatevault.app.SignInMethodsLogic
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.SignInMethod
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.httpsOrigin
import com.privatevault.app.security.loginAuthorized
import com.privatevault.app.security.registrableDomain
import com.privatevault.app.security.trustedBrowser
import com.privatevault.app.signInMarkBitmap

/** Dataset id of the sign-in reminder in a keyboard strip. Not a login: never counted, never used for a code step. */
internal const val SIGN_IN_REMINDER_DATASET_ID = "nuvori-sign-in-reminder"

/**
 * "Last time: GitHub · octocat": a reminder of the "Continue with" button and account the user chose on this site,
 * remembered by Nuvori for Windows (sync protocol `sign-in-methods-v1`). It never fills a password. Picking it fills
 * the remembered account into the username or email field only when that field was focused and empty; otherwise it
 * opens a short note and fills nothing.
 *
 * The same trust rules as logins: a website counts only as the exact https origin of a verified browser (never an
 * embedded WebView's claim or an unverified page), and an app only through the logins linked to that app.
 */
internal object SignInReminders {
    /** The sites (registrable domains) whose methods may be shown for [request]. */
    fun sites(request: LoginFillRequest, entries: List<VaultEntry>): Set<String> {
        if (request.unverified || request.embeddedWebView || request.otp != null || request.newPasswords.isNotEmpty()) return emptySet()
        val origin = request.origin
        if (origin != null) {
            if (!trustedBrowser(request.packageName, request.identity) || httpsOrigin(origin) != origin) return emptySet()
            return setOfNotNull(registrableDomain(origin))
        }
        return entries.asSequence()
            .filter { it.type == EntryType.PASSWORD && loginAuthorized(it, request.packageName, request.identity) }
            .mapNotNull { SignInMethodsLogic.siteOfLoginUrl(it.tertiaryValue) }
            .toSet()
    }

    /** The one reminder to show: the most recently used method of these sites. */
    fun pick(methods: List<SignInMethod>, sites: Set<String>): SignInMethod? =
        methods.filter { it.site in sites && !it.pendingDelete }.maxWithOrNull(compareBy<SignInMethod> { it.lastUsedAt }.thenBy { it.provider })

    /**
     * The account to fill when the reminder is picked, or null for "fill nothing". Only into the username or email
     * field, only when the user was in it and it was empty, and only an account that reads as a user name or an
     * email address (no spaces: a display name such as "Jamie Doe" is not a login).
     */
    fun fillableAccount(method: SignInMethod, request: LoginFillRequest): String? =
        fillableAccount(method.account, request.username != null, request.usernameFocusedEmpty)

    fun fillableAccount(account: String, usernameField: Boolean, focusedEmpty: Boolean): String? {
        val text = account.trim()
        return text.takeIf { usernameField && focusedEmpty && it.isNotEmpty() && it.none(Char::isWhitespace) }
    }

    fun title(method: SignInMethod): String = "Last time: ${SignInMethodsLogic.label(method)}"

    /** Spoken and shown in the note: what to tap, in plain words. */
    fun note(method: SignInMethod): String {
        val provider = SignInMarks.name(method.provider)
        val account = if (method.account.isEmpty()) "" else " as ${method.account}"
        return "Last time you used “Continue with $provider”$account on ${method.site}."
    }

    /**
     * The reminder dataset: a menu row and, where the keyboard supports it, a chip, both with the provider mark. It
     * carries no password value. [position] is its place among the inline specs.
     */
    @Suppress("DEPRECATION")
    fun dataset(context: Context, request: LoginFillRequest, method: SignInMethod, inlineRequest: InlineSuggestionsRequest?,
        position: Int, attribution: PendingIntent): Dataset? {
        val fieldIds = listOfNotNull(request.username, request.password)
        if (fieldIds.isEmpty()) return null
        val account = fillableAccount(method, request)
        val subtitle = if (account != null) "Sign-in reminder · fills the account only" else "Sign-in reminder · fills nothing"
        val description = note(method) + if (account != null) " Fills $account into the account field." else " Fills nothing."
        val mark = signInMarkBitmap(method.provider, (context.resources.displayMetrics.density * 32).toInt().coerceAtLeast(32))
        val menu = RemoteViews(context.packageName, R.layout.autofill_sign_in_reminder).apply {
            setImageViewBitmap(R.id.autofill_reminder_mark, mark)
            setTextViewText(R.id.autofill_reminder_title, title(method))
            setTextViewText(R.id.autofill_reminder_subtitle, subtitle)
            setContentDescription(R.id.autofill_reminder_root, description)
        }
        val inline = if (Build.VERSION.SDK_INT < 30 || inlineRequest == null) null else runCatching {
            val specs = inlineRequest.inlinePresentationSpecs
            val spec = specs.getOrNull(position) ?: specs.lastOrNull() ?: return@runCatching null
            if (!androidx.autofill.inline.UiVersions.getVersions(spec.style)
                    .contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return@runCatching null
            val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi
                .newContentBuilder(attribution)
                .setTitle("Last time: ${SignInMarks.name(method.provider)}")
                .setSubtitle(method.account.ifEmpty { "Sign-in reminder" })
                .setStartIcon(android.graphics.drawable.Icon.createWithBitmap(mark))
                .setContentDescription(description).build()
            InlinePresentation(content.slice, spec, false)
        }.getOrNull()
        val builder = Dataset.Builder(menu).setId(SIGN_IN_REMINDER_DATASET_ID)
        if (account != null) {
            builder.presentField(requireNotNull(request.username), AutofillValue.forText(account), menu, inline)
        } else {
            // Picking it opens a short note; the note finishes without a result, so Android fills nothing.
            val note = PendingIntent.getActivity(context, 0, Intent(context, SignInReminderActivity::class.java)
                .setAction("vault.signin.note.${method.entityId}")
                .putExtra(SignInReminderActivity.EXTRA_NOTE, note(method)),
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            builder.setAuthentication(note.intentSender)
            fieldIds.forEach { builder.presentField(it, null, menu, inline) }
        }
        return builder.build()
    }
}
