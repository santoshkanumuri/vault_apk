package com.privatevault.app.security

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.DuplicateHeaderMode
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
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

internal data class PasswordColumnPreview(val header: String, val sampleShape: String)

internal class PasswordColumnMappingRequired(
    val columns: List<PasswordColumnPreview>,
    val suggested: PasswordColumnMapping
) : IllegalArgumentException("Choose the website, username, and password columns.") {
    val headers: List<String> get() = columns.map { it.header }
}

internal class PasswordImportFormatException(message: String) : IllegalArgumentException(message)

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
    try {
        val text = readLimitedPasswordExport(input)
        val contentStart = text.indexOfFirst { it != '\uFEFF' && !it.isWhitespace() }
        if (contentStart < 0) throw PasswordImportFormatException("The password export is empty.")
        val content = text.substring(contentStart)
        return if (content.first() == '{') readBitwardenJson(content.reader()) else readPasswordCsv(content, mapping)
    } catch (error: PasswordColumnMappingRequired) {
        throw error
    } catch (error: PasswordImportFormatException) {
        throw error
    } catch (_: Exception) {
        // Parser exceptions can contain a fragment of a secret-bearing export.
        throw IllegalArgumentException("Could not read password export. Use a supported UTF-8 CSV or an unencrypted Bitwarden JSON export with up to 5,000 logins and 4 million characters.")
    }
}

internal fun readBrowserPasswords(input: InputStream, mapping: PasswordColumnMapping? = null): List<VaultEntry> {
    val source = PushbackInputStream(input, 3)
    val prefix = ByteArray(3)
    val count = source.read(prefix)
    val charset = when {
        count >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte() -> Charsets.UTF_8
        count >= 2 && prefix[0] == 0xFF.toByte() && prefix[1] == 0xFE.toByte() -> Charsets.UTF_16LE
        count >= 2 && prefix[0] == 0xFE.toByte() && prefix[1] == 0xFF.toByte() -> Charsets.UTF_16BE
        else -> Charsets.UTF_8
    }
    val consumed = when {
        charset == Charsets.UTF_8 && count >= 3 && prefix[0] == 0xEF.toByte() && prefix[1] == 0xBB.toByte() && prefix[2] == 0xBF.toByte() -> 3
        (charset == Charsets.UTF_16LE || charset == Charsets.UTF_16BE) && count >= 2 -> 2
        else -> 0
    }
    if (count > consumed) source.unread(prefix, consumed, count - consumed)
    val decoder = charset.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
    return readBrowserPasswords(InputStreamReader(source, decoder), mapping)
}

private fun readLimitedPasswordExport(input: Reader): String {
    val result = StringBuilder()
    val buffer = CharArray(8192)
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        result.append(buffer, 0, read)
        if (result.length > 4 * 1024 * 1024) {
            throw PasswordImportFormatException("The password export is larger than 4 million characters.")
        }
    }
    return result.toString()
}

private data class PreparedPasswordCsv(val text: String, val delimiter: Char)

private fun preparePasswordCsv(text: String): PreparedPasswordCsv {
    val firstBreak = text.indexOfAny(charArrayOf('\r', '\n'))
    val firstLine = if (firstBreak < 0) text else text.substring(0, firstBreak)
    val trimmed = firstLine.trim()
    if (trimmed.length == 5 && trimmed.substring(0, 4).equals("sep=", ignoreCase = true) &&
        trimmed[4] in charArrayOf(',', ';', '\t')) {
        var bodyStart = firstBreak
        while (bodyStart in text.indices && (text[bodyStart] == '\r' || text[bodyStart] == '\n')) bodyStart++
        if (bodyStart !in text.indices) throw PasswordImportFormatException("The CSV export has no header row.")
        return PreparedPasswordCsv(text.substring(bodyStart), trimmed[4])
    }
    val delimiter = charArrayOf(',', ';', '\t').maxByOrNull { candidate ->
        runCatching {
            CSVFormat.RFC4180.builder().setDelimiter(candidate).get().parse(firstLine.reader()).use {
                it.firstOrNull()?.size() ?: 0
            }
        }.getOrDefault(0)
    } ?: ','
    val columnCount = runCatching {
        CSVFormat.RFC4180.builder().setDelimiter(delimiter).get().parse(firstLine.reader()).use {
            it.firstOrNull()?.size() ?: 0
        }
    }.getOrDefault(0)
    if (columnCount < 2) throw PasswordImportFormatException("Could not detect separate CSV columns. Export passwords as comma, semicolon, or tab-separated CSV.")
    return PreparedPasswordCsv(text, delimiter)
}

private fun readPasswordCsv(text: String, mapping: PasswordColumnMapping?): List<VaultEntry> {
    val prepared = preparePasswordCsv(text)
    val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
        .setDelimiter(prepared.delimiter).setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
        .setIgnoreEmptyLines(true).get()
    return format.parse(prepared.text.reader()).use { csv ->
        val normalizedHeaders = csv.headerNames.map(::normalizePasswordHeader)
        require(normalizedHeaders.distinct().size == normalizedHeaders.size)
        val headers = csv.headerNames.associateBy(::normalizePasswordHeader)
        fun header(name: String): String? = headers[normalizePasswordHeader(name)]
        fun candidates(names: List<String>): List<String> = names.mapNotNull(::header).distinct()
        fun selected(value: String?): String? = value?.takeIf(csv.headerNames::contains)
        val matchedProfile = PASSWORD_PROVIDER_PROFILES.firstOrNull { it.match(headers) != null }
        val profile = matchedProfile?.match(headers)
        val aliasCandidates = PASSWORD_FIELD_ALIASES.mapValues { candidates(it.value) }
        val ambiguousAliases = aliasCandidates.values.any { it.size > 1 }
        val suggested = profile ?: PasswordColumnMapping(
            title = aliasCandidates.getValue(PasswordField.TITLE).singleOrNull(),
            website = aliasCandidates.getValue(PasswordField.WEBSITE).singleOrNull(),
            username = aliasCandidates.getValue(PasswordField.USERNAME).singleOrNull(),
            password = aliasCandidates.getValue(PasswordField.PASSWORD).singleOrNull(),
            notes = aliasCandidates.getValue(PasswordField.NOTES).singleOrNull()
        )
        val chosen = mapping?.let { PasswordColumnMapping(selected(it.title), selected(it.website), selected(it.username), selected(it.password), selected(it.notes)) }
            ?: suggested
        val selectedHeaders = listOfNotNull(chosen.title, chosen.website, chosen.username, chosen.password, chosen.notes)
        if (chosen.website == null || chosen.username == null || chosen.password == null ||
            selectedHeaders.distinct().size != selectedHeaders.size || (mapping == null && profile == null && ambiguousAliases)) {
            val firstRow = csv.firstOrNull()
            val passwordCandidates = aliasCandidates.getValue(PasswordField.PASSWORD).toSet()
            val columns = csv.headerNames.map { column ->
                val value = firstRow?.takeIf { it.isConsistent }?.get(column).orEmpty()
                PasswordColumnPreview(column, maskedSampleShape(value, column in passwordCandidates))
            }
            throw PasswordColumnMappingRequired(columns, if (mapping == null) suggested else chosen)
        }
        val passwordHeader = chosen.password
        val usernameHeader = chosen.username
        val urlHeader = chosen.website
        val titleHeader = chosen.title
        val notesHeader = chosen.notes
        val typeHeader = if (matchedProfile?.provider == "Bitwarden") header("type") else null
        val entries = mutableListOf<VaultEntry>()
        for (row in csv) {
            val hasUnexpectedValue = (csv.headerNames.size until row.size()).any { row.get(it).isNotEmpty() }
            if (hasUnexpectedValue) {
                throw PasswordImportFormatException("A CSV row does not match the header columns. Export the file again without editing it in a spreadsheet.")
            }
            if (typeHeader != null && !row.get(typeHeader).equals("login", true)) continue
            val password = passwordHeader.takeIf(row::isSet)?.let(row::get).orEmpty()
            if (password.isEmpty()) continue
            if (!row.isSet(urlHeader) || !row.isSet(usernameHeader)) {
                throw PasswordImportFormatException("A CSV row does not match the header columns. Export the file again without editing it in a spreadsheet.")
            }
            val url = row.get(urlHeader)
            require(entries.size < 5000)
            val title = titleHeader?.takeIf(row::isSet)?.let(row::get).orEmpty().ifBlank { url.ifBlank { "Imported login" } }
            entries += VaultEntry(type = EntryType.PASSWORD, title = title,
                primaryValue = row.get(usernameHeader), secondaryValue = password, tertiaryValue = url,
                notes = notesHeader?.takeIf(row::isSet)?.let(row::get).orEmpty())
        }
        if (entries.isEmpty()) throw PasswordImportFormatException("No non-empty passwords were found in the export.")
        entries
    }
}

internal fun normalizePasswordHeader(value: String): String = value
    .removePrefix("\uFEFF")
    .trim()
    .lowercase(Locale.ROOT)
    .filterNot { it.isWhitespace() || it == '_' || it == '-' }

private enum class PasswordField { TITLE, WEBSITE, USERNAME, PASSWORD, NOTES }

private val PASSWORD_FIELD_ALIASES = mapOf(
    PasswordField.TITLE to listOf("name", "title", "account", "account name"),
    PasswordField.WEBSITE to listOf("url", "website", "web site", "login_uri", "login_url", "hostname"),
    PasswordField.USERNAME to listOf("username", "user name", "login_username", "login name", "login", "email"),
    PasswordField.PASSWORD to listOf("password", "login_password"),
    PasswordField.NOTES to listOf("note", "notes", "extra", "extra notes", "comments")
)

private data class PasswordProviderProfile(
    val provider: String,
    val mapping: PasswordColumnMapping,
    val markers: List<String> = emptyList()
) {
    fun match(headers: Map<String, String>): PasswordColumnMapping? {
        if (markers.any { normalizePasswordHeader(it) !in headers }) return null
        fun source(name: String?): String? = name?.let { headers[normalizePasswordHeader(it)] }
        val matched = PasswordColumnMapping(source(mapping.title), source(mapping.website), source(mapping.username),
            source(mapping.password), source(mapping.notes))
        return matched.takeIf { it.website != null && it.username != null && it.password != null }
    }
}

/* Specific profiles precede the shared Chromium/Firefox shape. */
private val PASSWORD_PROVIDER_PROFILES = listOf(
    PasswordProviderProfile("Bitwarden", PasswordColumnMapping("name", "login_uri", "login_username", "login_password", "notes"), listOf("type")),
    PasswordProviderProfile("Bitwarden", PasswordColumnMapping("name", "login_url", "login_username", "login_password", "notes"), listOf("type")),
    PasswordProviderProfile("KeePass", PasswordColumnMapping("account", "web site", "login name", "password", "comments"), listOf("account")),
    PasswordProviderProfile("LastPass", PasswordColumnMapping("name", "url", "username", "password", "extra"), listOf("extra")),
    PasswordProviderProfile("1Password", PasswordColumnMapping("title", "website", "username", "password", "notes"), listOf("website")),
    PasswordProviderProfile("Safari", PasswordColumnMapping("title", "url", "username", "password", "notes"), listOf("title", "notes")),
    PasswordProviderProfile("Chromium", PasswordColumnMapping("name", "url", "username", "password", "note"), listOf("name")),
    PasswordProviderProfile("Firefox", PasswordColumnMapping(website = "url", username = "username", password = "password"), listOf("httpRealm"))
)

private fun maskedSampleShape(value: String, password: Boolean): String = when {
    value.isEmpty() -> "Empty"
    password -> "Hidden"
    value.startsWith("https://", true) -> "https://••••"
    value.startsWith("http://", true) -> "http://••••"
    '@' in value -> "••••@••••"
    else -> "••••"
}

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
