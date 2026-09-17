package com.privatevault.app

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

class VaultCodesTileService : TileService() {
    override fun onStartListening() {
        qsTile?.apply {
            label = "Vault codes"
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { openPicker() } else openPicker()
    }

    @Suppress("DEPRECATION")
    // The PendingIntent overload only exists on API 34+. The Intent call is API-gated below.
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openPicker() {
        val intent = Intent(this, VaultCodesActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        } else startActivityAndCollapse(intent)
    }
}

internal fun requestVaultCodesTile(context: Context) {
    if (Build.VERSION.SDK_INT >= 33) {
        context.getSystemService(StatusBarManager::class.java).requestAddTileService(
            ComponentName(context, VaultCodesTileService::class.java), "Vault codes",
            Icon.createWithResource(context, R.drawable.ic_vault_codes), context.mainExecutor
        ) { result ->
            val text = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Vault codes added to Quick Settings."
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "Vault codes is already in Quick Settings."
                else -> "You can add Vault codes from the Quick Settings edit screen."
            }
            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
        }
    } else Toast.makeText(context, "Open Quick Settings, tap Edit, then drag Vault codes into your tiles.", Toast.LENGTH_LONG).show()
}
