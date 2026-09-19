package com.privatevault.app.autofill;

import android.app.Activity;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.View;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Runs as a separate app. Uses platform classes only, not the target APK's Kotlin runtime. */
public class NativeLoginTestActivity extends Activity {
    private LinearLayout layout;

    private EditText field(String label, String hint, int type) {
        EditText view = new EditText(this);
        view.setId(View.generateViewId());
        view.setContentDescription(label);
        view.setHint(label);
        view.setInputType(type);
        view.setAutofillHints(hint);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        layout.addView(view);
        return view;
    }

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(24, 100, 24, 24);
        boolean otp = getIntent().getBooleanExtra("otp", false);
        EditText focus;
        if (otp) {
            focus = field("Test code", "2faAppOTPCode", InputType.TYPE_CLASS_NUMBER);
        } else {
            field("Test username", "username", InputType.TYPE_CLASS_TEXT);
            focus = field("Test password", "password", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            focus.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(Editable s) {
                    focus.setContentDescription("test-password-123".equals(s.toString()) ? "Verified test password" : "Test password");
                }
            });
        }
        Button request = new Button(this);
        request.setText("Request fill");
        request.setOnClickListener(v -> {
            focus.requestFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(focus, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            focus.postDelayed(() -> getSystemService(AutofillManager.class).requestAutofill(focus), 300);
        });
        layout.addView(request);
        setContentView(layout);
    }
}
