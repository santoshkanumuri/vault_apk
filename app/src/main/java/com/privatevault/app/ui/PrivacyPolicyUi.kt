package com.privatevault.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun PrivacyPolicyDialog(close: () -> Unit) {
    val context = LocalContext.current
    val policy = remember { context.assets.open("privacy-policy.txt").bufferedReader().use { it.readText() } }
    AlertDialog(onDismissRequest = close,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(12.dp),
        title = { Text("Privacy policy") },
        text = { Text(policy, Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
