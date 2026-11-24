package com.privatevault.app.notes

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichNoteEditorTest {
    private val palette = RichNotePalette(true, Color.Blue, Color.Gray, Color.LightGray, Color.Blue)

    private fun segment(vararg blocks: TextBlock) = RichTextSegment.fromBlocks(blocks.toList())

    private fun RichTextSegment.type(text: String) {
        val caret = value.selection.start
        val next = this.text.substring(0, caret) + text + this.text.substring(value.selection.end)
        onValueChange(TextFieldValue(next, TextRange(caret + text.length)))
    }

    private fun RichTextSegment.select(start: Int, end: Int = start) = onValueChange(value.copy(selection = TextRange(start, end)))

    @Test fun typingAfterBoldTextStaysBold() {
        val s = segment(TextBlock(spans = listOf(RichSpan("Hi", RichMarks(bold = true)))))
        s.select(2)
        s.type("!")
        assertEquals(listOf(RichSpan("Hi!", RichMarks(bold = true))), s.toBlocks().single().spans)
    }

    @Test fun repeatedLettersExtendTheMarkAtTheCaret() {
        val s = segment(TextBlock(spans = listOf(RichSpan("a"), RichSpan("l", RichMarks(bold = true)))))
        s.select(2)
        s.type("l")
        assertEquals(listOf(RichSpan("a"), RichSpan("ll", RichMarks(bold = true))), s.toBlocks().single().spans)
    }

    @Test fun boldToggledAtTheCaretAppliesToTheNextLetters() {
        val s = segment(TextBlock(spans = listOf(RichSpan("Hi "))))
        s.select(3)
        s.toggleMark(Mark(MarkKind.BOLD))
        assertTrue(Mark(MarkKind.BOLD) in s.activeMarks())
        s.type("there")
        assertEquals(listOf(RichSpan("Hi "), RichSpan("there", RichMarks(bold = true))), s.toBlocks().single().spans)
    }

    @Test fun boldOverASelectionTogglesOnAndOff() {
        val s = segment(TextBlock(spans = listOf(RichSpan("one two"))))
        s.select(0, 3)
        s.toggleMark(Mark(MarkKind.BOLD))
        assertEquals(listOf(RichSpan("one", RichMarks(bold = true)), RichSpan(" two")), s.toBlocks().single().spans)
        s.toggleMark(Mark(MarkKind.BOLD))
        assertEquals(listOf(RichSpan("one two")), s.toBlocks().single().spans)
    }

    @Test fun colorsReplaceEachOther() {
        val s = segment(TextBlock(spans = listOf(RichSpan("abc"))))
        s.select(0, 3)
        s.setValueMark(MarkKind.COLOR, Mark(MarkKind.COLOR, color = NoteColor.RED))
        s.select(1, 2)
        s.setValueMark(MarkKind.COLOR, Mark(MarkKind.COLOR, color = NoteColor.BLUE))
        assertEquals(listOf(RichSpan("a", RichMarks(color = NoteColor.RED)), RichSpan("b", RichMarks(color = NoteColor.BLUE)),
            RichSpan("c", RichMarks(color = NoteColor.RED))), s.toBlocks().single().spans)
    }

    @Test fun enterContinuesAListAndAnEmptyItemEndsIt() {
        val s = segment(TextBlock(BlockKind.CHECK, listOf(RichSpan("Milk")), checked = true))
        s.select(4)
        s.type("\n")
        assertEquals(listOf(LineAttrs(BlockKind.CHECK, checked = true), LineAttrs(BlockKind.CHECK)), s.lines)
        s.type("\n")
        assertEquals("Milk\n", s.text)
        assertEquals(LineAttrs(), s.lines[1])
    }

    @Test fun enterOnANestedEmptyItemStepsOut() {
        val s = segment(TextBlock(BlockKind.BULLET, listOf(RichSpan("a")), indent = 1), TextBlock(BlockKind.BULLET, indent = 1))
        s.select(2)
        s.type("\n")
        assertEquals(LineAttrs(BlockKind.BULLET, indent = 0), s.lines[1])
    }

    @Test fun enterAfterAHeadingStartsNormalText() {
        val s = segment(TextBlock(BlockKind.HEADING, listOf(RichSpan("Title")), level = 2))
        s.select(5)
        s.type("\nBody")
        assertEquals(listOf(LineAttrs(BlockKind.HEADING, level = 2), LineAttrs()), s.lines)
    }

    @Test fun enterAtTheStartOfALineKeepsItsStyle() {
        val s = segment(TextBlock(BlockKind.HEADING, listOf(RichSpan("Title")), level = 1))
        s.select(0)
        s.type("\n")
        assertEquals(listOf(LineAttrs(), LineAttrs(BlockKind.HEADING, level = 1)), s.lines)
    }

    @Test fun deletingALineBreakJoinsLines() {
        val s = segment(TextBlock(BlockKind.BULLET, listOf(RichSpan("a"))), TextBlock(spans = listOf(RichSpan("b", RichMarks(italic = true)))))
        s.onValueChange(TextFieldValue("ab", TextRange(1)))
        assertEquals(listOf(TextBlock(BlockKind.BULLET, listOf(RichSpan("a"), RichSpan("b", RichMarks(italic = true))))), s.toBlocks())
    }

    @Test fun pastedControlAndDirectionCharactersAreRemoved() {
        val s = segment(TextBlock())
        s.type("safe‮txt.exe\u0007")
        assertEquals("safetxt.exe", s.text)
        assertEquals(11, s.value.selection.start)
    }

    @Test fun lineStylesToggle() {
        val s = segment(TextBlock(spans = listOf(RichSpan("a"))), TextBlock(spans = listOf(RichSpan("b"))))
        s.select(0, 3)
        s.setLineKind(BlockKind.NUMBERED)
        assertEquals(listOf(BlockKind.NUMBERED, BlockKind.NUMBERED), s.lines.map { it.kind })
        s.indent(1)
        assertEquals(listOf(1, 1), s.lines.map { it.indent })
        s.setLineKind(BlockKind.NUMBERED)
        assertEquals(listOf(LineAttrs(), LineAttrs()), s.lines)
    }

    @Test fun linksAreInsertedAndRemoved() {
        val s = segment(TextBlock(spans = listOf(RichSpan("see "))))
        s.select(4)
        s.insertLink("site", "https://example.com")
        assertEquals(listOf(RichSpan("see "), RichSpan("site", RichMarks(link = "https://example.com"))), s.toBlocks().single().spans)
        s.type(" ok")
        assertEquals(RichSpan(" ok"), s.toBlocks().single().spans.last())
        s.select(5)
        s.removeLink()
        assertEquals(listOf(RichSpan("see site ok")), s.toBlocks().single().spans)
    }

    @Test fun visualTransformationAddsMarkersAndMapsOffsets() {
        val s = segment(TextBlock(BlockKind.NUMBERED, listOf(RichSpan("one"))), TextBlock(BlockKind.NUMBERED, listOf(RichSpan("two"))),
            TextBlock(BlockKind.CHECK, listOf(RichSpan("x")), checked = true), TextBlock(spans = listOf(RichSpan("p", RichMarks(bold = true)))))
        val transformed = RichVisualTransformation(s.lines, s.ranges, palette).filter(AnnotatedString(s.text))
        assertEquals("1. one\n2. two\n☑ x\np", transformed.text.text)
        val mapping = transformed.offsetMapping
        for (offset in 0..s.text.length) {
            val t = mapping.originalToTransformed(offset)
            assertEquals("offset $offset", offset, mapping.transformedToOriginal(t))
        }
        assertEquals(3, mapping.originalToTransformed(0))
        assertEquals(0, mapping.transformedToOriginal(1))
        assertEquals(4, mapping.transformedToOriginal(7))
    }

    @Test fun editorInsertsTablesAndUndoes() {
        val state = RichNoteEditorState(RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("before"))), TextBlock(spans = listOf(RichSpan("after"))))))
        state.focusedKey = state.items.first().key
        (state.items.first() as TextItem).segment.select(2)
        state.insertTable(2, 3, true)
        val doc = state.document()
        assertEquals(3, doc.blocks.size)
        assertEquals(TableBlock(true, List(2) { List(3) { emptyList() } }), doc.blocks[1])
        assertTrue(state.canUndo)
        state.undo()
        assertEquals(2, state.document().blocks.size)
        state.redo()
        assertEquals(3, state.document().blocks.size)
        state.remove(state.items.first { it is TableItem })
        assertEquals(RichNotes.normalize(RichNoteDocument(listOf(TextBlock(spans = listOf(RichSpan("before"))), TextBlock(spans = listOf(RichSpan("after")))))),
            state.document())
        assertEquals(1, state.items.size)
    }

    @Test fun tableCellsKeepFormattingUntilEdited() {
        val table = TableItem(true, listOf(listOf(listOf(RichSpan("Bold", RichMarks(bold = true))), listOf(RichSpan("x")))))
        assertEquals(listOf(RichSpan("Bold", RichMarks(bold = true))), table.toBlock().rows[0][0])
        table.rows[0][0].text = "Plain"
        assertEquals(listOf(RichSpan("Plain")), table.toBlock().rows[0][0])
        table.addRow(); table.addColumn()
        assertEquals(2, table.rows.size); assertEquals(3, table.columnCount)
    }

    @Test fun unchangedNotesReportNoChanges() {
        val state = RichNoteEditorState.forNote("Line one\nLine two", "")
        assertFalse(state.hasChanges)
        assertEquals("Line one\nLine two", RichNotes.storageFields(state.document()).first)
        assertEquals("", RichNotes.storageFields(state.document()).second)
    }
}
