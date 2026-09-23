package com.privatevault.app.passkeys

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.credentials.*
import androidx.credentials.exceptions.*
import androidx.credentials.provider.*
import com.privatevault.app.VaultCodesActivity
import com.privatevault.app.security.appSigningIdentity
import com.privatevault.app.security.trustedBrowser
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Base64
import java.util.UUID

@RequiresApi(34)
internal data class CredentialDestination(val packageName: String, val identity: String, val origin: String?)

@RequiresApi(34)
internal fun credentialDestination(context: Context, info: CallingAppInfo): CredentialDestination {
    val identity = requireNotNull(appSigningIdentity(context, info.packageName))
    if (!info.isOriginPopulated()) {
        val signers = info.signingInfo.apkContentsSigners
        require(signers.size == 1)
        val hash = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
        require(hash.joinToString("") { "%02x".format(it.toInt() and 0xff) } == identity)
        return CredentialDestination(info.packageName, identity, null)
    }
    require(trustedBrowser(info.packageName, identity))
    val fingerprint = identity.chunked(2).joinToString(":").uppercase(java.util.Locale.ROOT)
    val allowlist = JSONObject().put("apps", org.json.JSONArray().put(JSONObject().put("type", "android").put("info",
        JSONObject().put("package_name", info.packageName).put("signatures", org.json.JSONArray().put(
            JSONObject().put("build", "release").put("cert_fingerprint_sha256", fingerprint))))))
    val origin = requireNotNull(info.getOrigin(allowlist.toString()))
    val canonical = requireNotNull(com.privatevault.app.security.httpsOrigin(origin))
    require(origin == canonical || origin == "$canonical/")
    return CredentialDestination(info.packageName, identity, canonical)
}

@RequiresApi(34)
internal fun passkeyOrigin(context: Context, info: CallingAppInfo, rpId: String): String =
    credentialDestination(context, info).origin ?: run {
        val identity = requireNotNull(appSigningIdentity(context, info.packageName))
        val hash = identity.substringBefore(',').chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        "android:apk-key-hash:${Base64.getUrlEncoder().withoutPadding().encodeToString(hash)}"
    }

@RequiresApi(34)
class VaultCredentialService : CredentialProviderService() {
    private fun action(create: Boolean): PendingIntent {
        val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.passkey.${UUID.randomUUID()}")
            .putExtra("passkey_create", create).putExtra("passkey_issued", SystemClock.elapsedRealtime())
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    private fun passwordAction(create: Boolean): PendingIntent {
        val intent = Intent(this, VaultCodesActivity::class.java)
            .setAction("vault.password.${UUID.randomUUID()}")
            .putExtra(if (create) "credential_password_create" else "credential_password_get", true)
            .putExtra("credential_password_issued", SystemClock.elapsedRealtime())
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    override fun onBeginCreateCredentialRequest(request: BeginCreateCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>) {
        if (cancellationSignal.isCanceled) return
        try {
            when (request) {
                is BeginCreatePublicKeyCredentialRequest -> {
                    val rpId = JSONObject(request.requestJson).getJSONObject("rp").getString("id")
                    val origin = passkeyOrigin(this, requireNotNull(request.callingAppInfo), rpId)
                    PasskeyCrypto.request(request.requestJson, origin, true)
                    callback.onResult(BeginCreateCredentialResponse(listOf(CreateEntry("Nuvori", action(true)))))
                }
                is BeginCreatePasswordCredentialRequest -> {
                    credentialDestination(this, requireNotNull(request.callingAppInfo))
                    callback.onResult(BeginCreateCredentialResponse(listOf(CreateEntry("Save in Nuvori", passwordAction(true)))))
                }
                else -> callback.onError(CreateCredentialUnknownException("This credential type is not supported."))
            }
        } catch (_: Exception) { callback.onError(CreateCredentialUnknownException("This passkey request is not supported.")) }
    }
    override fun onBeginGetCredentialRequest(request: BeginGetCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>) {
        if (cancellationSignal.isCanceled) return
        try {
            val builder = BeginGetCredentialResponse.Builder()
            request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>().mapNotNull { option ->
                runCatching {
                    val rpId = JSONObject(option.requestJson).getString("rpId")
                    val origin = passkeyOrigin(this, requireNotNull(request.callingAppInfo), rpId)
                    PasskeyCrypto.request(option.requestJson, origin, false)
                    // No account names or credential IDs leave the encrypted vault before authentication.
                    PublicKeyCredentialEntry(this, "Unlock Nuvori", action(false), option)
                }.getOrNull()
            }.forEach(builder::addCredentialEntry)
            if (request.beginGetCredentialOptions.any { it is BeginGetPasswordOption }) {
                credentialDestination(this, requireNotNull(request.callingAppInfo))
                builder.addAction(Action("Search Nuvori passwords", passwordAction(false), "Unlock to choose a saved login"))
            }
            callback.onResult(builder.build())
        } catch (_: Exception) { callback.onError(GetCredentialUnknownException("This passkey request is not supported.")) }
    }
    override fun onClearCredentialStateRequest(request: ProviderClearCredentialStateRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>) { if (!cancellationSignal.isCanceled) callback.onResult(null) }
}

@RequiresApi(34)
internal class PasswordCredentialOperation private constructor(
    val create: Boolean,
    val destination: CredentialDestination,
    val username: String = "",
    val password: String = "",
    private val caller: CallingAppInfo,
) {
    fun revalidate(context: Context) {
        require(credentialDestination(context, caller) == destination)
    }

    companion object {
        fun from(context: Context, intent: Intent): PasswordCredentialOperation {
            val elapsed = SystemClock.elapsedRealtime() - intent.getLongExtra("credential_password_issued", Long.MIN_VALUE)
            require(elapsed in 0..120_000)
            return if (intent.hasExtra("credential_password_create")) {
                val providerRequest = requireNotNull(PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent))
                val request = providerRequest.callingRequest as CreatePasswordRequest
                val caller = providerRequest.callingAppInfo
                PasswordCredentialOperation(
                    true,
                    credentialDestination(context, caller),
                    request.id,
                    request.password,
                    caller,
                )
            } else {
                val request = requireNotNull(PendingIntentHandler.retrieveBeginGetCredentialRequest(intent))
                require(request.beginGetCredentialOptions.any { it is BeginGetPasswordOption })
                val caller = requireNotNull(request.callingAppInfo)
                PasswordCredentialOperation(false, credentialDestination(context, caller), caller = caller)
            }
        }
    }
}

@RequiresApi(34)
internal class PasskeyOperation private constructor(val create: Boolean, val input: JSONObject, val origin: String,
    val clientHash: ByteArray?, private val caller: CallingAppInfo) {
    fun displaySource(context: Context): String {
        val rpId = if (create) input.getJSONObject("rp").getString("id") else input.getString("rpId")
        if (origin.startsWith("https://")) return origin
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(caller.packageName, 0)).toString()
        }.getOrDefault(caller.packageName)
        return "$label (${caller.packageName}) requests $rpId"
    }
    fun revalidate(context: Context) {
        val rpId = if (create) input.getJSONObject("rp").getString("id") else input.getString("rpId")
        require(passkeyOrigin(context, caller, rpId) == origin)
    }
    companion object {
        fun from(context: Context, intent: Intent): PasskeyOperation {
            val elapsed = SystemClock.elapsedRealtime() - intent.getLongExtra("passkey_issued", Long.MIN_VALUE)
            require(elapsed in 0..120_000)
            val create = intent.getBooleanExtra("passkey_create", false)
            val caller: CallingAppInfo
            val json: String
            val hash: ByteArray?
            if (create) {
                val request = requireNotNull(PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent))
                val option = request.callingRequest as CreatePublicKeyCredentialRequest
                caller = request.callingAppInfo; json = option.requestJson; hash = option.clientDataHash
            } else {
                val request = requireNotNull(PendingIntentHandler.retrieveProviderGetCredentialRequest(intent))
                val option = request.credentialOptions.filterIsInstance<GetPublicKeyCredentialOption>().single()
                caller = request.callingAppInfo; json = option.requestJson; hash = option.clientDataHash
            }
            val input = JSONObject(json)
            val rpId = if (create) input.getJSONObject("rp").getString("id") else input.getString("rpId")
            val origin = passkeyOrigin(context, caller, rpId)
            if (origin.startsWith("https://")) require(hash?.size == 32) else require(hash == null)
            return PasskeyOperation(create, PasskeyCrypto.request(json, origin, create), origin, hash?.copyOf(), caller)
        }
    }
}
