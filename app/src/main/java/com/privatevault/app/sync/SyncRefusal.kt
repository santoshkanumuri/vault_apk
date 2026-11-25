package com.privatevault.app.sync

/**
 * M12, shared with Windows (`refusal.rs`): after both devices proved who they are, the one that
 * refuses what the other sent says so in one encrypted frame before the session ends. The sender
 * then shows it on that device and stops retrying at once, instead of reading it as a dropped
 * connection and retrying it forever.
 *
 * The frame is [MAGIC] and a code from [CODES], nothing else: no error text and no data from the
 * session. It goes only to a peer that announced [FEATURE] (an older build would read it as a bad
 * frame), only inside the authenticated channel after both identity proofs, and never in answer to
 * a refusal. The receiver shows its own words for every code, never words from the peer.
 * Vectors: `scripts/sync-vectors/sync_refusal.py`.
 */
internal object SyncRefusal {
    const val FEATURE = "sync-refusal-v1"

    /** A change failed verification: its signature, its chain, its sender or the reported history. */
    const val CHANGE = "change"

    /** A frame this build can't read or that breaks the exchange's rules. */
    const val PROTOCOL = "protocol"

    private val CODES = setOf(CHANGE, PROTOCOL)

    /** Starts with a NUL byte and is longer than any code, so no JSON, number or hash frame matches. */
    private val MAGIC = "\u0000nuvori-sync-refused-v1:".toByteArray(Charsets.US_ASCII)
    private const val MAX_CODE = 16

    fun encode(code: String): ByteArray {
        require(code in CODES)
        return MAGIC + code.toByteArray(Charsets.US_ASCII)
    }

    /** The code of a refusal frame (an unknown code reads as [PROTOCOL]), or null for any other frame. */
    fun decode(frame: ByteArray): String? {
        if (frame.size <= MAGIC.size || frame.size > MAGIC.size + MAX_CODE) return null
        if (MAGIC.indices.any { frame[it] != MAGIC[it] }) return null
        val code = String(frame, MAGIC.size, frame.size - MAGIC.size, Charsets.US_ASCII)
        return code.takeIf { it in CODES } ?: PROTOCOL
    }
}

/** This device refused what the peer sent ([SessionFailure.REFUSED]); [code] went to the peer if it understands it. */
internal class LocalRefusal(val code: String, cause: Throwable) :
    IllegalStateException("Refused what a paired device sent ($code)", cause)

/** The peer refused what this device sent, and said so ([SyncRefusal]). */
internal class PeerRefused(val code: String) : Exception("A paired device refused what this device sent ($code)")
