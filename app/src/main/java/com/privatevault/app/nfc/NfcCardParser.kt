package com.privatevault.app.nfc

import com.github.devnied.emvnfccard.parser.EmvTemplate
import com.github.devnied.emvnfccard.parser.IProvider
import com.github.devnied.emvnfccard.parser.impl.EmvParser
import java.text.SimpleDateFormat
import java.util.Locale

internal fun readCardDetails(provider: IProvider): CardImport? {
    val readOnly = object : IProvider {
        override fun getAt() = byteArrayOf()
        override fun transceive(command: ByteArray): ByteArray =
            if (allowedReadCommand(command)) provider.transceive(command) else byteArrayOf(0x6D, 0x00)
    }
    val config = EmvTemplate.Config().setContactLess(true).setReadAllAids(false)
        .setReadTransactions(false).setReadCplc(false).setReadAt(false)
        .setReadAllRecords(false).setReadExtendedData(false).setRemoveDefaultParsers(true)
    val parser = EmvTemplate.Builder().setProvider(readOnly).setConfig(config).build()
    parser.addParsers(EmvParser(parser))
    val card = parser.readEmvCard()
    val number = card.cardNumber.orEmpty().takeIf(::isCardNumber).orEmpty()
    val network = scannedCardNetwork(number, card.type?.getName().orEmpty())
    val expiry = card.expireDate?.let { SimpleDateFormat("MM/yy", Locale.US).format(it) }.orEmpty()
    val holder = listOfNotNull(card.holderFirstname, card.holderLastname).joinToString(" ")
        .filter { !it.isISOControl() }.trim().take(80)
    return if (number.isBlank() && network.isBlank()) null else CardImport(number, expiry, holder, network)
}
