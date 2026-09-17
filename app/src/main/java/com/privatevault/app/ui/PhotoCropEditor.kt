package com.privatevault.app

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.privatevault.app.security.PhotoCrop
import kotlin.math.abs

@Composable
internal fun PhotoCropEditor(bitmap: Bitmap, crop: PhotoCrop, onCrop: (PhotoCrop) -> Unit, modifier: Modifier = Modifier) {
    val currentCrop by rememberUpdatedState(crop)
    val update by rememberUpdatedState(onCrop)
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val ratio = bitmap.width.toFloat() / bitmap.height
        val imageWidth = minOf(maxWidth, maxHeight * ratio)
        val imageHeight = imageWidth / ratio
        Box(Modifier.size(imageWidth, imageHeight)) {
            Image(bitmap.asImageBitmap(), "Photo being cropped", Modifier.fillMaxSize())
            Canvas(Modifier.fillMaxSize().semantics { contentDescription = "Drag the crop edges or corners, or drag inside to move the selection" }.pointerInput(bitmap) {
                var left = false
                var right = false
                var top = false
                var bottom = false
                var move = false
                detectDragGestures(
                    onDragStart = { point ->
                        val c = currentCrop
                        val hit = 28.dp.toPx()
                        left = abs(point.x - c.left * size.width) < hit
                        right = !left && abs(point.x - c.right * size.width) < hit
                        top = abs(point.y - c.top * size.height) < hit
                        bottom = !top && abs(point.y - c.bottom * size.height) < hit
                        move = !left && !right && !top && !bottom &&
                            point.x / size.width in c.left..c.right && point.y / size.height in c.top..c.bottom
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        val c = currentCrop
                        val dx = amount.x / size.width
                        val dy = amount.y / size.height
                        val next = if (move) {
                            val x = dx.coerceIn(-c.left, 1f - c.right)
                            val y = dy.coerceIn(-c.top, 1f - c.bottom)
                            PhotoCrop((c.left + x).coerceAtLeast(0f), (c.top + y).coerceAtLeast(0f), (c.right + x).coerceAtMost(1f), (c.bottom + y).coerceAtMost(1f))
                        } else PhotoCrop(
                            if (left) (c.left + dx).coerceIn(0f, c.right - .04f) else c.left,
                            if (top) (c.top + dy).coerceIn(0f, c.bottom - .04f) else c.top,
                            if (right) (c.right + dx).coerceIn(c.left + .04f, 1f) else c.right,
                            if (bottom) (c.bottom + dy).coerceIn(c.top + .04f, 1f) else c.bottom
                        )
                        update(next)
                    }
                )
            }) {
                val x = crop.left * size.width
                val y = crop.top * size.height
                val r = crop.right * size.width
                val b = crop.bottom * size.height
                val shade = Color.Black.copy(alpha = .65f)
                drawRect(shade, size = Size(size.width, y))
                drawRect(shade, Offset(0f, b), Size(size.width, size.height - b))
                drawRect(shade, Offset(0f, y), Size(x, b - y))
                drawRect(shade, Offset(r, y), Size(size.width - r, b - y))
                drawRect(Color.White, Offset(x, y), Size(r - x, b - y), style = Stroke(2.dp.toPx()))
                for (step in 1..2) {
                    val gx = x + (r - x) * step / 3
                    val gy = y + (b - y) * step / 3
                    drawLine(Color.White.copy(alpha = .35f), Offset(gx, y), Offset(gx, b))
                    drawLine(Color.White.copy(alpha = .35f), Offset(x, gy), Offset(r, gy))
                }
                listOf(Offset(x, y), Offset(r, y), Offset(x, b), Offset(r, b),
                    Offset((x + r) / 2, y), Offset((x + r) / 2, b), Offset(x, (y + b) / 2), Offset(r, (y + b) / 2)
                ).forEach { drawCircle(Color.White, 5.dp.toPx(), it) }
            }
        }
    }
}

@Composable
internal fun CropEdgeControls(crop: PhotoCrop, onCrop: (PhotoCrop) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        listOf("Left", "Top", "Right", "Bottom").forEachIndexed { index, label ->
            val value = listOf(crop.left, crop.top, crop.right, crop.bottom)[index]
            val range = when (index) { 0 -> 0f..(crop.right - .04f); 1 -> 0f..(crop.bottom - .04f); 2 -> (crop.left + .04f)..1f; else -> (crop.top + .04f)..1f }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.width(60.dp))
                Slider(value, { next -> onCrop(when (index) {
                    0 -> crop.copy(left = next); 1 -> crop.copy(top = next)
                    2 -> crop.copy(right = next); else -> crop.copy(bottom = next)
                }) }, valueRange = range, modifier = Modifier.weight(1f).semantics { contentDescription = "$label crop edge" })
            }
        }
    }
}
