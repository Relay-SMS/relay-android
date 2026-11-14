package com.filestech.sms.ui.screens.linkeddevices

import com.google.common.truth.Truth.assertThat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Regression test for the 2026-11-13 crash: tapping Link device
 * killed the app on the first camera frame with
 * `UnsupportedOperationException: This luminance source does not
 * support rotation by 90 degrees`. ZXing 3.5.4's
 * `PlanarYUVLuminanceSource` does not override
 * `rotateCounterClockwise()` — the base method unconditionally throws
 * — and the analyzer called it on every portrait frame, outside the
 * `runCatching` that only wrapped `reader.decode`, on a background
 * executor thread where an uncaught exception kills the process.
 *
 * The fix rotates the Y-plane bytes manually before building the
 * source. These tests drive that rotation plus the exact decode path
 * the analyzer uses, on pure JVM (no Android APIs).
 */
class QrCodeAnalyzerTest {

    @Test fun `rotating four times returns the original bytes`() {
        val width = 7
        val height = 5
        val data = ByteArray(width * height) { (it * 37 % 251).toByte() }
        var rotated = RotatedYuv(data, width, height)
        repeat(4) {
            rotated = rotateYuv90CounterClockwise(rotated.data, rotated.width, rotated.height)
        }
        assertThat(rotated.width).isEqualTo(width)
        assertThat(rotated.height).isEqualTo(height)
        assertThat(rotated.data).isEqualTo(data)
    }

    @Test fun `a single rotation turns counter-clockwise with swapped dimensions`() {
        // 2 wide x 3 high:
        //   0 1
        //   2 3
        //   4 5
        // counter-clockwise 90 degrees becomes 3 wide x 2 high:
        //   1 3 5
        //   0 2 4
        val rotated = rotateYuv90CounterClockwise(byteArrayOf(0, 1, 2, 3, 4, 5), 2, 3)
        assertThat(rotated.width).isEqualTo(3)
        assertThat(rotated.height).isEqualTo(2)
        assertThat(rotated.data).isEqualTo(byteArrayOf(1, 3, 5, 0, 2, 4))
    }

    @Test fun `a QR frame rotated 90 degrees still decodes`() {
        assertThat(decodeRotatedQr(PAYLOAD, rotations = 1)).isEqualTo(PAYLOAD)
    }

    @Test fun `a QR frame rotated 270 degrees still decodes`() {
        assertThat(decodeRotatedQr(PAYLOAD, rotations = 3)).isEqualTo(PAYLOAD)
    }

    @Test fun `planar YUV source does not support rotation`() {
        // The trap itself: ZXing 3.5.4's PlanarYUVLuminanceSource
        // inherits the base rotateCounterClockwise(), which
        // unconditionally throws. If a future ZXing version supports
        // rotation, this test fails and the manual rotation above can
        // be revisited — until then, never call it.
        val source = PlanarYUVLuminanceSource(ByteArray(16), 4, 4, 0, 0, 4, 4, false)
        assertThrows<UnsupportedOperationException> { source.rotateCounterClockwise() }
    }

    /**
     * Encodes [text] as a QR code into Y-plane bytes, rotates the
     * bytes [rotations] times, then runs the exact decode path
     * `QrCodeAnalyzer.decode` uses. Returns the decoded text, or null
     * if anything in the path fails.
     */
    private fun decodeRotatedQr(text: String, rotations: Int): String? {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE)
        // BitMatrix -> Y-plane bytes: a black module is dark (0),
        // a white module is bright (255).
        val data = ByteArray(SIZE * SIZE)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                data[y * SIZE + x] = if (matrix[x, y]) 0 else 0xFF.toByte()
            }
        }
        var frame = RotatedYuv(data, SIZE, SIZE)
        repeat(rotations) {
            frame = rotateYuv90CounterClockwise(frame.data, frame.width, frame.height)
        }
        val source = PlanarYUVLuminanceSource(
            frame.data,
            frame.width,
            frame.height,
            0,
            0,
            frame.width,
            frame.height,
            false,
        )
        val reader = MultiFormatReader().apply {
            setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
        }
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
    }

    private companion object {
        /** Side of the square test QR frame, in pixels. */
        const val SIZE = 200

        /** A pairing payload shaped like the real one. */
        const val PAYLOAD = "relay://pair?v=1&id=abc&kind=desktop&name=Test&key=xyz"
    }
}
