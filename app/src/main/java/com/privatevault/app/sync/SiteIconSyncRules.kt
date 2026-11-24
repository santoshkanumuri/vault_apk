package com.privatevault.app.sync

import com.google.gson.JsonParser
import com.privatevault.app.data.SyncOperationEntity
import com.privatevault.app.data.SyncedSiteIcon
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol `site-icons-v1` ([SyncFeatures.SITE_ICONS]), shared with Windows (`site_icon_sync.rs`):
 * a website icon that Nuvori for Windows captured through the browser extension, shared with the group.
 *
 * - Entity type [ENTITY_TYPE], always an upsert. One entity per site.
 * - Entity id: [entityId], an HMAC of the site under the group's content key. The entity id travels in
 *   plain text, so it must not show which sites the vault holds.
 * - Plaintext `{"siteIcon":{"site":"<site>","png":"<standard base64>"}}`, encrypted like every entity.
 * - Concurrent changes never become a conflict to review: the later `occurredAtUtc` wins, then the
 *   greater device ID ([AppearanceSyncRules.incomingWins]).
 *
 * Pure functions only, so the shared vector file (`sync-vectors/site-icons-v1.json`) tests them on the JVM.
 */
internal object SiteIconSyncRules {
    const val ENTITY_TYPE = "site_icon"
    const val ID_PREFIX = "nuvori-sync-site-icon-id-v1:"
    const val MAX_PNG_BYTES = 32 * 1024
    const val MIN_SIDE = 16
    const val MAX_SIDE = 64
    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val LABEL = Regex("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?")

    /** One validated icon from a decrypted payload. */
    class Icon(val site: String, val png: ByteArray)

    /** The first 32 hex characters of HMAC-SHA256(content key, [ID_PREFIX] + site). */
    fun entityId(contentKey: ByteArray, site: String): String {
        require(contentKey.size == 32)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(contentKey, "HmacSHA256")) }
        return mac.doFinal((ID_PREFIX + site).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(32)
    }

    /** A lowercase ASCII host name with at least two labels, not an IPv4 address. No scheme, port or path. */
    fun validSite(site: String): Boolean {
        if (site.isEmpty() || site.length > 253) return false
        val labels = site.split('.')
        if (labels.size < 2 || labels.any { !LABEL.matches(it) }) return false
        return !labels.all { label -> label.all { it in '0'..'9' } }
    }

    /** PNG signature, IHDR as the first chunk, each side within [MIN_SIDE]..[MAX_SIDE], at most [MAX_PNG_BYTES]. */
    fun validPng(png: ByteArray): Boolean {
        if (png.size < 24 || png.size > MAX_PNG_BYTES) return false
        if (!png.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE)) return false
        if (int(png, 8) != 13L || String(png, 12, 4, Charsets.US_ASCII) != "IHDR") return false
        val width = int(png, 16)
        val height = int(png, 20)
        return width in MIN_SIDE.toLong()..MAX_SIDE.toLong() && height in MIN_SIDE.toLong()..MAX_SIDE.toLong()
    }

    private fun int(bytes: ByteArray, at: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xFF) }

    /**
     * The icon of a decrypted payload whose entity id is [entityId]. Extra keys are ignored. Anything else
     * (another site's id, a bad site, a bad PNG) throws [IllegalArgumentException], so the change is set aside.
     */
    fun parsePayload(json: String, entityId: String, contentKey: ByteArray): Icon {
        val (site, encoded) = try {
            val body = JsonParser.parseString(json).asJsonObject.getAsJsonObject("siteIcon")
            fun text(name: String): String = requireNotNull(body.get(name)?.takeIf {
                it.isJsonPrimitive && it.asJsonPrimitive.isString
            }).asString
            text("site") to text("png")
        } catch (_: Exception) { throw IllegalArgumentException("Invalid site icon change") }
        require(validSite(site)) { "Invalid site icon site" }
        require(entityId(contentKey, site) == entityId) { "Site icon id does not match its site" }
        val png = try { java.util.Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid site icon image") }
        require(validPng(png)) { "Invalid site icon image" }
        return Icon(site, png)
    }

    /** Milliseconds of an ISO-8601 instant, or 0 when it does not parse. Only orders the 2000-icon limit. */
    fun millis(occurredAtUtc: String): Long = AppearanceSyncRules.instant(occurredAtUtc)?.toEpochMilli() ?: 0L

    /**
     * How an incoming change with [relation] to the local record state meets the [stored] icon. This phone never
     * writes icons, so nothing is ever pending here: a later change applies, an earlier one is obsolete, and a
     * concurrent one competes by last writer wins.
     */
    fun decide(relation: VersionRelation, stored: SyncedSiteIcon?, incomingAt: String, incomingDevice: String): AppearanceOutcome =
        when (relation) {
            VersionRelation.BEFORE, VersionRelation.EQUAL -> AppearanceOutcome.OBSOLETE
            VersionRelation.AFTER -> AppearanceOutcome.APPLY
            VersionRelation.CONCURRENT -> if (AppearanceSyncRules.incomingWins(stored?.updatedAtUtc, stored?.updatedByDevice,
                    incomingAt, incomingDevice)) AppearanceOutcome.APPLY else AppearanceOutcome.KEEP_LOCAL
        }

    /**
     * The change each site icon entity settles on: of its changes that no other change includes, the last writer.
     * Used when a device joins from a snapshot and has applied none of them.
     */
    fun current(operations: List<SyncOperationEntity>): List<SyncOperationEntity> =
        operations.filter { it.entityType == ENTITY_TYPE && it.kind == "upsert" }.groupBy { it.entityId }.values.mapNotNull { ops ->
            val versions = ops.map { it to RecordVersion.parse(it.recordVersionJson) }
            versions.filter { (_, version) -> versions.none { (_, other) -> version.relationTo(other) == VersionRelation.BEFORE } }
                .map { it.first }.fold(null as SyncOperationEntity?) { best, next ->
                    if (best == null || AppearanceSyncRules.incomingWins(best.occurredAtUtc, best.deviceId, next.occurredAtUtc, next.deviceId)) next else best
                }
        }
}
