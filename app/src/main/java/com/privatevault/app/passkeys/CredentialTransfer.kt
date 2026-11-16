package com.privatevault.app.passkeys

import com.privatevault.app.data.VaultPasskey
import org.json.JSONObject

/**
 * [unsupportedPasskeys]: skipped, because the key is not an ES256 (P-256) key.
 * [extensionDataDropped]: imported, but they carried extension data Nuvori does not keep.
 *
 * The format's passkey extensions (hmacCredentials, credBlob, largeBlob, payments) do not change how a passkey signs in:
 * the key, credential ID, site and user handle are all a sign-in needs. Nuvori does not answer these extensions, so a site
 * that asks for one (PRF for encryption, a large blob, Secure Payment Confirmation) gets no result from Nuvori's copy, as
 * from any authenticator without that extension. Google Password Manager exports a PRF secret with its passkeys.
 */
internal data class CxfPasskeyImport(
    val exporterDisplayName: String,
    val passkeys: List<VaultPasskey>,
    val unsupportedPasskeys: Int,
    val extensionDataDropped: Int = 0,
)

internal fun readCxfPasskeys(json: String): CxfPasskeyImport {
    try {
        require(json.length in 1..4 * 1024 * 1024)
        val root = JSONObject(json)
        val version = root.getJSONObject("version")
        require(version.getInt("major") == 1 && version.getInt("minor") >= 0)
        val exporter = root.getString("exporterDisplayName")
        require(exporter.length in 1..200 && exporter.none(Char::isISOControl))
        val accounts = root.getJSONArray("accounts")
        require(accounts.length() <= 100)
        val imported = mutableListOf<VaultPasskey>()
        var unsupported = 0
        var dropped = 0
        var itemCount = 0
        for (accountIndex in 0 until accounts.length()) {
            val items = accounts.getJSONObject(accountIndex).getJSONArray("items")
            itemCount += items.length()
            require(itemCount <= 5000)
            for (itemIndex in 0 until items.length()) {
                val item = items.getJSONObject(itemIndex)
                val createdAt = item.optLong("creationAt", 0).takeIf { it in 1..System.currentTimeMillis() / 1000 + 86_400 }
                    ?.times(1000) ?: System.currentTimeMillis()
                val credentials = item.getJSONArray("credentials")
                require(credentials.length() <= 100)
                for (credentialIndex in 0 until credentials.length()) {
                    val credential = credentials.getJSONObject(credentialIndex)
                    if (credential.optString("type") != "passkey") continue
                    // Extension data is left behind; the passkey itself still signs in (unknown members are ignored,
                    // as the format requires).
                    val extensions = credential.optJSONObject("fido2Extensions")
                    val hasExtensionData = extensions != null && extensions.keys().asSequence().any { name ->
                        val value = extensions.opt(name)
                        value != null && value != JSONObject.NULL && value != false
                    }
                    val importedKey = PasskeyCrypto.importCxf(
                        credentialId = credential.getString("credentialId"),
                        rpId = credential.getString("rpId"),
                        userHandle = credential.getString("userHandle"),
                        username = credential.getString("username"),
                        displayName = credential.getString("userDisplayName"),
                        privateKey = credential.getString("key"),
                        createdAt = createdAt
                    )
                    if (importedKey == null) unsupported++ else {
                        imported += importedKey
                        if (hasExtensionData) dropped++
                    }
                    require(imported.size + unsupported <= 5000)
                }
            }
        }
        require(imported.isNotEmpty() || unsupported > 0)
        require(imported.map { it.id }.distinct().size == imported.size)
        return CxfPasskeyImport(exporter, imported, unsupported, dropped)
    } catch (_: Exception) {
        throw IllegalArgumentException("Could not validate the credential transfer. No passkeys were saved.")
    }
}
