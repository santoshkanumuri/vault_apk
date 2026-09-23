package com.privatevault.app.security

import com.privatevault.app.data.EntryType
import com.privatevault.app.data.EntryWithDetails
import com.privatevault.app.data.VaultEntry
import java.util.Locale

internal data class ExactPasswordDuplicateGroup(
    val keeper: EntryWithDetails,
    val duplicates: List<EntryWithDetails>
)

private data class ExactPasswordDuplicateKey(
    val entry: VaultEntry,
    val groupIds: List<String>
)

/**
 * Finds password entries whose user-controlled data and group membership match exactly.
 * Entries with photos are excluded because matching encrypted filenames does not prove that
 * their attachment contents are equal.
 */
internal fun exactPasswordDuplicateGroups(entries: List<EntryWithDetails>): List<ExactPasswordDuplicateGroup> =
    entries.asSequence()
        .filter { it.entry.type == EntryType.PASSWORD && it.photos.isEmpty() }
        .groupBy { item ->
            ExactPasswordDuplicateKey(
                item.entry.copy(id = "", lastOpenedAt = 0, sortOrder = 0, createdAt = 0, updatedAt = 0),
                item.groups.map { it.id }.sorted()
            )
        }
        .values
        .filter { it.size > 1 }
        .map { matches ->
            val ordered = matches.sortedWith(compareBy<EntryWithDetails> { it.entry.createdAt }.thenBy { it.entry.id })
            ExactPasswordDuplicateGroup(ordered.first(), ordered.drop(1))
        }
        .sortedWith(compareBy<ExactPasswordDuplicateGroup> { it.keeper.entry.title.lowercase(Locale.ROOT) }
            .thenBy { it.keeper.entry.primaryValue }
            .thenBy { it.keeper.entry.id })
        .toList()
