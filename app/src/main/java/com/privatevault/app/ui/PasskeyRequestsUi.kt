package com.privatevault.app

import androidx.compose.ui.res.stringResource
import com.privatevault.app.controls.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.passkeys.PasskeyRequestLog
import com.privatevault.app.passkeys.PasskeyRequestRecord

/**
 * "Passkey requests": what Android asked Nuvori, newest first, and why Nuvori said no. If an app shows only other
 * managers and nothing appears here, that app never asked Android's passkey providers. Kept on this device.
 */
@Composable
internal fun PasskeyRequestsSection() {
    val context = LocalContext.current
    LaunchedEffect(Unit) { PasskeyRequestLog.load(context) }
    val records by PasskeyRequestLog.recent.collectAsStateWithLifecycle()
    var all by rememberSaveable { mutableStateOf(false) }
    var problemsOnly by rememberSaveable { mutableStateOf(false) }
    val filtered = remember(records, problemsOnly) { if (problemsOnly) records.filter { !it.offered } else records }
    SettingsSection(stringResource(R.string.passkey_requests),
        description = stringResource(R.string.apps_and_websites_that_asked_nuvori_newe, PasskeyRequestLog.CAPACITY.toString())) {
        if (records.isEmpty()) {
            Text(stringResource(R.string.no_requests_yet_if_an_app_lists_other_ma),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SettingsSection
        }
        val problems = records.count { !it.offered }
        if (problems > 0) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !problemsOnly, onClick = { problemsOnly = false }, label = { Text(stringResource(R.string.all_2, records.size.toString())) })
            FilterChip(selected = problemsOnly, onClick = { problemsOnly = true }, label = { Text(stringResource(R.string.not_offered, problems.toString())) })
        }
        val visible = if (all) filtered else filtered.take(5)
        visible.forEachIndexed { index, record ->
            if (index > 0) HorizontalDivider()
            PasskeyRequestRow(record)
        }
        if (filtered.size > 5) TextButton(onClick = { all = !all }) { Text(if (all) "Show less" else "Show all ${filtered.size}") }
        SettingsSecondaryButton(stringResource(R.string.clear_request_history), onClick = { PasskeyRequestLog.clear(context) }, icon = NuvoriIcons.Delete)
    }
}

@Composable
private fun PasskeyRequestRow(record: PasskeyRequestRecord) {
    val action = when {
        record.passkey && record.create -> "Create passkey"
        record.passkey -> "Sign in with passkey"
        record.create -> "Save password"
        else -> "Fill password"
    }
    val time = remember(record.at) { requestTime(record.at) }
    val outcome = if (record.offered) "Nuvori was offered" else "Not offered: ${record.reason}"
    val icon = rememberInstalledAppIcon(record.packageName)
    Row(Modifier.fillMaxWidth().clearAndSetSemantics { contentDescription = "$action, ${record.appLabel}, ${record.site}, $time. $outcome" },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(32.dp).clip(RoundedCornerShape(8.dp)))
        else NuvoriAvatar(record.appLabel.ifBlank { record.packageName.ifBlank { "?" } }, size = 32.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${record.appLabel} · $action", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(record.site, record.packageName, time).filter(String::isNotBlank).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(outcome, style = MaterialTheme.typography.bodySmall,
                color = if (record.offered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
}
