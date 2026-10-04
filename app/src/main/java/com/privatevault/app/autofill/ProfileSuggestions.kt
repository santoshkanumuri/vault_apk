package com.privatevault.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.privatevault.app.MainActivity
import com.privatevault.app.R

internal fun profileSuggestions(context: Context, fields: Map<AutofillId, ProfileField>,
    profiles: List<Pair<String, AutofillProfile>>, inlineRequest: InlineSuggestionsRequest?): FillResponse? {
    val response = FillResponse.Builder()
    val slots = if (Build.VERSION.SDK_INT >= 30 && inlineRequest != null)
        inlineRequest.maxSuggestionCount.coerceIn(1, 8) else 8
    val attribution = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
        .setAction("vault.autofill.profiles"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    var added = 0
    profiles.take(slots).forEachIndexed { index, (title, profile) ->
        // Which kinds this profile fills, by label only: the stored values stay out of the suggestion.
        val filled = fields.filter { (_, field) -> profile.value(field).isNotBlank() }
        if (filled.isEmpty()) return@forEachIndexed
        val summary = profileFillSummary(filled.values)
        val menu = RemoteViews(context.packageName, R.layout.autofill_suggestion).apply {
            setTextViewText(R.id.autofill_suggestion_title, title)
            setTextViewText(R.id.autofill_suggestion_subtitle, summary)
            setContentDescription(R.id.autofill_suggestion_root, "Fill details from $title")
        }
        val inline = if (Build.VERSION.SDK_INT >= 30 && inlineRequest != null) runCatching {
            val specs = inlineRequest.inlinePresentationSpecs
            val spec = specs.getOrNull(index) ?: specs.lastOrNull() ?: return@runCatching null
            if (!androidx.autofill.inline.UiVersions.getVersions(spec.style)
                    .contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return@runCatching null
            val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi
                .newContentBuilder(attribution).setTitle(title).setSubtitle(summary)
                .setStartIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setContentDescription("Fill details from $title").build()
            InlinePresentation(content.slice, spec, false)
        }.getOrNull() else null
        val dataset = Dataset.Builder(menu).setId("profile-$index")
        filled.forEach { (id, field) ->
            dataset.presentField(id, AutofillValue.forText(profile.value(field)), menu, inline)
        }
        response.addDataset(dataset.build()); added++
    }
    return if (added > 0) response.build() else null
}
