package com.privatevault.app

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private fun decodeQr(source: LuminanceSource): String? = runCatching {
    QRCodeReader().decode(BinaryBitmap(HybridBinarizer(source)), mapOf(DecodeHintType.TRY_HARDER to true)).text
}.getOrNull()

internal fun readQr(bitmap: Bitmap): String? {
    val pixels = IntArray(bitmap.width * bitmap.height)
    return try {
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
        decodeQr(source) ?: decodeQr(source.invert())
    } finally { pixels.fill(0) }
}

@Composable
internal fun QrScanner(onResult: (String) -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val result by rememberUpdatedState(onResult)
    val previewView = remember { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE } }
    var error by remember { mutableStateOf<String?>(null) }
    DisposableEffect(owner) {
        val active = AtomicBoolean(true)
        val delivered = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(executor) { frame ->
            var luminance: ByteArray? = null
            try {
                if (active.get() && !delivered.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    val plane = frame.planes[0]
                    val buffer = plane.buffer
                    val start = buffer.position()
                    val bytes = ByteArray(frame.width * frame.height)
                    luminance = bytes
                    for (y in 0 until frame.height) for (x in 0 until frame.width) {
                        bytes[y * frame.width + x] = buffer.get(start + y * plane.rowStride + x * plane.pixelStride)
                    }
                    val source = PlanarYUVLuminanceSource(bytes, frame.width, frame.height, 0, 0, frame.width, frame.height, false)
                    val text = decodeQr(source) ?: decodeQr(source.invert())
                    if (text != null && delivered.compareAndSet(false, true)) main.execute {
                        if (active.get() && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) result(text)
                        else delivered.set(false)
                    }
                }
            } catch (_: Exception) { /* Discard unreadable frames; never log QR contents. */ }
            finally { luminance?.fill(0); frame.close() }
        }
        future.addListener({
            if (active.get()) runCatching { future.get().bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis) }
                .onFailure { error = "Camera unavailable. Close this scanner and import a QR image or enter the setup key." }
        }, main)
        onDispose {
            active.set(false)
            analysis.clearAnalyzer()
            if (future.isDone) runCatching { future.get().unbind(preview, analysis) }
            executor.shutdown()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(16.dp)) {
                Text("Scan authenticator setup QR", style = MaterialTheme.typography.titleLarge)
                Text("Only standard TOTP setup codes are supported. No camera images are saved.")
                AndroidView(factory = { previewView }, modifier = Modifier.weight(1f).fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = close, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("Cancel") }
            }
        }
    }
}
