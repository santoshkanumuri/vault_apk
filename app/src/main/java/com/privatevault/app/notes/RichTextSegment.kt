package com.privatevault.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** One inline mark over a character range of a text segment. */
internal enum class MarkKind { BOLD, ITALIC, UNDERLINE, STRIKE, CODE, COLOR, BG, LINK }

internal data class Mark(val kind: MarkKind, val color: NoteColor? = null, val link: String? = null)

internal data class MarkRange(val start: Int, val end: Int, val mark: Mark)

/** Paragraph-level attributes of one line (one [TextBlock]). */
internal data class LineAttrs(
    val kind: BlockKind = BlockKind.PARAGRAPH,
    val level: Int = 1,
    val indent: Int = 0,
    val checked: Boolean = false,
) {
    /** What a new line created by pressing Enter on this line gets. */
    fun continuation(): LineAttrs = when (kind) {
        BlockKind.HEADING -> LineAttrs()
        BlockKind.CHECK -> copy(checked = false)
        else -> this
    }
}

internal fun RichMarks.toMarks(): List<Mark> = buildList {
    if (bold) add(Mark(MarkKind.BOLD))
    if (italic) add(Mark(MarkKind.ITALIC))
    if (underline) add(Mark(MarkKind.UNDERLINE))
    if (strike) add(Mark(MarkKind.STRIKE))
    if (code) add(Mark(MarkKind.CODE))
    color?.let { add(Mark(MarkKind.COLOR, color = it)) }
    bg?.let { add(Mark(MarkKind.BG, color = it)) }
    link?.let { add(Mark(MarkKind.LINK, link = it)) }
}

internal fun Collection<Mark>.toRichMarks(): RichMarks = RichMarks(
    bold = any { it.kind == MarkKind.BOLD }, italic = any { it.kind == MarkKind.ITALIC },
    underline = any { it.kind == MarkKind.UNDERLINE }, strike = any { it.kind == MarkKind.STRIKE },
    code = any { it.kind == MarkKind.CODE }, color = firstOrNull { it.kind == MarkKind.COLOR }?.color,
    bg = firstOrNull { it.kind == MarkKind.BG }?.color, link = firstOrNull { it.kind == MarkKind.LINK }?.link)

/**
 * A run of text lines edited in one text field. The field holds only the characters; marks and
 * line attributes are kept here and drawn by [RichVisualTransformation], so pasted styled text
 * can never bring its own formatting in (it is rebuilt from this model only).
 */
internal class RichTextSegment(text: String, lines: List<LineAttrs>, ranges: List<MarkRange>) {
    var value by mutableStateOf(TextFieldValue(text))
        private set
    var lines by mutableStateOf(lines)
        private set
    var ranges by mutableStateOf(normalizeRanges(ranges, text.length))
        private set
    /** Marks chosen with the caret collapsed, used for the next typed text. */
    var pendingMarks by mutableStateOf<Set<Mark>?>(null)
        private set

    /** Called before any change, so the editor can record an undo step. */
    var beforeChange: (force: Boolean) -> Unit = {}

    init { require(lines.size == text.count { it == '\n' } + 1) }

    val text: String get() = value.text

    // ---- Reading ----

    fun lineStarts(text: String = this.text): IntArray {
        val starts = IntArray(text.count { it == '\n' } + 1)
        var line = 1
        text.forEachIndexed { index, char -> if (char == '\n') starts[line++] = index + 1 }
        return starts
    }

    fun lineOf(offset: Int, text: String = this.text): Int {
        var line = 0
        for (index in 0 until offset.coerceAtMost(text.length)) if (text[index] == '\n') line++
        return line
    }

    fun marksAt(offset: Int): Set<Mark> =
        ranges.filter { offset >= it.start && offset < it.end }.map { it.mark }.toSet()

    /** Marks typed text would get at [offset]: the previous character's, or the next one's at a line start. */
    fun typingMarks(offset: Int): Set<Mark> {
        val text = text
        val before = if (offset > 0 && text[offset - 1] != '\n') marksAt(offset - 1) else null
        val after = if (offset < text.length && text[offset] != '\n') marksAt(offset) else emptySet()
        val inherited = before ?: after
        // A link only continues when typing inside it, never at its edges.
        return inherited.filter { it.kind != MarkKind.LINK || (before != null && it in before && it in after) }.toSet()
    }

    fun activeMarks(): Set<Mark> {
        val selection = value.selection
        if (selection.collapsed) return pendingMarks ?: typingMarks(selection.start)
        val (start, end) = selection.min to selection.max
        val candidates = marksAt(start)
        return candidates.filter { mark -> covers(start, end, mark) }.toSet()
    }

    private fun covers(start: Int, end: Int, mark: Mark): Boolean {
        val text = text
        var position = start
        val sorted = ranges.filter { it.mark == mark }.sortedBy { it.start }
        while (position < end) {
            if (text[position] == '\n') { position++; continue }
            val range = sorted.firstOrNull { position >= it.start && position < it.end } ?: return false
            position = range.end
        }
        return true
    }

    fun selectedLines(): IntRange {
        val selection = value.selection
        return lineOf(selection.min)..lineOf(selection.max)
    }

    fun toBlocks(): List<TextBlock> {
        val text = text
        val starts = lineStarts(text)
        return lines.mapIndexed { index, attrs ->
            val start = starts[index]
            val end = if (index + 1 < starts.size) starts[index + 1] - 1 else text.length
            val cuts = sortedSetOf(start, end)
            ranges.forEach { range ->
                if (range.start in start..end) cuts += range.start
                if (range.end in start..end) cuts += range.end
            }
            val points = cuts.toList()
            val spans = points.zipWithNext().mapNotNull { (from, to) ->
                if (from >= to) null else RichSpan(text.substring(from, to), marksAt(from).toRichMarks())
            }
            TextBlock(attrs.kind, RichNotes.normalizeSpans(spans), attrs.level, attrs.indent, attrs.checked)
        }
    }

    // ---- Typing ----

    fun onValueChange(incoming: TextFieldValue) {
        val old = value
        if (incoming.text == old.text) {
            if (incoming.selection != old.selection) pendingMarks = null
            value = incoming
            return
        }
        val oldText = old.text
        var newText = incoming.text
        // Find the edited region. With a collapsed caret, anchor a pure insertion at the caret so
        // repeated letters extend the right mark.
        var prefix = 0
        var deletedEnd: Int
        var inserted: String
        val caret = incoming.selection.start
        val grow = newText.length - oldText.length
        val anchored = incoming.selection.collapsed && grow > 0 && caret - grow >= 0 && caret <= newText.length &&
            newText.regionMatches(0, oldText, 0, caret - grow) &&
            newText.regionMatches(caret, oldText, caret - grow, oldText.length - (caret - grow))
        if (anchored) {
            prefix = caret - grow
            deletedEnd = prefix
            inserted = newText.substring(prefix, caret)
        } else {
            val limit = minOf(oldText.length, newText.length)
            while (prefix < limit && oldText[prefix] == newText[prefix]) prefix++
            var suffix = 0
            while (suffix < limit - prefix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++
            deletedEnd = oldText.length - suffix
            inserted = newText.substring(prefix, newText.length - suffix)
        }
        var selection = incoming.selection
        var composition = incoming.composition
        val clean = RichNotes.sanitizeText(inserted)
        if (clean != inserted) {
            newText = oldText.substring(0, prefix) + clean + oldText.substring(deletedEnd)
            inserted = clean
            selection = TextRange(prefix + clean.length)
            composition = null
        }

        val oldStarts = lineStarts(oldText)
        val firstLine = lineOf(prefix, oldText)
        // Enter on an empty list item ends the list (or steps out one level) instead of adding a line.
        if (inserted == "\n" && deletedEnd == prefix) {
            val attrs = lines[firstLine]
            val lineEnd = if (firstLine + 1 < oldStarts.size) oldStarts[firstLine + 1] - 1 else oldText.length
            if (oldStarts[firstLine] == lineEnd && (attrs.kind.isListItem || attrs.kind == BlockKind.QUOTE || attrs.kind == BlockKind.CODE)) {
                beforeChange(true)
                lines = lines.toMutableList().also {
                    it[firstLine] = if (attrs.indent > 0) attrs.copy(indent = attrs.indent - 1) else LineAttrs()
                }
                value = old.copy(selection = TextRange(prefix))
                return
            }
        }

        beforeChange(inserted.contains('\n') || deletedEnd - prefix > 1 || inserted.length > 1)
        val typing = pendingMarks ?: typingMarks(prefix)
        ranges = shiftRanges(ranges, prefix, deletedEnd, inserted, typing, newText.length)
        lines = shiftLines(oldText, prefix, deletedEnd, inserted)
        pendingMarks = null
        value = TextFieldValue(newText, selection, composition)
    }

    private fun shiftLines(oldText: String, start: Int, end: Int, inserted: String): List<LineAttrs> {
        val first = lineOf(start, oldText)
        val last = lineOf(end, oldText)
        val newLines = inserted.count { it == '\n' }
        val starts = lineStarts(oldText)
        val lineEnd = if (first + 1 < starts.size) starts[first + 1] - 1 else oldText.length
        val attrs = lines[first]
        val region = if (inserted == "\n" && start == end && start == starts[first] && lineEnd > start) {
            // Enter at the start of a line moves it down and leaves an empty line above.
            listOf(attrs.continuation(), attrs)
        } else listOf(attrs) + List(newLines) { attrs.continuation() }
        return lines.subList(0, first) + region + lines.subList(last + 1, lines.size)
    }

    // ---- Commands ----

    fun toggleMark(mark: Mark) {
        val selection = value.selection
        val active = mark in activeMarks()
        if (selection.collapsed) {
            val base = pendingMarks ?: typingMarks(selection.start)
            pendingMarks = if (active) base - mark else exclusive(base, mark) + mark
            return
        }
        beforeChange(true)
        ranges = if (active) removeMark(ranges, selection.min, selection.max, mark.kind)
            else applyMark(ranges, selection.min, selection.max, mark)
    }

    /** Sets (or with null, clears) a color, highlight or link over the selection. */
    fun setValueMark(kind: MarkKind, mark: Mark?) {
        val selection = value.selection
        if (selection.collapsed) {
            val base = (pendingMarks ?: typingMarks(selection.start)).filter { it.kind != kind }.toSet()
            pendingMarks = if (mark == null) base else base + mark
            return
        }
        beforeChange(true)
        val cleared = removeMark(ranges, selection.min, selection.max, kind)
        ranges = if (mark == null) cleared else applyMark(cleared, selection.min, selection.max, mark)
    }

    /** Inserts [label] as a link at the caret, replacing any selected text. */
    fun insertLink(label: String, link: String, replace: IntRange? = null) {
        val clean = RichNotes.sanitizeText(label).replace('\n', ' ').ifEmpty { link }
        val selection = value.selection
        val start = replace?.first ?: selection.min
        val end = replace?.let { it.last + 1 } ?: selection.max
        val text = text
        val newText = text.substring(0, start) + clean + text.substring(end)
        onValueChange(TextFieldValue(newText, TextRange(start + clean.length)))
        beforeChange(true)
        ranges = applyMark(removeMark(ranges, start, start + clean.length, MarkKind.LINK), start, start + clean.length,
            Mark(MarkKind.LINK, link = link))
    }

    /** The link the caret or selection sits inside, if any. */
    fun linkRangeAtCaret(): MarkRange? {
        val selection = value.selection
        return ranges.firstOrNull { it.mark.kind == MarkKind.LINK && it.start <= selection.min && selection.max <= it.end &&
            (selection.min > it.start || selection.max < it.end || !selection.collapsed) }
    }

    fun removeLink() {
        val selection = value.selection
        val range = linkRangeAtCaret()
        beforeChange(true)
        ranges = if (selection.collapsed && range != null) ranges - range
            else removeMark(ranges, selection.min, selection.max, MarkKind.LINK)
        pendingMarks = pendingMarks?.filter { it.kind != MarkKind.LINK }?.toSet()
    }

    fun linkAtSelection(): String? = activeMarks().firstOrNull { it.kind == MarkKind.LINK }?.link
        ?: marksAt(value.selection.min).firstOrNull { it.kind == MarkKind.LINK }?.link

    fun clearFormatting() {
        beforeChange(true)
        val selection = value.selection
        if (selection.collapsed) pendingMarks = emptySet()
        else ranges = ranges.flatMap { subtract(it, selection.min, selection.max) }
        val selected = selectedLines()
        lines = lines.mapIndexed { index, attrs -> if (index in selected) LineAttrs() else attrs }
    }

    /** Applies a line style to every selected line; applying the current style again turns it off. */
    fun setLineKind(kind: BlockKind, level: Int = 1) {
        beforeChange(true)
        val selected = selectedLines()
        val allSame = selected.all { lines[it].kind == kind && (kind != BlockKind.HEADING || lines[it].level == level) }
        lines = lines.mapIndexed { index, attrs ->
            if (index !in selected) attrs
            else if (allSame && kind != BlockKind.PARAGRAPH) LineAttrs()
            else LineAttrs(kind, level, if (kind.isListItem) attrs.indent else 0, attrs.checked && kind == BlockKind.CHECK)
        }
    }

    fun indent(delta: Int) {
        beforeChange(true)
        val selected = selectedLines()
        lines = lines.mapIndexed { index, attrs ->
            if (index in selected && attrs.kind.isListItem) attrs.copy(indent = (attrs.indent + delta).coerceIn(0, RichNotes.MAX_INDENT))
            else attrs
        }
    }

    fun toggleChecked(line: Int) {
        if (lines.getOrNull(line)?.kind != BlockKind.CHECK) return
        beforeChange(true)
        lines = lines.toMutableList().also { it[line] = it[line].copy(checked = !it[line].checked) }
    }

    /** Splits after the caret's line: returns the lines after it as a new segment (or null at the end). */
    fun splitAfterCaretLine(): RichTextSegment? {
        val line = lineOf(value.selection.max)
        val starts = lineStarts()
        if (line + 1 >= starts.size) return null
        val cut = starts[line + 1]
        val tail = fromBlocks(toBlocks().drop(line + 1))
        val keepText = text.substring(0, cut - 1)
        ranges = normalizeRanges(ranges.mapNotNull { r -> if (r.start >= cut - 1) null else r.copy(end = minOf(r.end, cut - 1)) }, keepText.length)
        lines = lines.take(line + 1)
        value = TextFieldValue(keepText, TextRange(keepText.length))
        return tail
    }

    fun moveCaretToEnd() { value = value.copy(selection = TextRange(text.length)) }

    companion object {
        fun fromBlocks(blocks: List<TextBlock>): RichTextSegment {
            val source = blocks.ifEmpty { listOf(TextBlock()) }
            val text = StringBuilder()
            val ranges = mutableListOf<MarkRange>()
            source.forEachIndexed { index, block ->
                if (index > 0) text.append('\n')
                block.spans.forEach { span ->
                    val start = text.length
                    text.append(span.text.replace('\n', ' '))
                    span.marks.toMarks().forEach { ranges += MarkRange(start, text.length, it) }
                }
            }
            return RichTextSegment(text.toString(),
                source.map { LineAttrs(it.kind, it.level, it.indent, it.checked) }, ranges)
        }

        private fun exclusive(marks: Set<Mark>, mark: Mark): Set<Mark> =
            if (mark.kind in setOf(MarkKind.COLOR, MarkKind.BG, MarkKind.LINK)) marks.filter { it.kind != mark.kind }.toSet() else marks

        internal fun shiftRanges(ranges: List<MarkRange>, start: Int, end: Int, inserted: String, typing: Set<Mark>, newLength: Int): List<MarkRange> {
            val n = inserted.length
            fun map(x: Int) = if (x <= start) x else if (x >= end) x - (end - start) + n else start
            val shifted = ranges.map { MarkRange(map(it.start), map(it.end), it.mark) }
                .flatMap { subtract(it, start, start + n) }
            val added = if (n == 0) emptyList() else typing.map { MarkRange(start, start + n, it) }
            return normalizeRanges(shifted + added, newLength)
        }

        internal fun applyMark(ranges: List<MarkRange>, start: Int, end: Int, mark: Mark): List<MarkRange> {
            val base = if (mark.kind in setOf(MarkKind.COLOR, MarkKind.BG, MarkKind.LINK))
                ranges.flatMap { if (it.mark.kind == mark.kind) subtract(it, start, end) else listOf(it) } else ranges
            return normalizeRanges(base + MarkRange(start, end, mark), Int.MAX_VALUE)
        }

        internal fun removeMark(ranges: List<MarkRange>, start: Int, end: Int, kind: MarkKind): List<MarkRange> =
            ranges.flatMap { if (it.mark.kind == kind) subtract(it, start, end) else listOf(it) }

        private fun subtract(range: MarkRange, start: Int, end: Int): List<MarkRange> {
            if (end <= range.start || start >= range.end || start == end) return listOf(range)
            return listOfNotNull(
                if (range.start < start) range.copy(end = start) else null,
                if (range.end > end) range.copy(start = end) else null)
        }

        internal fun normalizeRanges(ranges: List<MarkRange>, length: Int): List<MarkRange> =
            ranges.map { it.copy(start = it.start.coerceIn(0, length), end = it.end.coerceIn(0, length)) }
                .filter { it.end > it.start }
                .groupBy { it.mark }
                .flatMap { (mark, group) ->
                    val merged = mutableListOf<MarkRange>()
                    group.sortedBy { it.start }.forEach { range ->
                        val last = merged.lastOrNull()
                        if (last != null && range.start <= last.end) merged[merged.lastIndex] = last.copy(end = maxOf(last.end, range.end))
                        else merged += MarkRange(range.start, range.end, mark)
                    }
                    merged
                }
    }
}
