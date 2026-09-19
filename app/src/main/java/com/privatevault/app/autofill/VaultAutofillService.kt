package com.privatevault.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.*
import android.text.InputType
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.trustedBrowser
import com.privatevault.app.security.httpsOrigin
import java.util.UUID

internal data class LoginFillRequest(val packageName: String, val identity: String,
    val username: AutofillId?, val password: AutofillId?, val otp: AutofillId?, val expiresAt: Long, val origin: String? = null,
    val newPasswords: List<AutofillId> = emptyList())

/** Only field IDs and verified destination identity. No form contents or vault data. */
internal object PendingLoginFills {
    private val requests = mutableMapOf<String, LoginFillRequest>()
    @Synchronized fun put(request: LoginFillRequest): String {
        requests.entries.removeAll { it.value.expiresAt <= SystemClock.elapsedRealtime() }
        if (requests.size >= 8) requests.remove(requests.keys.first())
        return UUID.randomUUID().toString().also { requests[it] = request }
    }
    @Synchronized fun take(token: String?): LoginFillRequest? = requests.remove(token)?.takeIf { it.expiresAt > SystemClock.elapsedRealtime() }
    @Synchronized fun remove(token: String) { requests.remove(token) }
}

internal data class NativeLoginFields(val username: AutofillId?, val password: AutofillId?, val otp: AutofillId?, val origin: String? = null,
    val newPasswords: List<AutofillId> = emptyList())

internal fun nativeLoginFields(structure: AssistStructure, browser: Boolean = false): NativeLoginFields? {
    val usernames = mutableListOf<AutofillId>()
    val passwords = mutableListOf<AutofillId>()
    val newPasswords = mutableListOf<AutofillId>()
    val codes = mutableListOf<AutofillId>()
    var unsafe = false
    var count = 0
    var forms = 0
    var unlabelledPassword = false
    val origins = mutableSetOf<String>()
    fun visit(node: AssistStructure.ViewNode, depth: Int, parentOrigin: String? = null, parentVisible: Boolean = true) {
        if (++count > 2000 || depth > 40) { unsafe = true; return }
        val web = !node.webDomain.isNullOrBlank() || node.htmlInfo != null || node.className?.contains("WebView", true) == true
        if (web && !browser) unsafe = true
        var origin = parentOrigin
        if (!node.webDomain.isNullOrBlank()) {
            origin = if (node.webScheme == "https") httpsOrigin("https://${node.webDomain}") else null
            if (origin == null) unsafe = true else origins.add(origin)
        }
        val attributes = node.htmlInfo?.attributes.orEmpty().associate { it.first.lowercase(java.util.Locale.ROOT) to it.second }
        if (node.htmlInfo?.tag?.lowercase(java.util.Locale.ROOT) in setOf("iframe", "frame")) unsafe = true
        if (node.htmlInfo?.tag.equals("form", true)) forms++
        val hints = node.autofillHints.orEmpty().toSet() + attributes["autocomplete"].orEmpty().lowercase(java.util.Locale.ROOT).split(' ')
        if (!browser && hints.any { it == "newPassword" || it == "newUsername" || it == "new-password" }) unsafe = true
        val visible = parentVisible && node.visibility == android.view.View.VISIBLE
        if (visible && node.isEnabled) node.autofillId?.let { id ->
            val variation = node.inputType and InputType.TYPE_MASK_VARIATION
            val htmlType = attributes["type"]?.lowercase(java.util.Locale.ROOT)
            val username = isUsernameField(hints, attributes,
                node.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT &&
                    variation in setOf(InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS), browser)
            val recognized = hints.any { it in setOf("2faAppOTPCode", "oneTimeCode", "one-time-code", "password", "current-password", "newPassword", "new-password", "username", "newUsername", "emailAddress") } ||
                username || htmlType == "password" || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            if (browser && recognized && origin == null) unsafe = true
            when {
                hints.any { it == "newPassword" || it == "new-password" } -> {
                    if (origin == null) unsafe = true
                    newPasswords.add(id)
                }
                hints.any { it == "2faAppOTPCode" || it == "oneTimeCode" || it == "one-time-code" } -> codes.add(id)
                "password" in hints || "current-password" in hints || htmlType == "password" || variation == InputType.TYPE_TEXT_VARIATION_PASSWORD || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD -> {
                    passwords.add(id)
                    if ("current-password" !in hints) unlabelledPassword = true
                }
                username -> usernames.add(id)
            }
        }
        for (i in 0 until node.childCount) visit(node.getChildAt(i), depth + 1, origin, visible)
    }
    for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode, 0)
    if (unsafe || passwords.size > 1 || newPasswords.size > 2 || usernames.size > 1 || codes.size > 1) return null
    if (browser && (origins.size != 1 || forms > 1)) return null
    if (newPasswords.isNotEmpty() && unlabelledPassword) return null
    // Separate OTP screens only: do not fill multiple factors in a mixed form.
    if (codes.isNotEmpty() && (passwords.isNotEmpty() || newPasswords.isNotEmpty() || usernames.isNotEmpty())) return null
    if (passwords.isEmpty() && newPasswords.isEmpty() && codes.isEmpty() && !(browser && usernames.size == 1)) return null
    return NativeLoginFields(usernames.singleOrNull(), passwords.singleOrNull(), codes.singleOrNull(), if (browser) origins.single() else null, newPasswords)
}

class VaultAutofillService : AutofillService() {
    @Suppress("DEPRECATION")
    override fun onFillRequest(request: FillRequest, cancellationSignal: CancellationSignal, callback: FillCallback) {
        if (cancellationSignal.isCanceled) return
        val response = runCatching {
            val structure = request.fillContexts.lastOrNull()?.structure ?: return@runCatching null
            val destination = structure.activityComponent?.packageName ?: return@runCatching null
            if (destination == packageName) return@runCatching null
            val identity = appSigningIdentity(this, destination) ?: return@runCatching null
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.invalid"))
            val browser = trustedBrowser(destination, identity)
            if (!browser && packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo.packageName == destination }) return@runCatching null
            val fields = nativeLoginFields(structure, browser) ?: return@runCatching null
            val token = PendingLoginFills.put(LoginFillRequest(destination, identity, fields.username, fields.password, fields.otp, SystemClock.elapsedRealtime() + 120_000, fields.origin, fields.newPasswords))
            cancellationSignal.setOnCancelListener { PendingLoginFills.remove(token) }
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.autofill.$token").putExtra("login_fill_token", token)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
            val sender = pendingIntent.intentSender
            val presentation = RemoteViews(packageName, com.privatevault.app.R.layout.autofill_suggestion)
            val dataset = Dataset.Builder(presentation).setAuthentication(sender)
            val inline = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
                request.inlineSuggestionsRequest?.takeIf { it.maxSuggestionCount > 0 }?.inlinePresentationSpecs?.firstOrNull {
                    androidx.autofill.inline.UiVersions.getVersions(it.style).contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)
                }?.let { spec ->
                    val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(pendingIntent)
                        .setTitle("Private Vault").setSubtitle("Unlock to fill")
                        .setStartIcon(android.graphics.drawable.Icon.createWithResource(this, com.privatevault.app.R.mipmap.ic_launcher))
                        .setContentDescription("Unlock Private Vault to choose a login").build()
                    InlinePresentation(content.slice, spec, false)
                }
            }.getOrNull() else null
            (listOfNotNull(fields.username, fields.password, fields.otp) + fields.newPasswords).forEach {
                if (android.os.Build.VERSION.SDK_INT >= 30 && inline != null) dataset.setValue(it, null, presentation, inline)
                else dataset.setValue(it, null)
            }
            val response = FillResponse.Builder().addDataset(dataset.build())
            val savedPassword = fields.newPasswords.firstOrNull() ?: fields.password
            if (browser && savedPassword != null && fields.otp == null && (fields.username != null || fields.newPasswords.isNotEmpty())) {
                response.setSaveInfo(SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                    (listOfNotNull(fields.username, savedPassword) + fields.newPasswords.drop(1)).toTypedArray()).setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build())
            }
            response.build()
        }.getOrNull()
        if (!cancellationSignal.isCanceled) callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        var pending: LoginSaveRequest? = null
        var token: String? = null
        try {
            val structure = request.fillContexts.lastOrNull()?.structure ?: error("Missing form")
            val destination = structure.activityComponent?.packageName ?: error("Missing browser")
            val identity = appSigningIdentity(this, destination) ?: error("Missing identity")
            check(trustedBrowser(destination, identity))
            val fields = nativeLoginFields(structure, browser = true) ?: error("Unsupported form")
            val savedPassword = fields.newPasswords.firstOrNull() ?: fields.password
            check(savedPassword != null && fields.otp == null)
            val origin = requireNotNull(fields.origin)
            val values = mutableMapOf<AutofillId, CharSequence>()
            fun visit(node: AssistStructure.ViewNode) {
                if (node.autofillId == fields.username || node.autofillId == savedPassword || node.autofillId in fields.newPasswords) {
                    node.autofillValue?.takeIf { it.isText }?.textValue?.let { values[requireNotNull(node.autofillId)] = it }
                }
                for (i in 0 until node.childCount) visit(node.getChildAt(i))
            }
            for (i in 0 until structure.windowNodeCount) visit(structure.getWindowNodeAt(i).rootViewNode)
            val username = values[fields.username]?.toString() ?: request.clientState?.takeIf {
                it.getString("selected_origin") == origin && it.getString("selected_browser") == destination
            }?.getString("selected_username") ?: error("Select an account before changing its password")
            val password = requireNotNull(values[savedPassword])
            check(fields.newPasswords.all { values[it]?.toString() == password.toString() })
            require(username.isNotBlank() && username.length <= 1024 && password.isNotEmpty() && password.length <= 4096)
            pending = LoginSaveRequest(destination, identity, origin, username, CharArray(password.length) { password[it] }, SystemClock.elapsedRealtime() + 120_000)
            token = PendingLoginSaves.put(pending)
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.save.$token").putExtra("login_save_token", token)
            val sender = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).intentSender
            callback.onSuccess(sender)
        } catch (_: Exception) {
            token?.let(PendingLoginSaves::remove)
            pending?.clear()
            callback.onFailure("Could not prepare this login. Save it manually in Private Vault.")
        }
    }
}
