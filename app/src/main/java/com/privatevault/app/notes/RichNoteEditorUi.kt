package com.privatevault.app.notes

import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatIndentDecrease
import androidx.compose.material.icons.automirrored.filled.FormatIndentIncrease
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatClear
import androidx.compose.material.icons.filled.FormatColorFill
import androidx.compose.material.icons.filled.FormatColorText
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatStrikethrough
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.HorizontalRule
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun rememberRichNotePalette(): RichNotePalette {
    val scheme = MaterialTheme.colorScheme
    val light = scheme.background.luminance() > .5f
    return remember(scheme, light) {
        RichNotePalette(light = light, link = scheme.primary, muted = scheme.onSurfaceVariant,
            codeBackground = scheme.surfaceVariant, accent = scheme.primary)
    }
}

/** The note body as editor rows, for the editor screen's list. */
internal fun LazyListScope.richNoteEditorItems(state: RichNoteEditorState, palette: RichNotePalette) {
    val single = state.items.size == 1
    state.items.forEachIndexed { index, item ->
        item(key = "rich-${item.key}") {
          // Copy and cut in note text go to the clipboard marked sensitive.
          CompositionLocalProvider(LocalClipboardManager provides rememberSensitiveClipboard()) {
            when (item) {
                is TextItem -> RichSegmentField(state, item, palette, placeholder = if (single && index == 0) "Start writing" else null)
                is DividerItem -> Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    HorizontalDivider(Modifier.weight(1f), thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    IconButton(onClick = { state.remove(item) }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove divider", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                is TableItem -> RichTableEditor(state, item)
            }
          }
        }
    }
}

@Composable
private fun RichSegmentField(state: RichNoteEditorState, item: TextItem, palette: RichNotePalette, placeholder: String?) {
    val segment = item.segment
    val requester = remember { FocusRequester() }
    LaunchedEffect(state.focusRequest) {
        if (state.focusRequest > 0 && state.focusedKey == item.key) runCatching { requester.requestFocus() }
    }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface)
    BasicTextField(
        value = segment.value,
        onValueChange = segment::onValueChange,
        textStyle = textStyle,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        visualTransformation = RichVisualTransformation(segment.lines, segment.ranges, palette),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text),
        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = 6.dp)
            .focusRequester(requester)
            .onFocusChanged { if (it.isFocused) { state.focusedKey = item.key; state.focusedCell = null } }
            .onPreviewKeyEvent { event -> event.type == KeyEventType.KeyDown && handleShortcut(state, segment, event.key, event.isCtrlPressed, event.isShiftPressed) },
        decorationBox = { inner ->
            Box {
                if (placeholder != null && segment.text.isEmpty())
                    Text(placeholder, style = textStyle, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
                inner()
            }
        },
    )
}

/** Hardware keyboard shortcuts (tablets, Chromebooks, keyboard cases). */
private fun handleShortcut(state: RichNoteEditorState, segment: RichTextSegment, key: Key, ctrl: Boolean, shift: Boolean): Boolean {
    if (key == Key.Tab && !ctrl) {
        val list = segment.selectedLines().any { segment.lines[it].kind.isListItem }
        if (list) segment.indent(if (shift) -1 else 1)
        return list
    }
    if (!ctrl) return false
    when (key) {
        Key.B -> segment.toggleMark(Mark(MarkKind.BOLD))
        Key.I -> segment.toggleMark(Mark(MarkKind.ITALIC))
        Key.U -> segment.toggleMark(Mark(MarkKind.UNDERLINE))
        Key.X -> if (shift) segment.toggleMark(Mark(MarkKind.STRIKE)) else return false
        Key.E -> segment.toggleMark(Mark(MarkKind.CODE))
        Key.Z -> if (shift) state.redo() else state.undo()
        Key.Y -> state.redo()
        Key.Seven -> if (shift) segment.setLineKind(BlockKind.NUMBERED) else return false
        Key.Eight -> if (shift) segment.setLineKind(BlockKind.BULLET) else return false
        Key.Nine -> if (shift) segment.setLineKind(BlockKind.CHECK) else return false
        else -> return false
    }
    return true
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RichTableEditor(state: RichNoteEditorState, table: TableItem) {
    val scheme = MaterialTheme.colorScheme
    val focused = state.focusedKey == table.key
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(Modifier.horizontalScroll(rememberScrollState()).border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
            table.rows.forEachIndexed { r, row ->
                val headerRow = table.header && r == 0
                Row(Modifier.background(if (headerRow) scheme.surfaceVariant else Color.Transparent)) {
                    row.forEachIndexed { c, cell ->
                        val cellFocused = focused && state.focusedCell == (r to c)
                        BasicTextField(
                            value = cell.text,
                            onValueChange = { text -> state.tableChanged(); cell.text = RichNotes.sanitizeText(text).replace('\n', ' ') },
                            textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface,
                                fontWeight = if (headerRow) FontWeight.SemiBold else FontWeight.Normal),
                            cursorBrush = SolidColor(scheme.primary),
                            modifier = Modifier.width(140.dp).heightIn(min = 44.dp)
                                .border(if (cellFocused) 2.dp else .5.dp, if (cellFocused) scheme.primary else scheme.outlineVariant)
                                .padding(horizontal = 10.dp, vertical = 12.dp)
                                .semantics { contentDescription = "Row ${r + 1}, column ${c + 1}" }
                                .onFocusChanged { if (it.isFocused) { state.focusedKey = table.key; state.focusedCell = r to c } },
                        )
                    }
                }
            }
        }
        if (focused) {
            val (row, column) = state.focusedCell ?: (0 to 0)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = { state.tableStructureChanged(); table.addRow(row) }, label = { Text("Add row") })
                AssistChip(onClick = { state.tableStructureChanged(); table.addColumn(column) }, label = { Text("Add column") })
                AssistChip(onClick = { state.tableStructureChanged(); table.removeRow(row); state.focusedCell = null }, label = { Text("Delete row") },
                    enabled = table.rows.size > 1)
                AssistChip(onClick = { state.tableStructureChanged(); table.removeColumn(column); state.focusedCell = null }, label = { Text("Delete column") },
                    enabled = table.columnCount > 1)
                FilterChip(selected = table.header, onClick = { state.tableStructureChanged(); table.header = !table.header }, label = { Text("Header row") })
                AssistChip(onClick = { state.remove(table) }, label = { Text("Delete table") })
            }
        }
    }
}

private enum class RichSheet { NONE, STYLE, COLOR, HIGHLIGHT, TABLE, LINK }

/**
 * The formatting toolbar, shown above the keyboard while a note is edited. Its buttons act on
 * the focused text, and show their on state for the selection.
 */
@Composable
internal fun RichNoteToolbar(state: RichNoteEditorState, modifier: Modifier = Modifier) {
    var sheet by remember { mutableStateOf(RichSheet.NONE) }
    val segment = state.focusedSegment
    // Reading the segment's state here keeps the buttons in step with the selection.
    val active = segment?.let { it.value; it.ranges; it.pendingMarks; it.activeMarks() } ?: emptySet()
    val lineKinds = segment?.let { s -> s.lines; s.value; s.selectedLines().map { s.lines.getOrNull(it)?.kind } }.orEmpty()
    fun has(kind: MarkKind) = active.any { it.kind == kind }
    fun lineIs(kind: BlockKind) = lineKinds.isNotEmpty() && lineKinds.all { it == kind }
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 2.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically) {
                ToolButton(Icons.AutoMirrored.Filled.Undo, "Undo", enabled = state.canUndo) { state.undo() }
                ToolButton(Icons.AutoMirrored.Filled.Redo, "Redo", enabled = state.canRedo) { state.redo() }
                ToolDivider()
                ToolButton(Icons.Filled.TextFields, "Text style") { sheet = RichSheet.STYLE }
                ToolButton(Icons.Filled.FormatBold, "Bold", on = has(MarkKind.BOLD)) { segment?.toggleMark(Mark(MarkKind.BOLD)) }
                ToolButton(Icons.Filled.FormatItalic, "Italic", on = has(MarkKind.ITALIC)) { segment?.toggleMark(Mark(MarkKind.ITALIC)) }
                ToolButton(Icons.Filled.FormatUnderlined, "Underline", on = has(MarkKind.UNDERLINE)) { segment?.toggleMark(Mark(MarkKind.UNDERLINE)) }
                ToolButton(Icons.Filled.FormatStrikethrough, "Strikethrough", on = has(MarkKind.STRIKE)) { segment?.toggleMark(Mark(MarkKind.STRIKE)) }
                ToolButton(Icons.Filled.FormatColorText, "Text color", on = has(MarkKind.COLOR)) { sheet = RichSheet.COLOR }
                ToolButton(Icons.Filled.FormatColorFill, "Highlight", on = has(MarkKind.BG)) { sheet = RichSheet.HIGHLIGHT }
                ToolDivider()
                ToolButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Bulleted list", on = lineIs(BlockKind.BULLET)) { segment?.setLineKind(BlockKind.BULLET) }
                ToolButton(Icons.Filled.FormatListNumbered, "Numbered list", on = lineIs(BlockKind.NUMBERED)) { segment?.setLineKind(BlockKind.NUMBERED) }
                ToolButton(Icons.Filled.CheckBox, "Checklist", on = lineIs(BlockKind.CHECK)) {
                    if (lineIs(BlockKind.CHECK)) state.toggleCheckedAtCaret() else segment?.setLineKind(BlockKind.CHECK)
                }
                ToolButton(Icons.AutoMirrored.Filled.FormatIndentIncrease, "Indent") { segment?.indent(1) }
                ToolButton(Icons.AutoMirrored.Filled.FormatIndentDecrease, "Outdent") { segment?.indent(-1) }
                ToolDivider()
                ToolButton(Icons.Filled.Link, "Link", on = has(MarkKind.LINK)) { sheet = RichSheet.LINK }
                ToolButton(Icons.Filled.Code, "Inline code", on = has(MarkKind.CODE)) { segment?.toggleMark(Mark(MarkKind.CODE)) }
                ToolButton(Icons.Filled.TableChart, "Insert table") { sheet = RichSheet.TABLE }
                ToolButton(Icons.Filled.HorizontalRule, "Insert divider") { state.insertDivider() }
                ToolButton(Icons.Filled.FormatClear, "Clear formatting") { segment?.clearFormatting() }
            }
            if (lineIs(BlockKind.CHECK)) Text("Tap the checklist button again to tick the item.",
                Modifier.padding(start = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    when (sheet) {
        RichSheet.NONE -> Unit
        RichSheet.STYLE -> TextStyleSheet(segment, onDismiss = { sheet = RichSheet.NONE })
        RichSheet.COLOR -> ColorSheet("Text color", active.firstOrNull { it.kind == MarkKind.COLOR }?.color, text = true,
            onPick = { color -> segment?.setValueMark(MarkKind.COLOR, color?.let { Mark(MarkKind.COLOR, color = it) }) },
            onDismiss = { sheet = RichSheet.NONE })
        RichSheet.HIGHLIGHT -> ColorSheet("Highlight", active.firstOrNull { it.kind == MarkKind.BG }?.color, text = false,
            onPick = { color -> segment?.setValueMark(MarkKind.BG, color?.let { Mark(MarkKind.BG, color = it) }) },
            onDismiss = { sheet = RichSheet.NONE })
        RichSheet.TABLE -> TableSheet(onInsert = { rows, columns, header -> state.insertTable(rows, columns, header) },
            onDismiss = { sheet = RichSheet.NONE })
        RichSheet.LINK -> segment?.let { LinkDialog(it, onDismiss = { sheet = RichSheet.NONE }) }
    }
}

@Composable
private fun ToolButton(icon: ImageVector, label: String, on: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(44.dp).semantics { selected = on }) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)).background(if (on) scheme.secondaryContainer else Color.Transparent),
            contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = label, tint = when {
                !enabled -> scheme.onSurface.copy(alpha = .38f)
                on -> scheme.onSecondaryContainer
                else -> scheme.onSurfaceVariant
            })
        }
    }
}

@Composable
private fun ToolDivider() = Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(MaterialTheme.colorScheme.outlineVariant))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextStyleSheet(segment: RichTextSegment?, onDismiss: () -> Unit) {
    val current = segment?.let { s -> s.lines.getOrNull(s.lineOf(s.value.selection.min)) } ?: LineAttrs()
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            Text("Text style", Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
            listOf(
                Triple("Normal text", BlockKind.PARAGRAPH, 1), Triple("Heading 1", BlockKind.HEADING, 1),
                Triple("Heading 2", BlockKind.HEADING, 2), Triple("Heading 3", BlockKind.HEADING, 3),
                Triple("Quote", BlockKind.QUOTE, 1), Triple("Code block", BlockKind.CODE, 1),
            ).forEach { (label, kind, level) ->
                val selected = current.kind == kind && (kind != BlockKind.HEADING || current.level == level)
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable {
                    segment?.setLineKind(kind, level); onDismiss()
                }.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(label, Modifier.weight(1f), style = when {
                        kind == BlockKind.HEADING -> MaterialTheme.typography.bodyLarge.copy(fontSize = headingSize(level), fontWeight = FontWeight.SemiBold)
                        kind == BlockKind.CODE -> MaterialTheme.typography.bodyLarge.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                        else -> MaterialTheme.typography.bodyLarge
                    })
                    if (selected) Icon(Icons.Filled.Check, contentDescription = "Current style", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ColorSheet(title: String, selected: NoteColor?, text: Boolean, onPick: (NoteColor?) -> Unit, onDismiss: () -> Unit) {
    val light = MaterialTheme.colorScheme.background.luminance() > .5f
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Swatch("Default", null, selected == null, text, light) { onPick(null); onDismiss() }
                NoteColor.entries.forEach { color ->
                    Swatch(NoteColors.label(color), color, selected == color, text, light) { onPick(color); onDismiss() }
                }
            }
        }
    }
}

@Composable
private fun Swatch(label: String, color: NoteColor?, selected: Boolean, text: Boolean, light: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.width(56.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp)
        .semantics { this.selected = selected }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(40.dp).clip(CircleShape)
            .background(if (color == null) scheme.surface else if (text) scheme.surface else NoteColors.background(color, light))
            .border(if (selected) 3.dp else 1.dp, if (selected) scheme.primary else scheme.outlineVariant, CircleShape),
            contentAlignment = Alignment.Center) {
            Text("A", fontWeight = FontWeight.Bold,
                color = if (color != null && text) NoteColors.text(color, light) else scheme.onSurface)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TableSheet(onInsert: (Int, Int, Boolean) -> Unit, onDismiss: () -> Unit) {
    var rows by remember { mutableIntStateOf(3) }
    var columns by remember { mutableIntStateOf(2) }
    var header by remember { mutableStateOf(true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Insert table", style = MaterialTheme.typography.titleMedium)
            Stepper("Rows", rows, 1, RichNotes.MAX_TABLE_ROWS) { rows = it }
            Stepper("Columns", columns, 1, RichNotes.MAX_TABLE_COLUMNS) { columns = it }
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Header row", Modifier.weight(1f))
                Switch(checked = header, onCheckedChange = { header = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("Cancel") }
                TextButton(onClick = { onInsert(rows, columns, header); onDismiss() }) { Text("Insert") }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, onValue: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        TextButton(onClick = { onValue((value - 1).coerceAtLeast(min)) }, enabled = value > min) { Text("−") }
        Text("$value", Modifier.width(32.dp), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { onValue((value + 1).coerceAtMost(max)) }, enabled = value < max) { Text("+") }
    }
}

@Composable
private fun LinkDialog(segment: RichTextSegment, onDismiss: () -> Unit) {
    val selection = segment.value.selection
    val existingRange = segment.linkRangeAtCaret()
    val selectedText = existingRange?.let { segment.text.substring(it.start, it.end) }
        ?: segment.text.substring(selection.min, selection.max)
    val existing = existingRange?.mark?.link ?: segment.linkAtSelection()
    var label by remember { mutableStateOf(selectedText) }
    var address by remember { mutableStateOf(existing.orEmpty()) }
    val link = RichNotes.linkFromInput(address)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Add link" else "Edit link") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(label, { label = it }, label = { Text("Text") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(address, { address = it }, label = { Text("Web or email address") }, singleLine = true,
                    isError = address.isNotBlank() && link == null,
                    supportingText = { Text(if (address.isNotBlank() && link == null) "Use a web address (https://) or an email address." else "Links open only after you confirm.") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { link?.let { segment.insertLink(label.ifBlank { it }, it, existingRange?.let { r -> r.start until r.end }) }; onDismiss() }, enabled = link != null) { Text("Save link") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { segment.removeLink(); onDismiss() }) { Text("Remove link") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
