package com.privatevault.app.sync

import android.content.Context
import com.privatevault.app.data.VaultDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * H5 and M15: changes that arrived while Nuvori was locked apply on every path that unlocks the vault,
 * not only in the main app, so autofill never fills a password another device already changed. What
 * applied is passed on to the other devices and, when watch codes are on, to the watch.
 */
internal object UnlockedSyncApply {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Applies waiting changes for up to [budgetMs] (the rest applies at the next unlock or in the main
     * app). Returns how many applied. Never throws except for cancellation.
     */
    suspend fun applyWaiting(context: Context, database: VaultDatabase, vaultKey: ByteArray, budgetMs: Long = 4_000): Int {
        val app = context.applicationContext
        val store = LanSyncService.store(app)
        if (runCatching { store.queuedCount() }.getOrDefault(0) == 0) return 0
        val applied = try { withTimeoutOrNull(budgetMs) { store.applyQueued(database, vaultKey) } ?: 0 }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { 0 }
        if (applied > 0) {
            try { LanSyncService.publishCredentialChanges(app, database) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
            republishWatchLater(app, database, vaultKey)
        }
        return applied
    }

    /**
     * M15: sends the watch the current codes when watch sync is on, without holding up the caller.
     * [database] must stay open until the caller is done with it; the codes are read first.
     */
    private suspend fun republishWatchLater(context: Context, database: VaultDatabase, vaultKey: ByteArray) {
        val settings = runCatching { database.dao().settings() }.getOrNull() ?: return
        if (!settings.watchSyncEnabled) return
        val accounts = runCatching { database.dao().authenticatorEntries() }.getOrNull() ?: return
        val key = vaultKey.copyOf()
        scope.launch {
            try {
                if (com.privatevault.app.watch.WatchSyncPublisher(context).publish(settings.vaultId, key, accounts))
                    context.getSharedPreferences("vault_preferences", Context.MODE_PRIVATE).edit()
                        .putLong("watch_last_sent_at", System.currentTimeMillis()).apply()
            } catch (_: Exception) { }
            finally { key.fill(0) }
        }
    }
}
