package com.filestech.sms.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Round 33 — the PNG header probe: real PNG bytes (signature +
 * IHDR) yield their dimensions; anything else yields `null`.
 */
class PngDimensionsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a 512x512 PNG header probes to 512x512`() {
        assertThat(probePngDimensions(stream(pngHeader(512, 512))))
            .isEqualTo(512 to 512)
    }

    @Test
    fun `a photo-sized PNG header probes to its dimensions`() {
        assertThat(probePngDimensions(stream(pngHeader(2048, 1536))))
            .isEqualTo(2048 to 1536)
    }

    @Test
    fun `a JPEG header is not a PNG`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
            ByteArray(20)
        assertThat(probePngDimensions(stream(jpeg))).isNull()
    }

    @Test
    fun `a truncated header probes to null`() {
        assertThat(probePngDimensions(stream(ByteArray(10)))).isNull()
    }

    @Test
    fun `a PNG signature without IHDR probes to null`() {
        val header = pngHeader(512, 512)
        // Corrupt the chunk type: IHDR -> XXXX.
        header[12] = 'X'.code.toByte()
        header[13] = 'X'.code.toByte()
        header[14] = 'X'.code.toByte()
        header[15] = 'X'.code.toByte()
        assertThat(probePngDimensions(stream(header))).isNull()
    }

    @Test
    fun `a zero width probes to null`() {
        assertThat(probePngDimensions(stream(pngHeader(0, 512)))).isNull()
    }

    @Test
    fun `a PNG file on disk probes to its dimensions`() {
        val file = File(tempDir, "sticker.png").apply { writeBytes(pngHeader(512, 512)) }
        assertThat(probePngDimensions(file)).isEqualTo(512 to 512)
    }

    @Test
    fun `a missing file probes to null`() {
        assertThat(probePngDimensions(File(tempDir, "absent.png"))).isNull()
    }

    private fun stream(bytes: ByteArray) = ByteArrayInputStream(bytes)

    /** A 24-byte PNG header: signature + IHDR length + "IHDR" + width + height. */
    private fun pngHeader(width: Int, height: Int): ByteArray {
        val out = ByteArray(24)
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A).copyInto(out, 0)
        // IHDR length: 13.
        out[11] = 13
        "IHDR".toByteArray(Charsets.US_ASCII).copyInto(out, 12)
        out.intInto(16, width)
        out.intInto(20, height)
        return out
    }

    private fun ByteArray.intInto(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }
}
