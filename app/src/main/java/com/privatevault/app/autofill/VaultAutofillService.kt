package com.privatevault.app.autofill

import android.app.PendingIntent
import android.app.assist.AssistStructure
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.SystemClock
import android.service.autofill.*
import android.view.autofill.AutofillId
import android.widget.RemoteViews
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.trustedBrowser
import com.privatevault.app.security.VaultKeyManager
import java.util.UUID

internal data class LoginFillRequest(val packageName: String, val identity: String,
    val username: AutofillId?, val password: AutofillId?, val otp: AutofillId?, val expiresAt: Long, val origin: String? = null,
    val newPasswords: List<AutofillId> = emptyList(), val embeddedWebView: Boolean = false,
    val previousUsername: AutofillId? = null, val pinPassword: Boolean = false)

private const val SAVE_USERNAME_ID = "save_username_id"
private const val SAVE_ORIGIN = "save_origin"
private const val SAVE_PACKAGE = "save_package"
private const val SAVE_IDENTITY = "save_identity"

internal fun LoginFillRequest.saveClientState(): Bundle? {
    val id = username ?: previousUsername ?: return null
    val website = origin ?: return null
    return Bundle().apply {
        putParcelable(SAVE_USERNAME_ID, id)
        putString(SAVE_ORIGIN, website)
        putString(SAVE_PACKAGE, packageName)
        putString(SAVE_IDENTITY, identity)
    }
}

@Suppress("DEPRECATION")
internal fun Bundle.previousUsernameFor(packageName: String, identity: String, origin: String?): AutofillId? {
    if (origin == null || getString(SAVE_ORIGIN) != origin || getString(SAVE_PACKAGE) != packageName ||
        getString(SAVE_IDENTITY) != identity) return null
    return if (Build.VERSION.SDK_INT >= 33) getParcelable(SAVE_USERNAME_ID, AutofillId::class.java)
        else getParcelable(SAVE_USERNAME_ID)
}

internal fun LoginFillRequest.saveInfo(): SaveInfo? {
    val savedPassword = newPasswords.firstOrNull() ?: password ?: return null
    if (otp != null) return null
    // A native form without a username field still saves its password; the review screen asks which account it belongs to.
    // A lone numeric PIN (app lock, card PIN) is not offered: it is rarely a login and would prompt on every entry.
    if (origin == null && username == null && previousUsername == null && newPasswords.isEmpty())
        return if (pinPassword) null else SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_PASSWORD, arrayOf(savedPassword))
            .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
    val canSave = if (origin != null) username != null || newPasswords.isNotEmpty()
        else username != null && password != null
    if (!canSave && previousUsername == null) return null
    return SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME or SaveInfo.SAVE_DATA_TYPE_PASSWORD,
        (listOfNotNull(username ?: previousUsername, savedPassword) + newPasswords.drop(1)).toTypedArray())
        .setFlags(SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE).build()
}

internal fun LoginFillRequest.delayedUsernameSave(): SaveInfo? =
    if (origin != null && username != null && password == null && newPasswords.isEmpty() && otp == null)
        SaveInfo.Builder(SaveInfo.SAVE_DATA_TYPE_USERNAME, arrayOf(username))
            .setFlags(SaveInfo.FLAG_DELAY_SAVE).build() else null

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
    val newPasswords: List<AutofillId> = emptyList(), val embeddedWebView: Boolean = false, val pinPassword: Boolean = false,
    val usernameGuessed: Boolean = false)

/** Thin adapter: the rules live in [classifyLoginForm], which runs on plain nodes so it can be unit tested. */
internal fun nativeLoginFields(structure: AssistStructure, browser: Boolean = false): NativeLoginFields? {
    val tree = formTree(structure) ?: return null
    val form = classifyLoginForm(tree.roots, browser) ?: return null
    return NativeLoginFields(form.username?.let { tree.ids[it] }, form.password?.let { tree.ids[it] },
        form.otp?.let { tree.ids[it] }, form.origin, form.newPasswords.map { tree.ids[it] }, form.embeddedWebView, form.pinPassword, form.usernameGuessed)
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
            val fields = nativeLoginFields(structure, browser)
            if (fields?.password == null && fields?.otp == null && fields?.newPasswords.isNullOrEmpty()) {
                val store = UnlockedProfileStore(this)
                if (!VaultKeyManager(this).isInitialized) store.clear()
                val profileIds = profileFields(structure, browser)
                if (profileIds != null) profileSuggestions(this, profileIds, store.read(),
                    if (Build.VERSION.SDK_INT >= 30) request.inlineSuggestionsRequest else null)
                    ?.let { return@runCatching it }
            }
            if (fields == null) return@runCatching null
            val previousUsername = if (browser) request.clientState?.previousUsernameFor(destination, identity, fields.origin) else null
            val fill = LoginFillRequest(destination, identity, fields.username, fields.password, fields.otp,
                SystemClock.elapsedRealtime() + 120_000, fields.origin, fields.newPasswords, fields.embeddedWebView,
                previousUsername, fields.pinPassword)
            val keyboard = getSharedPreferences("vault_preferences", MODE_PRIVATE)
                .getBoolean("autofill_keyboard_suggestions", true) && fields.otp == null && fields.newPasswords.isEmpty()
            val token = PendingLoginFills.put(fill)
            cancellationSignal.setOnCancelListener { PendingLoginFills.remove(token) }
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.autofill.$token")
                .putExtra("login_fill_token", token).putExtra("keyboard_suggestions", keyboard)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_MUTABLE)
            val sender = pendingIntent.intentSender
            val presentation = RemoteViews(packageName, com.privatevault.app.R.layout.autofill_suggestion)
            presentation.setContentDescription(com.privatevault.app.R.id.autofill_suggestion_root,
                if (fields.otp != null) "Unlock Nuvori to choose a code" else "Unlock Nuvori to choose a login")
            val dataset = Dataset.Builder(presentation).setAuthentication(sender)
            val inline = if (android.os.Build.VERSION.SDK_INT >= 30) runCatching {
                request.inlineSuggestionsRequest?.takeIf { it.maxSuggestionCount > 0 }?.inlinePresentationSpecs?.firstOrNull {
                    androidx.autofill.inline.UiVersions.getVersions(it.style).contains(androidx.autofill.inline.UiVersions.INLINE_UI_VERSION_1)
                }?.let { spec ->
                    val content: androidx.autofill.inline.UiVersions.Content = androidx.autofill.inline.v1.InlineSuggestionUi.newContentBuilder(pendingIntent)
                        .setTitle("Nuvori").setSubtitle(if (fields.otp != null) "Unlock to choose code" else "Unlock to fill")
                        .setStartIcon(android.graphics.drawable.Icon.createWithResource(this, com.privatevault.app.R.mipmap.ic_launcher))
                        .setContentDescription(if (fields.otp != null) "Unlock Nuvori to choose a code" else "Unlock Nuvori to choose a login").build()
                    InlinePresentation(content.slice, spec, false)
                }
            }.getOrNull() else null
            val fieldIds = listOfNotNull(fields.username, fields.password, fields.otp) + fields.newPasswords
            fieldIds.forEach { dataset.presentField(it, null, presentation, inline) }
            val response = FillResponse.Builder()
            if (keyboard) response.requireUnlock(fieldIds, sender, presentation, inline)
            else response.addDataset(dataset.build())
            (fill.saveInfo() ?: fill.delayedUsernameSave())?.let(response::setSaveInfo)
            fill.saveClientState()?.let(response::setClientState)
            response.build()
        }.getOrNull()
        if (!cancellationSignal.isCanceled) callback.onSuccess(response)
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        var pending: LoginSaveRequest? = null
        var token: String? = null
        try {
            val structure = request.fillContexts.asReversed().map { it.structure }.firstOrNull { candidate ->
                val candidatePackage = candidate.activityComponent?.packageName ?: return@firstOrNull false
                val candidateIdentity = appSigningIdentity(this, candidatePackage) ?: return@firstOrNull false
                val candidateBrowser = trustedBrowser(candidatePackage, candidateIdentity)
                nativeLoginFields(candidate, candidateBrowser)?.let { it.password != null || it.newPasswords.isNotEmpty() } == true
            } ?: error("Missing login form")
            val destination = structure.activityComponent?.packageName ?: error("Missing app")
            val identity = appSigningIdentity(this, destination) ?: error("Missing identity")
            val browser = trustedBrowser(destination, identity)
            val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.invalid"))
            if (!browser && packageManager.queryIntentActivities(browserIntent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)
                    .any { it.activityInfo.packageName == destination }) error("Unsupported browser")
            val fields = nativeLoginFields(structure, browser) ?: error("Unsupported form")
            val savedPassword = fields.newPasswords.firstOrNull() ?: fields.password
            check(savedPassword != null && fields.otp == null)
            val origin = fields.origin
            if (browser) requireNotNull(origin)
            val previousUsername = if (browser) request.clientState?.previousUsernameFor(destination, identity, origin) else null
            val values = mutableMapOf<AutofillId, CharSequence>()
            fun visit(node: AssistStructure.ViewNode) {
                if (node.autofillId == fields.username || node.autofillId == previousUsername || node.autofillId == savedPassword || node.autofillId in fields.newPasswords) {
                    node.autofillValue?.takeIf { it.isText }?.textValue?.let { values[requireNotNull(node.autofillId)] = it }
                }
                for (i in 0 until node.childCount) visit(node.getChildAt(i))
            }
            for (context in request.fillContexts) {
                if (context.structure.activityComponent?.packageName != destination) continue
                if (browser && nativeLoginFields(context.structure, true)?.origin != origin) continue
                for (i in 0 until context.structure.windowNodeCount) visit(context.structure.getWindowNodeAt(i).rootViewNode)
            }
            val selected = request.clientState?.takeIf {
                browser && it.getString("selected_origin") == origin && it.getString("selected_browser") == destination
            }?.getString("selected_username")
            // No readable account: the review screen asks for it. A blank field counts as missing.
            val formUsername = values[fields.username]?.toString()?.takeIf { it.isNotBlank() }
            val username = listOf(formUsername, values[previousUsername]?.toString(), selected)
                .firstOrNull { !it.isNullOrBlank() }.orEmpty()
            // A guessed account box (the field before a lone password) may hold a server or workspace name: confirm it first.
            val confirmed = formUsername == null || !fields.usernameGuessed
            val password = requireNotNull(values[savedPassword])
            check(fields.newPasswords.all { values[it]?.toString() == password.toString() })
            require(username.length <= 1024 && password.isNotEmpty() && password.length <= 4096)
            pending = LoginSaveRequest(destination, identity, origin, username, CharArray(password.length) { password[it] },
                SystemClock.elapsedRealtime() + 120_000, usernameConfirmed = confirmed)
            token = PendingLoginSaves.put(pending)
            val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.save.$token").putExtra("login_save_token", token)
            val sender = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE).intentSender
            callback.onSuccess(sender)
        } catch (_: Exception) {
            token?.let(PendingLoginSaves::remove)
            pending?.clear()
            callback.onFailure("Could not prepare this login. Save it manually in Nuvori.")
        }
    }
}
