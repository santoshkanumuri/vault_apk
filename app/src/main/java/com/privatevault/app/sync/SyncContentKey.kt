package com.privatevault.app.sync

import com.privatevault.app.data.VaultDatabase
import java.util.Base64

/** Shared content keys are read only from the open SQLCipher vault. */
internal suspend fun VaultDatabase.syncContentKey(localKey: ByteArray): ByteArray {
    require(localKey.size == 32)
    val state = syncDao().vaultState() ?: return localKey.copyOf()
    require(state.vaultId == dao().settings()?.vaultId && state.keyEpoch == 1L) {
        "Invalid sync key state"
    }
    return Base64.getUrlDecoder().decode(state.contentKey).also { require(it.size == 32) }
}
