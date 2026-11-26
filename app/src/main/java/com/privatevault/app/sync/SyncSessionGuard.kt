package com.privatevault.app.sync

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * M11: notices a Wi-Fi session that stopped moving. A read already times out ([java.net.Socket.setSoTimeout]),
 * but a write to a device that left the network can block until TCP gives up, minutes later, while
 * the exchange lock turns every other device away. The session's streams go through [input] and
 * [output]; a watchdog closes the socket when [stalled] or past its deadline. Writes go out in
 * chunks so a large photo frame on a slow but working link keeps counting as progress.
 */
internal class SessionWatch(private val now: () -> Long, private val startedAt: Long = now()) {
    @Volatile var lastProgressAt: Long = startedAt
        private set

    fun input(stream: InputStream): InputStream = object : FilterInputStream(stream) {
        override fun read(): Int = super.read().also { lastProgressAt = now() }
        override fun read(b: ByteArray, off: Int, len: Int): Int = `in`.read(b, off, len).also { lastProgressAt = now() }
    }

    fun output(stream: OutputStream): OutputStream = object : FilterOutputStream(stream) {
        override fun write(b: Int) { out.write(b); lastProgressAt = now() }
        override fun write(b: ByteArray, off: Int, len: Int) {
            var at = off
            val end = off + len
            while (at < end) {
                val size = minOf(CHUNK, end - at)
                out.write(b, at, size)
                at += size
                lastProgressAt = now()
            }
        }
        override fun flush() { out.flush(); lastProgressAt = now() }
    }

    /** True when nothing moved for [quietLimit], or the session ran past [deadline] in all. */
    fun expired(at: Long, quietLimit: Long, deadline: Long): Boolean =
        at - lastProgressAt > quietLimit || at - startedAt > deadline

    companion object { const val CHUNK = 64 * 1024 }
}

/** M12: how a failed Wi-Fi session is shown and retried. */
internal enum class SessionFailure {
    /** The connection dropped or timed out: retried quietly, shown as a Wi-Fi problem. */
    TEMPORARY,
    /**
     * The other device sent something this one refuses (a signature, a forked history, an unknown
     * protocol): retrying changes nothing until someone updates or re-pairs, so it is shown per device.
     */
    REFUSED;

    companion object {
        fun of(failure: Throwable): SessionFailure = when (failure) {
            is PeerRefused -> REFUSED
            is IOException -> TEMPORARY
            is IllegalArgumentException, is IllegalStateException, is SecurityException,
            is java.security.GeneralSecurityException, is com.google.gson.JsonParseException -> REFUSED
            else -> TEMPORARY
        }
    }
}

/**
 * M10: a one-time pass (Sync now, sync on opening) ends once every active paired device was reached
 * in this attempt, not after the first one.
 */
internal fun oneTimePassDone(activePeers: Collection<String>, reached: Collection<String>): Boolean =
    activePeers.isNotEmpty() && reached.containsAll(activePeers)
