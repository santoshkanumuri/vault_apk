package com.privatevault.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.autofill.Dataset
import android.service.autofill.InlinePresentation
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.view.inputmethod.InlineSuggestionsRequest
import android.widget.RemoteViews
import com.privatevault.app.MainActivity
import com.privatevault.app.R

/**
 * One suggestion per saved profile, from the device copy. Like the system's own Autofill, each field shows the value
 * it receives as the main line ("alice@example.com" in the email field), and the profile's name with the other values
 * it fills below ("Home · Alice Smith · +1 555 0100").
 */
@Suppress("DEPRECATION")
internal fun profileDatasets(context: Context, fields: Map<AutofillId, ProfileField>,
    profiles: List<Pair<String, AutofillProfile>>, inlineRequest: InlineSuggestionsRequest?,
    options: Map<AutofillId, List<String>> = emptyMap()): List<Dataset> {
    val datasets = ArrayList<Dataset>()
    val slots = if (Build.VERSION.SDK_INT >= 30 && inlineRequest != null)
        inlineRequest.maxSuggestionCount.coerceIn(1, 8) else 8
    val attribution = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java)
        .setAction("vault.autofill.profiles"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    profiles.take(slots).forEachIndexed { index, (title, profile) ->
        // A drop-down without an entry for this address is left as it is.
        val values = profileAutofillValues(profile, fields, options)
        val filled = profileFillValues(profile, fields).filterKeys(values::containsKey)
        if (filled.isEmpty()) return@forEachIndexed
        val kinds = filled.keys.map(fields::getValue)
        val name = title.ifBlank { "Contact details" }
        val rows = filled.mapValues { (_, value) ->
            val main = value.trim()
            val sub = listOf(name, profileFillPreview(profile, kinds, except = main)).filter(String::isNotBlank).joinToString(" · ")
            val description = "Fill $main from $name"
            val menu = RemoteViews(context.packageName, R.layout.autofill_suggestion).apply {
                setTextViewText(R.id.autofill_suggestion_title, main)
                setTextViewText(R.id.autofill_suggestion_subtitle, sub)
                setContentDescription(R.id.autofill_suggestion_root, description)
            }
            menu to profileInlinePresentation(context, inlineRequest, index, attribution, main, sub, description)
        }
        val dataset = Dataset.Builder(rows.values.first().first).setId("profile-$index")
        filled.keys.forEach { id ->
            val (menu, inline) = rows.getValue(id)
            dataset.presentField(id, values.getValue(id), menu, inline)
        }
        datasets.add(dataset.build())
    }
    return datasets
}

private fun profileInlinePresentation(context: Context, request: InlineSuggestionsRequest?, index: Int,
    attribution: PendingIntent, title: String, subtitle: String, description: String): InlinePresentation? {
    if (Build.VERSION.SDK_INT < 30 || request == null) return null
    return runCatching {
        val specs = request.inlinePresentationSpecs
        val spec = specs.getOrNull(index) ?: specs.lastOrNull() ?: return@runCatching null
        if (!androidx.autofill.inline.UiVersions.getVersions(spec.style)
                .contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)) return@runCatching null
        val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi
            .newContentBuilder(attribution).setTitle(title).setSubtitle(subtitle)
            .setStartIcon(android.graphics.drawable.Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setContentDescription(description).build()
        InlinePresentation(content.slice, spec, false)
    }.getOrNull()
}
