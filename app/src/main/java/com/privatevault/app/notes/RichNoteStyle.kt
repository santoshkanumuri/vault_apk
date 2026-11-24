package com.privatevault.app.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Theme colors for note color tokens. Shared values with the Windows app (docs/RICH-NOTES-PLAN.md). */
internal object NoteColors {
    private val lightText = mapOf(
        NoteColor.GRAY to 0xFF5F6368, NoteColor.RED to 0xFFC62828, NoteColor.ORANGE to 0xFFE65100,
        NoteColor.YELLOW to 0xFF9A6700, NoteColor.GREEN to 0xFF2E7D32, NoteColor.TEAL to 0xFF00796B,
        NoteColor.BLUE to 0xFF1565C0, NoteColor.PURPLE to 0xFF6A1B9A, NoteColor.PINK to 0xFFAD1457)
    private val darkText = mapOf(
        NoteColor.GRAY to 0xFFB0B4BA, NoteColor.RED to 0xFFFF8A80, NoteColor.ORANGE to 0xFFFFB74D,
        NoteColor.YELLOW to 0xFFFFE082, NoteColor.GREEN to 0xFF81C784, NoteColor.TEAL to 0xFF80CBC4,
        NoteColor.BLUE to 0xFF90CAF9, NoteColor.PURPLE to 0xFFCE93D8, NoteColor.PINK to 0xFFF48FB1)
    private val lightBg = mapOf(
        NoteColor.GRAY to 0xFFE8EAED, NoteColor.RED to 0xFFFFDAD6, NoteColor.ORANGE to 0xFFFFE0B2,
        NoteColor.YELLOW to 0xFFFFF59D, NoteColor.GREEN to 0xFFC8E6C9, NoteColor.TEAL to 0xFFB2DFDB,
        NoteColor.BLUE to 0xFFBBDEFB, NoteColor.PURPLE to 0xFFE1BEE7, NoteColor.PINK to 0xFFF8BBD0)
    private val darkBg = mapOf(
        NoteColor.GRAY to 0xFF3C4043, NoteColor.RED to 0xFF5C1F1B, NoteColor.ORANGE to 0xFF5A3300,
        NoteColor.YELLOW to 0xFF524800, NoteColor.GREEN to 0xFF1E4620, NoteColor.TEAL to 0xFF0E3F3A,
        NoteColor.BLUE to 0xFF173A5E, NoteColor.PURPLE to 0xFF3E2050, NoteColor.PINK to 0xFF561D38)

    fun text(color: NoteColor, light: Boolean) = Color((if (light) lightText else darkText).getValue(color))
    fun background(color: NoteColor, light: Boolean) = Color((if (light) lightBg else darkBg).getValue(color))

    fun label(color: NoteColor): String = color.token.replaceFirstChar { it.uppercase() }
}

/** Colors the editor and viewer draw with, taken from the app theme. */
internal data class RichNotePalette(
    val light: Boolean,
    val link: Color,
    val muted: Color,
    val codeBackground: Color,
    val accent: Color,
)

internal fun headingSize(level: Int): TextUnit = when (level) { 1 -> 24.sp; 2 -> 20.sp; else -> 17.sp }

internal fun markStyle(marks: RichMarks, palette: RichNotePalette): SpanStyle {
    val decorations = listOfNotNull(
        if (marks.underline || marks.link != null) TextDecoration.Underline else null,
        if (marks.strike) TextDecoration.LineThrough else null)
    return SpanStyle(
        fontWeight = if (marks.bold) FontWeight.Bold else null,
        fontStyle = if (marks.italic) FontStyle.Italic else null,
        fontFamily = if (marks.code) FontFamily.Monospace else null,
        textDecoration = if (decorations.isEmpty()) null else TextDecoration.combine(decorations),
        color = marks.color?.let { NoteColors.text(it, palette.light) }
            ?: if (marks.link != null) palette.link else Color.Unspecified,
        background = marks.bg?.let { NoteColors.background(it, palette.light) }
            ?: if (marks.code) palette.codeBackground else Color.Unspecified,
    )
}

internal fun lineStyle(attrs: LineAttrs, palette: RichNotePalette): SpanStyle? = when (attrs.kind) {
    BlockKind.HEADING -> SpanStyle(fontSize = headingSize(attrs.level), fontWeight = FontWeight.SemiBold)
    BlockKind.QUOTE -> SpanStyle(fontStyle = FontStyle.Italic, color = palette.muted)
    BlockKind.CODE -> SpanStyle(fontFamily = FontFamily.Monospace, background = palette.codeBackground)
    BlockKind.CHECK -> null
    else -> null
}

/** The marker drawn before a line: indent, bullet, number, checkbox or quote bar. */
internal fun linePrefix(attrs: LineAttrs, number: Int): String {
    val pad = " ".repeat(attrs.indent)
    return when (attrs.kind) {
        BlockKind.BULLET -> pad + (if (attrs.indent % 2 == 0) "•" else "◦") + " "
        BlockKind.NUMBERED -> "$pad$number. "
        BlockKind.CHECK -> pad + (if (attrs.checked) "☑" else "☐") + " "
        BlockKind.QUOTE -> "▎ "
        else -> ""
    }
}

/** Numbers for consecutive numbered lines at each indent, as the plain-text fallback counts them. */
internal fun lineNumbers(lines: List<LineAttrs>): IntArray {
    val numbers = IntArray(lines.size)
    val counters = IntArray(RichNotes.MAX_INDENT + 1)
    lines.forEachIndexed { index, attrs ->
        if (attrs.kind != BlockKind.NUMBERED) { counters.fill(0); return@forEachIndexed }
        val level = attrs.indent.coerceIn(0, RichNotes.MAX_INDENT)
        counters[level] += 1
        for (deeper in level + 1..RichNotes.MAX_INDENT) counters[deeper] = 0
        numbers[index] = counters[level]
    }
    return numbers
}

/**
 * Draws a [RichTextSegment]'s marks and line styles over the plain text in its field, adding
 * list markers in front of lines. The offset mapping keeps the caret off the markers.
 */
internal data class RichVisualTransformation(
    val lines: List<LineAttrs>,
    val ranges: List<MarkRange>,
    val palette: RichNotePalette,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val source = text.text
        val starts = IntArray(lines.size)
        var line = 1
        source.forEachIndexed { index, char -> if (char == '\n' && line < starts.size) starts[line++] = index + 1 }
        if (line != lines.size) return TransformedText(text, OffsetMapping.Identity)
        val numbers = lineNumbers(lines)
        val prefixes = lines.mapIndexed { index, attrs -> linePrefix(attrs, numbers[index]) }
        // shifts[i]: characters added before the text of line i.
        val shifts = IntArray(lines.size)
        var total = 0
        prefixes.forEachIndexed { index, prefix -> total += prefix.length; shifts[index] = total }
        val builder = AnnotatedString.Builder()
        lines.indices.forEach { index ->
            if (index > 0) builder.append('\n')
            val prefix = prefixes[index]
            if (prefix.isNotEmpty()) {
                val prefixStart = builder.length
                builder.append(prefix)
                builder.addStyle(SpanStyle(color = palette.accent, fontWeight = FontWeight.Normal, fontStyle = FontStyle.Normal), prefixStart, builder.length)
            }
            val end = if (index + 1 < lines.size) starts[index + 1] - 1 else source.length
            val textStart = builder.length
            builder.append(source.substring(starts[index], end))
            lineStyle(lines[index], palette)?.let { builder.addStyle(it, textStart, builder.length) }
            if (lines[index].kind == BlockKind.CHECK && lines[index].checked)
                builder.addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough, color = palette.muted), textStart, builder.length)
        }
        fun toTransformed(offset: Int): Int {
            val clamped = offset.coerceIn(0, source.length)
            var index = starts.size - 1
            while (index > 0 && starts[index] > clamped) index--
            return clamped + shifts[index]
        }
        ranges.forEach { range ->
            val start = toTransformed(range.start)
            val end = toTransformed(range.end).coerceAtLeast(start)
            if (end > start) builder.addStyle(markStyle(listOf(range.mark).toRichMarks(), palette), start, end)
        }
        val transformedLength = builder.length
        val mapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int = toTransformed(offset).coerceIn(0, transformedLength)
            override fun transformedToOriginal(offset: Int): Int {
                var index = 0
                while (index + 1 < starts.size && starts[index + 1] + shifts[index + 1] - prefixes[index + 1].length <= offset) index++
                val lineStart = starts[index] + shifts[index]
                return if (offset < lineStart) starts[index] else (offset - shifts[index]).coerceIn(0, source.length)
            }
        }
        return TransformedText(builder.toAnnotatedString(), mapping)
    }
}

/** A text block rendered for reading, with link annotations under the tag "link". */
internal fun spansToAnnotated(spans: List<RichSpan>, palette: RichNotePalette): AnnotatedString {
    val builder = AnnotatedString.Builder()
    spans.forEach { span ->
        val start = builder.length
        builder.append(span.text)
        builder.addStyle(markStyle(span.marks, palette), start, builder.length)
        span.marks.link?.let { builder.addStringAnnotation("link", it, start, builder.length) }
    }
    return builder.toAnnotatedString()
}
