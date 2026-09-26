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
        if (getIntent().getBooleanExtra("webview", false)) {
            android.webkit.WebView web = new android.webkit.WebView(this);
            web.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
            web.getSettings().setJavaScriptEnabled(true);
            web.loadDataWithBaseURL("https://fill.dev/", "<html><meta name='viewport' content='width=device-width, initial-scale=1'>" +
                "<body><form><label>Test username<input id='username' autocomplete='username'></label><br>" +
                "<label>Test password<input id='password' type='password' autocomplete='current-password'></label>" +
                "</form></body></html>", "text/html", "UTF-8", null);
            Button check = new Button(this);
            check.setAllCaps(false);
            check.setText("Check fill");
            check.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            check.setOnClickListener(v -> web.evaluateJavascript(
                "document.getElementById('password').value === 'test-password-123' && document.getElementById('username').value === 'test-account'",
                result -> check.setText("true".equals(result) ? "Verified WebView password" : "Not filled")));
            layout.addView(check);
            layout.addView(web, new LinearLayout.LayoutParams(-1, -1));
            setContentView(layout);
            return;
        }
        boolean otp = getIntent().getBooleanExtra("otp", false);
        boolean profile = getIntent().getBooleanExtra("profile", false);
        EditText focus;
        if (profile) {
            focus = field("Profile email", "emailAddress", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            field("Profile phone", "phone", InputType.TYPE_CLASS_PHONE);
            field("Profile address", "postalAddress", InputType.TYPE_CLASS_TEXT);
            field("Profile city", "addressLevel2", InputType.TYPE_CLASS_TEXT);
            field("Profile postcode", "postalCode", InputType.TYPE_CLASS_NUMBER);
        } else if (otp) {
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
        request.setText(getIntent().getStringExtra("requestLabel") == null ? "Request fill" : getIntent().getStringExtra("requestLabel"));
        request.setOnClickListener(v -> {
            focus.requestFocus();
            getSystemService(android.view.inputmethod.InputMethodManager.class).showSoftInput(focus, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
            focus.postDelayed(() -> getSystemService(AutofillManager.class).requestAutofill(focus), 300);
        });
        if (profile) layout.addView(request, 0);
        else layout.addView(request);
        if (!otp) {
            Button login = new Button(this);
            login.setId(View.generateViewId());
            login.setText("Login");
            login.setOnClickListener(v -> {
                if (getIntent().getBooleanExtra("retainForm", false)) {
                    login.setText("Submitted");
                    getSystemService(AutofillManager.class).commit();
                }
                else layout.setVisibility(View.GONE);
            });
            layout.addView(login);
        }
        setContentView(layout);
    }
}
