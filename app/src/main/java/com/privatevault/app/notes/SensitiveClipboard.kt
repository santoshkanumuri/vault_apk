package com.privatevault.app.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import com.privatevault.app.copySensitive

/**
 * The clipboard for note text: text copied or cut in the editor is marked sensitive, so Android
 * hides it from clipboard previews and history, and is cleared after 30 seconds like other vault values.
 */
@Composable
internal fun rememberSensitiveClipboard(): ClipboardManager {
    val platform = LocalClipboardManager.current
    val context = LocalContext.current
    return remember(platform, context) {
        object : ClipboardManager by platform {
            override fun setText(annotatedString: AnnotatedString) {
                runCatching { copySensitive(context, "Note", annotatedString.text) }
            }
        }
    }
}
