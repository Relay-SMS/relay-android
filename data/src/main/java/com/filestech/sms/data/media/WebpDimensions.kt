package com.filestech.sms.data.media

import java.io.File
import java.io.InputStream

/**
 * Round 36 — the intrinsic dimensions of a WebP, read from its
 * header alone (no decode, no pixels), mirroring
 * [probePngDimensions]: a RIFF container whose `WEBP` chunk is
 * one of `VP8 ` (lossy), `VP8L` (lossless) or `VP8X`
 * (extended), each carrying the width and height in its first
 * bytes:
 *
 * - `VP8 `: a 3-byte frame tag, the 3-byte start code
 *   `9D 01 2A`, then width-1 and height-1 as 14-bit
 *   little-endian values.
 * - `VP8L`: a 1-byte signature `2F`, then one 32-bit
 *   little-endian word packing width-1 (low 14 bits) and
 *   height-1 (next 14 bits).
 * - `VP8X`: a flags byte + 3 reserved bytes, then width-1 and
 *   height-1 as 24-bit little-endian values.
 *
 * Pure JVM — no `BitmapFactory`, so it runs on the plain-JVM
 * harness (whose android.jar stubs throw from the `Options`
 * constructor itself) and is unit-tested with real WebP bytes.
 *
 * `null` on anything unreadable — not a WebP, truncated,
 * missing file, I/O failure: the caller treats it as
 * "dimensions unknown", never as a sticker signal.
 */
fun probeWebpDimensions(input: InputStream): Pair<Int, Int>? {
    val header = readHeader(input) ?: return null
    if (!header.copyOfRange(0, RIFF_MAGIC_BYTES).contentEquals(RIFF_MAGIC)) return null
    if (!header.copyOfRange(WEBP_MAGIC_OFFSET, WEBP_MAGIC_OFFSET + WEBP_MAGIC_BYTES)
            .contentEquals(WEBP_MAGIC)
    ) {
        return null
    }
    val chunk = header.copyOfRange(CHUNK_TYPE_OFFSET, CHUNK_TYPE_OFFSET + CHUNK_TYPE_BYTES)
    return when {
        chunk.contentEquals(VP8_MAGIC) -> vp8Dimensions(header)
        chunk.contentEquals(VP8L_MAGIC) -> vp8lDimensions(header)
        chunk.contentEquals(VP8X_MAGIC) -> vp8xDimensions(header)
        else -> null
    }
}

/** The first [WEBP_HEADER_BYTES] bytes of [input], or `null` when they cannot be read. */
private fun readHeader(input: InputStream): ByteArray? {
    val header = ByteArray(WEBP_HEADER_BYTES)
    var read = 0
    while (read < WEBP_HEADER_BYTES) {
        val n = runCatching { input.read(header, read, WEBP_HEADER_BYTES - read) }.getOrNull()
            ?: return null
        if (n < 0) return null
        read += n
    }
    return header
}

/** Lossy `VP8 `: width/height are 14-bit little-endian, stored minus one. */
private fun vp8Dimensions(header: ByteArray): Pair<Int, Int>? {
    if (!header.copyOfRange(VP8_START_CODE_OFFSET, VP8_START_CODE_OFFSET + VP8_START_CODE_BYTES)
            .contentEquals(VP8_START_CODE)
    ) {
        return null
    }
    val width = header.int14At(VP8_WIDTH_OFFSET) + 1
    val height = header.int14At(VP8_HEIGHT_OFFSET) + 1
    return if (width > 0 && height > 0) width to height else null
}

/** Lossless `VP8L`: one 32-bit word packs width-1 and height-1, 14 bits each. */
private fun vp8lDimensions(header: ByteArray): Pair<Int, Int>? {
    if (header[VP8L_SIGNATURE_OFFSET] != VP8L_SIGNATURE) return null
    val packed = header.int32LeAt(VP8L_PACKED_OFFSET)
    val width = (packed and 0x3FFF) + 1
    val height = ((packed ushr 14) and 0x3FFF) + 1
    return if (width > 0 && height > 0) width to height else null
}

/** Extended `VP8X`: width/height are 24-bit little-endian, stored minus one. */
private fun vp8xDimensions(header: ByteArray): Pair<Int, Int>? {
    val width = header.int24LeAt(VP8X_WIDTH_OFFSET) + 1
    val height = header.int24LeAt(VP8X_HEIGHT_OFFSET) + 1
    return if (width > 0 && height > 0) width to height else null
}

/** The dimensions of the WebP in [file], or `null` when it cannot be read. */
fun probeWebpDimensions(file: File): Pair<Int, Int>? =
    runCatching { file.inputStream().use { probeWebpDimensions(it) } }.getOrNull()

/** The 14-bit little-endian integer starting at [offset]. */
private fun ByteArray.int14At(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0x3F) shl 8)

/** The 24-bit little-endian integer starting at [offset]. */
private fun ByteArray.int24LeAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16)

/** The 32-bit little-endian integer starting at [offset]. */
private fun ByteArray.int32LeAt(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

/** Bytes read: RIFF header (12) + chunk type (4) + chunk size (4) + the longest payload prefix (10). */
private const val WEBP_HEADER_BYTES = 30
private const val RIFF_MAGIC_BYTES = 4
private const val WEBP_MAGIC_OFFSET = 8
private const val WEBP_MAGIC_BYTES = 4
private const val CHUNK_TYPE_OFFSET = 12
private const val CHUNK_TYPE_BYTES = 4
private const val VP8_START_CODE_OFFSET = 23
private const val VP8_START_CODE_BYTES = 3
private const val VP8_WIDTH_OFFSET = 26
private const val VP8_HEIGHT_OFFSET = 28
private const val VP8L_SIGNATURE_OFFSET = 20
private const val VP8L_PACKED_OFFSET = 21
private const val VP8X_WIDTH_OFFSET = 24
private const val VP8X_HEIGHT_OFFSET = 27
private val RIFF_MAGIC = "RIFF".toByteArray(Charsets.US_ASCII)
private val WEBP_MAGIC = "WEBP".toByteArray(Charsets.US_ASCII)
private val VP8_MAGIC = "VP8 ".toByteArray(Charsets.US_ASCII)
private val VP8L_MAGIC = "VP8L".toByteArray(Charsets.US_ASCII)
private val VP8X_MAGIC = "VP8X".toByteArray(Charsets.US_ASCII)
private val VP8_START_CODE = byteArrayOf(0x9D.toByte(), 0x01, 0x2A)
private const val VP8L_SIGNATURE: Byte = 0x2F
