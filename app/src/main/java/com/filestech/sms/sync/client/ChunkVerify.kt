package com.filestech.sms.sync.client

import java.security.MessageDigest
import java.util.Locale

/** Client mode — lowercase hex (the digest alphabet the chunk frames use). */
internal fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * Client mode C5 — the transfer check every chunk download shares
 * (C4 avatars + C5 attachments): the final chunk's size first, then
 * its SHA-256 — each only when the phone sent it (either field may
 * be absent on an older hub). The metadata-size cross-check (a
 * truncated stream is self-consistent) lives in the caller, which
 * is the one that knows the metadata.
 */
internal fun verifyTransferBytes(data: ByteArray, sha256: String?, sizeBytes: Long?): Boolean {
    if (sizeBytes != null && data.size.toLong() != sizeBytes) return false
    if (sha256 == null) return true
    val digest = MessageDigest.getInstance("SHA-256").digest(data).toHex()
    return digest == sha256.lowercase(Locale.ROOT)
}

/**
 * Client mode C5 — SHA-256 of [data] as lowercase hex: the digest
 * the `attachment_upload_start` frame carries (the phone requires
 * it — unlike the desktop, this device always hashes).
 */
internal fun sha256Hex(data: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(data).toHex()
