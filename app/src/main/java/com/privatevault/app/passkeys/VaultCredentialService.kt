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
import java.util.UUID

@RequiresApi(34)
internal fun browserOrigin(context: Context, info: CallingAppInfo): String {
    val identity = requireNotNull(appSigningIdentity(context, info.packageName))
    require(trustedBrowser(info.packageName, identity))
    val fingerprint = identity.chunked(2).joinToString(":").uppercase(java.util.Locale.ROOT)
    val allowlist = JSONObject().put("apps", org.json.JSONArray().put(JSONObject().put("type", "android").put("info",
        JSONObject().put("package_name", info.packageName).put("signatures", org.json.JSONArray().put(
            JSONObject().put("build", "release").put("cert_fingerprint_sha256", fingerprint))))))
    val origin = requireNotNull(info.getOrigin(allowlist.toString()))
    val canonical = requireNotNull(com.privatevault.app.security.httpsOrigin(origin))
    require(origin == canonical || origin == "$canonical/")
    return canonical
}

@RequiresApi(34)
class VaultCredentialService : CredentialProviderService() {
    private fun action(create: Boolean): PendingIntent {
        val intent = Intent(this, VaultCodesActivity::class.java).setAction("vault.passkey.${UUID.randomUUID()}")
            .putExtra("passkey_create", create).putExtra("passkey_issued", SystemClock.elapsedRealtime())
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
    override fun onBeginCreateCredentialRequest(request: BeginCreateCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>) {
        if (cancellationSignal.isCanceled) return
        try {
            require(request is BeginCreatePublicKeyCredentialRequest)
            val origin = browserOrigin(this, requireNotNull(request.callingAppInfo))
            PasskeyCrypto.request(request.requestJson, origin, true)
            callback.onResult(BeginCreateCredentialResponse(listOf(CreateEntry("Private Vault", action(true)))))
        } catch (_: Exception) { callback.onError(CreateCredentialUnknownException("This passkey request is not supported.")) }
    }
    override fun onBeginGetCredentialRequest(request: BeginGetCredentialRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>) {
        if (cancellationSignal.isCanceled) return
        try {
            val origin = browserOrigin(this, requireNotNull(request.callingAppInfo))
            val options = request.beginGetCredentialOptions.filterIsInstance<BeginGetPublicKeyCredentialOption>()
            val entries = options.mapNotNull { option ->
                runCatching {
                    PasskeyCrypto.request(option.requestJson, origin, false)
                    // No account names or credential IDs leave the encrypted vault before authentication.
                    PublicKeyCredentialEntry(this, "Unlock Private Vault", action(false), option)
                }.getOrNull()
            }
            callback.onResult(BeginGetCredentialResponse(entries))
        } catch (_: Exception) { callback.onError(GetCredentialUnknownException("This passkey request is not supported.")) }
    }
    override fun onClearCredentialStateRequest(request: ProviderClearCredentialStateRequest, cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>) { if (!cancellationSignal.isCanceled) callback.onResult(null) }
}

@RequiresApi(34)
internal class PasskeyOperation private constructor(val create: Boolean, val input: JSONObject, val origin: String,
    val clientHash: ByteArray, private val caller: CallingAppInfo) {
    fun revalidate(context: Context) { require(browserOrigin(context, caller) == origin) }
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
            val origin = browserOrigin(context, caller)
            require(hash?.size == 32)
            return PasskeyOperation(create, PasskeyCrypto.request(json, origin, create), origin, hash.copyOf(), caller)
        }
    }
}
