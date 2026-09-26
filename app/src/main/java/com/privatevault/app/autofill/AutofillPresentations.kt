package com.privatevault.app.autofill

import android.os.Build
import android.content.IntentSender
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
