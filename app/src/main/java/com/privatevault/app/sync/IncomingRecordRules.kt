package com.privatevault.app.sync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.data.CardKind
import com.privatevault.app.data.EntryType
import com.privatevault.app.data.VaultEntry

/**
 * Parses the decrypted `entry` and `photo` payload objects shared with Windows. Only the record ID
 * and type are required for entries; fields a peer omits or sends as null take Android's defaults
 * instead of quarantining the change and stalling that device's whole chain.
 */
internal object SyncEntryCodec {
    internal data class PhotoChange(val id: String, val entryId: String, val isCover: Boolean,
        val addedAt: Long, val ref: PhotoBlobRef)

    fun parseEntry(json: String, expectedId: String): VaultEntry = try {
        val o = JsonParser.parseString(json).asJsonObject
        val createdAt = o.long("createdAt") ?: 0L
        val parsed = VaultEntry(
            id = requireNotNull(o.text("id")),
            type = EntryType.valueOf(requireNotNull(o.text("type"))),
            title = o.text("title").orEmpty(),
            primaryValue = o.text("primaryValue").orEmpty(),
            secondaryValue = o.text("secondaryValue").orEmpty(),
            tertiaryValue = o.text("tertiaryValue").orEmpty(),
            fourthValue = o.text("fourthValue").orEmpty(),
            cardKind = o.text("cardKind")?.let { kind -> CardKind.entries.firstOrNull { it.name == kind } }
                ?: CardKind.CREDIT,
            network = o.text("network").orEmpty(),
            totpAlgorithm = o.text("totpAlgorithm")?.takeIf(String::isNotBlank) ?: "SHA1",
            totpDigits = o.long("totpDigits")?.let(Math::toIntExact) ?: 6,
            totpPeriod = o.long("totpPeriod")?.let(Math::toIntExact) ?: 30,
            linkedApps = o.text("linkedApps").orEmpty(),
            autofillSignatures = o.text("autofillSignatures").orEmpty(),
            autofillOrigins = o.text("autofillOrigins").orEmpty(),
            linkedAuthenticatorId = o.text("linkedAuthenticatorId").orEmpty(),
            notes = o.text("notes").orEmpty(),
            color = o.long("color") ?: 0xFF08704AL,
            tags = o.text("tags").orEmpty(),
            favorite = o.bool("favorite") ?: false,
            lastOpenedAt = o.long("lastOpenedAt") ?: 0L,
            sortOrder = o.long("sortOrder") ?: createdAt,
            createdAt = createdAt,
            updatedAt = o.long("updatedAt") ?: createdAt,
        )
        require(parsed.id == expectedId) { "Invalid sync entry" }
        val entry = if (parsed.title.isBlank()) parsed.copy(title = fallbackTitle(parsed)) else parsed
        if (entry.type == EntryType.AUTHENTICATOR)
            com.privatevault.app.security.Totp.validate(entry.secondaryValue, entry.totpAlgorithm,
                entry.totpDigits, entry.totpPeriod)
        entry
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync entry") }

    fun parsePhoto(json: String, expectedId: String): PhotoChange = try {
        val o = JsonParser.parseString(json).asJsonObject
        PhotoChange(requireNotNull(o.text("id")), requireNotNull(o.text("entryId")),
            o.bool("isCover") ?: false, o.long("addedAt") ?: 0L,
            PhotoBlobRef(requireNotNull(o.text("blobHash")), requireNotNull(o.long("blobSize")))).also {
            require(it.id == expectedId && it.entryId.isNotBlank() && it.entryId.length <= 128 &&
                it.ref.hash.matches(Regex("[a-f0-9]{64}")) &&
                it.ref.size in 28..PhotoSyncBlobs.MAX_PHOTO_BYTES + 28)
        }
    } catch (_: Exception) { throw IllegalArgumentException("Invalid sync photo") }

    /** Deterministic, so every Android device shows the same label for a peer's blank title. */
    internal fun fallbackTitle(entry: VaultEntry): String {
        if (entry.type == EntryType.PASSWORD) {
            val origin = entry.tertiaryValue.ifBlank {
                entry.autofillOrigins.lineSequence().map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
            }
            val host = origin.substringAfter("://").substringBefore('/').trim()
            if (host.isNotBlank()) return host
            if (entry.primaryValue.isNotBlank()) return entry.primaryValue.trim().take(200)
            return "Login"
        }
        return "Untitled"
    }

    private fun JsonObject.value(name: String): JsonElement? = get(name)?.takeUnless { it.isJsonNull }

    private fun JsonObject.text(name: String): String? = value(name)?.let {
        require(it.isJsonPrimitive) { "Invalid text field" }
        it.asString
    }

    private fun JsonObject.long(name: String): Long? = value(name)?.let {
        val primitive = it.asJsonPrimitive
        require(!primitive.isBoolean) { "Invalid number field" }
        if (primitive.isString) primitive.asString.trim().toLong() else primitive.asBigDecimal.toLong()
    }

    private fun JsonObject.bool(name: String): Boolean? = value(name)?.let {
        val primitive = it.asJsonPrimitive
        when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isString && primitive.asString.equals("true", true) -> true
            primitive.isString && primitive.asString.equals("false", true) -> false
            else -> throw IllegalArgumentException("Invalid boolean field")
        }
    }
}

/**
 * Decides how an incoming entry's links fit this device's records. Links to records whose delete
 * tombstone is known are dropped instead of waiting forever, folders of another item type are
 * dropped (a peer may have changed the item's type, for example to NOTE), and only a link that
 * may still arrive makes the change wait.
 */
internal object EntryLinkRules {
    data class Context(
        /** Existing groups and folders on this device: ID to folder type (null for a plain group). */
        val groups: Map<String, EntryType?>,
        /** Groups that are absent here and have a known delete tombstone. */
        val deletedGroupIds: Set<String>,
        /** Type of the linked authenticator entry when it exists here. */
        val linkedTargetType: EntryType?,
        /** The linked entry is absent here and has a known delete tombstone. */
        val linkedTargetDeleted: Boolean,
    )

    sealed interface Resolution {
        data class Ready(val entry: VaultEntry, val groupIds: Set<String>) : Resolution
        data object Waiting : Resolution
    }

    fun resolve(entry: VaultEntry, groupIds: Set<String>, context: Context): Resolution {
        val live = groupIds - context.deletedGroupIds
        if (!context.groups.keys.containsAll(live)) return Resolution.Waiting
        val plainGroups = live.filter { context.groups[it] == null }
        val folder = live.filter { context.groups[it] == entry.type }.minOrNull()
        val linked = entry.linkedAuthenticatorId
        val keepLink = when {
            linked.isBlank() -> true
            entry.type != EntryType.PASSWORD || linked == entry.id -> false
            context.linkedTargetDeleted -> false
            context.linkedTargetType == null -> return Resolution.Waiting
            else -> context.linkedTargetType == EntryType.AUTHENTICATOR
        }
        val resolved = if (keepLink) entry else entry.copy(linkedAuthenticatorId = "")
        return Resolution.Ready(resolved, (plainGroups + listOfNotNull(folder)).toSet())
    }
}

/** Metadata of a queued operation; readable without the vault key. */
internal data class QueuedOperationMeta(val deviceId: String, val sequence: Long, val entityType: String,
    val entityId: String, val kind: String)

/**
 * A photo upsert whose blob is unavailable can be recorded without its image when the same author
 * later deleted or replaced that photo, or deleted its entry. The author's chain proves the later
 * operation saw the upsert, so the end state is identical and the chain no longer stalls on a blob
 * the sender may have discarded.
 */
internal object PhotoSupersession {
    fun isSuperseded(blocked: QueuedOperationMeta, photoId: String, entryId: String,
        queued: Collection<QueuedOperationMeta>): Boolean = queued.any { later ->
        later.deviceId == blocked.deviceId && later.sequence > blocked.sequence &&
            (later.entityType == "photo" && later.entityId == photoId ||
                later.entityType == "entry" && later.kind == "delete" && later.entityId == entryId)
    }
}
