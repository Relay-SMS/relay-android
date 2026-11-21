package com.filestech.sms.e2e

import java.security.SecureRandom
import java.util.Base64

/**
 * The ciphertext wire format (design §3.2): regular SMS, base64,
 * app-level fragments. Each fragment is its own single-part SMS, so it
 * carries the full 160 GSM characters:
 *
 *     RLY1:<mid>:<ii>/<nn>:<chunk>
 *
 * - `RLY1:` — magic prefix (5 chars).
 * - `<mid>` — 6 hex chars, random per message (24 bits). Identifies the
 *   fragment set.
 * - `<ii>/<nn>` — 2-digit index / 2-digit total, 1-based, max 99 fragments.
 * - `<chunk>` — a substring of the base64 of the whole payload.
 *
 * Header length is 18 chars, so each fragment carries 142 base64 chars
 * (= 106 bytes). The whole payload is base64'd first and then split, so
 * there are no per-fragment padding issues; reassembly concatenates the
 * substrings and decodes once.
 *
 * The payload is:
 *
 *     [version: 1 byte = 0x01][msg type: 1 byte][payload type: 1 byte][Olm message bytes]
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 */
object E2eWireFormat {

    /** Magic prefix every ciphertext fragment starts with. */
    const val MAGIC: String = "RLY1:"

    /** Characters per fragment available for the base64 chunk (160 - 18 header). */
    const val CHUNK_CHARS: Int = 142

    /** Maximum fragments per message (the 2-digit total field). */
    const val MAX_FRAGMENTS: Int = 99

    /** Maximum outgoing encrypted message length, in characters (design §3.2). */
    const val MAX_PLAINTEXT_CHARS: Int = 8_000

    /** Payload version byte. */
    const val PAYLOAD_VERSION: Byte = 0x01

    private const val MESSAGE_ID_CHARS: Int = 6
    private const val PAYLOAD_HEADER_BYTES: Int = 3
    private const val MSG_TYPE_PRE_KEY: Byte = 0x01
    private const val MSG_TYPE_NORMAL: Byte = 0x02
    private val BASE64_CHUNK_REGEX: Regex = Regex("[A-Za-z0-9+/=]+")
    private val HEX_REGEX: Regex = Regex("[0-9a-fA-F]+")
    private val DIGITS_REGEX: Regex = Regex("[0-9]+")

    /** The kind of content inside an encrypted payload. */
    enum class PayloadType(val code: Byte) {
        TEXT(0x01),
        REACTION(0x02),
    }

    /** One parsed ciphertext fragment. */
    data class Fragment(
        val messageId: String,
        val index: Int,
        val total: Int,
        val chunk: String,
    )

    /** A reassembled + decoded payload: the Olm message and its content kind. */
    data class DecodedPayload(
        val message: EncryptedMessage,
        val payloadType: PayloadType,
    )

    /**
     * A random 6-hex-char message id (24 bits) for a new outgoing message.
     * The caller keeps it: a retry re-sends the same fragment set with the
     * same id, and the receiver dedupes by (sender, id, index).
     */
    fun generateMessageId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(3)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    /** Whether this plaintext fits the encrypted cap (checked by the composer). */
    fun plaintextExceedsCap(text: String): Boolean = text.length > MAX_PLAINTEXT_CHARS

    /** Quick classifier for the receive hook: is this SMS body a ciphertext fragment? */
    fun isFragment(text: String): Boolean = parseFragment(text) != null

    /**
     * Encode an encrypted message as SMS fragments. The message id must be
     * 6 hex chars (see [generateMessageId]).
     *
     * @throws IllegalArgumentException if the id is malformed or the
     * payload needs more than [MAX_FRAGMENTS] fragments.
     */
    fun encode(messageId: String, message: EncryptedMessage, payloadType: PayloadType): List<String> {
        require(isValidMessageId(messageId)) { "message id must be 6 hex chars" }
        val encoded = Base64.getEncoder().encodeToString(encodePayload(message, payloadType))
        val total = (encoded.length + CHUNK_CHARS - 1) / CHUNK_CHARS
        require(total <= MAX_FRAGMENTS) { "payload needs $total fragments, max is $MAX_FRAGMENTS" }
        return (0 until total).map { i ->
            val chunk = encoded.substring(i * CHUNK_CHARS, minOf((i + 1) * CHUNK_CHARS, encoded.length))
            "$MAGIC$messageId:${"%02d".format(i + 1)}/${"%02d".format(total)}:$chunk"
        }
    }

    /**
     * Parse one SMS body as a fragment. Returns null for anything that is
     * not a well-formed fragment (bad magic, bad hex, bad index, bad
     * base64 chars, oversized chunk) — never throws on input. A human
     * would have to type this exact shape for a false positive, and even
     * then the payload would fail to decrypt and become a placeholder.
     */
    fun parseFragment(text: String): Fragment? {
        val parts = fragmentParts(text) ?: return null
        val messageId = parts[0].takeIf(::isValidMessageId)
        val range = parseIndexRange(parts[1])
        val chunk = parts[2].takeIf(::isValidChunk)
        return if (messageId != null && range != null && chunk != null) {
            Fragment(messageId, range.first, range.second, chunk)
        } else {
            null
        }
    }

    /**
     * Reassemble a complete fragment set into its payload bytes: all
     * fragments must share one message id and total, cover indices
     * 1..total exactly once, and carry full 142-char chunks except the
     * last. Concatenates in index order and base64-decodes once.
     *
     * @throws IllegalArgumentException on any inconsistency or bad base64.
     */
    fun assemble(fragments: List<Fragment>): ByteArray {
        require(fragments.isNotEmpty()) { "cannot assemble an empty fragment set" }
        val first = fragments.first()
        require(fragments.all { it.messageId == first.messageId }) {
            "fragment set mixes message ids"
        }
        require(fragments.all { it.total == first.total }) {
            "fragment set mixes totals"
        }
        require(fragments.size == first.total) {
            "fragment set has ${fragments.size} fragments, expected ${first.total}"
        }
        val byIndex = fragments.sortedBy { it.index }
        require(byIndex.map { it.index } == (1..first.total).toList()) {
            "fragment indices must cover 1..${first.total} exactly once"
        }
        require(byIndex.dropLast(1).all { it.chunk.length == CHUNK_CHARS }) {
            "non-final fragments must carry a full $CHUNK_CHARS-char chunk"
        }
        val encoded = byIndex.joinToString("") { it.chunk }
        return runCatching { Base64.getDecoder().decode(encoded) }
            .getOrElse { throw IllegalArgumentException("fragment set is not valid base64", it) }
    }

    /**
     * Decode reassembled payload bytes into the Olm message + content kind.
     *
     * @throws IllegalArgumentException on a bad version, message type, or
     * payload type, or a payload too short to hold the header.
     */
    fun parsePayload(payload: ByteArray): DecodedPayload {
        require(payload.size > PAYLOAD_HEADER_BYTES) { "payload too short for its header" }
        require(payload[0] == PAYLOAD_VERSION) { "unsupported payload version ${payload[0]}" }
        val type = when (payload[1]) {
            MSG_TYPE_PRE_KEY -> OlmMessageType.PRE_KEY
            MSG_TYPE_NORMAL -> OlmMessageType.NORMAL
            else -> throw IllegalArgumentException("unknown Olm message type ${payload[1]}")
        }
        val payloadType = PayloadType.entries.firstOrNull { it.code == payload[2] }
            ?: throw IllegalArgumentException("unknown payload type ${payload[2]}")
        val bytes = payload.copyOfRange(PAYLOAD_HEADER_BYTES, payload.size)
        return DecodedPayload(EncryptedMessage(type, bytes), payloadType)
    }

    /** Build the payload bytes for an encrypted message (header + Olm bytes). */
    fun encodePayload(message: EncryptedMessage, payloadType: PayloadType): ByteArray {
        val typeByte = when (message.type) {
            OlmMessageType.PRE_KEY -> MSG_TYPE_PRE_KEY
            OlmMessageType.NORMAL -> MSG_TYPE_NORMAL
        }
        val payload = ByteArray(PAYLOAD_HEADER_BYTES + message.bytes.size)
        payload[0] = PAYLOAD_VERSION
        payload[1] = typeByte
        payload[2] = payloadType.code
        message.bytes.copyInto(payload, PAYLOAD_HEADER_BYTES)
        return payload
    }

    private fun isValidMessageId(messageId: String): Boolean =
        messageId.length == MESSAGE_ID_CHARS && messageId.matches(HEX_REGEX)

    private fun isValidChunk(chunk: String): Boolean =
        chunk.length in 1..CHUNK_CHARS && chunk.matches(BASE64_CHUNK_REGEX)

    /** The three body parts of a fragment (id, index/total, chunk), or null if not a fragment. */
    private fun fragmentParts(text: String): List<String>? {
        if (!text.startsWith(MAGIC)) return null
        return text.substring(MAGIC.length).split(":").takeIf { it.size == 3 }
    }

    /** Parse the `ii/nn` index field into (index, total), 1-based, or null if malformed. */
    private fun parseIndexRange(text: String): Pair<Int, Int>? {
        val pair = text.split("/").takeIf { it.size == 2 } ?: return null
        val index = pair[0].toTwoDigitIntOrNull() ?: return null
        val total = pair[1].toTwoDigitIntOrNull() ?: return null
        return if (total in 1..MAX_FRAGMENTS && index in 1..total) index to total else null
    }

    private fun String.toTwoDigitIntOrNull(): Int? =
        takeIf { length == 2 && matches(DIGITS_REGEX) }?.toIntOrNull()
}
