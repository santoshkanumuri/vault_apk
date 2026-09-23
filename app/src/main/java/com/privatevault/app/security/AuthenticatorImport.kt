package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import java.util.Locale

internal enum class AuthenticatorImportStatus { NEW, ALREADY_SAVED, CONFLICT }

internal fun authenticatorImportStatuses(saved: List<VaultEntry>, incoming: List<TotpSetup>): List<AuthenticatorImportStatus> {
    data class Known(val title: String, val account: String, val secret: String, val algorithm: String, val digits: Int, val period: Int)
    fun normalized(value: String) = value.trim().lowercase(Locale.ROOT)
    fun secret(value: String) = value.filterNot(Char::isWhitespace).uppercase(Locale.ROOT).trimEnd('=')
    val known = saved.filter { it.type == EntryType.AUTHENTICATOR }.mapTo(mutableListOf()) {
        Known(normalized(it.title), normalized(it.primaryValue), secret(it.secondaryValue), it.totpAlgorithm.uppercase(Locale.ROOT), it.totpDigits, it.totpPeriod)
    }
    return incoming.map { account ->
        Totp.validate(account.secret, account.algorithm, account.digits, account.period)
        val candidate = Known(normalized(account.issuer.ifBlank { account.account }), normalized(account.account),
            secret(account.secret), account.algorithm.uppercase(Locale.ROOT), account.digits, account.period)
        val status = when {
            known.any { it.secret == candidate.secret && it.algorithm == candidate.algorithm && it.digits == candidate.digits && it.period == candidate.period } -> AuthenticatorImportStatus.ALREADY_SAVED
            known.any { it.title == candidate.title && it.account == candidate.account } -> AuthenticatorImportStatus.CONFLICT
            else -> AuthenticatorImportStatus.NEW
        }
        if (status == AuthenticatorImportStatus.NEW) known += candidate
        status
    }
}
