package com.privatevault.app.sync

import java.io.InputStream
import java.io.OutputStream

internal class SyncChannelOutput(private val channel: EncryptedSyncChannel) : OutputStream() {
    private val buffer = ByteArray(64 * 1024)
    private var used = 0
    private var closed = false
    private var total = 0L

    override fun write(value: Int) {
        check(!closed)
        buffer[used++] = value.toByte()
        if (used == buffer.size) flush()
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        check(!closed)
        var position = offset
        val end = offset + length
        while (position < end) {
            val count = minOf(buffer.size - used, end - position)
            bytes.copyInto(buffer, used, position, position + count)
            used += count; position += count
            if (used == buffer.size) flush()
        }
    }

    override fun flush() {
        check(!closed)
        if (used == 0) return
        total += used
        require(total <= MAX_SNAPSHOT_BYTES) { "Vault copy exceeds the transfer limit" }
        val bytes = buffer.copyOf(used)
        try { channel.send(bytes) } finally { bytes.fill(0); buffer.fill(0); used = 0 }
    }

    override fun close() {
        if (closed) return
        try { flush(); channel.send(byteArrayOf()) }
        finally { closed = true; buffer.fill(0) }
    }
}

internal class SyncChannelInput(private val channel: EncryptedSyncChannel) : InputStream() {
    private var buffer = byteArrayOf()
    private var position = 0
    private var ended = false
    private var total = 0L

    override fun read(): Int {
        if (!availableFrame()) return -1
        return buffer[position++].toInt() and 255
    }

    override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
        if (length == 0) return 0
        if (!availableFrame()) return -1
        val count = minOf(length, buffer.size - position)
        buffer.copyInto(bytes, offset, position, position + count)
        position += count
        return count
    }

    private fun availableFrame(): Boolean {
        if (ended) return false
        if (position < buffer.size) return true
        buffer.fill(0)
        buffer = channel.receive()
        position = 0
        total += buffer.size
        require(total <= MAX_SNAPSHOT_BYTES) { "Vault copy exceeds the transfer limit" }
        if (buffer.isEmpty()) ended = true
        return !ended
    }

    override fun close() { buffer.fill(0); ended = true }
}

private const val MAX_SNAPSHOT_BYTES = 512L * 1024 * 1024
