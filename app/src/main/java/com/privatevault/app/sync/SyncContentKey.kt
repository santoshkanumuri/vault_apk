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

/**
 * Every content key this device holds for the current group, by epoch ([SyncKeyring]): epoch 1 from
 * `sync_vault_state`, later epochs from `sync_epoch_keys` (feature epoch-keys-v1). Entity IDs that are
 * HMACs (site icons, sign-in methods) always use epoch 1 ([syncContentKey]) so they never change.
 */
internal suspend fun VaultDatabase.syncKeyring(localKey: ByteArray): SyncKeyring {
    val first = syncContentKey(localKey)
    val vaultId = dao().settings()?.vaultId
    val later = syncDao().epochKeys().filter { it.vaultId == vaultId && it.epoch >= 2 }
        .associate { it.epoch to Base64.getUrlDecoder().decode(it.contentKey).also { key -> require(key.size == 32) } }
    return SyncKeyring(later + (1L to first))
}

/** The keyring of an enrollment snapshot: its content key and the later ones it carries. */
internal fun SnapshotSyncState.keyring(): SyncKeyring = SyncKeyring(contentKeys.orEmpty()
    .mapNotNull { (epoch, key) -> epoch.toLongOrNull()?.takeIf { it >= 2 }?.let { it to Base64.getUrlDecoder().decode(key) } }
    .toMap() + (1L to Base64.getUrlDecoder().decode(contentKey)))
