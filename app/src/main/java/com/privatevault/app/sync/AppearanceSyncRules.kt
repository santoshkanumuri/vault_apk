package com.privatevault.app.sync

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.privatevault.app.appearance.Appearance
import com.privatevault.app.appearance.StoredAppearance
import com.privatevault.app.appearance.isValidAppearanceId
import com.privatevault.app.data.SyncMembershipEntity
import com.privatevault.app.data.SyncOperationEntity
import java.time.Instant
import java.time.OffsetDateTime
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** What an incoming appearance change does here. Every outcome except [OBSOLETE] merges the record version. */
internal enum class AppearanceOutcome { APPLY, KEEP_LOCAL, OBSOLETE }

/**
 * Protocol `appearance-v1` ([SyncFeatures.APPEARANCE]), shared with Windows: one record per vault,
 * entity type [ENTITY_TYPE] and id [ENTITY_ID], always an upsert, plaintext
 * `{"appearance":{"accent":"<id>","surface":"<id>"}}`. Concurrent changes never become a conflict to
 * review: the later `occurredAtUtc` wins, then the greater device ID. Pure functions only, so the
 * shared vector file (`sync-vectors/appearance-v1.json`) tests them on the JVM.
 */
internal object AppearanceSyncRules {
    const val ENTITY_TYPE = "appearance"
    const val ENTITY_ID = "vault"

    /** The payload AAD; the same string the generic [IncomingEntryChangeApplier.decryptBytes] builds. */
    fun aad(vaultId: String, mutationId: String) = "nuvori-sync-$ENTITY_TYPE-v1:$vaultId:$mutationId:$ENTITY_ID"

    fun encodePayload(appearance: Appearance): ByteArray = JsonObject().apply {
        add("appearance", JsonObject().apply {
            addProperty("accent", appearance.accent)
            addProperty("surface", appearance.surface)
        })
    }.toString().toByteArray(Charsets.UTF_8)

    /**
     * The ids of a decrypted payload. Unknown but well-formed ids (a newer palette) are kept as received;
     * extra keys are ignored. Anything else throws [IllegalArgumentException], so the change is set aside.
     */
    fun parsePayload(json: String): Appearance {
        val ids = try {
            val body = JsonParser.parseString(json).asJsonObject.getAsJsonObject("appearance")
            fun id(name: String): String = requireNotNull(body.get(name)?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }).asString
            id("accent") to id("surface")
        } catch (_: Exception) { throw IllegalArgumentException("Invalid appearance change") }
        require(isValidAppearanceId(ids.first) && isValidAppearanceId(ids.second)) { "Invalid appearance id" }
        return Appearance(ids.first, ids.second)
    }

    /** AES-256-GCM with the payload key HMAC-SHA256(contentKey, "nuvori-sync-payload-key-v1"), as for every entity. */
    fun encrypt(contentKey: ByteArray, aad: String, nonce: ByteArray, plaintext: ByteArray): ByteArray {
        require(contentKey.size == 32 && nonce.size == 12)
        val payloadKey = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(contentKey, "HmacSHA256"))
            doFinal("nuvori-sync-payload-key-v1".toByteArray(Charsets.UTF_8))
        }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(payloadKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD(aad.toByteArray(Charsets.UTF_8))
                doFinal(plaintext)
            }
        } finally { payloadKey.fill(0) }
    }

    /** An ISO-8601 instant, with `Z` or a numeric offset; null when it does not parse. */
    fun instant(text: String): Instant? = runCatching { Instant.parse(text) }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

    /**
     * Last writer wins: true when the incoming change is later than the current value. Times compare as
     * instants (a missing or unreadable time is earlier than any time), then device IDs as plain strings,
     * where a missing device counts as "". A current value without a time loses to anything.
     */
    fun incomingWins(currentAt: String?, currentDevice: String?, incomingAt: String, incomingDevice: String): Boolean {
        if (currentAt == null) return true
        val byTime = compareValues(instant(incomingAt), instant(currentAt))
        if (byTime != 0) return byTime > 0
        return incomingDevice > (currentDevice ?: "")
    }

    /**
     * True when this device may write an appearance change: it is an active member of the group, it has
     * at least one other active member, and every other active member announced [SyncFeatures.APPEARANCE]
     * in its last Wi-Fi handshake. Released 2.2.3 builds set aside an unknown entity type, and every later
     * change of the writing device then stops on that peer, so one silent member keeps the gate closed.
     * Removed members do not count. Features are learned only over Wi-Fi: a member that syncs only through
     * Google Drive never announces anything, and the appearance then stays on this device. That is accepted.
     */
    fun gateOpen(localDeviceId: String, members: List<SyncMembershipEntity>, peerFeatures: Map<String, List<String>>): Boolean {
        val active = members.filter { it.status == MemberStatus.ACTIVE.name }
        if (active.none { it.deviceId == localDeviceId }) return false
        val others = active.filter { it.deviceId != localDeviceId }
        return others.isNotEmpty() && others.all { SyncFeatures.APPEARANCE in peerFeatures[it.deviceId].orEmpty() }
    }

    /**
     * How an incoming change with [relation] to the local record state meets the [stored] value.
     * A change that includes everything seen here applies, unless a local change is still pending: that
     * one was never written, so it competes by last writer wins like a concurrent change. Its missing
     * device is this device ([localDeviceId]), the ID it will carry once written.
     */
    fun decide(relation: VersionRelation, stored: StoredAppearance, localDeviceId: String?, incomingAt: String,
        incomingDevice: String): AppearanceOutcome {
        if (relation == VersionRelation.BEFORE || relation == VersionRelation.EQUAL) return AppearanceOutcome.OBSOLETE
        if (relation == VersionRelation.AFTER && !stored.pendingSync) return AppearanceOutcome.APPLY
        val device = stored.updatedByDevice ?: if (stored.pendingSync) localDeviceId else null
        return if (incomingWins(stored.updatedAtUtc, device, incomingAt, incomingDevice)) AppearanceOutcome.APPLY
            else AppearanceOutcome.KEEP_LOCAL
    }

    /** The pointwise maximum, so the next local write dominates every version seen here. */
    fun merge(local: RecordVersion, incoming: RecordVersion): RecordVersion =
        RecordVersion((local.counters.keys + incoming.counters.keys).associateWith {
            maxOf(local.counters[it] ?: 0L, incoming.counters[it] ?: 0L)
        })

    /**
     * The value a history of appearance [operations] settles on: of the changes no other change includes,
     * the last writer. Used when a device joins from a snapshot and has applied none of them.
     */
    fun current(operations: List<SyncOperationEntity>): SyncOperationEntity? {
        val candidates = operations.filter {
            it.entityType == ENTITY_TYPE && it.entityId == ENTITY_ID && it.kind == "upsert"
        }.map { it to RecordVersion.parse(it.recordVersionJson) }
        return candidates.filter { (_, version) ->
            candidates.none { (_, other) -> version.relationTo(other) == VersionRelation.BEFORE }
        }.map { it.first }.fold(null as SyncOperationEntity?) { best, next ->
            if (best == null || incomingWins(best.occurredAtUtc, best.deviceId, next.occurredAtUtc, next.deviceId)) next else best
        }
    }
}
