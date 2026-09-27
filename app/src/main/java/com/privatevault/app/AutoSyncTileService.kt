package com.privatevault.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import com.privatevault.app.sync.LanSyncService
import com.privatevault.app.sync.MemberStatus

class AutoSyncTileService : TileService() {
    override fun onTileAdded() {
        super.onTileAdded()
        updateState()
    }

    override fun onStartListening() {
        super.onStartListening()
        updateState()
    }

    override fun onClick() {
        super.onClick()
        if (!hasPairedGroup() || !getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            openSyncSettings()
            return
        }
        if (LanSyncService.isPaused(this)) {
            runCatching { LanSyncService.start(this, resume = true) }
                .onFailure { LanSyncService.pause(this); openSyncSettings() }
        } else LanSyncService.pause(this)
        updateState()
    }

    private fun hasPairedGroup(): Boolean {
        val mirror = runCatching { LanSyncService.store(this).snapshot() }.getOrNull() ?: return false
        val active = mirror.members.filter { it.status == MemberStatus.ACTIVE.name }
        return active.size > 1 && active.any { it.deviceId == mirror.localDeviceId }
    }

    private fun updateState() {
        qsTile?.apply {
            label = "Auto sync"
            state = if (hasPairedGroup() && !LanSyncService.isPaused(this@AutoSyncTileService) &&
                getSystemService(NotificationManager::class.java).areNotificationsEnabled())
                Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @Suppress("DEPRECATION")
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openSyncSettings() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(ACTION_QS_TILE_PREFERENCES)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(this, AutoSyncTileService::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(intent)
    }
}

internal fun requestAutoSyncTile(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, AutoSyncTileService::class.java), "Auto sync",
            Icon.createWithResource(context, R.drawable.ic_auto_sync), context.mainExecutor
        ) { result ->
            val message = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Auto sync added to Quick Settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Auto sync is already in Quick Settings."
                else -> "You can add Auto sync from the Quick Settings edit screen."
            }
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    } else Toast.makeText(context,
        "Open Quick Settings, tap Edit, then drag Auto sync into your tiles.", Toast.LENGTH_LONG).show()
}
