package com.privatevault.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.privatevault.app.security.loginsWithPasskeys
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** "passkey_mark": ids of the logins with a passkey for the same account. [EntryListRow] shows a mark on them. */
internal val LocalPasskeyLogins = compositionLocalOf<Set<String>> { emptySet() }

/** Works out the passkey marks off the main thread (the rule reads the Public Suffix List) and provides them to [content]. */
@Composable
internal fun PasskeyLoginsProvider(viewModel: VaultViewModel, content: @Composable () -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val passkeys by viewModel.passkeys.collectAsStateWithLifecycle()
    val marked by produceState(emptySet<String>(), entries, passkeys) {
        value = if (passkeys.isEmpty()) emptySet()
            else withContext(Dispatchers.Default) { loginsWithPasskeys(entries.map { it.entry }, passkeys) }
    }
    CompositionLocalProvider(LocalPasskeyLogins provides marked, content = content)
}
