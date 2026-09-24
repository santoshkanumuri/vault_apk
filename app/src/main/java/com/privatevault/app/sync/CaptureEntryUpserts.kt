package com.privatevault.app.sync

import androidx.room.withTransaction
import com.privatevault.app.data.VaultDao
import com.privatevault.app.data.VaultDatabase

/** Keeps DAO import and credential-save decisions atomic with their signed sync changes. */
suspend fun <T> VaultDatabase.captureEntryUpserts(
    identityStore: AndroidDeviceIdentityStore,
    vaultKey: ByteArray,
    action: suspend VaultDao.() -> T,
): T = withTransaction {
    val vaultDao = dao()
    val before = vaultDao.allEntries().associateBy { it.entry.id }
    val result = vaultDao.action()
    val after = vaultDao.allEntries().associateBy { it.entry.id }
    require(before.keys.all(after::containsKey)) { "Unexpected deletion in an entry import" }
    val writer = LocalEntryChangeWriter(this, identityStore)
    after.values.forEach { item ->
        val previous = before[item.entry.id]
        val groups = item.groups.map { it.id }.toSet()
        if (previous?.entry != item.entry || previous?.groups.orEmpty().map { it.id }.toSet() != groups)
            writer.save(item.entry, groups, vaultKey)
    }
    result
}
