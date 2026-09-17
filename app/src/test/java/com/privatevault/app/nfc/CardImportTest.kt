package com.privatevault.app.nfc

import org.junit.Assert.*
import org.junit.Test

class CardImportTest {
    @Test fun recognizesNetworksAndMastercardRangeBoundaries() {
        assertEquals("Visa", cardNetwork("4111111111111111"))
        assertEquals("Mastercard", cardNetwork("5555555555554444"))
        assertEquals("Mastercard", cardNetwork("2221000000000000"))
        assertEquals("Mastercard", cardNetwork("2720000000000000"))
        assertEquals("", cardNetwork("2721000000000000"))
        assertEquals("Discover", cardNetwork("6011111111111117"))
        assertEquals("American Express", cardNetwork("378282246310005"))
        assertEquals("UnionPay", cardNetwork("6210000000000000"))
        assertEquals("", cardNetwork(""))
    }

    @Test fun respectsRupayChipIdentityInsteadOfNumberGuess() {
        assertEquals("RuPay", scannedCardNetwork("6500000000000000", "Rupay"))
        assertEquals("RuPay", scannedCardNetwork("", "RUPAY"))
        assertEquals("Discover", scannedCardNetwork("6500000000000000", "Discover"))
        assertEquals("Visa", scannedCardNetwork("4111111111111111", ""))
    }

    @Test fun rejectsIncompleteOrCorruptCardNumbers() {
        assertTrue(isCardNumber("4111111111111111"))
        assertFalse(isCardNumber("4111111111111112"))
        assertFalse(isCardNumber("411111"))
        assertFalse(isCardNumber("0000000000000000"))
        assertFalse(isCardNumber("411111111111111F"))
    }

    @Test fun permitsOnlyReadingCommands() {
        for (ins in listOf(0xA4, 0xB2, 0xC0)) assertTrue(allowedReadCommand(byteArrayOf(0, ins.toByte(), 0, 0, 0)))
        assertTrue(allowedReadCommand(byteArrayOf(0x80.toByte(), 0xA8.toByte(), 0, 0, 0)))
        // VERIFY, GENERATE AC, UPDATE RECORD, PUT DATA, GET DATA/log counters.
        for (ins in listOf(0x20, 0xAE, 0xDC, 0xDA, 0xCA)) {
            assertFalse(allowedReadCommand(byteArrayOf(0, ins.toByte(), 0, 0, 0)))
            assertFalse(allowedReadCommand(byteArrayOf(0x80.toByte(), ins.toByte(), 0, 0, 0)))
        }
        assertFalse(allowedReadCommand(byteArrayOf()))
        assertFalse(allowedReadCommand(ByteArray(262)))
    }

    @Test fun resultDoesNotPrintSecrets() {
        assertEquals("CardImport(redacted)", CardImport("4111111111111111", "12/30", "Sample", "Visa").toString())
    }
}
