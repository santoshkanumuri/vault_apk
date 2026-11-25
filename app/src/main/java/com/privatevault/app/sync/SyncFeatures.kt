package com.privatevault.app.sync

/**
 * The optional protocol features this build announces in the sync handshake `features` list
 * (see [pairingHandshake]) and in its Google Drive progress file (`features`, see [CloudSyncPass]).
 * A peer that sends no list supports none of them. Peers record what the other side announced
 * ([LockedSyncStore.recordPeerFeatures]) and use a feature only when every device that must accept
 * it announced it.
 */
internal object SyncFeatures {
    /** Changes of the shared vault appearance, entity type [AppearanceSyncRules.ENTITY_TYPE]. */
    const val APPEARANCE = "appearance-v1"

    /** Website icons captured on Windows, entity type [SiteIconSyncRules.ENTITY_TYPE]. This phone receives them. */
    const val SITE_ICONS = "site-icons-v1"

    /**
     * This build reads enrollment snapshot v4 (a signed checkpoint instead of the full history), and
     * it relays only gap-free chains and acknowledges old resends ([RelayRetention], [IncomingChain]).
     */
    const val CHECKPOINT_ENROLL = SyncCheckpoints.FEATURE

    /**
     * Sign-in methods that Nuvori for Windows remembers, entity type [SignInMethodSyncRules.ENTITY_TYPE]. This phone
     * receives them and writes deletes, only while every other active member announced it.
     */
    const val SIGN_IN_METHODS = "sign-in-methods-v1"

    /**
     * In the pairing hello: this build joins a group keeping its items, matched by ID ([MergeJoinRules]),
     * and as the host sends the lineage of its old group. Used only when both sides announced it.
     */
    const val MERGE_JOIN = MergeJoinRules.FEATURE

    /** Keys of rotated epochs and signed key statements ([EpochKeys]). Rotation is gated on statements, not on this string. */
    const val EPOCH_KEYS = EpochKeys.FEATURE

    /** This build reads drive floor markers; drive cleanup needs it from every active member ([DriveCompaction]). */
    const val HISTORY_COMPACTION = DriveCompaction.FEATURE

    /**
     * Password checks from the managing device and each device's switch to them, entity type
     * [MasterPasswordRules.ENTITY_TYPE]. The managing device changes its master password while it shares the
     * vault only when every other active member announced it ([MasterPasswordRules.devicesWithoutFeature]).
     */
    const val MASTER_PASSWORD = MasterPasswordRules.FEATURE

    /** Everything this build announces, in one list for the handshake. */
    val ANNOUNCED: List<String> = listOf(RemovalCutoff.FEATURE, APPEARANCE, SITE_ICONS, CHECKPOINT_ENROLL, SIGN_IN_METHODS,
        MERGE_JOIN, EPOCH_KEYS, HISTORY_COMPACTION, MASTER_PASSWORD)
}
