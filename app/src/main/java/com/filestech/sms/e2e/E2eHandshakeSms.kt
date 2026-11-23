package com.filestech.sms.e2e

import java.util.Base64

/**
 * The handshake-over-regular-SMS wire format (round 45): the same
 * 70/102-byte packets ([E2eHandshake]), base64'd under a distinct
 * magic prefix, one single-part SMS each:
 *
 *     RLYH:<base64 of the packet>
 *
 * Probe → 101 chars, bundle → 141 chars — both under one 160-char
 * GSM-7 segment (the base64 alphabet is entirely GSM-7, so there is
 * no UCS-2 fallback and no multi-part sending). One SMS each way,
 * the same count as the data-SMS handshake.
 *
 * The magic `RLYH:` is distinct from the ciphertext magic `RLY1:`
 * ([E2eWireFormat.MAGIC]): a handshake SMS never parses as a
 * fragment and vice versa — the two receive branches are disjoint
 * by construction.
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 */
object E2eHandshakeSms {

    /** Magic prefix every handshake SMS starts with. */
    const val MAGIC: String = "RLYH:"

    /** Length of a probe handshake SMS, in characters (5 magic + 96 base64). */
    const val PROBE_TEXT_LENGTH: Int = 101

    /** Length of a bundle handshake SMS, in characters (5 magic + 136 base64). */
    const val BUNDLE_TEXT_LENGTH: Int = 141

    /**
     * Encode a handshake packet as one SMS body. The packet must be a
     * probe- or bundle-sized packet (see [E2eHandshake]).
     *
     * @throws IllegalArgumentException if the packet is neither size.
     */
    fun encode(packet: ByteArray): String {
        require(packet.size == E2eHandshake.PROBE_SIZE || packet.size == E2eHandshake.BUNDLE_SIZE) {
            "handshake packet must be ${E2eHandshake.PROBE_SIZE} or ${E2eHandshake.BUNDLE_SIZE} bytes"
        }
        return MAGIC + Base64.getEncoder().encodeToString(packet)
    }

    /**
     * Parse an SMS body as a handshake packet. Returns null for
     * anything that is not a well-formed handshake SMS (bad magic,
     * bad base64, wrong decoded length) — never throws on input.
     * The caller still runs [E2eHandshake.decode] on the bytes,
     * which re-validates the packet magic, version, and type.
     */
    fun parse(text: String): ByteArray? {
        if (!text.startsWith(MAGIC)) return null
        val decoded = runCatching {
            Base64.getDecoder().decode(text.substring(MAGIC.length))
        }.getOrNull() ?: return null
        return decoded.takeIf {
            it.size == E2eHandshake.PROBE_SIZE || it.size == E2eHandshake.BUNDLE_SIZE
        }
    }

    /** Quick classifier for the receive hook: is this SMS body a handshake SMS? */
    fun isHandshakeSms(text: String): Boolean = parse(text) != null
}
