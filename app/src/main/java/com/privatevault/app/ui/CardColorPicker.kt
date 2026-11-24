package com.privatevault.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.privatevault.app.CardColorMath.argbToHsv
import com.privatevault.app.CardColorMath.hsvToArgb
import com.privatevault.app.CardColorMath.parseHex
import com.privatevault.app.CardColorMath.toHex
import kotlin.math.roundToInt

/**
 * Any card color: a live card preview, a saturation and brightness square, a hue bar, an exact
 * #RRGGBB field, and the colors already on the user's other cards. [color] is opaque ARGB.
 */
@Composable
internal fun CardColorPicker(
    color: Long,
    onColor: (Long) -> Unit,
    used: List<Long>,
    title: String,
    lastDigits: String,
) {
    // Hue is kept here, not derived from the color, so dragging through grays and black keeps it.
    var hsv by remember { mutableStateOf(argbToHsv(color)) }
    LaunchedEffect(color) { if (hsvToArgb(hsv[0], hsv[1], hsv[2]) != color) hsv = argbToHsv(color) }
    val report by rememberUpdatedState(onColor)
    fun pick(hue: Float = hsv[0], saturation: Float = hsv[1], value: Float = hsv[2]) {
        hsv = floatArrayOf(hue.coerceIn(0f, 360f), saturation.coerceIn(0f, 1f), value.coerceIn(0f, 1f))
        report(hsvToArgb(hsv[0], hsv[1], hsv[2]))
    }
    val hueColor = Color(hsvToArgb(hsv[0], 1f, 1f))

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.card_color), style = MaterialTheme.typography.labelLarge)
        CardPreview(Color(color), title, lastDigits)

        // Saturation (left to right) and brightness (top to bottom) for the current hue.
        Box(
            Modifier.fillMaxWidth().height(168.dp)
                .pointerInput(Unit) {
                    fun at(position: Offset) = pick(
                        saturation = position.x / size.width.coerceAtLeast(1),
                        value = 1f - position.y / size.height.coerceAtLeast(1))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        at(down.position); down.consume()
                        drag(down.id) { change -> at(change.position); change.consume() }
                    }
                }
                .semantics {
                    contentDescription = "Saturation and brightness"
                    stateDescription = "Saturation ${(hsv[1] * 100).roundToInt()} percent, brightness ${(hsv[2] * 100).roundToInt()} percent"
                }
        ) {
            // The rounded gradient sits on its own layer: the clip neither limits touches nor cuts
            // the marker off at the edges.
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(16.dp))
                .background(Brush.horizontalGradient(listOf(Color.White, hueColor)))
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black))))
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(hsv[1] * size.width, (1f - hsv[2]) * size.height)
                drawCircle(Color.White, radius = 11.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
                drawCircle(Color.Black.copy(alpha = .35f), radius = 13.dp.toPx(), center = center, style = Stroke(1.dp.toPx()))
            }
        }

        // Hue, as a rainbow bar. Accessibility services can set it like a slider.
        val rainbow = remember { (0..6).map { Color(hsvToArgb(it * 60f, 1f, 1f)) } }
        Box(
            Modifier.fillMaxWidth().height(30.dp)
                .pointerInput(Unit) {
                    fun at(position: Offset) = pick(hue = 360f * position.x / size.width.coerceAtLeast(1))
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        at(down.position); down.consume()
                        drag(down.id) { change -> at(change.position); change.consume() }
                    }
                }
                .semantics {
                    contentDescription = "Hue"
                    progressBarRangeInfo = ProgressBarRangeInfo(hsv[0], 0f..360f)
                    setProgress { target -> pick(hue = target); true }
                }
        ) {
            Box(Modifier.matchParentSize().clip(RoundedCornerShape(15.dp)).background(Brush.horizontalGradient(rainbow)))
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(hsv[0] / 360f * size.width, size.height / 2)
                drawCircle(hueColor, radius = size.height / 2 - 3.dp.toPx(), center = center)
                drawCircle(Color.White, radius = size.height / 2 - 3.dp.toPx(), center = center, style = Stroke(3.dp.toPx()))
            }
        }

        // The exact value. Typing a full #RRGGBB (or #RGB) applies it at once.
        var hexText by remember(color) { mutableStateOf(toHex(color)) }
        val typedValid = parseHex(hexText) != null
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).background(Color(color))
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = .25f), CircleShape))
            OutlinedTextField(
                hexText,
                { typed ->
                    val cleaned = "#" + typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                    hexText = cleaned
                    parseHex(cleaned)?.takeIf { cleaned.length == 7 || cleaned.length == 4 }?.let { exact ->
                        hsv = argbToHsv(exact); report(exact)
                    }
                },
                label = { Text(stringResource(R.string.hex)) },
                isError = !typedValid && hexText.length > 1,
                supportingText = { Text(if (typedValid || hexText.length <= 1) "Any color, for example #2E7034" else "Use 6 hex digits, 0 to 9 and A to F") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
                modifier = Modifier.weight(1f),
            )
        }

        if (used.isNotEmpty()) {
            Text(stringResource(R.string.on_your_other_cards), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(used, key = { it }) { option ->
                    val selected = option == color
                    Box(
                        Modifier.size(44.dp)
                            .selectable(selected = selected, role = Role.RadioButton, onClick = { hsv = argbToHsv(option); report(option) })
                            .semantics { contentDescription = toHex(option) }
                            .padding(3.dp)
                            .border(if (selected) 2.dp else 1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) 1f else .3f), CircleShape)
                            .padding(3.dp)
                            .background(Color(option), CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(NuvoriIcons.Check, contentDescription = null, tint = inkFor(Color(option)), modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** A small card in the chosen color, with the text color the real card will use. */
@Composable
private fun CardPreview(base: Color, title: String, lastDigits: String) {
    val ink = inkFor(base)
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp)).background(base)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = if (base.luminance() > .9f || base.luminance() < .03f) .2f else 0f), RoundedCornerShape(18.dp))
            .padding(16.dp)
            .semantics(mergeDescendants = true) {},
    ) {
        Text(title.ifBlank { "Card" }, color = ink, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1,
            modifier = Modifier.align(Alignment.TopStart))
        Text(if (lastDigits.isBlank()) "•••• ••••" else "•••• $lastDigits", color = ink, fontFamily = FontFamily.Monospace,
            fontSize = 16.sp, letterSpacing = 1.5.sp, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/** Black or white, whichever reads better on [background]; the same rule as the card face. */
internal fun inkFor(background: Color): Color {
    val darkContrast = (background.luminance() + .05f) / .05f
    val whiteContrast = 1.05f / (background.luminance() + .05f)
    return if (darkContrast >= whiteContrast) Color.Black else Color.White
}
