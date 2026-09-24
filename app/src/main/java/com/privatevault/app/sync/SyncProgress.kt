package com.privatevault.app.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class SyncChainHead(val sequence: Long, val hash: String)

/** Peer progress is advisory until its source hashes are checked against local history. */
internal data class SyncProgress(val received: Map<String, SyncChainHead>,
    val applied: Map<String, SyncChainHead>) {
    fun encode(): ByteArray = JsonObject().apply {
        add("received", headsJson(received))
        add("applied", headsJson(applied))
    }.toString().toByteArray(Charsets.UTF_8)

    fun validate() {
        require(received.size <= 256 && applied.size <= 256)
        (received.entries + applied.entries).forEach { (deviceId, head) ->
            require(deviceId.isNotBlank() && deviceId.length <= 128 && head.sequence >= 0 &&
                head.hash.matches(Regex("[a-f0-9]{64}")))
            if (head.sequence == 0L) require(head.hash == GENESIS_HASH)
        }
        applied.forEach { (deviceId, head) ->
            val durable = requireNotNull(received[deviceId]) { "Applied progress exceeds received progress" }
            require(head.sequence <= durable.sequence &&
                (head.sequence != durable.sequence || head.hash == durable.hash)) {
                "Applied progress exceeds received progress"
            }
        }
    }

    companion object {
        fun parse(bytes: ByteArray): SyncProgress {
            require(bytes.size in 1..100_000) { "Invalid sync progress size" }
            val root = JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
            require(root.entrySet().map { it.key }.toSet() == setOf("received", "applied"))
            return SyncProgress(parseHeads(root.getAsJsonObject("received")),
                parseHeads(root.getAsJsonObject("applied"))).also { it.validate() }
        }

        private fun parseHeads(json: JsonObject): Map<String, SyncChainHead> {
            require(json.entrySet().size <= 256)
            return json.entrySet().associate { (deviceId, value) ->
                val pair = value.asJsonArray
                require(pair.size() == 2 && pair[0].asJsonPrimitive.isNumber &&
                    pair[1].asJsonPrimitive.isString)
                deviceId to SyncChainHead(pair[0].asBigDecimal.longValueExact(), pair[1].asString)
            }
        }

        private fun headsJson(heads: Map<String, SyncChainHead>) = JsonObject().apply {
            heads.toSortedMap().forEach { (deviceId, head) ->
                add(deviceId, JsonArray().apply { add(head.sequence); add(head.hash) })
            }
        }
    }
}
