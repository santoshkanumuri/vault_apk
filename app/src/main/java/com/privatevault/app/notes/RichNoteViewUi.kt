package com.privatevault.app.notes

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

/** Returns the document with the check item at [blockIndex] ticked or unticked. */
internal fun RichNoteDocument.toggleCheck(blockIndex: Int): RichNoteDocument {
    val block = blocks.getOrNull(blockIndex) as? TextBlock ?: return this
    if (block.kind != BlockKind.CHECK) return this
    return RichNoteDocument(blocks.toMutableList().also { it[blockIndex] = block.copy(checked = !block.checked) })
}

/**
 * A note rendered for reading, as rows of the detail list. Checkboxes can be ticked in place;
 * links call [onLink], which must ask before anything opens.
 */
internal fun LazyListScope.richNoteViewItems(
    document: RichNoteDocument,
    palette: RichNotePalette,
    onToggleCheck: ((Int) -> Unit)?,
    onLink: (String) -> Unit,
) {
    val numbers = lineNumbers(document.blocks.map { (it as? TextBlock)?.let { b -> LineAttrs(b.kind, b.level, b.indent, b.checked) } ?: LineAttrs() })
    document.blocks.forEachIndexed { index, block ->
        item(key = "note-block-$index") {
            when (block) {
                is TextBlock -> RichTextBlockView(block, numbers[index], palette, onToggleCheck?.let { { it(index) } }, onLink)
                DividerBlock -> HorizontalDivider(Modifier.padding(vertical = 10.dp), thickness = 2.dp, color = MaterialTheme.colorScheme.outlineVariant)
                is TableBlock -> RichTableView(block, palette, onLink)
            }
        }
    }
}

/** The same rendering in a plain column, for previews outside a list. */
@Composable
internal fun RichNoteView(document: RichNoteDocument, palette: RichNotePalette, onLink: (String) -> Unit, modifier: Modifier = Modifier) {
    val numbers = lineNumbers(document.blocks.map { (it as? TextBlock)?.let { b -> LineAttrs(b.kind, b.level, b.indent, b.checked) } ?: LineAttrs() })
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        document.blocks.forEachIndexed { index, block ->
            when (block) {
                is TextBlock -> RichTextBlockView(block, numbers[index], palette, null, onLink)
                DividerBlock -> HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                is TableBlock -> RichTableView(block, palette, onLink)
            }
        }
    }
}

@Composable
private fun RichTextBlockView(block: TextBlock, number: Int, palette: RichNotePalette, onToggle: (() -> Unit)?, onLink: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val base = when (block.kind) {
        BlockKind.HEADING -> MaterialTheme.typography.bodyLarge.copy(fontSize = headingSize(block.level), fontWeight = FontWeight.SemiBold)
        BlockKind.QUOTE -> MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic, color = scheme.onSurfaceVariant)
        BlockKind.CODE -> MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
        else -> MaterialTheme.typography.bodyLarge
    }.let { style -> if (style.color == Color.Unspecified) style.copy(color = scheme.onSurface) else style }
    val style = if (block.kind == BlockKind.CHECK && block.checked)
        base.copy(textDecoration = TextDecoration.LineThrough, color = scheme.onSurfaceVariant) else base
    val indent = (block.indent * 20).dp
    val rowModifier = Modifier.fillMaxWidth().padding(start = indent)
        .then(if (block.kind == BlockKind.HEADING) Modifier.padding(top = 8.dp).semantics { heading() } else Modifier)
    when (block.kind) {
        BlockKind.BULLET, BlockKind.NUMBERED -> Row(rowModifier.padding(vertical = 2.dp)) {
            Text(if (block.kind == BlockKind.NUMBERED) "$number." else if (block.indent % 2 == 0) "•" else "◦",
                Modifier.widthIn(min = 22.dp), style = style.copy(color = palette.accent))
            Spacer(Modifier.width(4.dp))
            LinkedText(block.spans, style, palette, onLink, Modifier.weight(1f))
        }
        BlockKind.CHECK -> Row(rowModifier, verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = block.checked, onCheckedChange = onToggle?.let { toggle -> { toggle() } }, enabled = true,
                modifier = Modifier.semantics { contentDescription = block.text })
            LinkedText(block.spans, style, palette, onLink, Modifier.weight(1f))
        }
        BlockKind.QUOTE -> Row(rowModifier.height(IntrinsicSize.Min).padding(vertical = 4.dp)) {
            Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(palette.accent))
            Spacer(Modifier.width(10.dp))
            LinkedText(block.spans, style, palette, onLink, Modifier.weight(1f))
        }
        BlockKind.CODE -> Box(rowModifier.clip(RoundedCornerShape(6.dp)).background(palette.codeBackground)
            .horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp)) {
            LinkedText(block.spans, style, palette, onLink, Modifier)
        }
        else -> LinkedText(block.spans, style, palette, onLink, rowModifier.padding(vertical = 3.dp).heightIn(min = 20.dp))
    }
}

@Composable
private fun LinkedText(spans: List<RichSpan>, style: TextStyle, palette: RichNotePalette, onLink: (String) -> Unit, modifier: Modifier) {
    val text = spansToAnnotated(spans, palette)
    if (spans.none { it.marks.link != null }) Text(text, modifier, style = style)
    else ClickableText(text, modifier, style = style) { offset ->
        text.getStringAnnotations("link", offset, offset).firstOrNull()?.let { onLink(it.item) }
    }
}

@Composable
private fun RichTableView(table: TableBlock, palette: RichNotePalette, onLink: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth().padding(vertical = 6.dp).horizontalScroll(rememberScrollState())) {
        Column(Modifier.border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
            table.rows.forEachIndexed { r, row ->
                val header = table.header && r == 0
                Row(Modifier.height(IntrinsicSize.Min).background(if (header) scheme.surfaceVariant else Color.Transparent)) {
                    row.forEach { cell ->
                        Box(Modifier.widthIn(min = 96.dp, max = 240.dp).fillMaxHeight().border(.5.dp, scheme.outlineVariant)
                            .padding(horizontal = 10.dp, vertical = 8.dp)) {
                            LinkedText(cell, MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface,
                                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal), palette, onLink, Modifier)
                        }
                    }
                }
            }
        }
    }
}

/** Shows the full address and opens it only when the person confirms. */
@Composable
internal fun LinkConfirmDialog(link: String, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Open this link?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("It opens outside Nuvori. Check the full address first:")
                Text(link, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.primary)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                RichNotes.sanitizeLink(link)?.let { safe -> runCatching { uriHandler.openUri(safe) } }
                onDismiss()
            }) { Text("Open") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun RichNoteFallbackNotice(source: RichNoteSource, modifier: Modifier = Modifier) {
    val message = when (source) {
        RichNoteSource.DIVERGED -> "This note was changed in an older version of Nuvori, so it is shown without its formatting."
        RichNoteSource.NEWER_VERSION -> "This note has formatting from a newer version of Nuvori. Update the app to see it. Saving here keeps the text but replaces that formatting."
        else -> return
    }
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.secondaryContainer)
        .padding(12.dp)) {
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
