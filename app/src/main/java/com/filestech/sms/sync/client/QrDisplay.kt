package com.filestech.sms.sync.client

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Client mode — renders a pairing URI as a QR code the hub phone
 * scans: ZXing core `QRCodeWriter` → `BitMatrix` → `Bitmap`, error
 * correction M, dark-on-white (the white background is part of the
 * bitmap, so it stays scannable in either theme). Returns null when
 * the content can't be encoded — the caller shows the error state.
 */
fun qrBitmap(content: String, sizePx: Int): Bitmap? {
    if (content.isBlank() || sizePx <= 0) return null
    return try {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 1,
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val pixels = IntArray(sizePx * sizePx)
        for (y in 0 until sizePx) {
            for (x in 0 until sizePx) {
                pixels[y * sizePx + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
            }
        }
        Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
    } catch (_: Exception) {
        null
    }
}

/**
 * Client mode — the pairing QR as a composable. The bitmap is
 * remembered per content + size, so recompositions don't re-encode.
 */
@Composable
fun QrDisplay(
    content: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    sizeDp: Dp = 264.dp,
) {
    val density = LocalDensity.current
    val bitmap = remember(content, sizeDp, density) {
        qrBitmap(content, with(density) { sizeDp.roundToPx() })
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = contentDescription,
            modifier = modifier.size(sizeDp),
        )
    }
}
