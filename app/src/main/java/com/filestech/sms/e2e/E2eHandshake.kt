package com.filestech.sms.e2e

/**
 * The probe / bundle handshake packets (design §2.2), carried by data SMS
 * on port [E2E_DATA_SMS_PORT]. Data SMS is invisible and never stored in
 * the system inbox, so a probe to a phone without Relay is silently
 * dropped by the OS — the recipient sees nothing.
 *
 * Round 45 — the same packets can also travel as regular SMS
 * ([E2eHandshakeSms]): one `RLYH:` text each, for peers whose data
 * SMS never arrive. A bundle reply follows the probe's arrival
 * channel ([HandshakeChannel]) — symmetric, no extra signalling.
 *
 * Both packets share a header — magic `RLYH` (4 bytes) + version `0x01`
 * (1 byte) + type (1 byte) — and both fit in one 140-byte data-SMS
 * segment:
 *
 *     Probe:  "RLYH" 0x01 0x01 <curve32> <ed32>                    = 70 bytes
 *     Bundle: "RLYH" 0x01 0x02 <curve32> <ed32> <one-time-key32>   = 102 bytes
 *
 * Round 47 — the manual flag: bit 7 (`0x80`) of the type byte marks a
 * probe the user sent by tapping Retry in the Details panel (or the
 * desktop retry command). A manual probe's type byte is `0x81`; an
 * automatic probe's stays `0x01`. The flag lives inside the packet,
 * so both transports carry it unchanged — the data-SMS bytes and the
 * `RLYH:` base64 text are the same 70 bytes, and the text form stays
 * 101 chars. The receiver ([E2eHandshakeHandler]) bypasses its
 * one-bundle-per-sender-per-day rate limit for a manual probe: the
 * sender-side budget bypass alone did not help, because the
 * receiver's limit still blocked the bundle reply and the retry
 * failed unless both phones reset timing first. Automatic probes
 * are NEVER marked manual — the limit still guards against probe
 * spam. A manual bundle send still consumes the limit (the reply
 * timestamp is recorded), so the NEXT automatic probe is gated.
 *
 * Version note: a build from before round 47 drops a manual probe —
 * `0x81` is not a type it knows — so a manual retry against a peer
 * on an old build gets no reply until they update. Automatic probes
 * are byte-identical to before, so the automatic handshake with old
 * builds is unaffected. There is no spare byte for a signal an old
 * build would ignore: every header byte is validated (magic,
 * version, type, exact length) and the key bytes are real keys.
 *
 * No signature on the reply: this is TOFU — a man-in-the-middle who swaps
 * the whole bundle defeats any signature on it anyway. Detection is the
 * safety-number verification ([E2eSafetyNumber]). The Olm pre-key message
 * itself is authenticated, so a forged bundle yields a session that fails
 * to decrypt and is dropped.
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 */
object E2eHandshake {

    /** Data-SMS port for the handshake (adjacent to the sync port 47831). */
    const val E2E_DATA_SMS_PORT: Int = 47832

    /** Total size of a probe packet, in bytes. */
    const val PROBE_SIZE: Int = 70

    /** Total size of a bundle-reply packet, in bytes. */
    const val BUNDLE_SIZE: Int = 102

    private const val HEADER_SIZE: Int = 6
    private const val VERSION: Byte = 0x01
    private const val TYPE_PROBE: Byte = 0x01
    private const val TYPE_BUNDLE: Byte = 0x02

    /**
     * Bit 7 of the type byte: set on a probe the user sent by tapping
     * Retry (round 47). A non-const val — `0x80` does not fit a Byte
     * literal, so the const form cannot spell it.
     */
    private val MANUAL_FLAG: Byte = 0x80.toByte()

    /** The type byte with the flag bit masked out — the packet type itself. */
    private const val TYPE_MASK: Byte = 0x7F

    private val MAGIC: ByteArray = byteArrayOf(0x52, 0x4C, 0x59, 0x48) // "RLYH"

    /**
     * A decoded handshake packet. Plain classes (not data classes):
     * data-class equality on [ByteArray] is referential, which would
     * silently break key comparisons.
     */
    sealed interface HandshakePacket {

        /** Our / their Curve25519 identity key (32 bytes). */
        val curveKey: ByteArray

        /** Our / their Ed25519 identity key (32 bytes). */
        val edKey: ByteArray

        /**
         * A probe: "I run Relay, here are my identity keys".
         * [manual] is true when the user sent this probe by tapping
         * Retry (round 47) — the receiver bypasses its bundle
         * rate limit for it. Automatic probes are never manual.
         */
        class Probe(
            override val curveKey: ByteArray,
            override val edKey: ByteArray,
            val manual: Boolean = false,
        ) : HandshakePacket {

            init {
                require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
                require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Probe) return false
                return curveKey.contentEquals(other.curveKey) &&
                    edKey.contentEquals(other.edKey) &&
                    manual == other.manual
            }

            override fun hashCode(): Int {
                var result = curveKey.contentHashCode()
                result = 31 * result + edKey.contentHashCode()
                result = 31 * result + manual.hashCode()
                return result
            }

            override fun toString(): String =
                "Probe(<32-byte curve key>, <32-byte ed key>, manual=$manual)"
        }

        /** A bundle reply: identity keys + one one-time public key. */
        class Bundle(
            override val curveKey: ByteArray,
            override val edKey: ByteArray,
            val oneTimeKey: ByteArray,
        ) : HandshakePacket {

            init {
                require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
                require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
                require(oneTimeKey.size == E2E_KEY_LENGTH) { "one-time key must be 32 bytes" }
            }

            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is Bundle) return false
                return curveKey.contentEquals(other.curveKey) &&
                    edKey.contentEquals(other.edKey) &&
                    oneTimeKey.contentEquals(other.oneTimeKey)
            }

            override fun hashCode(): Int {
                var result = curveKey.contentHashCode()
                result = 31 * result + edKey.contentHashCode()
                result = 31 * result + oneTimeKey.contentHashCode()
                return result
            }

            override fun toString(): String =
                "Bundle(<32-byte curve key>, <32-byte ed key>, <32-byte one-time key>)"
        }
    }

    /**
     * Encode a probe packet (70 bytes). [manual] sets the flag bit
     * (round 47): the type byte becomes `0x81` instead of `0x01`,
     * telling the receiver to bypass its bundle rate limit for
     * this probe. Only a user-initiated retry sets it — automatic
     * probes always encode with the default `false`.
     */
    fun encodeProbe(curveKey: ByteArray, edKey: ByteArray, manual: Boolean = false): ByteArray {
        require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
        require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
        val type = if (manual) (TYPE_PROBE.toInt() or MANUAL_FLAG.toInt()).toByte() else TYPE_PROBE
        return encode(type, curveKey, edKey, null)
    }

    /** Encode a bundle-reply packet (102 bytes). */
    fun encodeBundle(curveKey: ByteArray, edKey: ByteArray, oneTimeKey: ByteArray): ByteArray {
        require(curveKey.size == E2E_KEY_LENGTH) { "curve key must be 32 bytes" }
        require(edKey.size == E2E_KEY_LENGTH) { "ed key must be 32 bytes" }
        require(oneTimeKey.size == E2E_KEY_LENGTH) { "one-time key must be 32 bytes" }
        return encode(TYPE_BUNDLE, curveKey, edKey, oneTimeKey)
    }

    /**
     * Decode a received packet. Returns null for anything that is not one
     * of our packets (bad magic, version, type, or length) — the receiver
     * drops those silently, since a random data SMS to this port is not
     * our business. Never throws on input.
     */
    fun decode(bytes: ByteArray): HandshakePacket? {
        if (bytes.size < HEADER_SIZE) return null
        if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC)) return null
        if (bytes[4] != VERSION) return null
        // Round 47 — bit 7 of the type byte is the manual flag.
        // Mask it out for the type dispatch; the flag rides on the
        // decoded Probe below. The flag is meaningless on a bundle,
        // so a bundle with the bit set still decodes as a bundle
        // (lenient — a future bundle flag must not break decoding).
        val typeByte = bytes[5]
        return when ((typeByte.toInt() and TYPE_MASK.toInt()).toByte()) {
            TYPE_PROBE -> decodeProbe(bytes, isManual(typeByte))
            TYPE_BUNDLE -> decodeBundle(bytes)
            else -> null
        }
    }

    /** True when the type byte carries the manual flag (bit 7 set). */
    private fun isManual(typeByte: Byte): Boolean =
        (typeByte.toInt() and MANUAL_FLAG.toInt()) != 0

    private fun encode(type: Byte, curveKey: ByteArray, edKey: ByteArray, oneTimeKey: ByteArray?): ByteArray {
        val size = if (oneTimeKey == null) PROBE_SIZE else BUNDLE_SIZE
        return ByteArray(size).apply {
            MAGIC.copyInto(this, 0)
            this[4] = VERSION
            this[5] = type
            curveKey.copyInto(this, HEADER_SIZE)
            edKey.copyInto(this, HEADER_SIZE + E2E_KEY_LENGTH)
            oneTimeKey?.copyInto(this, HEADER_SIZE + 2 * E2E_KEY_LENGTH)
        }
    }

    private fun decodeProbe(bytes: ByteArray, manual: Boolean): HandshakePacket.Probe? =
        if (bytes.size != PROBE_SIZE) {
            null
        } else {
            HandshakePacket.Probe(
                curveKey = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + E2E_KEY_LENGTH),
                edKey = bytes.copyOfRange(HEADER_SIZE + E2E_KEY_LENGTH, PROBE_SIZE),
                manual = manual,
            )
        }

    private fun decodeBundle(bytes: ByteArray): HandshakePacket.Bundle? =
        if (bytes.size != BUNDLE_SIZE) {
            null
        } else {
            HandshakePacket.Bundle(
                curveKey = bytes.copyOfRange(HEADER_SIZE, HEADER_SIZE + E2E_KEY_LENGTH),
                edKey = bytes.copyOfRange(
                    HEADER_SIZE + E2E_KEY_LENGTH,
                    HEADER_SIZE + 2 * E2E_KEY_LENGTH,
                ),
                oneTimeKey = bytes.copyOfRange(HEADER_SIZE + 2 * E2E_KEY_LENGTH, BUNDLE_SIZE),
            )
        }
}

/**
 * Which transport carried (or will carry) a handshake packet
 * (round 45). A bundle reply follows the probe's arrival channel,
 * and a manual retry picks one channel per button — symmetric, no
 * extra signalling. Automatic probing stays data-SMS-only.
 */
enum class HandshakeChannel {
    /** Port-addressed data SMS (the original handshake transport). */
    DATA_SMS,

    /** Regular SMS, `RLYH:` + base64 (the round-45 fallback). */
    TEXT_SMS,
}
