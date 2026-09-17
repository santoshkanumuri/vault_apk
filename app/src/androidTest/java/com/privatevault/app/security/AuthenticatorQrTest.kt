package com.privatevault.app.security

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.privatevault.app.readQr
import org.junit.Assert.*
import org.junit.Test

class AuthenticatorQrTest {
    @Test fun decodesSetupImageLocally() {
        val text = "otpauth://totp/Example:account?secret=JBSWY3DPEHPK3PXP&issuer=Example&digits=8&period=60"
        val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until 400) for (x in 0 until 400) bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            val scanned = requireNotNull(readQr(bitmap))
            assertEquals(text, scanned)
            assertEquals(60, Totp.parse(scanned).period)
        } finally { bitmap.recycle() }
    }
}
