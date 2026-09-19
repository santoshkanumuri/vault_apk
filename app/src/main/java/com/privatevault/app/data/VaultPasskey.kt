package com.privatevault.app.data

/** Stored only in the SQLCipher vault and authenticated encrypted backups. */
@androidx.room.Entity(tableName = "passkeys")
data class VaultPasskey(
    @androidx.room.PrimaryKey val id: String,
    val rpId: String,
    val userHandle: String,
    val username: String,
    val displayName: String,
    val privateKey: String,
    val publicKey: String,
    val createdAt: Long = System.currentTimeMillis()
)

data class PasskeySummary(val id: String, val rpId: String, val username: String, val createdAt: Long)
