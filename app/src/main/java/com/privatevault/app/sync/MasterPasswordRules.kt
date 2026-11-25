package com.privatevault.app.sync

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.privatevault.app.security.KdfParams
import com.privatevault.app.security.PasswordCrypto
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Protocol `master-password-v1` ([SyncFeatures.MASTER_PASSWORD]), shared with Windows (`master_password.rs`):
 * every device in a sync group keeps the managing device's master password. Shared vectors:
 * `sync-vectors/master-password-v1.json` (scripts/sync-vectors/master_password.py, which states the rules in full).
 *
 * When the managing device changes its master password while it shares the vault, it writes a signed
 * [ENTITY_TYPE] change under the vault ID: a password [Check]. The check proves that a typed password is
 * the new one without holding it: an Argon2id key of the password (64 MiB, the vault's own settings) and an
 * HMAC of the vault ID and the check's epoch under that key. Guessing the password from it costs one
 * Argon2id run per guess, as guessing it from an encrypted backup does, and it travels only inside the
 * group's end-to-end encrypted sync changes. The password itself never leaves the device.
 *
 * Every other device that holds a newer check than it switched to asks for the new password after unlock,
 * tests it against the check, wraps its vault key under it and writes an [ENTITY_TYPE] change under its own
 * device ID, `{"switchedEpoch": E}`. Until then it still opens with its old password and its sync waits.
 * A check from any device that was not the managing device at the membership event it names is never
 * used ([current]). Pure functions only, so the shared vector file tests them on the JVM.
 */
internal object MasterPasswordRules {
    const val FEATURE = "master-password-v1"
    const val ENTITY_TYPE = "master_password"
    const val LABEL = "nuvori-master-password-check-v1"
    const val MEMORY_KIB = 65_536
    const val PARALLELISM = 1
    const val MIN_ITERATIONS = 3
    const val MAX_ITERATIONS = 64
    /** Epochs and sequences stay below 2^53, so that every platform reads them exactly. */
    const val MAX_NUMBER = (1L shl 53) - 1

    /** A password check from the managing device. [toString] shows the epoch and the membership sequence only. */
    class Check(val epoch: Long, val membershipSequence: Long, salt: ByteArray, val iterations: Int, check: ByteArray) {
        private val salt = salt.copyOf()
        private val check = check.copyOf()

        init {
            require(epoch in 1..MAX_NUMBER && membershipSequence in 1..MAX_NUMBER && this.salt.size == 16 &&
                iterations in MIN_ITERATIONS..MAX_ITERATIONS && this.check.size == 32) { "Invalid password check" }
        }

        /**
         * True when [password] is the master password this check was made from, for this vault. Takes one
         * Argon2id run (64 MiB, up to about a second); the tags are compared in constant time.
         */
        fun matches(vaultId: String, password: CharArray): Boolean {
            val tag = tag(vaultId, password, salt, iterations, epoch)
            return try { java.security.MessageDigest.isEqual(tag, check) } finally { tag.fill(0) }
        }

        /** The same check for another epoch, for tests of the epoch binding. */
        internal fun withEpoch(other: Long) = Check(other, membershipSequence, salt, iterations, check)

        fun toPayload(): JsonObject = JsonObject().apply {
            addProperty("epoch", epoch)
            addProperty("membershipSequence", membershipSequence)
            addProperty("salt", java.util.Base64.getEncoder().encodeToString(salt))
            addProperty("memoryKib", MEMORY_KIB)
            addProperty("iterations", iterations)
            addProperty("parallelism", PARALLELISM)
            addProperty("check", java.util.Base64.getEncoder().encodeToString(check))
        }

        internal fun checkHex(): String = check.joinToString("") { "%02x".format(it) }

        override fun equals(other: Any?) = other is Check && other.epoch == epoch &&
            other.membershipSequence == membershipSequence && other.iterations == iterations &&
            other.salt.contentEquals(salt) && other.check.contentEquals(check)

        override fun hashCode() = (epoch * 31 + membershipSequence).hashCode()
        override fun toString() = "Check(epoch=$epoch, membershipSequence=$membershipSequence)"
    }

    /**
     * The managing device's check for [password]: a fresh random [salt] of 16 bytes and the Argon2id passes
     * of its own vault key ([iterations]), so a check costs a guesser as much as the vault key file does.
     */
    fun create(vaultId: String, password: CharArray, epoch: Long, membershipSequence: Long, salt: ByteArray,
        iterations: Int): Check {
        val tag = tag(vaultId, password, salt, iterations, epoch)
        return try { Check(epoch, membershipSequence, salt, iterations, tag) } finally { tag.fill(0) }
    }

    /** HMAC-SHA256 under Argon2id(password), fed "nuvori-master-password-check-v1\n<vaultId>\n<epoch>". */
    private fun tag(vaultId: String, password: CharArray, salt: ByteArray, iterations: Int, epoch: Long): ByteArray {
        val key = PasswordCrypto.derive(password, salt, KdfParams(MEMORY_KIB, iterations, PARALLELISM))
        return try {
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal("$LABEL\n$vaultId\n$epoch".toByteArray(Charsets.UTF_8))
            }
        } finally { key.fill(0) }
    }

    /**
     * The check in a payload, or null when the payload breaks the protocol: exactly the seven keys, whole
     * numbers in range, the fixed Argon2id memory and lanes, canonical padded base64.
     */
    fun parseCheck(json: String): Check? = runCatching {
        val body = strictObject(json)
        if (body.size() != 7 || number(body, "memoryKib") != MEMORY_KIB.toLong() ||
            number(body, "parallelism") != PARALLELISM.toLong()) return null
        val iterations = number(body, "iterations") ?: return null
        if (iterations !in MIN_ITERATIONS.toLong()..MAX_ITERATIONS.toLong()) return null
        Check(number(body, "epoch") ?: return null, number(body, "membershipSequence") ?: return null,
            bytes(body, "salt", 16) ?: return null, iterations.toInt(), bytes(body, "check", 32) ?: return null)
    }.getOrNull()

    fun switchedPayload(epoch: Long): JsonObject = JsonObject().apply {
        require(epoch in 1..MAX_NUMBER)
        addProperty("switchedEpoch", epoch)
    }

    fun parseSwitched(json: String): Long? = runCatching {
        val body = strictObject(json)
        if (body.size() != 1) null else number(body, "switchedEpoch")?.takeIf { it in 1..MAX_NUMBER }
    }.getOrNull()

    /** One JSON object and nothing after it, read strictly (no comments, single quotes or bare names), as Windows reads it. */
    private fun strictObject(json: String): JsonObject {
        val reader = JsonReader(java.io.StringReader(json))
        reader.setStrictness(Strictness.STRICT)
        val body = JsonParser.parseReader(reader).asJsonObject
        require(reader.peek() == JsonToken.END_DOCUMENT)
        return body
    }

    /** A JSON integer written plainly (no sign, fraction or exponent), in 0..[MAX_NUMBER]. */
    private fun number(body: JsonObject, key: String): Long? {
        val element: JsonElement = body.get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
        val text = element.asJsonPrimitive.asNumber.toString()
        if (!text.matches(Regex("0|[1-9][0-9]{0,15}"))) return null
        return text.toLong().takeIf { it <= MAX_NUMBER }
    }

    private fun bytes(body: JsonObject, key: String, size: Int): ByteArray? {
        val element = body.get(key) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        val text = element.asString
        val decoded = runCatching { java.util.Base64.getDecoder().decode(text) }.getOrNull() ?: return null
        // Java accepts missing padding and set unused bits; the round trip refuses both, as Windows does.
        if (decoded.size != size || java.util.Base64.getEncoder().encodeToString(decoded) != text) return null
        return decoded
    }

    /** What one [ENTITY_TYPE] change says. */
    sealed interface Record {
        /** Under the vault ID: a password check. Whether its author may write one is decided by [current]. */
        data class CheckRecord(val check: Check) : Record
        /** Under the author's own device ID: the author switched to this check epoch. */
        data class Switched(val epoch: Long) : Record
    }

    /**
     * The record a change holds, or null when the change is invalid: a delete, an entity ID that is neither
     * the vault ID nor the author's own device ID, or a payload of the wrong shape for its ID.
     */
    fun read(vaultId: String, author: String, entityId: String, upsert: Boolean, json: String): Record? = when {
        !upsert -> null
        entityId == vaultId -> parseCheck(json)?.let(Record::CheckRecord)
        entityId == author -> parseSwitched(json)?.let(Record::Switched)
        else -> null
    }

    /** A password check held here, with its author and change hash. */
    data class Candidate(val author: String, val hash: String, val check: Check)

    /**
     * The check every device must hold: the highest epoch, then the highest membership sequence, then the
     * highest change hash (UTF-8 order), among the checks whose membership sequence is within [head] and whose
     * author was the managing device once that membership event applied ([managerAfter]). Checks of any other
     * device are ignored, so a former manager's check from before a transfer still counts and one it wrote
     * afterwards does not.
     */
    fun current(candidates: List<Candidate>, head: Long, managerAfter: (Long) -> String?): Candidate? =
        candidates.filter { it.check.membershipSequence in 1..head && managerAfter(it.check.membershipSequence) == it.author }
            .maxWithOrNull(compareBy<Candidate>({ it.check.epoch }, { it.check.membershipSequence })
                .then(SyncCheckpoints.utf8Order { it.hash }))

    /**
     * True when [deviceId] holds the password of [current]: it wrote the check, it switched to that epoch or a
     * later one, or its newest ADD (or GENESIS) membership event came after the check, so its pairing already
     * proved the password.
     */
    fun satisfied(deviceId: String, current: Candidate, switchedEpoch: Long?, addedAt: Long?): Boolean =
        deviceId == current.author || (switchedEpoch != null && switchedEpoch >= current.check.epoch) ||
            (addedAt != null && addedAt > current.check.membershipSequence)

    /**
     * The managing device once each membership event applied: [events] holds each event's action name and
     * subject device ID, in sequence order. GENESIS and TRANSFER name a new managing device; no other event does.
     */
    fun managersAfter(events: List<Pair<String, String>>): List<String> {
        var manager = ""
        return events.map { (action, subject) ->
            if (action == "GENESIS" || action == "TRANSFER") manager = subject
            manager
        }
    }

    /** The sequence of [deviceId]'s newest ADD or GENESIS event in [events] (as in [managersAfter]), or null. */
    fun addedAt(events: List<Pair<String, String>>, deviceId: String): Long? =
        events.withIndex().lastOrNull { (_, event) -> (event.first == "ADD" || event.first == "GENESIS") && event.second == deviceId }
            ?.let { it.index + 1L }

    /**
     * The managing device may change the master password while it shares the vault only when every other
     * active member announced [FEATURE] (over Wi-Fi or in its Drive progress file): an older build would
     * stop at the check and never learn the new password. The devices that did not, by device ID.
     */
    fun devicesWithoutFeature(otherActive: List<String>, peerFeatures: Map<String, List<String>>): List<String> =
        otherActive.filter { FEATURE !in peerFeatures[it].orEmpty() }
}
