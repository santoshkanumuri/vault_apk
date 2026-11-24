package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.privatevault.app.data.SyncOperationEntity
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol `sign-in-methods-v1` ([SyncFeatures.SIGN_IN_METHODS]), shared with Windows (`sign_in_sync.rs`): which
 * "Continue with <provider>" button, and which account, the user used on a site. Nuvori for Windows notices it
 * through the browser extension; this phone receives it and can delete it.
 *
 * - Entity type [ENTITY_TYPE], one entity per (site, provider, account key). Entity id: [entityId], an HMAC under
 *   the group's content key, because entity ids travel in plain text.
 * - Upsert plaintext `{"signInMethod":{"site","provider","account","firstUsedAt","lastUsedAt","useCount"}}`;
 *   delete plaintext `{"deleted":true}` with a tombstone, as for entries. Every field is checked ([parsePayload]).
 * - Never a conflict to review. Concurrent upserts merge field by field ([merge]); an upsert concurrent with a delete
 *   goes to the later writer ([AppearanceSyncRules.incomingWins]).
 *
 * Pure functions only, so the shared vector file (`sync-vectors/sign-in-methods-v1.json`) tests them on the JVM.
 */
internal object SignInMethodSyncRules {
    const val ENTITY_TYPE = "sign_in_method"
    const val ID_PREFIX = "nuvori-sync-sign-in-method-id-v1:"
    const val MAX_ACCOUNT_CHARS = 256
    const val MAX_USE_COUNT = 1_000_000_000L
    private val INTEGER = Regex("-?(0|[1-9][0-9]{0,17})")
    val PROVIDERS = listOf("google", "apple", "microsoft", "github", "facebook", "x", "linkedin", "discord", "amazon", "slack")

    /** One method as it travels. [account] is trimmed; "" means not known. */
    data class Method(val site: String, val provider: String, val account: String, val firstUsedAt: Long,
        val lastUsedAt: Long, val useCount: Long) {
        val accountKey: String get() = accountKey(account)
    }

    /** Unicode White_Space, as Rust `str::trim` and Python `str.strip` use it. */
    private fun isUnicodeSpace(c: Char): Boolean = c in '\u0009'..'\u000D' || c == ' ' || c == '\u0085' || c == ' ' ||
        c == ' ' || c in ' '..' ' || c == ' ' || c == ' ' || c == ' ' || c == ' ' ||
        c == '　'

    fun trim(text: String): String = text.trim(::isUnicodeSpace)

    /** The account trimmed and lower-cased with Unicode default case mapping (pinned by the vectors: İ, final sigma). */
    fun accountKey(account: String): String = trim(account).lowercase(Locale.ROOT)

    /** At most [MAX_ACCOUNT_CHARS] characters after trimming, no control (Cc) or format (Cf) characters. */
    fun validAccount(account: String): Boolean {
        val text = trim(account)
        if (text.codePointCount(0, text.length) > MAX_ACCOUNT_CHARS) return false
        var index = 0
        while (index < text.length) {
            val point = text.codePointAt(index)
            val type = Character.getType(point)
            if (type == Character.CONTROL.toInt() || type == Character.FORMAT.toInt()) return false
            index += Character.charCount(point)
        }
        return true
    }

    /** The first 32 hex characters of HMAC-SHA256(content key, [ID_PREFIX] + site + "\n" + provider + "\n" + account key). */
    fun entityId(contentKey: ByteArray, site: String, provider: String, account: String): String {
        require(contentKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(contentKey, "HmacSHA256")) }
        val message = "$ID_PREFIX$site\n$provider\n${accountKey(account)}"
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }

    fun valid(method: Method): Boolean = SiteIconSyncRules.validSite(method.site) && method.provider in PROVIDERS &&
        validAccount(method.account) && method.firstUsedAt >= 0 && method.firstUsedAt <= method.lastUsedAt &&
        method.useCount in 1..MAX_USE_COUNT

    /**
     * The method of a decrypted upsert whose entity id is [entityId]. Extra keys are ignored; a missing field, a
     * string or fraction where an integer belongs, a value out of range or another method's id throws
     * [IllegalArgumentException], so the change is set aside.
     */
    fun parsePayload(json: String, entityId: String, contentKey: ByteArray): Method {
        val method = try {
            val body = JsonParser.parseString(json).asJsonObject.getAsJsonObject("signInMethod")
            fun primitive(name: String): JsonPrimitive = requireNotNull(body.get(name)?.takeIf { it.isJsonPrimitive }).asJsonPrimitive
            fun text(name: String): String = primitive(name).also { require(it.isString) }.asString
            // A JSON integer only: a string, a fraction or an exponent is refused.
            fun integer(name: String): Long {
                val value = primitive(name)
                require(value.isNumber && INTEGER.matches(value.asString))
                return value.asString.toLong()
            }
            Method(text("site"), text("provider"), trim(text("account")), integer("firstUsedAt"), integer("lastUsedAt"),
                integer("useCount"))
        } catch (_: Exception) { throw IllegalArgumentException("Invalid sign-in method change") }
        require(valid(method)) { "Invalid sign-in method" }
        require(entityId(contentKey, method.site, method.provider, method.account) == entityId) {
            "Sign-in method id does not match its method"
        }
        return method
    }

    /** The plaintext of a delete: `{"deleted":true}`. True only for exactly that flag. */
    fun isDelete(json: String): Boolean = runCatching {
        JsonParser.parseString(json).asJsonObject.get("deleted")?.let { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean && it.asBoolean }
    }.getOrNull() == true

    fun deletePayload(): ByteArray = JsonObject().apply { addProperty("deleted", true) }.toString().toByteArray(Charsets.UTF_8)

    /** AAD of a change: `nuvori-sync-sign_in_method-v1:<vaultId>:<mutationId>:<entityId>`. */
    fun aad(vaultId: String, mutationId: String, entityId: String) = "nuvori-sync-$ENTITY_TYPE-v1:$vaultId:$mutationId:$entityId"

    /**
     * Concurrent upserts: first use = min, last use = max, count = max (both sides may count the same use after a
     * restore), and the account text of the later writer. Both sides compute the same value.
     */
    fun merge(current: Method, currentAt: String?, currentDevice: String?, incoming: Method, incomingAt: String,
        incomingDevice: String): Method = Method(current.site, current.provider,
        if (AppearanceSyncRules.incomingWins(currentAt, currentDevice, incomingAt, incomingDevice)) incoming.account else current.account,
        minOf(current.firstUsedAt, incoming.firstUsedAt), maxOf(current.lastUsedAt, incoming.lastUsedAt),
        maxOf(current.useCount, incoming.useCount))

    /** An upsert concurrent with a delete: true when the upsert is the later writer. */
    fun upsertBeatsDelete(deleteAt: String?, deleteDevice: String?, upsertAt: String, upsertDevice: String): Boolean =
        AppearanceSyncRules.incomingWins(deleteAt, deleteDevice, upsertAt, upsertDevice)

    /** What this phone holds for an entity when a change arrives. */
    sealed class Local {
        /** Nothing known here. */
        data object None : Local()
        /** A live method, last written by ([at], [device]). */
        data class Live(val method: Method, val at: String?, val device: String?) : Local()
        /** Deleted in the group (a tombstone of ([at], [device])). */
        data class Deleted(val at: String?, val device: String?) : Local()
        /** Deleted on this phone at [at]; the delete is not written yet. It competes like a concurrent delete. */
        data class PendingDelete(val method: Method, val at: String?, val device: String?) : Local()
    }

    /** What the applier does. Every outcome but [Obsolete] stores the merged record version. */
    sealed class Outcome {
        data object Obsolete : Outcome()
        /** Keep what this phone has. */
        data object KeepLocal : Outcome()
        /** Store [method], last written by ([at], [device]). */
        data class Store(val method: Method, val at: String, val device: String) : Outcome()
        /** Delete the method and keep the tombstone. */
        data object Delete : Outcome()
    }

    /**
     * Section 3 of the spec. The version vectors decide first: an older or equal change is obsolete, a dominating one
     * applies. Concurrent upserts merge; an upsert concurrent with a delete goes to the later writer. A delete waiting
     * on this phone competes like a concurrent delete, also against a dominating upsert (the group never saw it).
     * [incoming] is null for a delete.
     */
    fun decide(relation: VersionRelation, local: Local, incoming: Method?, at: String, device: String): Outcome {
        if (relation == VersionRelation.BEFORE || relation == VersionRelation.EQUAL) return Outcome.Obsolete
        val after = relation == VersionRelation.AFTER
        if (incoming == null) return when {
            after -> Outcome.Delete
            local is Local.Live -> if (AppearanceSyncRules.incomingWins(local.at, local.device, at, device)) Outcome.Delete
                else Outcome.KeepLocal
            else -> Outcome.Delete
        }
        return when (local) {
            is Local.PendingDelete -> if (upsertBeatsDelete(local.at, local.device, at, device)) Outcome.Store(incoming, at, device)
                else Outcome.KeepLocal
            is Local.Live -> if (after) Outcome.Store(incoming, at, device) else {
                val later = AppearanceSyncRules.incomingWins(local.at, local.device, at, device)
                Outcome.Store(merge(local.method, local.at, local.device, incoming, at, device),
                    if (later || local.at == null) at else local.at, if (later || local.at == null) device else local.device.orEmpty())
            }
            is Local.Deleted -> if (after || upsertBeatsDelete(local.at, local.device, at, device)) Outcome.Store(incoming, at, device)
                else Outcome.KeepLocal
            Local.None -> Outcome.Store(incoming, at, device)
        }
    }

    /** The pointwise maximum, so the next local write dominates every version seen here. */
    fun mergeVersions(local: RecordVersion, incoming: RecordVersion): RecordVersion = AppearanceSyncRules.merge(local, incoming)

    /** What a snapshot holds for one entity: the merged method, or null when a delete is the latest writer. */
    data class Choice(val entityId: String, val operation: SyncOperationEntity, val method: Method?)

    /**
     * Of each entity's changes that no other change of the entity includes: the merge of the upserts (oldest writer
     * first), unless a delete is the latest writer. [read] decrypts one change: its method, null for a valid delete,
     * or throws for an unreadable one (left out).
     */
    fun current(operations: List<SyncOperationEntity>, read: (SyncOperationEntity) -> Method?): List<Choice> =
        operations.filter { it.entityType == ENTITY_TYPE }.mapNotNull { operation ->
            runCatching { operation to read(operation) }.getOrNull()
        }.groupBy { it.first.entityId }.mapNotNull { (entityId, changes) ->
            val versions = changes.map { it to RecordVersion.parse(it.first.recordVersionJson) }
            val maximal = versions.filter { (change, version) ->
                versions.none { (other, otherVersion) -> other.first.hash != change.first.hash &&
                    version.relationTo(otherVersion) == VersionRelation.BEFORE }
            }.map { it.first }.sortedWith { a, b ->
                when {
                    AppearanceSyncRules.incomingWins(a.first.occurredAtUtc, a.first.deviceId, b.first.occurredAtUtc, b.first.deviceId) -> -1
                    AppearanceSyncRules.incomingWins(b.first.occurredAtUtc, b.first.deviceId, a.first.occurredAtUtc, a.first.deviceId) -> 1
                    else -> 0
                }
            }
            var merged: Pair<Method, SyncOperationEntity>? = null
            var deleted: SyncOperationEntity? = null
            maximal.forEach { (operation, method) ->
                if (method == null) deleted = operation
                else merged = merged?.let { (current, last) -> merge(current, last.occurredAtUtc, last.deviceId, method,
                    operation.occurredAtUtc, operation.deviceId) to operation } ?: (method to operation)
            }
            val live = merged
            val gone = deleted
            when {
                live != null && (gone == null || upsertBeatsDelete(gone.occurredAtUtc, gone.deviceId,
                    live.second.occurredAtUtc, live.second.deviceId)) -> Choice(entityId, live.second, live.first)
                gone != null -> Choice(entityId, gone, null)
                else -> null
            }
        }

    /**
     * True when this device may write a sign-in method change: it is an active member, it has another active member,
     * and every other active member announced [SyncFeatures.SIGN_IN_METHODS]. Released builds stop a writer's chain at
     * an unknown entity type, so one member without it keeps the gate closed.
     */
    fun gateOpen(localDeviceId: String, members: List<com.privatevault.app.data.SyncMembershipEntity>,
        peerFeatures: Map<String, List<String>>): Boolean {
        val active = members.filter { it.status == MemberStatus.ACTIVE.name }
        if (active.none { it.deviceId == localDeviceId }) return false
        val others = active.filter { it.deviceId != localDeviceId }
        return others.isNotEmpty() && others.all { SyncFeatures.SIGN_IN_METHODS in peerFeatures[it.deviceId].orEmpty() }
    }
}
