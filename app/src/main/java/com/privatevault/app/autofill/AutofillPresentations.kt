package com.privatevault.app.autofill

import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.content.IntentSender
import android.graphics.drawable.Icon
import android.view.inputmethod.InlineSuggestionsRequest
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import com.privatevault.app.R
import android.service.autofill.Dataset
import android.service.autofill.Field
import android.service.autofill.FillResponse
import android.service.autofill.InlinePresentation
import android.service.autofill.Presentations
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews

/** Offer keyboard suggestions with a menu fallback for keyboards without inline support. */
@Suppress("DEPRECATION")
internal fun Dataset.Builder.presentField(id: AutofillId, value: AutofillValue?,
    menu: RemoteViews, inline: InlinePresentation?): Dataset.Builder = apply {
    if (Build.VERSION.SDK_INT >= 33) {
        val presentations = Presentations.Builder().setMenuPresentation(menu)
        if (inline != null) presentations.setInlinePresentation(inline)
        val field = Field.Builder().setPresentations(presentations.build())
        if (value != null) field.setValue(value)
        setField(id, field.build())
    } else if (Build.VERSION.SDK_INT >= 30 && inline != null) setValue(id, value, menu, inline)
    else setValue(id, value, menu)
}

/** The menu row of an unlock suggestion: "Nuvori" and [subtitle] (the layout's "Unlock to fill" when null). No vault data. */
internal fun unlockMenuPresentation(context: Context, description: String, subtitle: String? = null): RemoteViews =
    RemoteViews(context.packageName, R.layout.autofill_suggestion).apply {
        subtitle?.let { setTextViewText(R.id.autofill_suggestion_subtitle, it) }
        setContentDescription(R.id.autofill_suggestion_root, description)
    }

/** The keyboard chip of an unlock suggestion, or null when the keyboard offers no usable inline spec. No vault data. */
internal fun unlockInlinePresentation(context: Context, request: InlineSuggestionsRequest?, attribution: PendingIntent,
    subtitle: String, description: String): InlinePresentation? {
    if (Build.VERSION.SDK_INT < 30 || request == null) return null
    return runCatching {
        val spec = request.takeIf { it.maxSuggestionCount > 0 }?.inlinePresentationSpecs?.firstOrNull {
            UiVersions.getVersions(it.style).contains(UiVersions.INLINE_UI_VERSION_1)
        } ?: return@runCatching null
        val content: UiVersions.Content = InlineSuggestionUi.newContentBuilder(attribution)
            .setTitle("Nuvori").setSubtitle(subtitle)
            .setStartIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setContentDescription(description).build()
        InlinePresentation(content.slice, spec, false)
    }.getOrNull()
}

@Suppress("DEPRECATION")
internal fun FillResponse.Builder.requireUnlock(ids: List<AutofillId>, sender: IntentSender,
    menu: RemoteViews, inline: InlinePresentation?): FillResponse.Builder = apply {
    if (Build.VERSION.SDK_INT >= 33) {
        val presentations = Presentations.Builder().setMenuPresentation(menu)
        if (inline != null) presentations.setInlinePresentation(inline)
        setAuthentication(ids.toTypedArray(), sender, presentations.build())
    } else if (Build.VERSION.SDK_INT >= 30 && inline != null) setAuthentication(ids.toTypedArray(), sender, menu, inline)
    else setAuthentication(ids.toTypedArray(), sender, menu)
}
