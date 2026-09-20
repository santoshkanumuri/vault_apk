package com.privatevault.app.security

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.DuplicateHeaderMode
import java.io.FilterReader
import java.io.Reader
import java.net.IDN
import java.net.URI
import java.util.Locale

internal data class PasswordColumnMapping(
    val title: String? = null,
    val website: String? = null,
    val username: String? = null,
    val password: String? = null,
    val notes: String? = null
)

internal class PasswordColumnMappingRequired(
    val headers: List<String>,
    val suggested: PasswordColumnMapping
) : IllegalArgumentException("Choose the website, username, and password columns.")

/** Exact HTTPS origin only. No suffix, subdomain, title, or public-suffix guessing. */
internal fun httpsOrigin(value: String): String? = runCatching {
    if (value.any { it.isWhitespace() || it.isISOControl() } || '\\' in value) return null
    val uri = URI(value)
    if (!uri.scheme.equals("https", true) || uri.rawUserInfo != null) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    if (host.endsWith('.') || host.startsWith('[') || !host.contains('.')) return null
    val ascii = IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
    if (uri.port != -1 && uri.port !in 1..65535) return null
    "https://$ascii" + if (uri.port == -1 || uri.port == 443) "" else ":${uri.port}"
}.getOrNull()

internal fun readBrowserPasswords(input: Reader, mapping: PasswordColumnMapping? = null): List<VaultEntry> {
    val limited = object : FilterReader(input) {
        var count = 0
        override fun read(buffer: CharArray, off: Int, len: Int): Int = super.read(buffer, off, len).also {
            if (it > 0) { count += it; require(count <= 4 * 1024 * 1024) }
        }
        override fun read(): Int = super.read().also { if (it != -1) { count++; require(count <= 4 * 1024 * 1024) } }
    }
    try {
        // UTF-8 BOM occurs in some exports. Password whitespace is never trimmed.
        val reader = java.io.PushbackReader(limited, 1)
        var first = reader.read()
        if (first == 0xFEFF) first = reader.read()
        while (first != -1 && first.toChar().isWhitespace()) first = reader.read()
        require(first != -1)
        reader.unread(first)
        return if (first.toChar() == '{') readBitwardenJson(reader) else readPasswordCsv(reader, mapping)
    } catch (error: PasswordColumnMappingRequired) {
        throw error
    } catch (_: Exception) {
        // Parser exceptions can contain a fragment of a secret-bearing export.
        throw IllegalArgumentException("Could not read password export. Use a supported UTF-8 CSV or an unencrypted Bitwarden JSON export with up to 5,000 logins and 4 million characters.")
    }
}

private fun readPasswordCsv(reader: Reader, mapping: PasswordColumnMapping?): List<VaultEntry> {
    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(true).get()
    return format.parse(reader).use { csv ->
        val normalizedHeaders = csv.headerNames.map(::normalizePasswordHeader)
        require(normalizedHeaders.distinct().size == normalizedHeaders.size)
        val headers = csv.headerNames.associateBy(::normalizePasswordHeader)
        fun header(vararg names: String): String? = names.firstNotNullOfOrNull { headers[normalizePasswordHeader(it)] }
        fun selected(value: String?): String? = value?.takeIf(csv.headerNames::contains)
        val bitwarden = header("login_password") != null && header("login_username") != null
        val suggested = PasswordColumnMapping(
            title = header("name", "title", "account", "account name"),
            website = header("url", "website", "web site", "login_uri", "login_url", "hostname"),
            username = header("username", "user name", "login_username", "login name", "login", "email"),
            password = header("password", "login_password"),
            notes = header("note", "notes", "extra", "extra notes", "comments")
        )
        val chosen = mapping?.let { PasswordColumnMapping(selected(it.title), selected(it.website), selected(it.username), selected(it.password), selected(it.notes)) }
            ?: suggested
        val selectedHeaders = listOfNotNull(chosen.title, chosen.website, chosen.username, chosen.password, chosen.notes)
        if (chosen.website == null || chosen.username == null || chosen.password == null ||
            selectedHeaders.distinct().size != selectedHeaders.size) {
            throw PasswordColumnMappingRequired(csv.headerNames.toList(), if (mapping == null) suggested else chosen)
        }
        val passwordHeader = chosen.password
        val usernameHeader = chosen.username
        val urlHeader = chosen.website
        val titleHeader = chosen.title
        val notesHeader = chosen.notes
        val typeHeader = if (bitwarden) header("type") else null
        val entries = mutableListOf<VaultEntry>()
        for (row in csv) {
            require(row.isConsistent)
            if (typeHeader != null && !row.get(typeHeader).equals("login", true)) continue
            val url = row.get(urlHeader)
            val password = row.get(passwordHeader)
            if (password.isEmpty()) continue
            require(entries.size < 5000)
            val title = titleHeader?.let(row::get).orEmpty().ifBlank { url.ifBlank { "Imported login" } }
            entries += VaultEntry(type = EntryType.PASSWORD, title = title,
                primaryValue = row.get(usernameHeader), secondaryValue = password, tertiaryValue = url,
                notes = notesHeader?.let(row::get).orEmpty())
        }
        require(entries.isNotEmpty())
        entries
    }
}

internal fun normalizePasswordHeader(value: String): String = value
    .trim()
    .lowercase(Locale.ROOT)
    .filterNot { it.isWhitespace() || it == '_' || it == '-' }

private fun readBitwardenJson(reader: Reader): List<VaultEntry> {
    val root = JsonParser.parseReader(reader).asJsonObject
    require(root.get("encrypted")?.takeIf { it.isJsonPrimitive }?.asBoolean != true)
    val items = root.getAsJsonArray("items") ?: error("Missing items")
    val entries = mutableListOf<VaultEntry>()
    for (element in items) {
        val item = element.asJsonObject
        if (item.int("type") != 1) continue
        val login = item.getAsJsonObject("login") ?: error("Missing login")
        val password = login.string("password")
        if (password.isEmpty()) continue
        require(entries.size < 5000)
        val url = login.getAsJsonArray("uris")?.asSequence()
            ?.mapNotNull { it.takeIf { value -> value.isJsonObject }?.asJsonObject?.string("uri") }
            ?.firstOrNull { it.isNotBlank() }.orEmpty()
        val name = item.string("name")
        require(name.isNotBlank())
        entries += VaultEntry(type = EntryType.PASSWORD, title = name, primaryValue = login.string("username"),
            secondaryValue = password, tertiaryValue = url, notes = item.string("notes"))
    }
    require(entries.isNotEmpty())
    return entries
}

private fun JsonObject.string(name: String): String = get(name)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
private fun JsonObject.int(name: String): Int? = get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull()

internal fun sameImportedLogin(a: VaultEntry, b: VaultEntry): Boolean =
    sameImportedAccount(a, b) && a.secondaryValue == b.secondaryValue

internal data class ImportedLoginKey(val site: String, val username: String)

internal fun importedLoginKey(entry: VaultEntry): ImportedLoginKey? {
    if (entry.type != EntryType.PASSWORD) return null
    val rawSite = entry.tertiaryValue.trim()
    val site = httpsOrigin(rawSite) ?: rawSite.lowercase(Locale.ROOT)
    if (site.isBlank()) return null
    return ImportedLoginKey(site, entry.primaryValue)
}

internal fun sameImportedAccount(a: VaultEntry, b: VaultEntry): Boolean {
    val first = importedLoginKey(a)
    val second = importedLoginKey(b)
    return if (first != null && second != null) first == second
    else a.type == EntryType.PASSWORD && b.type == EntryType.PASSWORD &&
        a.title == b.title && a.primaryValue == b.primaryValue && a.tertiaryValue == b.tertiaryValue
}

/** One incoming record per site and username. A later CSV row is the explicit import candidate. */
internal fun deduplicateImportedLogins(incoming: List<VaultEntry>): List<VaultEntry> {
    val keyed = linkedMapOf<ImportedLoginKey, VaultEntry>()
    val unkeyed = mutableListOf<VaultEntry>()
    incoming.forEach { entry ->
        require(entry.type == EntryType.PASSWORD)
        val key = importedLoginKey(entry)
        if (key == null) {
            if (unkeyed.none { it.title == entry.title && it.primaryValue == entry.primaryValue &&
                    it.secondaryValue == entry.secondaryValue && it.tertiaryValue == entry.tertiaryValue && it.notes == entry.notes }) unkeyed += entry
        } else keyed[key] = entry
    }
    return keyed.values + unkeyed
}
