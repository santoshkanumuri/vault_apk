package com.privatevault.app.sync

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal

/**
 * M2: two devices that edit different fields of the same login (or folder) while apart get both
 * edits, instead of a conflict to review. Shared with Windows (`entry_merge.rs`); the rule and its
 * vectors are in `scripts/sync-vectors/entry_merge.py` (`entry-merge-v1.json`).
 *
 * Nothing on the wire changes. The merged record goes out as an ordinary signed change whose
 * version is the pointwise maximum of the two versions ([ConflictVersions.join]), so older versions
 * of both apps apply it and close their copy of the conflict. Two devices that merge the same pair
 * write the same version and content: each sees the other's change as equal, and no new conflict
 * starts. A conflict stays for the user when the same field changed to different values, the item
 * type changed, or an edit met a delete.
 */
internal object EntryFieldMerge {
    /** Entity types whose concurrent upserts merge field by field. */
    val ENTITY_TYPES = setOf("entry", "group")

    /** Fields of the record that take the later value instead of making a conflict. */
    private val LATEST = setOf("updatedAt", "lastOpenedAt")

    private val OBJECT_KEY = mapOf("entry" to "entry", "group" to "group")

    /**
     * The merged payload, or null when the user has to decide. [base] is the payload of the change
     * both sides started from ([ConflictVersions.meet]), or null when this device doesn't hold it.
     * A delete's payload is `{"deleted": true}`. Symmetric: swapping [local] and [remote] gives the
     * same result, so every device computes the same merge.
     */
    fun merge(entityType: String, base: JSONObject?, local: JSONObject, remote: JSONObject): JSONObject? {
        val key = OBJECT_KEY[entityType] ?: return null
        if (deleted(local) || deleted(remote))
            return if (deleted(local) && deleted(remote)) JSONObject().put("deleted", true) else null
        val localObject = local.optJSONObject(key) ?: return null
        val remoteObject = remote.optJSONObject(key) ?: return null
        if (!same(localObject.opt("id"), remoteObject.opt("id"))) return null
        val start = base?.takeIf { !deleted(it) && it.optJSONObject(key) != null }
            ?: if (equivalent(entityType, local, remote)) local else return null
        val baseObject = start.getJSONObject(key)
        if (entityType == "entry" && !(same(baseObject.opt("type"), localObject.opt("type")) &&
                same(localObject.opt("type"), remoteObject.opt("type")))) return null
        return try {
            val result = JSONObject().put(key, mergeObject(baseObject, localObject, remoteObject, latest = true))
            if (entityType == "entry") {
                val b = groupIds(start); val l = groupIds(local); val r = groupIds(remote)
                result.put("groupIds", JSONArray(((l intersect r) + (l - b) + (r - b)).sorted()))
            }
            (start.keySet + local.keySet + remote.keySet - setOf(key, "groupIds")).sorted().forEach { other ->
                threeWay(other, start, local, remote, latest = false)?.let { result.put(other, it) }
            }
            result
        } catch (_: FieldConflict) { null }
    }

    private class FieldConflict : Exception()

    private fun mergeObject(base: JSONObject, local: JSONObject, remote: JSONObject, latest: Boolean): JSONObject {
        val out = JSONObject()
        (base.keySet + local.keySet + remote.keySet).sorted().forEach { key ->
            threeWay(key, base, local, remote, latest)?.let { out.put(key, it) }
        }
        return out
    }

    private fun threeWay(key: String, base: JSONObject, local: JSONObject, remote: JSONObject, latest: Boolean): Any? {
        val b = value(base, key); val l = value(local, key); val r = value(remote, key)
        if (latest && key in LATEST && l is Number && r is Number) return if (decimal(l) >= decimal(r)) l else r
        return when {
            same(l, b) -> r
            same(r, b) -> l
            same(l, r) -> l
            else -> throw FieldConflict()
        }
    }

    /** Equal apart from the latest-wins fields: two devices chose the same side, no base needed. */
    private fun equivalent(entityType: String, local: JSONObject, remote: JSONObject): Boolean {
        val key = OBJECT_KEY.getValue(entityType)
        val l = local.getJSONObject(key); val r = remote.getJSONObject(key)
        if (!((l.keySet + r.keySet) - LATEST).all { same(value(l, it), value(r, it)) }) return false
        if (!((local.keySet + remote.keySet) - setOf(key, "groupIds")).all { same(value(local, it), value(remote, it)) })
            return false
        return entityType != "entry" || groupIds(local) == groupIds(remote)
    }

    private fun deleted(payload: JSONObject): Boolean =
        payload.opt("deleted") == true && OBJECT_KEY.values.none(payload::has)

    /** Android's org.json has no `keySet()`. */
    private val JSONObject.keySet: Set<String> get() = keys().asSequence().toSet()

    private fun groupIds(payload: JSONObject): Set<String> {
        val ids = payload.optJSONArray("groupIds") ?: return emptySet()
        return (0 until ids.length()).map(ids::getString).toSet()
    }

    private fun value(json: JSONObject, key: String): Any? = json.opt(key)?.takeUnless { it == JSONObject.NULL }

    /** The same JSON value: numbers by value, a missing key and null alike. */
    internal fun same(a: Any?, b: Any?): Boolean {
        val x = a?.takeUnless { it == JSONObject.NULL }
        val y = b?.takeUnless { it == JSONObject.NULL }
        if (x == null || y == null) return x == null && y == null
        if (x is Number || y is Number) return x is Number && y is Number && decimal(x).compareTo(decimal(y)) == 0
        if (x is JSONObject && y is JSONObject)
            return (x.keySet + y.keySet).all { same(value(x, it), value(y, it)) }
        if (x is JSONArray && y is JSONArray)
            return x.length() == y.length() && (0 until x.length()).all { same(x.opt(it), y.opt(it)) }
        return x::class == y::class && x == y
    }

    private fun decimal(number: Number): BigDecimal = when (number) {
        is BigDecimal -> number
        is Double, is Float -> BigDecimal(number.toDouble())
        else -> BigDecimal(number.toString())
    }
}
