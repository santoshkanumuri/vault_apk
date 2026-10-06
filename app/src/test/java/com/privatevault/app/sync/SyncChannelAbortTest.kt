package com.privatevault.app.sync

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class SyncChannelAbortTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    private fun send(block: (SyncChannelOutput) -> Unit): ByteArray {
        val wire = ByteArrayOutputStream()
        val channel = EncryptedSyncChannel(SyncFrames(ByteArrayInputStream(byteArrayOf()), wire), key, true)
        block(SyncChannelOutput(channel))
        return wire.toByteArray()
    }

    private fun receiver(wire: ByteArray) = SyncChannelInput(
        EncryptedSyncChannel(SyncFrames(ByteArrayInputStream(wire), ByteArrayOutputStream()), key, false))

    @Test
    fun abortBeforeDataReportsReasonInsteadOfEmptyCopy() {
        val wire = send { it.abort("Only the managing phone can enroll a device") }
        val input = DataInputStream(receiver(wire))
        val magic = ByteArray(SNAPSHOT_FAILURE.size).also(input::readFully)
        assertArrayEquals(SNAPSHOT_FAILURE, magic)
        assertEquals("Only the managing phone can enroll a device", readSnapshotFailure(input).message)
    }

    @Test
    fun abortAfterDataNeverSendsTheEndFrame() {
        val wire = send { output ->
            output.write(ByteArray(70 * 1024) { 1 })
            output.abort("ignored")
        }
        val input = receiver(wire)
        val received = ByteArrayOutputStream()
        val failure = runCatching { input.copyTo(received) }.exceptionOrNull()
        // A clean -1 here would let the receiver treat a partial copy as complete.
        assertTrue(failure != null)
        assertEquals(64 * 1024, received.size())
    }

    @Test
    fun closeStillEndsANormalCopy() {
        val wire = send { output -> output.write(byteArrayOf(1, 2, 3)); output.close() }
        assertArrayEquals(byteArrayOf(1, 2, 3), receiver(wire).readBytes())
    }

    @Test
    fun failureReasonShowsOwnMessagesAndHidesOthers() {
        assertEquals("Enrolled device identity changed or was removed",
            exportFailureReason(IllegalArgumentException("Enrolled device identity changed or was removed")))
        assertEquals("The phone could not prepare the vault copy (FileNotFoundException)",
            exportFailureReason(java.io.FileNotFoundException("/data/user/0/secret/path")))
        assertEquals("The phone could not prepare the vault copy (IllegalArgumentException)",
            exportFailureReason(IllegalArgumentException("Failed requirement.")))
    }
}
