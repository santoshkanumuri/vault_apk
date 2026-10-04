package com.privatevault.app.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream

class WindowsPortableBackupWriterTest {
    @Test
    fun writesExactPortableFraming() {
        val output = ByteArrayOutputStream()
        WindowsPortableBackupWriter.write(output, byteArrayOf(0x11, 0x22), listOf(
            WindowsPortablePhoto("photo-1") { byteArrayOf(0x33, 0x44, 0x55) },
        ))

        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val magic = ByteArray(8).also(input::readFully)
        assertEquals("NUVPORT1", magic.toString(Charsets.US_ASCII))
        assertEquals(2, input.readInt())
        assertArrayEquals(byteArrayOf(0x11, 0x22), ByteArray(2).also(input::readFully))
        assertEquals(1, input.readInt())
        assertEquals(7, input.readUnsignedShort())
        assertEquals("photo-1", ByteArray(7).also(input::readFully).toString(Charsets.US_ASCII))
        assertEquals(3L, input.readLong())
        assertArrayEquals(byteArrayOf(0x33, 0x44, 0x55), ByteArray(3).also(input::readFully))
        assertEquals(-1, input.read())
    }

    @Test
    fun writesEachPhotoLengthAndPayload() {
        val output = ByteArrayOutputStream()
        WindowsPortableBackupWriter.write(output, byteArrayOf(9), listOf(
            WindowsPortablePhoto("a") { byteArrayOf(1) },
            WindowsPortablePhoto("second") { byteArrayOf(2, 3, 4, 5) },
        ))

        val input = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        input.skipBytes(8)
        assertEquals(1, input.readInt())
        assertEquals(9, input.read())
        assertEquals(2, input.readInt())
        assertEquals("a", ByteArray(input.readUnsignedShort()).also(input::readFully).toString(Charsets.US_ASCII))
        assertEquals(1L, input.readLong())
        assertArrayEquals(byteArrayOf(1), ByteArray(1).also(input::readFully))
        assertEquals("second", ByteArray(input.readUnsignedShort()).also(input::readFully).toString(Charsets.US_ASCII))
        assertEquals(4L, input.readLong())
        assertArrayEquals(byteArrayOf(2, 3, 4, 5), ByteArray(4).also(input::readFully))
        assertEquals(-1, input.read())
    }

    @Test
    fun rejectsInvalidPhotoIdBeforeReadingPhotoBytes() {
        var read = false
        val output = ByteArrayOutputStream()
        val result = runCatching {
            WindowsPortableBackupWriter.write(output, byteArrayOf(1), listOf(
                WindowsPortablePhoto("../photo") { read = true; byteArrayOf(1) },
            ))
        }

        assertTrue(result.isFailure)
        assertFalse(read)
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsInvalidMetadataBeforeWritingHeader() {
        val output = ByteArrayOutputStream()
        val result = runCatching {
            WindowsPortableBackupWriter.write(output, byteArrayOf(), emptyList())
        }

        assertTrue(result.isFailure)
        assertEquals(0, output.size())
    }

    @Test
    fun rejectsEmptyPhotoBytes() {
        val result = runCatching {
            WindowsPortableBackupWriter.write(ByteArrayOutputStream(), byteArrayOf(1), listOf(
                WindowsPortablePhoto("photo") { byteArrayOf() },
            ))
        }

        assertTrue(result.isFailure)
    }
}
