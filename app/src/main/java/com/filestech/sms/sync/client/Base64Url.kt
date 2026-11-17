package com.filestech.sms.sync.client

import java.util.Base64

/**
 * Client mode — base64url without padding, the protocol's byte
 * alphabet (SYNC_PROTOCOL.md): public keys, challenges and signatures
 * all travel this way. `java.util.Base64` wants its padding back
 * before decoding, so it is restored here — the same rule as
 * [com.filestech.sms.sync.QrPairing]'s decoder, in our own package.
 *
 * Pure Kotlin, no Android imports: unit-tested on the JVM.
 */
object Base64Url {

    /** Encodes [bytes] as base64url with no padding (43 chars for 32 bytes). */
    fun encode(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    /**
     * Decodes base64url text (padding optional). `null` when the text
     * is not valid base64url at all — callers treat that as a protocol
     * failure, never as an exception.
     */
    fun decode(text: String): ByteArray? =
        runCatching { Base64.getUrlDecoder().decode(pad(text)) }.getOrNull()

    private fun pad(value: String): String {
        val remainder = value.length % 4
        if (remainder == 1) return value
        return value + "=".repeat((4 - remainder) % 4)
    }
}
