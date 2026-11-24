package com.privatevault.app.passkeys

import com.privatevault.app.security.httpsOrigin
import com.privatevault.app.security.registrableDomainOfHost
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Binds a passkey request to the app or website that made it (security review H-01).
 *
 * - A website (an origin a verified browser reported): the RP ID must be the origin's host, or a parent domain of it
 *   that is not a public suffix (the WebAuthn "registrable domain suffix" rule). Otherwise the site must be listed in
 *   `https://<rpId>/.well-known/webauthn` (WebAuthn related origin requests).
 * - An app (origin `android:apk-key-hash:...`): `https://<rpId>/.well-known/assetlinks.json` must grant
 *   [ASSET_LINKS_RELATION] to the app's package and signing certificate (Digital Asset Links), the same rule Google
 *   Password Manager applies. `include` statements are not followed.
 *
 * Network answers are only ever positive grants, kept in memory for [CACHE_MILLIS]. The credential service checks
 * with [verify] off the main thread before it offers anything; the picker then relies on [allowedNow], which never
 * touches the network.
 */
internal object PasskeyCallerCheck {
    const val ASSET_LINKS_RELATION = "delegate_permission/common.get_login_creds"
    private const val APP_ORIGIN_PREFIX = "android:apk-key-hash:"
    private const val CACHE_MILLIS = 6L * 60 * 60 * 1000
    private const val MAX_BYTES = 256 * 1024
    private const val TIMEOUT_MILLIS = 3_000
    /** Chrome's limit on distinct sites in a related origins file. */
    private const val MAX_RELATED_SITES = 5

    private val grants = ConcurrentHashMap<String, Long>()

    /** Monotonic milliseconds; a wall clock change can't extend a grant. */
    private fun now(): Long = System.nanoTime() / 1_000_000

    fun isAppOrigin(origin: String) = origin.startsWith(APP_ORIGIN_PREFIX)

    /** A plain lowercase ASCII host that may be fetched over https: not an IP, not a public suffix. */
    internal fun fetchableRpId(rpId: String): Boolean =
        rpId == rpId.lowercase(Locale.ROOT) && httpsOrigin("https://$rpId") == "https://$rpId" && registrableDomainOfHost(rpId) != null

    /** True when [rpId] is [host] or a parent domain of it that is not a public suffix (co.uk, github.io). */
    fun rpIdCoversHost(rpId: String, host: String): Boolean {
        val rp = rpId.lowercase(Locale.ROOT)
        val target = host.lowercase(Locale.ROOT)
        if (rp.isEmpty() || target.isEmpty() || rp.startsWith('.') || rp.endsWith('.')) return false
        if (rp == target) return true
        return target.endsWith(".$rp") && registrableDomainOfHost(rp) != null
    }

    /** The host of an https origin, or null. */
    fun originHost(origin: String): String? {
        val canonical = httpsOrigin(origin) ?: return null
        return runCatching { URI(canonical).host?.lowercase(Locale.ROOT) }.getOrNull()
    }

    /**
     * True when the Digital Asset Links file [json] grants [ASSET_LINKS_RELATION] to [packageName] signed with one of
     * [certSha256] (hex, any case, with or without colons).
     */
    fun assetLinksGrant(json: String, packageName: String, certSha256: Collection<String>): Boolean = runCatching {
        val wanted = certSha256.map(::normalizedFingerprint).filter { it.length == 64 }.toSet()
        if (wanted.isEmpty() || packageName.isEmpty()) return false
        val statements = JSONArray(json)
        (0 until statements.length()).any { index ->
            val statement = statements.optJSONObject(index) ?: return@any false
            val relations = statement.optJSONArray("relation") ?: return@any false
            if ((0 until relations.length()).none { relations.optString(it) == ASSET_LINKS_RELATION }) return@any false
            val target = statement.optJSONObject("target") ?: return@any false
            if (target.optString("namespace") != "android_app" || target.optString("package_name") != packageName) return@any false
            val prints = target.optJSONArray("sha256_cert_fingerprints") ?: return@any false
            (0 until prints.length()).any { normalizedFingerprint(prints.optString(it)) in wanted }
        }
    }.getOrDefault(false)

    /** True when the related origins file [json] (`{"origins": [...]}`) lists [origin] exactly. */
    fun relatedOriginsGrant(json: String, origin: String): Boolean = runCatching {
        val canonical = httpsOrigin(origin) ?: return false
        if (canonical != origin.removeSuffix("/")) return false
        val origins = JSONObject(json).optJSONArray("origins") ?: return false
        val sites = LinkedHashSet<String>()
        for (index in 0 until origins.length()) {
            val listed = httpsOrigin(origins.optString(index)) ?: continue
            val site = originHost(listed)?.let(::registrableDomainOfHost) ?: continue
            // Like Chrome, only the first few distinct sites in the file count.
            if (site !in sites && sites.size >= MAX_RELATED_SITES) continue
            sites += site
            if (listed == canonical) return true
        }
        false
    }.getOrDefault(false)

    private fun normalizedFingerprint(text: String) = text.replace(":", "").lowercase(Locale.ROOT)

    private fun grantKey(rpId: String, origin: String, packageName: String) = "$rpId\n$origin\n$packageName"

    private fun granted(key: String): Boolean {
        val until = grants[key] ?: return false
        if (until > now()) return true
        grants.remove(key, until)
        return false
    }

    /**
     * True when [origin] (from [packageName]) may use passkeys for [rpId] without asking the network: a website under
     * the RP ID, or a grant [verify] recorded recently.
     */
    fun allowedNow(rpId: String, origin: String, packageName: String): Boolean {
        if (!isAppOrigin(origin)) {
            val host = originHost(origin) ?: return false
            if (rpIdCoversHost(rpId, host)) return true
        }
        return granted(grantKey(rpId.lowercase(Locale.ROOT), origin, packageName))
    }

    /**
     * Checks [origin] for [rpId] as [allowedNow] does, then asks the RP's well-known files when that isn't enough.
     * [identity] is the caller's signing certificate digest (hex). Blocking: never call it on the main thread.
     */
    fun verify(rpId: String, origin: String, packageName: String, identity: String,
        fetch: (URL) -> String? = ::fetchSmall): Boolean {
        val rp = rpId.lowercase(Locale.ROOT)
        if (rp != rpId) return false
        if (allowedNow(rp, origin, packageName)) return true
        if (!fetchableRpId(rp)) return false
        val ok = if (isAppOrigin(origin)) {
            fetch(URI("https://$rp/.well-known/assetlinks.json").toURL())?.let { assetLinksGrant(it, packageName, listOf(identity)) } == true
        } else {
            fetch(URI("https://$rp/.well-known/webauthn").toURL())?.let { relatedOriginsGrant(it, origin) } == true
        }
        if (ok) grants[grantKey(rp, origin, packageName)] = now() + CACHE_MILLIS
        return ok
    }

    /** Forgets every grant. Tests only. */
    internal fun clear() = grants.clear()

    /** A small JSON file over https, without following redirects; null on any failure. */
    private fun fetchSmall(url: URL): String? = runCatching {
        require(url.protocol == "https")
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = TIMEOUT_MILLIS
            connection.readTimeout = TIMEOUT_MILLIS
            connection.useCaches = false
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != 200) return@runCatching null
            connection.inputStream.use { readLimited(it) }?.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun readLimited(input: InputStream): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return out.toByteArray()
            if (out.size() + read > MAX_BYTES) return null
            out.write(buffer, 0, read)
        }
    }
}
