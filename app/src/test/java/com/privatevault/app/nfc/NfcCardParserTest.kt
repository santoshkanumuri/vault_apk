package com.privatevault.app.nfc

import com.github.devnied.emvnfccard.parser.IProvider
import org.junit.Assert.*
import org.junit.Test
import org.slf4j.LoggerFactory

class NfcCardParserTest {
    private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun readsSyntheticContactlessVisaWithoutPaymentCommands() {
        val sent = mutableListOf<ByteArray>()
        val result = readCardDetails(object : IProvider {
            override fun getAt() = byteArrayOf()
            override fun transceive(command: ByteArray): ByteArray {
                sent.add(command)
                assertTrue(allowedReadCommand(command))
                return when (command[1].toInt() and 255) {
                    0xA4 -> if (command[4].toInt() == 14)
                        hex("6F23840E325041592E5359532E4444463031A511BF0C0E610C4F07A00000000310108701019000")
                    else hex("6F098407A00000000310109000")
                    0xA8 -> hex("77168202000057104111111111111111D3012201123456789F9000")
                    else -> hex("6A82")
                }
            }
        })
        assertNotNull(result)
        assertEquals("4111111111111111", result!!.number)
        assertEquals("12/30", result.expiry)
        assertEquals("Visa", result.network)
        assertTrue(sent.size <= 6)
    }

    @Test fun parserLoggingIsDisabled() {
        val logger = LoggerFactory.getLogger("com.github.devnied.emvnfccard.parser.EmvTemplate")
        assertFalse(logger.isTraceEnabled)
        assertFalse(logger.isDebugEnabled)
        assertFalse(logger.isInfoEnabled)
        assertFalse(logger.isWarnEnabled)
        assertFalse(logger.isErrorEnabled)
    }
}
