package com.privatevault.app.notes

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry
import java.security.MessageDigest

/*
 * Rich notes, format v1 (docs/RICH-NOTES-PLAN.md). A note keeps a plain-text fallback in
 * `VaultEntry.notes` and the rich document as JSON in `VaultEntry.fourthValue`, so the document
 * travels inside the existing encrypted entry everywhere an entry already goes.
 */

/** Color tokens. Each app maps them to theme colors, so notes read well in light and dark. */
enum class NoteColor(val token: String) {
    GRAY("gray"), RED("red"), ORANGE("orange"), YELLOW("yellow"), GREEN("green"),
    TEAL("teal"), BLUE("blue"), PURPLE("purple"), PINK("pink");

    companion object {
        fun fromToken(token: String?): NoteColor? = entries.firstOrNull { it.token == token }
    }
}

data class RichMarks(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val code: Boolean = false,
    val color: NoteColor? = null,
    val bg: NoteColor? = null,
    val link: String? = null,
) {
    val isPlain: Boolean get() = this == NONE

    companion object {
        val NONE = RichMarks()
    }
}

data class RichSpan(val text: String, val marks: RichMarks = RichMarks.NONE)

enum class BlockKind(val token: String) {
    PARAGRAPH("paragraph"), HEADING("heading"), BULLET("bullet"), NUMBERED("numbered"),
    CHECK("check"), QUOTE("quote"), CODE("code");

    val isListItem: Boolean get() = this == BULLET || this == NUMBERED || this == CHECK

    companion object {
        fun fromToken(token: String?): BlockKind? = entries.firstOrNull { it.token == token }
    }
}

sealed interface RichBlock

data class TextBlock(
    val kind: BlockKind = BlockKind.PARAGRAPH,
    val spans: List<RichSpan> = emptyList(),
    /** Heading level, 1 to 3. Ignored for other kinds. */
    val level: Int = 1,
    /** Nesting for list and check items, 0 to [RichNotes.MAX_INDENT]. */
    val indent: Int = 0,
    val checked: Boolean = false,
) : RichBlock {
    val text: String get() = spans.joinToString("") { it.text }
}

data object DividerBlock : RichBlock

data class TableBlock(val header: Boolean = true, val rows: List<List<List<RichSpan>>>) : RichBlock {
    val columnCount: Int get() = rows.maxOfOrNull { it.size } ?: 0
}

data class RichNoteDocument(val blocks: List<RichBlock>) {
    val isEmpty: Boolean get() = RichNotes.plainText(this).isBlank()
}

/** How a note's document was obtained, so the UI can explain a fallback. */
enum class RichNoteSource {
    /** A valid v1 document whose fallback still matches the note text. */
    RICH,
    /** A plain note, or a document that could not be read: shown as plain paragraphs. */
    PLAIN,
    /** The text was edited by an app that does not know rich notes; its text wins. */
    DIVERGED,
    /** Written by a newer app version; shown as plain text, and editing replaces the formatting. */
    NEWER_VERSION,
}

data class LoadedRichNote(val document: RichNoteDocument, val source: RichNoteSource)

class RichNoteTooLargeException(message: String) : IllegalArgumentException(message)

object RichNotes {
    const val FORMAT = "nuvori.richnote"
    const val VERSION = 1
    const val MAX_INDENT = 4
    const val MAX_BLOCKS = 4_000
    const val MAX_TABLE_ROWS = 50
    const val MAX_TABLE_COLUMNS = 12
    const val MAX_TEXT_CHARS = 200_000
    const val MAX_JSON_BYTES = 512 * 1024
    /** The escaped plain text and JSON together, leaving room for the rest of the entry in a sync payload. */
    const val MAX_STORED_BYTES = 900_000
    const val MAX_LINK_CHARS = 2_048
    const val DIVIDER_TEXT = "———"

    /** Reads the note the way every app must (plan: "Reading rules"). Never throws. */
    fun load(entry: VaultEntry): LoadedRichNote {
        if (entry.type != EntryType.NOTE) return LoadedRichNote(fromPlain(entry.notes), RichNoteSource.PLAIN)
        return load(entry.notes, entry.fourthValue)
    }

    fun load(plain: String, json: String): LoadedRichNote {
        if (json.isBlank()) return LoadedRichNote(fromPlain(plain), RichNoteSource.PLAIN)
        val decoded = try { decode(json) } catch (_: Exception) { null }
            ?: return LoadedRichNote(fromPlain(plain), RichNoteSource.PLAIN)
        if (decoded.version > VERSION) return LoadedRichNote(fromPlain(plain), RichNoteSource.NEWER_VERSION)
        if (decoded.plainHash != hash(plain)) return LoadedRichNote(fromPlain(plain), RichNoteSource.DIVERGED)
        return LoadedRichNote(decoded.document, RichNoteSource.RICH)
    }

    /**
     * The two values to store for a note: (plain fallback for `notes`, JSON for `fourthValue`).
     * A document with no formatting at all is stored as plain text only, which keeps plain notes
     * exactly as they were.
     */
    fun storageFields(document: RichNoteDocument): Pair<String, String> {
        val clean = normalize(document)
        val plain = plainText(clean)
        if (isPlainParagraphs(clean)) return plain to ""
        val json = encode(clean)
        // Both values travel in one sync payload, which peers cap at 1 MiB.
        val stored = Gson().toJson(plain).toByteArray(Charsets.UTF_8).size + Gson().toJson(json).toByteArray(Charsets.UTF_8).size
        if (stored > MAX_STORED_BYTES) throw RichNoteTooLargeException("This note is too long. Split it into two notes.")
        return plain to json
    }

    fun applyTo(entry: VaultEntry, document: RichNoteDocument): VaultEntry {
        val (plain, json) = storageFields(document)
        return entry.copy(notes = plain, fourthValue = json)
    }

    /** One paragraph per line; its plain text is the original text (after trimming). */
    fun fromPlain(text: String): RichNoteDocument {
        val normalized = normalizeNewlines(text).trim()
        if (normalized.isEmpty()) return RichNoteDocument(listOf(TextBlock()))
        return RichNoteDocument(normalized.split('\n').map { line ->
            TextBlock(spans = if (line.isEmpty()) emptyList() else listOf(RichSpan(sanitizeText(line))))
        })
    }

    fun plainText(document: RichNoteDocument): String {
        val lines = mutableListOf<String>()
        var number = IntArray(MAX_INDENT + 1)
        for (block in document.blocks) {
            when (block) {
                is TextBlock -> {
                    val pad = "  ".repeat(block.indent.coerceIn(0, MAX_INDENT))
                    if (block.kind != BlockKind.NUMBERED) number = IntArray(MAX_INDENT + 1)
                    val text = block.text
                    lines += when (block.kind) {
                        BlockKind.PARAGRAPH, BlockKind.HEADING, BlockKind.CODE -> text
                        BlockKind.QUOTE -> text.split('\n').joinToString("\n") { "> $it" }
                        BlockKind.BULLET -> "$pad• $text"
                        BlockKind.CHECK -> "$pad${if (block.checked) "☑" else "☐"} $text"
                        BlockKind.NUMBERED -> {
                            val level = block.indent.coerceIn(0, MAX_INDENT)
                            number[level] += 1
                            for (deeper in level + 1..MAX_INDENT) number[deeper] = 0
                            "$pad${number[level]}. $text"
                        }
                    }
                }
                DividerBlock -> { number = IntArray(MAX_INDENT + 1); lines += DIVIDER_TEXT }
                is TableBlock -> {
                    number = IntArray(MAX_INDENT + 1)
                    block.rows.forEach { row -> lines += row.joinToString(" | ") { cell -> cell.joinToString("") { it.text }.replace('\n', ' ') } }
                }
            }
        }
        return normalizeNewlines(lines.joinToString("\n")).trim()
    }

    /** First 32 hex characters of SHA-256 over the normalized plain text. */
    fun hash(plain: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(normalizeNewlines(plain).trim().toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    fun normalizeNewlines(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

    /** Removes control characters (except line breaks and tabs) and bidirectional overrides. */
    fun sanitizeText(text: String): String {
        val normalized = normalizeNewlines(text)
        val out = StringBuilder(normalized.length)
        for (char in normalized) {
            val code = char.code
            val control = (code < 0x20 && char != '\n' && char != '\t') || code == 0x7F || code in 0x80..0x9F
            val bidi = code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0x200E || code == 0x200F
            if (!control && !bidi) out.append(char)
        }
        return out.toString()
    }

    /** Only http, https and mailto links are kept; anything else is dropped. */
    fun sanitizeLink(link: String?): String? {
        val value = link?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_LINK_CHARS } ?: return null
        if (value.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F }) return null
        val scheme = value.substringBefore(':', "").lowercase()
        return when (scheme) {
            "https", "http" -> value.takeIf {
                value.substring(scheme.length).startsWith("://") &&
                    runCatching { java.net.URI(value).host }.getOrNull()?.matches(HOST) == true
            }
            "mailto" -> value.takeIf { value.length > 7 }
            else -> null
        }
    }

    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$|^\\[[0-9A-Fa-f:.]+]$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** Adds https:// to a bare address typed into the link box, then applies [sanitizeLink]. */
    fun linkFromInput(input: String): String? {
        val trimmed = input.trim()
        if (SCHEME.containsMatchIn(trimmed) && !trimmed.contains("://") && !trimmed.startsWith("mailto:", true) &&
            !Regex("^[^:/]+:\\d+(/|$)").containsMatchIn(trimmed)) return null
        val withScheme = if (trimmed.contains("://") || trimmed.startsWith("mailto:", true)) trimmed
            else if (trimmed.contains('@') && !trimmed.contains('/')) "mailto:$trimmed" else "https://$trimmed"
        return sanitizeLink(withScheme)
    }

    /** Merges adjacent spans with equal marks, drops empty spans, clamps values. */
    fun normalize(document: RichNoteDocument): RichNoteDocument {
        val blocks = document.blocks.flatMap { block ->
            when (block) {
                // A text block is one line: text with line breaks becomes one block per line.
                is TextBlock -> splitLines(block.spans).map { line ->
                    block.copy(
                        spans = normalizeSpans(line),
                        level = block.level.coerceIn(1, 3),
                        indent = if (block.kind.isListItem) block.indent.coerceIn(0, MAX_INDENT) else 0,
                        checked = block.kind == BlockKind.CHECK && block.checked,
                    )
                }
                else -> listOf(block)
            }
        }.mapNotNull { block ->
            when (block) {
                is TextBlock -> block
                DividerBlock -> DividerBlock
                is TableBlock -> {
                    val rows = block.rows.filter { it.isNotEmpty() }
                    if (rows.isEmpty()) null else {
                        val columns = rows.maxOf { it.size }
                        TableBlock(block.header, rows.map { row ->
                            (row + List(columns - row.size) { emptyList() }).map(::normalizeSpans)
                        })
                    }
                }
            }
        }
        return RichNoteDocument(blocks.ifEmpty { listOf(TextBlock()) })
    }

    private fun splitLines(spans: List<RichSpan>): List<List<RichSpan>> {
        val lines = mutableListOf(mutableListOf<RichSpan>())
        for (span in spans) {
            val parts = normalizeNewlines(span.text).split('\n')
            parts.forEachIndexed { index, part ->
                if (index > 0) lines += mutableListOf<RichSpan>()
                if (part.isNotEmpty()) lines.last() += RichSpan(part, span.marks)
            }
        }
        return lines
    }

    fun normalizeSpans(spans: List<RichSpan>): List<RichSpan> {
        val out = mutableListOf<RichSpan>()
        for (raw in spans) {
            val span = RichSpan(sanitizeText(raw.text).replace('\n', ' '), raw.marks)
            if (span.text.isEmpty()) continue
            val marks = span.marks.copy(link = sanitizeLink(span.marks.link))
            val last = out.lastOrNull()
            if (last != null && last.marks == marks) out[out.lastIndex] = RichSpan(last.text + span.text, marks)
            else out += RichSpan(span.text, marks)
        }
        return out
    }

    private fun isPlainParagraphs(document: RichNoteDocument): Boolean = document.blocks.all { block ->
        block is TextBlock && block.kind == BlockKind.PARAGRAPH && block.spans.all { it.marks.isPlain } && !block.text.contains('\n')
    } && fromPlain(plainText(document)) == document

    // ---- JSON ----

    internal data class Decoded(val version: Int, val plainHash: String, val document: RichNoteDocument)

    fun encode(document: RichNoteDocument): String {
        val clean = normalize(document)
        if (clean.blocks.size > MAX_BLOCKS) throw RichNoteTooLargeException("This note is too long. Split it into two notes.")
        val plain = plainText(clean)
        val textChars = clean.blocks.sumOf { block ->
            when (block) {
                is TextBlock -> block.text.length
                is TableBlock -> block.rows.sumOf { row -> row.sumOf { cell -> cell.sumOf { it.text.length } } }
                DividerBlock -> 0
            }
        }
        if (textChars > MAX_TEXT_CHARS) throw RichNoteTooLargeException("This note is too long. Split it into two notes.")
        clean.blocks.filterIsInstance<TableBlock>().forEach {
            if (it.rows.size > MAX_TABLE_ROWS || it.columnCount > MAX_TABLE_COLUMNS)
                throw RichNoteTooLargeException("Tables can have up to $MAX_TABLE_ROWS rows and $MAX_TABLE_COLUMNS columns.")
        }
        val root = JsonObject().apply {
            addProperty("format", FORMAT)
            addProperty("version", VERSION)
            addProperty("plainHash", hash(plain))
            add("blocks", JsonArray().apply { clean.blocks.forEach { add(blockJson(it)) } })
        }
        val json = root.toString()
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES)
            throw RichNoteTooLargeException("This note has too much formatting to save. Split it into two notes.")
        return json
    }

    private fun blockJson(block: RichBlock): JsonObject = JsonObject().apply {
        when (block) {
            is TextBlock -> {
                addProperty("type", block.kind.token)
                if (block.kind == BlockKind.HEADING) addProperty("level", block.level)
                if (block.kind.isListItem && block.indent > 0) addProperty("indent", block.indent)
                if (block.kind == BlockKind.CHECK) addProperty("checked", block.checked)
                add("spans", spansJson(block.spans))
            }
            DividerBlock -> addProperty("type", "divider")
            is TableBlock -> {
                addProperty("type", "table")
                addProperty("header", block.header)
                add("rows", JsonArray().apply {
                    block.rows.forEach { row ->
                        add(JsonArray().apply { row.forEach { cell -> add(JsonObject().apply { add("spans", spansJson(cell)) }) } })
                    }
                })
            }
        }
    }

    private fun spansJson(spans: List<RichSpan>): JsonArray = JsonArray().apply {
        spans.forEach { span ->
            add(JsonObject().apply {
                addProperty("text", span.text)
                val m = span.marks
                if (m.bold) addProperty("bold", true)
                if (m.italic) addProperty("italic", true)
                if (m.underline) addProperty("underline", true)
                if (m.strike) addProperty("strike", true)
                if (m.code) addProperty("code", true)
                m.color?.let { addProperty("color", it.token) }
                m.bg?.let { addProperty("bg", it.token) }
                m.link?.let { addProperty("link", it) }
            })
        }
    }

    internal fun decode(json: String): Decoded? {
        if (json.toByteArray(Charsets.UTF_8).size > MAX_JSON_BYTES) return null
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return null
        if (root.string("format") != FORMAT) return null
        val version = root.get("version")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt ?: return null
        val plainHash = root.string("plainHash") ?: return null
        if (version > VERSION) return Decoded(version, plainHash, RichNoteDocument(emptyList()))
        val blocksJson = root.get("blocks")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
        if (blocksJson.size() > MAX_BLOCKS) return null
        var textChars = 0
        val blocks = blocksJson.mapNotNull { element ->
            val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            when (val type = o.string("type")) {
                "divider" -> DividerBlock
                "table" -> {
                    val rows = o.get("rows")?.takeIf { it.isJsonArray }?.asJsonArray ?: return@mapNotNull null
                    if (rows.size() > MAX_TABLE_ROWS) return null
                    val parsed = rows.map { row ->
                        val cells = row.takeIf { it.isJsonArray }?.asJsonArray ?: return null
                        if (cells.size() > MAX_TABLE_COLUMNS) return null
                        cells.map { cell -> parseSpans(cell.takeIf { it.isJsonObject }?.asJsonObject?.get("spans")).also { spans -> textChars += spans.sumOf { it.text.length } } }
                    }
                    TableBlock(o.bool("header") ?: true, parsed)
                }
                else -> {
                    val kind = BlockKind.fromToken(type) ?: return@mapNotNull null
                    val spans = parseSpans(o.get("spans"))
                    textChars += spans.sumOf { it.text.length }
                    TextBlock(kind, spans, level = o.int("level") ?: 1, indent = o.int("indent") ?: 0,
                        checked = o.bool("checked") ?: false)
                }
            }
        }
        if (textChars > MAX_TEXT_CHARS) return null
        return Decoded(version, plainHash, normalize(RichNoteDocument(blocks)))
    }

    private fun parseSpans(element: JsonElement?): List<RichSpan> {
        val array = element?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val o = item.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val text = sanitizeText(o.string("text") ?: return@mapNotNull null)
            RichSpan(text, RichMarks(
                bold = o.bool("bold") == true, italic = o.bool("italic") == true,
                underline = o.bool("underline") == true, strike = o.bool("strike") == true,
                code = o.bool("code") == true, color = NoteColor.fromToken(o.string("color")),
                bg = NoteColor.fromToken(o.string("bg")), link = sanitizeLink(o.string("link"))))
        }
    }

    private fun JsonObject.string(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

    private fun JsonObject.bool(key: String): Boolean? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.int(key: String): Int? =
        get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
            ?.takeIf { it == Math.floor(it) && it in -1e6..1e6 }?.toInt()
}

/**
 * Combines two edits of the same note into one "keep both" document: the blocks both versions
 * share appear once, and each side's own blocks keep their place in document order (the current
 * device's first where they compete). Used for a "Keep both edits" conflict choice.
 */
object RichNoteMerge {
    /** Above this many block pairs the middle parts are kept one after the other instead. */
    private const val MAX_PAIRS = 1_000_000

    /** A block with the facts [combine] compares: an unformatted line, and its plain text. */
    private class Keyed(val block: RichBlock) {
        val plainLine = block is TextBlock && block.kind == BlockKind.PARAGRAPH && block.spans.all { it.marks.isPlain }
        val text = RichNotes.plainText(RichNoteDocument(listOf(block))).trim()
    }

    /**
     * Two blocks are the same content when equal, or when one is an unformatted line (as an
     * app without rich notes writes) that reads like the other's plain text.
     */
    private fun matches(a: Keyed, b: Keyed) = a.block == b.block || (a.plainLine != b.plainLine && a.text == b.text)

    /** Of two matching blocks, the formatted one is kept. */
    private fun pick(a: Keyed, b: Keyed): RichBlock = if (a.plainLine && !b.plainLine) b.block else a.block

    fun combine(current: RichNoteDocument, incoming: RichNoteDocument): RichNoteDocument {
        val a = RichNotes.normalize(current).blocks.map(::Keyed)
        val b = RichNotes.normalize(incoming).blocks.map(::Keyed)
        if (a.map { it.block } == b.map { it.block }) return RichNoteDocument(a.map { it.block })
        // Shared start and end appear once; only the middle parts are compared.
        val head = mutableListOf<RichBlock>()
        var prefix = 0
        while (prefix < a.size && prefix < b.size && matches(a[prefix], b[prefix])) { head += pick(a[prefix], b[prefix]); prefix++ }
        val tail = ArrayDeque<RichBlock>()
        var suffix = 0
        while (suffix < a.size - prefix && suffix < b.size - prefix && matches(a[a.size - 1 - suffix], b[b.size - 1 - suffix])) {
            tail.addFirst(pick(a[a.size - 1 - suffix], b[b.size - 1 - suffix])); suffix++
        }
        val midA = a.subList(prefix, a.size - suffix)
        val midB = b.subList(prefix, b.size - suffix)
        val middle = if (midA.size.toLong() * midB.size > MAX_PAIRS) {
            val seen = midA.map { it.text }.toHashSet()
            midA.map { it.block } + midB.filterNot { it.text in seen }.map { it.block }
        } else interleave(midA, midB)
        return RichNotes.normalize(RichNoteDocument(head + middle + tail))
    }

    /** Longest common subsequence of blocks, so shared content appears once. */
    private fun interleave(a: List<Keyed>, b: List<Keyed>): List<RichBlock> {
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (matches(a[i], b[j])) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        val out = mutableListOf<RichBlock>()
        var i = 0
        var j = 0
        while (i < a.size || j < b.size) {
            when {
                i < a.size && j < b.size && matches(a[i], b[j]) -> { out += pick(a[i], b[j]); i++; j++ }
                j >= b.size || (i < a.size && lengths[i + 1][j] >= lengths[i][j + 1]) -> { out += a[i].block; i++ }
                else -> { out += b[j].block; j++ }
            }
        }
        return out
    }

    /**
     * "Keep both edits" for a note in a sync conflict: each side's stored (plain, formatting)
     * values in, the combined values to store out. Throws [RichNoteTooLargeException].
     */
    fun combineStored(currentPlain: String, currentJson: String, incomingPlain: String, incomingJson: String): Pair<String, String> =
        RichNotes.storageFields(combine(RichNotes.load(currentPlain, currentJson).document,
            RichNotes.load(incomingPlain, incomingJson).document))
}
