package com.privatevault.app.nfc

// Ephemeral form data only. Never place this object in logs or saved instance state.
class CardImport(val number: String, val expiry: String, val holder: String, val network: String) {
    override fun toString() = "CardImport(redacted)"
}

fun cardNetwork(number: String): String {
    val digits = number.filter { it in '0'..'9' }
    val two = digits.take(2).toIntOrNull() ?: 0
    val three = digits.take(3).toIntOrNull() ?: 0
    val four = digits.take(4).toIntOrNull() ?: 0
    val six = digits.take(6).toIntOrNull() ?: 0
    return when {
        digits.startsWith("4") -> "Visa"
        two in 51..55 || four in 2221..2720 -> "Mastercard"
        two == 34 || two == 37 -> "American Express"
        digits.startsWith("6011") || two == 65 || three in 644..649 || six in 622126..622925 -> "Discover"
        four in 3528..3589 -> "JCB"
        two == 62 -> "UnionPay"
        three in 300..305 || two in listOf(36, 38, 39) -> "Diners Club"
        else -> ""
    }
}

internal fun scannedCardNetwork(number: String, chipNetwork: String): String =
    if (chipNetwork.equals("rupay", ignoreCase = true)) "RuPay"
    else cardNetwork(number).ifBlank { chipNetwork.take(40) }

internal fun isCardNumber(number: String): Boolean {
    if (number.length !in 13..19 || number.any { it !in '0'..'9' } || number.all { it == '0' }) return false
    val sum = number.reversed().mapIndexed { i, c ->
        val digit = c - '0'
        if (i % 2 == 0) digit else (digit * 2).let { if (it > 9) it - 9 else it }
    }.sum()
    return sum % 10 == 0
}

internal fun allowedReadCommand(command: ByteArray): Boolean {
    if (command.size !in 4..261) return false
    val cla = command[0].toInt() and 255
    val ins = command[1].toInt() and 255
    return (cla == 0 && ins in listOf(0xA4, 0xB2, 0xC0)) || (cla == 0x80 && ins == 0xA8)
}
