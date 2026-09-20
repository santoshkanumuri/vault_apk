package com.privatevault.app

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

internal enum class FirstRunChoice { NEW, RESTORE, BROWSER_IMPORT }

private data class IntroPage(
    val icon: ImageVector,
    val title: String,
    val description: String,
    val points: List<Pair<ImageVector, String>>
)

private val introPages = listOf(
    IntroPage(Icons.Outlined.CreditCard, "Your essentials, together",
        "Keep cards, logins, authenticator codes, questions, and notes in one place.",
        listOf(Icons.Outlined.CreditCard to "Cards and photos", Icons.Outlined.Key to "Passwords and passkeys", Icons.Outlined.Timer to "Authenticator codes")),
    IntroPage(Icons.Outlined.Lock, "Private by design",
        "Your vault stays on this device. It works offline and has no account or cloud sync.",
        listOf(Icons.Outlined.Password to "A master password protects your vault", Icons.Outlined.Fingerprint to "Fingerprint access after setup", Icons.Outlined.CloudOff to "No Internet permission")),
    IntroPage(Icons.Outlined.Backup, "Bring your data over",
        "Restore an encrypted Nuvori backup, or import passwords from a Chrome or Brave CSV after setup.",
        listOf(Icons.Outlined.Security to "Backups need their original password", Icons.Outlined.Key to "Browser CSV files contain readable passwords"))
)

@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun OnboardingScreen(
    page: Int,
    onPageChange: (Int) -> Unit,
    onStart: (FirstRunChoice) -> Unit,
    onPrivacy: () -> Unit
) {
    var choice by rememberSaveable { mutableStateOf(FirstRunChoice.NEW) }
    val context = LocalContext.current
    val motionDuration = if (Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) 0 else 220
    Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
        Column(Modifier.align(Alignment.Center).widthIn(max = 560.dp).fillMaxSize().padding(horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                NuvoriLogo(Modifier.size(38.dp))
                Column(Modifier.weight(1f)) {
                    Text("NUVORI", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Text("Yours, by design", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${page + 1} of 4", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            AnimatedContent(page, modifier = Modifier.weight(1f), transitionSpec = {
                val direction = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(motionDuration)) { it * direction } + fadeIn(tween(motionDuration)))
                    .togetherWith(slideOutHorizontally(tween(motionDuration)) { -it * direction } + fadeOut(tween(motionDuration)))
            }, label = "Introduction page") { currentPage ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (currentPage < introPages.size) {
                        val item = introPages[currentPage]
                        IntroIcon(item.icon)
                        Text(item.title, style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.semantics { heading() })
                        Text(item.description, style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.points.forEach { (icon, label) -> IntroPoint(icon, label) }
                    } else {
                        IntroIcon(Icons.Outlined.Security)
                        Text("Ready to start?", style = MaterialTheme.typography.headlineLarge,
                            modifier = Modifier.semantics { heading() })
                        Text("Choose what to do after you create your master password.",
                            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceRow(FirstRunChoice.NEW, choice, Icons.Outlined.Key, "New vault", "Start with an empty vault") { choice = it }
                            ChoiceRow(FirstRunChoice.RESTORE, choice, Icons.Outlined.Backup, "Restore a backup", "Use an encrypted .pvault file") { choice = it }
                            ChoiceRow(FirstRunChoice.BROWSER_IMPORT, choice, Icons.Outlined.Password, "Import browser passwords", "Choose a Chrome or Brave CSV") { choice = it }
                        }
                        TextButton(onClick = onPrivacy) { Text("Privacy policy") }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (page > 0) OutlinedIconButton(onClick = { onPageChange(page - 1) }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Previous page")
                }
                else Spacer(Modifier.size(52.dp))
                Spacer(Modifier.weight(1f))
                Button(onClick = { if (page == 3) onStart(choice) else onPageChange(page + 1) },
                    modifier = Modifier.heightIn(min = 52.dp)) { Text(if (page == 3) "Start" else "Next") }
            }
        }
    }
}

@Composable
private fun IntroIcon(icon: ImageVector) {
    Box(Modifier.size(88.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f), RoundedCornerShape(24.dp)),
        contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(46.dp))
    }
}

@Composable
private fun IntroPoint(icon: ImageVector, label: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(24.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ChoiceRow(choice: FirstRunChoice, selected: FirstRunChoice, icon: ImageVector,
    title: String, description: String, onSelect: (FirstRunChoice) -> Unit) {
    val active = choice == selected
    Card(Modifier.fillMaxWidth().selectable(selected = active, role = Role.RadioButton, onClick = { onSelect(choice) }),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = if (active) MaterialTheme.colorScheme.primary.copy(alpha = .16f) else MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(26.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = active, onClick = null)
        }
    }
}
