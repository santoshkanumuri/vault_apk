package com.privatevault.app.sync

/**
 * The optional protocol features this build announces in the sync handshake `features` list
 * (see [pairingHandshake]). A peer that sends no list supports none of them. Peers record what
 * the other side announced ([LockedSyncStore.recordPeerFeatures]) and use a feature only when every
 * device that must accept it announced it.
 */
internal object SyncFeatures {
    /** Changes of the shared vault appearance, entity type [AppearanceSyncRules.ENTITY_TYPE]. */
    const val APPEARANCE = "appearance-v1"

    /** Everything this build announces, in one list for the handshake. */
    val ANNOUNCED: List<String> = listOf(RemovalCutoff.FEATURE, APPEARANCE)
}
