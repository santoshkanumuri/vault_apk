package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.DuplicateHeaderMode
import java.io.FilterReader
import java.io.Reader
import java.net.IDN
import java.net.URI
import java.util.Locale

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

internal fun readBrowserPasswords(input: Reader): List<VaultEntry> {
    val limited = object : FilterReader(input) {
        var count = 0
        override fun read(buffer: CharArray, off: Int, len: Int): Int = super.read(buffer, off, len).also {
            if (it > 0) { count += it; require(count <= 4 * 1024 * 1024) }
        }
        override fun read(): Int = super.read().also { if (it != -1) { count++; require(count <= 4 * 1024 * 1024) } }
    }
    try {
        // UTF-8 BOM occurs in some browser exports. Password whitespace is never trimmed.
        val reader = java.io.PushbackReader(limited, 1)
        val first = reader.read()
        if (first != -1 && first != 0xFEFF) reader.unread(first)
        val format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW).setIgnoreEmptyLines(true).get()
        return format.parse(reader).use { csv ->
            require(csv.headerNames.containsAll(listOf("name", "url", "username", "password")))
            val entries = mutableListOf<VaultEntry>()
            for (row in csv) {
                require(entries.size < 5000 && row.isConsistent)
                val url = row.get("url")
                val password = row.get("password")
                require(password.isNotEmpty())
                entries.add(VaultEntry(type = EntryType.PASSWORD, title = row.get("name").ifBlank { url.ifBlank { "Imported login" } },
                    primaryValue = row.get("username"), secondaryValue = password, tertiaryValue = url,
                    notes = if (row.isMapped("note")) row.get("note") else ""))
            }
            require(entries.isNotEmpty())
            entries
        }
    } catch (_: Exception) {
        // Parser exceptions can contain a fragment of a secret-bearing CSV row.
        throw IllegalArgumentException("Could not read CSV. Use a UTF-8 Chrome/Brave export with nonempty passwords, up to 5,000 rows and 4 million characters.")
    }
}

internal fun sameImportedLogin(a: VaultEntry, b: VaultEntry): Boolean =
    a.type == EntryType.PASSWORD && b.type == EntryType.PASSWORD && a.title == b.title &&
        a.primaryValue == b.primaryValue && a.secondaryValue == b.secondaryValue &&
        a.tertiaryValue == b.tertiaryValue && a.notes == b.notes
