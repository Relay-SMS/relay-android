package com.filestech.sms.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.File

/**
 * Round 36 — the WebP header probe: real WebP bytes (lossy
 * `VP8 `, lossless `VP8L`, extended `VP8X`) yield their
 * dimensions; anything else yields `null`.
 */
class WebpDimensionsTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a 512x512 lossy VP8 header probes to 512x512`() {
        assertThat(probeWebpDimensions(stream(vp8Header(512, 512))))
            .isEqualTo(512 to 512)
    }

    @Test
    fun `a photo-sized lossy VP8 header probes to its dimensions`() {
        assertThat(probeWebpDimensions(stream(vp8Header(2048, 1536))))
            .isEqualTo(2048 to 1536)
    }

    @Test
    fun `a 512x512 lossless VP8L header probes to 512x512`() {
        assertThat(probeWebpDimensions(stream(vp8lHeader(512, 512))))
            .isEqualTo(512 to 512)
    }

    @Test
    fun `a 512x512 extended VP8X header probes to 512x512`() {
        assertThat(probeWebpDimensions(stream(vp8xHeader(512, 512))))
            .isEqualTo(512 to 512)
    }

    @Test
    fun `a 72x72 lossless VP8L header probes to 72x72`() {
        assertThat(probeWebpDimensions(stream(vp8lHeader(72, 72))))
            .isEqualTo(72 to 72)
    }

    @Test
    fun `a PNG header is not a WebP`() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            ByteArray(22)
        assertThat(probeWebpDimensions(stream(png))).isNull()
    }

    @Test
    fun `a truncated header probes to null`() {
        assertThat(probeWebpDimensions(stream(ByteArray(10)))).isNull()
    }

    @Test
    fun `a RIFF header without WEBP probes to null`() {
        val header = vp8Header(512, 512)
        // Corrupt the WEBP magic: WEBP -> XXXX.
        header[8] = 'X'.code.toByte()
        header[9] = 'X'.code.toByte()
        header[10] = 'X'.code.toByte()
        header[11] = 'X'.code.toByte()
        assertThat(probeWebpDimensions(stream(header))).isNull()
    }

    @Test
    fun `a lossy VP8 header without the start code probes to null`() {
        val header = vp8Header(512, 512)
        header[23] = 0
        header[24] = 0
        header[25] = 0
        assertThat(probeWebpDimensions(stream(header))).isNull()
    }

    @Test
    fun `a lossless VP8L header without the signature probes to null`() {
        val header = vp8lHeader(512, 512)
        header[20] = 0
        assertThat(probeWebpDimensions(stream(header))).isNull()
    }

    @Test
    fun `an unknown chunk probes to null`() {
        val header = vp8Header(512, 512)
        "XXXX".toByteArray(Charsets.US_ASCII).copyInto(header, 12)
        assertThat(probeWebpDimensions(stream(header))).isNull()
    }

    @Test
    fun `a WebP file on disk probes to its dimensions`() {
        val file = File(tempDir, "sticker.webp").apply { writeBytes(vp8lHeader(512, 512)) }
        assertThat(probeWebpDimensions(file)).isEqualTo(512 to 512)
    }

    @Test
    fun `a missing file probes to null`() {
        assertThat(probeWebpDimensions(File(tempDir, "absent.webp"))).isNull()
    }

    private fun stream(bytes: ByteArray) = ByteArrayInputStream(bytes)

    /** RIFF + WEBP envelope, 30 bytes; the chunk type + payload are filled by each builder. */
    private fun webpShell(chunk: String): ByteArray {
        val out = ByteArray(30)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        // File size: 22 (the rest of the file after this field).
        out.int32LeInto(4, 22)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        chunk.toByteArray(Charsets.US_ASCII).copyInto(out, 12)
        // Chunk size: 10 (the rest of the chunk after this field).
        out.int32LeInto(16, 10)
        return out
    }

    /** Lossy `VP8 `: frame tag + start code + width-1/height-1 as 14-bit little-endian. */
    private fun vp8Header(width: Int, height: Int): ByteArray {
        val out = webpShell("VP8 ")
        out[23] = 0x9D.toByte()
        out[24] = 0x01
        out[25] = 0x2A
        out.int14LeInto(26, width - 1)
        out.int14LeInto(28, height - 1)
        return out
    }

    /** Lossless `VP8L`: signature + one 32-bit word packing width-1 and height-1. */
    private fun vp8lHeader(width: Int, height: Int): ByteArray {
        val out = webpShell("VP8L")
        out[20] = 0x2F
        val packed = (width - 1) or ((height - 1) shl 14)
        out.int32LeInto(21, packed)
        return out
    }

    /** Extended `VP8X`: flags + reserved + width-1/height-1 as 24-bit little-endian. */
    private fun vp8xHeader(width: Int, height: Int): ByteArray {
        val out = webpShell("VP8X")
        out.int24LeInto(24, width - 1)
        out.int24LeInto(27, height - 1)
        return out
    }

    private fun ByteArray.int14LeInto(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.int24LeInto(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
    }

    private fun ByteArray.int32LeInto(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}
