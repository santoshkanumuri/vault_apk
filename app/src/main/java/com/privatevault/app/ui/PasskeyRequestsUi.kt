package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.passkeys.PasskeyRequestLog
import com.privatevault.app.passkeys.PasskeyRequestRecord
import java.text.DateFormat
import java.util.Date

/**
 * "Recent passkey requests": what Android asked Nuvori since the app started, and why Nuvori said no. If an app shows
 * only other managers and nothing appears here, that app never asked Android's passkey providers.
 */
@Composable
internal fun PasskeyRequestsSection() {
    val records by PasskeyRequestLog.recent.collectAsStateWithLifecycle()
    SettingsSection("Recent passkey requests",
        description = "Apps and websites that asked Nuvori since it started, newest first. Kept only until Nuvori closes.") {
        if (records.isEmpty()) {
            Text("No requests yet. If an app lists other managers but nothing shows here after you try it, either Nuvori is turned off as a provider (use the button above), or the app uses Google's older sign-in method, which offers only Google's own manager.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            records.forEachIndexed { index, record ->
                if (index > 0) HorizontalDivider()
                PasskeyRequestRow(record)
            }
            SettingsSecondaryButton("Clear list", onClick = PasskeyRequestLog::clear)
        }
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
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(record.at))
    val outcome = if (record.offered) "Nuvori was offered" else "Not offered: ${record.reason}"
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "$action, ${record.appLabel}, ${record.site}, $time. $outcome" },
        verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${record.appLabel} · $action", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(listOf(record.site, record.packageName, time).filter(String::isNotBlank).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(outcome, style = MaterialTheme.typography.bodySmall,
            color = if (record.offered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
    }
}
