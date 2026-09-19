package com.privatevault.app.security

import android.content.Context
import android.content.pm.PackageManager
import com.privatevault.app.data.VaultEntry
import com.privatevault.app.data.EntryType
import java.security.MessageDigest
import java.security.SecureRandom

internal fun appSigningIdentity(context: Context, packageName: String): String? = runCatching {
    @Suppress("DEPRECATION")
    val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    val certificates = info.signingInfo?.apkContentsSigners ?: return null
    if (certificates.isEmpty()) return null
    certificates.map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.sorted().joinToString(",")
}.getOrNull()

internal fun loginAuthorized(entry: VaultEntry, packageName: String, identity: String): Boolean =
    entry.type == EntryType.PASSWORD && identity.isNotBlank() &&
        entry.autofillSignatures.lineSequence().any { it == "$packageName=$identity" }

internal fun loginAuthorizedForDestination(entry: VaultEntry, packageName: String, identity: String, origin: String?): Boolean =
    if (origin == null) loginAuthorized(entry, packageName, identity)
    else entry.type == EntryType.PASSWORD && trustedBrowser(packageName, identity) &&
        httpsOrigin(origin) == origin && httpsOrigin(entry.tertiaryValue) == origin

internal fun generateLoginPassword(length: Int = 24): String {
    require(length in 16..64)
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#%*-_=+?"
    val random = SecureRandom()
    return buildString { repeat(length) { append(alphabet[random.nextInt(alphabet.length)]) } }
}
