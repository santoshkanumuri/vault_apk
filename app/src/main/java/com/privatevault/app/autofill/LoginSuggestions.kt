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
import com.privatevault.app.security.authorizedPasswordSuggestions
import com.privatevault.app.security.loginSuggestionLabel

@Suppress("DEPRECATION")
internal fun passwordSuggestions(context: Context, request: LoginFillRequest, entries: List<VaultEntry>,
    inlineRequest: InlineSuggestionsRequest?): FillResponse {
    val matches = authorizedPasswordSuggestions(entries, request.packageName, request.identity, request.origin)
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
    fun inline(position: Int, title: String, subtitle: String, description: String): InlinePresentation? {
        if (Build.VERSION.SDK_INT < 30 || inlineRequest == null) return null
        return runCatching {
            val specs = inlineRequest.inlinePresentationSpecs
            val spec = specs.getOrNull(position) ?: specs.lastOrNull() ?: return null
            if (!androidx.autofill.inline.UiVersions.getVersions(spec.style)
                    .contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return null
            val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(attribution)
                .setTitle(title).setSubtitle(subtitle)
                .setStartIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setContentDescription(description).build()
            InlinePresentation(content.slice, spec, false)
        }.getOrNull()
    }
    val response = FillResponse.Builder()
    val visible = matches.take(slots - 1)
    visible.forEachIndexed { index, entry ->
        val label = loginSuggestionLabel(entry)
        val menu = menu(label.title, label.subtitle, label.contentDescription)
        val inline = inline(index, label.title, entry.title, label.contentDescription)
        val dataset = Dataset.Builder(menu).setId(entry.id)
        request.username?.let { dataset.presentField(it, AutofillValue.forText(entry.primaryValue), menu, inline) }
        request.password?.let { dataset.presentField(it, AutofillValue.forText(entry.secondaryValue), menu, inline) }
        response.addDataset(dataset.build())
    }
    val token = PendingLoginFills.put(request)
    try {
        val picker = PendingIntent.getActivity(context, 0, Intent(context, VaultCodesActivity::class.java)
            .setAction("vault.autofill.picker.$token").putExtra("login_fill_token", token),
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
        val title = "Choose another login"
        val description = "Choose another login in Nuvori's account picker"
        // Shown before unlock, so it names the action rather than any saved account.
        val menu = menu(title, "Search all saved logins", description)
        val inline = inline(visible.size, title, "Nuvori", description)
        val dataset = Dataset.Builder(menu).setId("nuvori-picker").setAuthentication(picker.intentSender)
        listOfNotNull(request.username, request.password).forEach { dataset.presentField(it, null, menu, inline) }
        response.addDataset(dataset.build())
        request.saveInfo()?.let(response::setSaveInfo)
        request.saveClientState()?.let(response::setClientState)
        return response.build()
    } catch (failure: Exception) {
        PendingLoginFills.remove(token)
        throw failure
    }
}
