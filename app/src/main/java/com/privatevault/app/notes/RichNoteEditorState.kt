package com.privatevault.app.notes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import java.util.UUID

internal class TableCell(text: String, private val original: List<RichSpan>) {
    var text by mutableStateOf(text)
    /** Cells keep their formatting until their text is changed here. */
    fun spans(): List<RichSpan> = if (text == original.joinToString("") { it.text }) original
        else listOf(RichSpan(RichNotes.sanitizeText(text).replace('\n', ' ')))
}

internal sealed interface EditorItem { val key: String }

internal class TextItem(val segment: RichTextSegment, override val key: String = UUID.randomUUID().toString()) : EditorItem

internal class DividerItem(override val key: String = UUID.randomUUID().toString()) : EditorItem

internal class TableItem(header: Boolean, rows: List<List<List<RichSpan>>>, override val key: String = UUID.randomUUID().toString()) : EditorItem {
    var header by mutableStateOf(header)
    val rows: SnapshotStateList<SnapshotStateList<TableCell>> = mutableStateListOf<SnapshotStateList<TableCell>>().apply {
        val columns = rows.maxOfOrNull { it.size } ?: 1
        rows.forEach { row ->
            add(mutableStateListOf<TableCell>().apply {
                (row + List(columns - row.size) { emptyList() }).forEach { add(TableCell(it.joinToString("") { span -> span.text }, it)) }
            })
        }
    }
    val columnCount: Int get() = rows.firstOrNull()?.size ?: 0

    fun addRow(after: Int = rows.lastIndex) {
        if (rows.size >= RichNotes.MAX_TABLE_ROWS) return
        rows.add((after + 1).coerceIn(0, rows.size), mutableStateListOf<TableCell>().apply { repeat(columnCount) { add(TableCell("", emptyList())) } })
    }
    fun removeRow(index: Int) { if (rows.size > 1 && index in rows.indices) rows.removeAt(index) }
    fun addColumn(after: Int = columnCount - 1) {
        if (columnCount >= RichNotes.MAX_TABLE_COLUMNS) return
        rows.forEach { it.add((after + 1).coerceIn(0, it.size), TableCell("", emptyList())) }
    }
    fun removeColumn(index: Int) { if (columnCount > 1) rows.forEach { if (index in it.indices) it.removeAt(index) } }
    fun toBlock(): TableBlock = TableBlock(header, rows.map { row -> row.map { it.spans() } })
}

/**
 * Editing state for a note body: text segments between tables and dividers, the focused place
 * for toolbar commands, and undo and redo. Everything is rebuilt into a [RichNoteDocument] to save.
 */
internal class RichNoteEditorState(initial: RichNoteDocument, val source: RichNoteSource = RichNoteSource.RICH) {
    val items: SnapshotStateList<EditorItem> = mutableStateListOf()
    var focusedKey by mutableStateOf<String?>(null)
    /** Focused table cell as (row, column), when a table has focus. */
    var focusedCell by mutableStateOf<Pair<Int, Int>?>(null)
    /** Bumped after undo, redo and structural changes, so the UI can move focus. */
    var focusRequest by mutableStateOf(0)
        private set
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    private val undo = ArrayDeque<RichNoteDocument>()
    private val redo = ArrayDeque<RichNoteDocument>()
    private var lastCheckpoint = 0L
    private val initialDocument: RichNoteDocument

    init {
        load(initial)
        initialDocument = document()
    }

    val focusedSegment: RichTextSegment?
        get() = (items.firstOrNull { it.key == focusedKey } as? TextItem)?.segment
            ?: (items.lastOrNull { it is TextItem } as? TextItem)?.segment

    val focusedTable: TableItem? get() = items.firstOrNull { it.key == focusedKey } as? TableItem

    val hasChanges: Boolean get() = document() != initialDocument

    fun document(): RichNoteDocument = RichNotes.normalize(RichNoteDocument(items.flatMap { item ->
        when (item) {
            is TextItem -> item.segment.toBlocks()
            is DividerItem -> listOf(DividerBlock)
            is TableItem -> listOf(item.toBlock())
        }
    }))

    val isEmpty: Boolean get() = RichNotes.plainText(document()).isBlank()

    private fun load(document: RichNoteDocument) {
        val built = mutableListOf<EditorItem>()
        val pending = mutableListOf<TextBlock>()
        fun flush() { if (pending.isNotEmpty()) { built += textItem(RichTextSegment.fromBlocks(pending.toList())); pending.clear() } }
        document.blocks.forEach { block ->
            when (block) {
                is TextBlock -> pending += block
                DividerBlock -> { flush(); built += DividerItem() }
                is TableBlock -> { flush(); built += TableItem(block.header, block.rows) }
            }
        }
        flush()
        items.clear()
        items.addAll(withTextEdges(built))
    }

    private fun textItem(segment: RichTextSegment): TextItem {
        segment.beforeChange = ::checkpoint
        return TextItem(segment)
    }

    /** Text before the first and after the last table or divider, so there is always a place to type. */
    private fun withTextEdges(list: List<EditorItem>): List<EditorItem> {
        val out = list.toMutableList()
        if (out.firstOrNull() !is TextItem) out.add(0, textItem(RichTextSegment.fromBlocks(emptyList())))
        if (out.last() !is TextItem) out.add(textItem(RichTextSegment.fromBlocks(emptyList())))
        // Two text items next to each other become one.
        var index = 0
        while (index < out.size - 1) {
            val a = out[index]
            val b = out[index + 1]
            if (a is TextItem && b is TextItem) {
                out[index] = textItem(RichTextSegment.fromBlocks(a.segment.toBlocks() + b.segment.toBlocks()))
                out.removeAt(index + 1)
            } else index++
        }
        return out
    }

    // ---- Undo ----

    fun checkpoint(force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckpoint < 1_000) return
        lastCheckpoint = now
        val current = document()
        if (undo.lastOrNull() != current) undo.addLast(current)
        while (undo.size > 100) undo.removeFirst()
        redo.clear()
        canUndo = undo.isNotEmpty()
        canRedo = false
    }

    fun undo() {
        val current = document()
        while (undo.isNotEmpty()) {
            val previous = undo.removeLast()
            if (previous != current) {
                redo.addLast(current)
                restore(previous)
                break
            }
        }
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
        lastCheckpoint = 0
    }

    fun redo() {
        val next = redo.removeLastOrNull() ?: return
        undo.addLast(document())
        restore(next)
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
        lastCheckpoint = 0
    }

    private fun restore(document: RichNoteDocument) {
        val index = items.indexOfFirst { it.key == focusedKey }.coerceAtLeast(0)
        load(document)
        val target = items.getOrNull(index) as? TextItem ?: items.filterIsInstance<TextItem>().last()
        target.segment.moveCaretToEnd()
        focusedKey = target.key
        focusRequest++
    }

    // ---- Structure ----

    /** Inserts a block after the caret's line in the focused text, with text to continue after it. */
    private fun insertAfterCaret(item: EditorItem) {
        checkpoint(true)
        val textItem = items.firstOrNull { it.key == focusedKey } as? TextItem ?: items.filterIsInstance<TextItem>().last()
        val index = items.indexOf(textItem)
        val tail = textItem.segment.splitAfterCaretLine() ?: RichTextSegment.fromBlocks(emptyList())
        val tailItem = textItem(tail)
        items.add(index + 1, item)
        items.add(index + 2, tailItem)
        val normalized = withTextEdges(items.toList())
        items.clear(); items.addAll(normalized)
        focusedKey = if (item is TableItem) item.key else tailItem.key.takeIf { k -> items.any { it.key == k } } ?: items.last().key
        focusedCell = if (item is TableItem) 0 to 0 else null
        focusRequest++
    }

    fun insertTable(rows: Int, columns: Int, header: Boolean) {
        val r = rows.coerceIn(1, RichNotes.MAX_TABLE_ROWS)
        val c = columns.coerceIn(1, RichNotes.MAX_TABLE_COLUMNS)
        insertAfterCaret(TableItem(header, List(r) { List(c) { emptyList() } }))
    }

    fun insertDivider() = insertAfterCaret(DividerItem())

    fun remove(item: EditorItem) {
        if (item is TextItem) return
        checkpoint(true)
        val index = items.indexOf(item)
        items.remove(item)
        val normalized = withTextEdges(items.toList())
        items.clear(); items.addAll(normalized)
        focusedKey = items.getOrNull((index - 1).coerceAtLeast(0))?.key
        focusedCell = null
        (items.firstOrNull { it.key == focusedKey } as? TextItem)?.segment?.moveCaretToEnd()
        focusRequest++
    }

    fun tableChanged() = checkpoint(false)
    fun tableStructureChanged() = checkpoint(true)

    fun toggleCheckedAtCaret() {
        val segment = focusedSegment ?: return
        segment.selectedLines().forEach { segment.toggleChecked(it) }
    }

    companion object {
        fun forNote(plain: String, json: String): RichNoteEditorState {
            val loaded = RichNotes.load(plain, json)
            return RichNoteEditorState(loaded.document, loaded.source)
        }
    }
}
