package com.privatevault.app.sync

internal fun requireDeviceCapacity(activeIds: Set<String>, windowsIds: Set<String>,
    joiningId: String, windows: Boolean) {
    if (joiningId in activeIds) return
    val windowsCount = activeIds.count { it in windowsIds }
    require(activeIds.size < MAX_ACTIVE_SYNC_DEVICES) { "This vault already has $MAX_ACTIVE_SYNC_DEVICES active devices" }
    if (windows) require(windowsCount < MAX_ACTIVE_WINDOWS_DEVICES) {
        "This vault already has $MAX_ACTIVE_WINDOWS_DEVICES active Windows devices"
    } else require(activeIds.size - windowsCount < MAX_ACTIVE_MOBILE_DEVICES) {
        "This vault already has $MAX_ACTIVE_MOBILE_DEVICES active mobile devices"
    }
}
