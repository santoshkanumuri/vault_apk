package com.privatevault.app.autofill

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.UUID

internal class LoginSaveRequest(val packageName: String, val identity: String, val origin: String?,
    val username: String, val password: CharArray, val expiresAt: Long) {
    fun clear() = password.fill('\u0000')
}

/** One-use handles only travel in intents. Abandoned passwords are wiped after two minutes. */
internal object PendingLoginSaves {
    private val requests = mutableMapOf<String, LoginSaveRequest>()
    private val handler = Handler(Looper.getMainLooper())
    @Synchronized fun put(request: LoginSaveRequest): String {
        if (requests.size >= 4) remove(requests.keys.first())
        val token = UUID.randomUUID().toString()
        requests[token] = request
        handler.postDelayed({ remove(token) }, (request.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        return token
    }
    @Synchronized fun take(token: String?): LoginSaveRequest? {
        val request = requests.remove(token) ?: return null
        if (request.expiresAt <= SystemClock.elapsedRealtime()) { request.clear(); return null }
        return request
    }
    @Synchronized fun remove(token: String) { requests.remove(token)?.clear() }
}
