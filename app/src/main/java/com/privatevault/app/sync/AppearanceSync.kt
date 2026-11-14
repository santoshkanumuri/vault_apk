package com.privatevault.app.sync

import android.content.Context
import com.privatevault.app.appearance.AppearanceStore
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Base64

/**
 * Shares the vault appearance with the sync group (protocol [SyncFeatures.APPEARANCE]).
 *
 * A local choice waits in [AppearanceStore] as pending. The unlocked app writes it ([flush]) when the
 * group allows it: at unlock, after each local choice, and whenever [requests] ticks, that is when a
 * Wi-Fi or Google Drive pass starts or a peer's handshake features were recorded. Writing needs the
 * vault key, so a locked device only collects ticks; the next unlock flushes.
 */
internal object AppearanceSync {
    private val mutableRequests = MutableStateFlow(0L)
    /** Ticks when a flush may now succeed. The unlocked app collects it. */
    val requests: StateFlow<Long> = mutableRequests.asStateFlow()
    private val writing = Mutex()

    fun requestFlush() = mutableRequests.update { it + 1 }

    /**
     * Writes the pending appearance when the gate is open ([AppearanceSyncRules.gateOpen], computed from
     * the handshake features in [store]). True when a change was written; the caller then publishes it.
     */
    suspend fun flush(context: Context, database: VaultDatabase, identityStore: AndroidDeviceIdentityStore,
        store: LockedSyncStore, vaultKey: ByteArray): Boolean = writing.withLock {
        if (!AppearanceStore.read(context).pendingSync) return@withLock false
        if (!store.appearanceSyncOpen()) return@withLock false
        val vaultId = store.snapshot()?.vaultId ?: return@withLock false
        LocalAppearanceChangeWriter(database, identityStore).write(context, vaultKey, vaultId)
    }

    /**
     * A device that joined from a snapshot holds the group's appearance changes without applying them.
     * It takes the value they settle on ([AppearanceSyncRules.current]); a pending local choice competes
     * by last writer wins and, if it is later, stays pending and is written by the next flush.
     */
    internal fun adoptFromSnapshot(context: Context, state: SnapshotSyncState) {
        val chosen = AppearanceSyncRules.current(state.operations) ?: return
        val contentKey = Base64.getUrlDecoder().decode(state.contentKey)
        val appearance = try {
            val plaintext = IncomingEntryChangeApplier.decryptBytes(chosen.toSyncChange(), contentKey)
            try { AppearanceSyncRules.parsePayload(plaintext.toString(Charsets.UTF_8)) } finally { plaintext.fill(0) }
        } finally { contentKey.fill(0) }
        val stored = AppearanceStore.read(context)
        val localId = if (stored.pendingSync && stored.updatedByDevice == null)
            AndroidDeviceIdentityStore(context).getOrCreate().deviceId else null
        if (AppearanceSyncRules.decide(VersionRelation.AFTER, stored, localId, chosen.occurredAtUtc, chosen.deviceId) ==
            AppearanceOutcome.APPLY) AppearanceStore.applyRemote(context, appearance, chosen.occurredAtUtc, chosen.deviceId)
    }
}
