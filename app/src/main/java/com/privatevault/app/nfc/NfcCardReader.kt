package com.privatevault.app.nfc

import android.app.Activity
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.github.devnied.emvnfccard.exception.CommunicationException
import com.github.devnied.emvnfccard.parser.IProvider
import java.util.concurrent.atomic.AtomicBoolean

/** Reader mode only: no tag intents, background service, or card-emulation service. */
class NfcCardReader(private val activity: Activity) {
    private val main = Handler(Looper.getMainLooper())
    private var adapter: NfcAdapter? = null
    @Volatile private var generation = 0
    @Volatile private var connection: IsoDep? = null
    private var timeout: Runnable? = null

    fun start(permitted: () -> Boolean, success: (CardImport) -> Unit, failure: (String) -> Unit) {
        stop()
        if (!permitted()) return
        val nfc = NfcAdapter.getDefaultAdapter(activity)
        if (nfc == null || !nfc.isEnabled) {
            failure(if (nfc == null) "This phone has no NFC reader. Enter the card manually." else "Turn on NFC in your phone settings, then try again.")
            return
        }
        adapter = nfc
        val token = generation
        val reading = AtomicBoolean(false)
        fun finish(result: CardImport?, error: String) {
            main.post {
                if (generation != token || !permitted()) return@post
                stop()
                if (result != null) success(result) else failure(error)
            }
        }
        timeout = Runnable { if (generation == token) { stop(); failure("No card read. Tap Scan card to try again.") } }.also { main.postDelayed(it, 30_000) }
        try {
            nfc.enableReaderMode(activity, { tag ->
                if (generation == token && permitted() && reading.compareAndSet(false, true)) {
                    try {
                        val result = read(tag) { generation == token && permitted() }
                        finish(result, "This card did not expose readable details. Enter it manually.")
                    } catch (_: Exception) {
                        finish(null, "Could not read the card. Hold it still and try again, or enter it manually.")
                    }
                }
            }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK, null)
        } catch (_: Exception) {
            stop()
            failure("NFC scanning is unavailable. Enter the card manually.")
        }
    }

    fun stop() {
        generation++
        timeout?.let(main::removeCallbacks)
        timeout = null
        runCatching { connection?.close() }
        connection = null
        adapter?.let { runCatching { it.disableReaderMode(activity) } }
        adapter = null
    }

    private fun read(tag: Tag, permitted: () -> Boolean): CardImport? {
        val iso = IsoDep.get(tag) ?: return null
        val responses = mutableListOf<ByteArray>()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var exchanges = 0
        try {
            connection = iso
            if (!permitted()) return null
            iso.connect()
            iso.timeout = 2_000
            val provider = object : IProvider {
                override fun getAt() = byteArrayOf()
                override fun transceive(command: ByteArray): ByteArray {
                    if (!permitted() || SystemClock.elapsedRealtime() >= deadline || ++exchanges > 80) throw CommunicationException("Read ended")
                    // Never send VERIFY PIN, GENERATE AC, writes, or transaction-log requests.
                    if (!allowedReadCommand(command)) return byteArrayOf(0x6D, 0x00)
                    val response = iso.transceive(command)
                    if (response.size !in 2..16384) { response.fill(0); throw CommunicationException("Invalid response") }
                    responses.add(response)
                    return response
                }
            }
            val result = readCardDetails(provider)
            return result.takeIf { permitted() }
        } finally {
            runCatching { iso.close() }
            if (connection === iso) connection = null
            responses.forEach { it.fill(0) }
        }
    }
}
