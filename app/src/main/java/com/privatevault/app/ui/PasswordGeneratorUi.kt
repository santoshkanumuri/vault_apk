package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.TextButton
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.privatevault.app.security.GeneratorMode
import com.privatevault.app.security.GeneratorSettings
import com.privatevault.app.security.GeneratorSettingsStore
import com.privatevault.app.security.PasswordGenerator
import kotlin.math.roundToInt

private val separatorLabels = listOf("-" to "Hyphen", "." to "Period", "_" to "Underscore", " " to "Space")

/** Display only: lets a long passphrase wrap after a separator instead of inside a word. */
private fun breakable(value: String, settings: GeneratorSettings): String =
    if (settings.mode == GeneratorMode.PASSPHRASE) value.replace(settings.separator, settings.separator + "\u200B") else value

/**
 * Generator options with a live preview. The settings are saved on this device when the user
 * picks the result, so the next one-tap generation (for example during autofill) uses them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PasswordGeneratorDialog(onDismiss: () -> Unit, onUse: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember { GeneratorSettingsStore(context) }
    var settings by remember { mutableStateOf(store.read()) }
    var value by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(settings, round) {
        runCatching { PasswordGenerator.generate(context, settings) }
            .onSuccess { value = it; error = null }
            .onFailure { value = ""; error = "The word list could not be loaded. Use a password instead." }
    }
    fun change(next: GeneratorSettings) { settings = next.normalized() }
    val passphrase = settings.mode == GeneratorMode.PASSPHRASE
    val noun = if (passphrase) "passphrase" else "password"
    val bits = PasswordGenerator.entropyBits(settings)
    val setsOn = listOf(settings.uppercase, settings.lowercase, settings.digits, settings.symbols).count { it }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.generate_a_password)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !passphrase, onClick = { change(settings.copy(mode = GeneratorMode.PASSWORD)) }, label = { Text(stringResource(R.string.password)) })
                    FilterChip(selected = passphrase, onClick = { change(settings.copy(mode = GeneratorMode.PASSPHRASE)) }, label = { Text(stringResource(R.string.passphrase)) })
                }
                Surface(shape = NuvoriShapes.Card, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(breakable(value, settings).ifEmpty { "…" }, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f).padding(vertical = 8.dp).semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Generated $noun: $value"
                            })
                        IconButton(onClick = { round++ }, enabled = error == null) { Icon(Icons.Outlined.Refresh, contentDescription = "New $noun") }
                    }
                }
                Text("${PasswordGenerator.strengthLabel(bits)} · about $bits bits of randomness", style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (!passphrase) {
                    CountSlider("Length", settings.length, PasswordGenerator.LENGTH_RANGE) { change(settings.copy(length = it)) }
                    GeneratorSwitch("Uppercase", "A–Z", settings.uppercase, enabled = !(settings.uppercase && setsOn == 1)) { change(settings.copy(uppercase = it)) }
                    GeneratorSwitch("Lowercase", "a–z", settings.lowercase, enabled = !(settings.lowercase && setsOn == 1)) { change(settings.copy(lowercase = it)) }
                    GeneratorSwitch("Numbers", "0–9", settings.digits, enabled = !(settings.digits && setsOn == 1)) { change(settings.copy(digits = it)) }
                    GeneratorSwitch("Symbols", "! @ # $ % ^ & * - _ = + ?", settings.symbols, enabled = !(settings.symbols && setsOn == 1)) { change(settings.copy(symbols = it)) }
                    GeneratorSwitch("Avoid similar characters", "Leaves out l, I, O, 0 and 1", settings.avoidSimilar) { change(settings.copy(avoidSimilar = it)) }
                } else {
                    CountSlider("Words", settings.words, PasswordGenerator.WORDS_RANGE) { change(settings.copy(words = it)) }
                    Text(stringResource(R.string.separator), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        separatorLabels.forEach { (separator, label) ->
                            FilterChip(selected = settings.separator == separator, onClick = { change(settings.copy(separator = separator)) }, label = { Text(label) })
                        }
                    }
                    GeneratorSwitch("Capitalize words", null, settings.capitalize) { change(settings.copy(capitalize = it)) }
                    GeneratorSwitch("Include a number", "Many websites ask for one", settings.includeNumber) { change(settings.copy(includeNumber = it)) }
                }
                Text(stringResource(R.string.this_replaces_the_password_in_this_form),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(onClick = { store.save(settings); onUse(value) }, enabled = value.isNotEmpty()) { Text("Use $noun") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun CountSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("$value", style = MaterialTheme.typography.labelLarge)
        }
        Slider(value = value.toFloat(), onValueChange = { onChange(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(), steps = range.last - range.first - 1,
            modifier = Modifier.semantics { contentDescription = "$label: $value" })
    }
}

@Composable
private fun GeneratorSwitch(label: String, detail: String?, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}
