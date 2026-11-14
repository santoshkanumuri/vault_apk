package com.privatevault.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.privatevault.app.appearance.AppearancePalette

private val SwatchTouch = 48.dp
private val SwatchSize = 36.dp

/** Live preview, accent grid, background grid and reset for the Appearance settings page. Taps apply at once. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.AppearanceColorSections(accentId: String, surfaceId: String, pendingSyncNote: Boolean,
    onChange: (accent: String, surface: String) -> Unit) {
    val accent = AppearancePalette.accent(accentId)
    val surface = AppearancePalette.surface(surfaceId)
    SettingsSection("Colors", description = "Pick an accent and a background. The whole app changes at once.") {
        AppearancePreview()
        Text("Accent color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.fillMaxWidth()) {
            AppearancePalette.accents.forEach { option ->
                SwatchButton(option.name, selected = option.id == accent.id, CircleShape, onClick = { onChange(option.id, surface.id) }) {
                    Box(Modifier.size(SwatchSize).clip(CircleShape).background(Color(option.swatch)), contentAlignment = Alignment.Center) {
                        if (option.id == accent.id) CheckMark(Color(option.swatch))
                    }
                }
            }
        }
        Text("Background", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        FlowRow(Modifier.fillMaxWidth()) {
            AppearancePalette.surfaces.forEach { option ->
                SwatchButton("${option.name} background", selected = option.id == surface.id, RoundedCornerShape(14.dp), onClick = { onChange(accent.id, option.id) }) {
                    Box(Modifier.size(SwatchSize).clip(RoundedCornerShape(10.dp)).background(Color(option.swatch))
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center) {
                        if (option.id == surface.id) CheckMark(Color(option.swatch))
                        else Box(Modifier.size(12.dp).clip(CircleShape).background(Color(accent.swatch)))
                    }
                }
            }
        }
        if (accent.id != AppearancePalette.DEFAULT_ACCENT || surface.id != AppearancePalette.DEFAULT_SURFACE)
            TextButton(onClick = { onChange(AppearancePalette.DEFAULT_ACCENT, AppearancePalette.DEFAULT_SURFACE) }) {
                Text("Reset to Nuvori green")
            }
        if (pendingSyncNote)
            Text("Saved on this device. Your other devices get it after each has Nuvori 2.2.4 or newer and has synced once on Wi-Fi.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CheckMark(behind: Color) {
    Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(20.dp),
        tint = if (behind.luminance() > 0.5f) Color.Black else Color.White)
}

@Composable
private fun SwatchButton(name: String, selected: Boolean, ringShape: androidx.compose.ui.graphics.Shape, onClick: () -> Unit, content: @Composable () -> Unit) {
    val description = if (selected) "$name, selected" else name
    Box(Modifier.size(SwatchTouch).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
        .semantics { contentDescription = description }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(SwatchSize + 8.dp).then(
            if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, ringShape) else Modifier),
            contentAlignment = Alignment.Center) { content() }
    }
}

/** Shows a button, text and chip in the colors now in use. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppearancePreview() {
    Surface(Modifier.fillMaxWidth(), shape = NuvoriShapes.Card, color = MaterialTheme.colorScheme.background,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        androidx.compose.foundation.layout.Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Preview", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            Text("This is how your vault looks.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = {}, enabled = true, modifier = Modifier.clearAndSetSemantics { }) { Text("Button") }
                AssistChip(onClick = {}, label = { Text("Chip") }, modifier = Modifier.clearAndSetSemantics { },
                    colors = AssistChipDefaults.assistChipColors(containerColor = MaterialTheme.colorScheme.primaryContainer,
                        labelColor = MaterialTheme.colorScheme.onPrimaryContainer),
                    border = null)
            }
        }
    }
}
