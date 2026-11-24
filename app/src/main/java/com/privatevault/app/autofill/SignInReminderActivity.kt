package com.privatevault.app.autofill

import android.app.Activity
import android.os.Bundle
import android.widget.Toast

/**
 * The short note behind a sign-in reminder that fills nothing ([SignInReminders]). It shows the reminder as a toast
 * and finishes without an Autofill result, so Android fills no field. It reads no vault data: the text comes from
 * the suggestion that the unlocked picker built.
 */
class SignInReminderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val note = intent.getStringExtra(EXTRA_NOTE)?.take(400)
        if (!note.isNullOrBlank()) Toast.makeText(applicationContext, note, Toast.LENGTH_LONG).show()
        setResult(RESULT_CANCELED)
        finish()
    }

    companion object {
        const val EXTRA_NOTE = "sign_in_note"
    }
}
