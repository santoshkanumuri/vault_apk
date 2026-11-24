package com.privatevault.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.privatevault.app.MainActivity
import com.privatevault.app.R
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.security.UsageSnapshot
import com.privatevault.app.security.authorizedPasswordSuggestions
import com.privatevault.app.security.loginSuggestionLabel

/**
 * The keyboard strip's marker on the login this device fills most for the site. Plain text: chips take no badges, and
 * a star would read as a favorite.
 */
internal const val MOST_USED_MARK = "Most used"

/**
 * [passkeyLogins]: ids of the logins with a passkey for the same account ("passkey_mark"). Their chip and row say so.
 * [usage]: logins this device fills most come first, and the top one of several says "Most used".
 */
@Suppress("DEPRECATION")
internal fun passwordSuggestions(context: Context, request: LoginFillRequest, entries: List<VaultEntry>,
    inlineRequest: InlineSuggestionsRequest?, passkeyLogins: Set<String> = emptySet(),
    usage: UsageSnapshot = UsageSnapshot.EMPTY, reminder: com.privatevault.app.data.SignInMethod? = null): FillResponse {
    val matches = authorizedPasswordSuggestions(entries, request.packageName, request.identity, request.origin, usage)
    // Reserve a place for the picker and keep the Binder response bounded.
    val slots = if (Build.VERSION.SDK_INT >= 30 && inlineRequest != null)
        inlineRequest.maxSuggestionCount.coerceIn(1, 9) else 9
    val attribution = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
        .setAction("vault.autofill.attribution"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun menu(title: String, subtitle: String, description: String) = RemoteViews(context.packageName, R.layout.autofill_suggestion).apply {
        setTextViewText(R.id.autofill_suggestion_title, title)
        setTextViewText(R.id.autofill_suggestion_subtitle, subtitle)
        setContentDescription(R.id.autofill_suggestion_root, description)
    }
    fun inline(position: Int, title: String, subtitle: String, description: String, passkey: Boolean = false): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30 || inlineRequest == null) return null
        return runCatching {
            val specs = inlineRequest.inlinePresentationSpecs
            val spec = specs.getOrNull(position) ?: specs.lastOrNull() ?: return null
            if (!androidx.autofill.inline.UiVersions.getVersions(spec.style)
                    .contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return null
            val builder = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title).setSubtitle(subtitle)
                .setStartIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setContentDescription(description)
            if (passkey) builder.setEndIcon(android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_passkey))
            val content: androidx.autofill.inline.UiVersions.Content = builder.build()
            InlinePresentation(content.slice, spec, false)
        }.getOrNull()
    }
    val response = FillResponse.Builder()
    // A sign-in reminder ("Last time: GitHub · octocat", SignInReminders) takes one place after the logins.
    val visible = matches.take((slots - 1 - (if (reminder != null) 1 else 0)).coerceAtLeast(0))
    val mostUsedId = usage.mostUsed(visible.map { it.id })
    visible.forEachIndexed { index, entry ->
        val label = loginSuggestionLabel(entry)
        val passkey = entry.id in passkeyLogins
        val mostUsed = entry.id == mostUsedId
        val description = label.contentDescription + (if (mostUsed) ". Most used" else "") + (if (passkey) ". Passkey saved" else "")
        val menu = menu(label.title, listOfNotNull(MOST_USED_MARK.takeIf { mostUsed }, label.subtitle,
            "Passkey".takeIf { passkey }).joinToString(" · "), description)
        val inline = inline(index, label.title, if (mostUsed) "$MOST_USED_MARK · ${entry.title}" else entry.title, description, passkey)
        val dataset = Dataset.Builder(menu).setId(entry.id)
        request.username?.let { dataset.presentField(it, AutofillValue.forText(entry.primaryValue), menu, inline) }
        request.password?.let { dataset.presentField(it, AutofillValue.forText(entry.secondaryValue), menu, inline) }
        response.addDataset(dataset.build())
    }
    reminder?.let { SignInReminders.dataset(context, request, it, inlineRequest, visible.size, attribution) }?.let(response::addDataset)
    val token = PendingLoginFills.put(request)
    try {
        val picker = PendingIntent.getActivity(context, 0, Intent(context, VaultCodesActivity::class.java)
            .setAction("vault.autofill.picker.$token").putExtra("login_fill_token", token),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val title = "Choose another login"
        val description = "Choose another login in Nuvori's account picker"
        // Shown before unlock, so it names the action rather than any saved account.
        val menu = menu(title, "Search all saved logins", description)
        val inline = inline(visible.size + if (reminder != null) 1 else 0, title, "Nuvori", description)
        val dataset = Dataset.Builder(menu).setId(PICKER_DATASET_ID).setAuthentication(picker.intentSender)
        listOfNotNull(request.username, request.password).forEach { dataset.presentField(it, null, menu, inline) }
        response.addDataset(dataset.build())
        request.saveInfo()?.let(response::setSaveInfo)
        // Also stamps where and when this strip was built, so a code screen next can offer the picked login's code.
        response.setClientState(request.suggestionClientState(android.os.SystemClock.elapsedRealtime()))
        return response.build()
    } catch (failure: Exception) {
        PendingLoginFills.remove(token)
        throw failure
    }
}
