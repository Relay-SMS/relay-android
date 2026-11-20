package com.filestech.sms.data.media

import java.io.File
import java.io.InputStream

/**
 * Round 33 — the intrinsic dimensions of a PNG, read from its
 * header alone (no decode, no pixels): the 8-byte PNG signature,
 * then the IHDR chunk, whose first 8 payload bytes are the width
 * and height as big-endian 32-bit integers.
 *
 * Pure JVM — no `BitmapFactory`, so it runs on the plain-JVM
 * harness (whose android.jar stubs throw from the `Options`
 * constructor itself) and is unit-tested with real PNG bytes.
 * Only PNG is probed: the sticker rule only needs PNG dimensions
 * (512×512), and every other format answers `null` here.
 *
 * `null` on anything unreadable — not a PNG, truncated, missing
 * file, I/O failure: the caller treats it as "dimensions unknown",
 * never as a sticker signal.
 */
fun probePngDimensions(input: InputStream): Pair<Int, Int>? {
    val header = readHeader(input) ?: return null
    if (!header.copyOfRange(0, PNG_SIGNATURE_BYTES).contentEquals(PNG_SIGNATURE)) return null
    if (!header.copyOfRange(PNG_SIGNATURE_BYTES + CHUNK_LENGTH_BYTES, PNG_HEADER_BYTES - 8)
            .contentEquals(IHDR_MAGIC)
    ) {
        return null
    }
    val width = header.intAt(PNG_HEADER_BYTES - 8)
    val height = header.intAt(PNG_HEADER_BYTES - 4)
    return if (width > 0 && height > 0) width to height else null
}

/** The first [PNG_HEADER_BYTES] bytes of [input], or `null` when they cannot be read. */
private fun readHeader(input: InputStream): ByteArray? {
    val header = ByteArray(PNG_HEADER_BYTES)
    var read = 0
    while (read < PNG_HEADER_BYTES) {
        val n = runCatching { input.read(header, read, PNG_HEADER_BYTES - read) }.getOrNull()
            ?: return null
        if (n < 0) return null
        read += n
    }
    return header
}

/** The dimensions of the PNG in [file], or `null` when it cannot be read. */
fun probePngDimensions(file: File): Pair<Int, Int>? =
    runCatching { file.inputStream().use { probePngDimensions(it) } }.getOrNull()

/** The big-endian 32-bit integer starting at [offset]. */
private fun ByteArray.intAt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

/** Bytes read: signature (8) + IHDR length (4) + type (4) + width (4) + height (4). */
private const val PNG_HEADER_BYTES = 24
private const val PNG_SIGNATURE_BYTES = 8
private const val CHUNK_LENGTH_BYTES = 4
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
private val IHDR_MAGIC = "IHDR".toByteArray(Charsets.US_ASCII)
