package com.filestech.sms.e2e

import com.filestech.sms.core.ext.canonicalPhone
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * The encrypted-group wire format (round 49, design §13): the group
 * identity and the sender-key message envelope. Pure Kotlin, no
 * Android APIs — fully unit-testable on the JVM.
 *
 * **Group identity.** A group is its full member list — every
 * member's canonical address ([canonicalPhone]), sorted,
 * including ourselves. The group id is the first 16 bytes of
 * SHA-256 over the list joined with `"\n"`, hex-encoded (32
 * chars). Every member computes the same id for the same group,
 * whatever order the members arrived in — and a changed member
 * list is a DIFFERENT group id, which is what makes membership
 * changes re-key by construction: the new group gets fresh sender
 * keys, and a removed member never receives them.
 *
 * **The envelope.** A group message travels as `RLY1:` fragments
 * with message type GROUP (`0x03`) and payload type GROUP_TEXT
 * (`0x05`) / GROUP_REACTION (`0x06`) — see [E2eWireFormat]. The
 * bytes after the 3-byte header are the envelope:
 *
 *     [group id: 16 bytes][key id: 8 bytes][AEAD blob]
 *
 * The group id + key id are IN THE CLEAR (they select the key —
 * the receiver must find the sender's sender key for this group
 * before it can decrypt anything). The AEAD blob is the
 * [com.filestech.sms.core.crypto.AeadCipher] envelope
 * (`[version:1][iv:12][ciphertext+tag]`, AES-256-GCM) over the
 * message body, with the AAD binding the ciphertext to the group,
 * the sender, and the key ([aadFor]): a ciphertext lifted into
 * another group, or replayed as another sender, fails the GCM
 * tag. The key itself is the sender's sender key for the group —
 * distributed over the pairwise vodozemac sessions, never on this
 * channel (see [E2eGroupSenderKeyMessage]).
 */
object E2eGroupWireFormat {

    /** Group-id length on the wire: the first 16 bytes of the member-list hash. */
    const val GROUP_ID_BYTES: Int = 16

    /** Group-id length as hex: 32 chars. */
    const val GROUP_ID_HEX_CHARS: Int = 32

    /** Sender-key id length on the wire: 8 random bytes. */
    const val KEY_ID_BYTES: Int = 8

    /** Sender-key id length as hex: 16 chars. */
    const val KEY_ID_HEX_CHARS: Int = 16

    /** Sender-key length: 32 bytes (AES-256). */
    const val SENDER_KEY_BYTES: Int = 32

    /** Envelope header length: group id (16) + key id (8). */
    const val ENVELOPE_HEADER_BYTES: Int = GROUP_ID_BYTES + KEY_ID_BYTES

    private const val HEX_RADIX: Int = 16
    private val GROUP_ID_REGEX: Regex = Regex("[0-9a-f]{$GROUP_ID_HEX_CHARS}")
    private val KEY_ID_REGEX: Regex = Regex("[0-9a-f]{$KEY_ID_HEX_CHARS}")

    /** A parsed group message envelope: the key selector + the AEAD blob to decrypt. */
    class GroupEnvelope(
        /** The group id (32 hex chars) — selects the group. */
        val groupIdHex: String,
        /** The sender-key id (16 hex chars) — selects the key generation. */
        val keyIdHex: String,
        /** The [com.filestech.sms.core.crypto.AeadCipher] blob. */
        val aeadBlob: ByteArray,
    ) {

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GroupEnvelope) return false
            return groupIdHex == other.groupIdHex &&
                keyIdHex == other.keyIdHex &&
                aeadBlob.contentEquals(other.aeadBlob)
        }

        override fun hashCode(): Int {
            var result = groupIdHex.hashCode()
            result = 31 * result + keyIdHex.hashCode()
            result = 31 * result + aeadBlob.contentHashCode()
            return result
        }

        override fun toString(): String =
            "GroupEnvelope(groupId=$groupIdHex, keyId=$keyIdHex, blob=<${aeadBlob.size} bytes>)"
    }

    /**
     * The group id for a member list: the first 16 bytes of
     * SHA-256 over the canonical addresses, sorted, joined with
     * `"\n"`, hex-encoded. The list MUST be the full member set
     * (including ourselves) — a partial list derives a different
     * id, which is a different group. Order-independent: the same
     * members in any order derive the same id.
     */
    fun deriveGroupIdHex(members: List<String>): String {
        val canonical = members.map { it.canonicalPhone() }.distinct().sorted()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(canonical.joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.digest().copyOf(GROUP_ID_BYTES).toHex()
    }

    /**
     * The canonical member list, sorted and deduplicated: the
     * form every group-id derivation and member-list comparison
     * uses. Callers pass raw addresses; this normalizes them.
     */
    fun canonicalMembers(members: List<String>): List<String> =
        members.map { it.canonicalPhone() }.distinct().sorted()

    /** Whether this string is a well-formed group id (32 lowercase hex chars). */
    fun isValidGroupIdHex(groupIdHex: String): Boolean = groupIdHex.matches(GROUP_ID_REGEX)

    /** Whether this string is a well-formed key id (16 lowercase hex chars). */
    fun isValidKeyIdHex(keyIdHex: String): Boolean = keyIdHex.matches(KEY_ID_REGEX)

    /** A fresh random sender key: 32 bytes. */
    fun generateSenderKey(random: SecureRandom = SecureRandom()): ByteArray {
        val key = ByteArray(SENDER_KEY_BYTES)
        random.nextBytes(key)
        return key
    }

    /** A fresh random key id: 16 lowercase hex chars. */
    fun generateKeyId(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(KEY_ID_BYTES)
        random.nextBytes(bytes)
        return bytes.toHex()
    }

    /**
     * The AAD binding a group ciphertext to its group, sender, and
     * key: `groupIdBytes || senderCanonicalAddress (UTF-8) ||
     * keyIdBytes`. The sender address is the SMS sender's
     * canonical address — both sides canonicalize the same number
     * ([canonicalPhone] is deterministic, not region-dependent),
     * so the AAD agrees. A ciphertext replayed into another group
     * or as another sender fails the GCM tag.
     */
    fun aadFor(groupIdHex: String, senderAddress: String, keyIdHex: String): ByteArray? {
        val groupId = groupIdHex.hexToBytes() ?: return null
        val keyId = keyIdHex.hexToBytes() ?: return null
        val sender = senderAddress.canonicalPhone().toByteArray(Charsets.UTF_8)
        return groupId + sender + keyId
    }

    /**
     * Build an envelope: the group id + key id in the clear,
     * then the AEAD blob. Returns null when either id is
     * malformed (the caller never builds an envelope it cannot
     * parse back).
     */
    fun encodeEnvelope(groupIdHex: String, keyIdHex: String, aeadBlob: ByteArray): ByteArray? {
        val groupId = groupIdHex.hexToBytes() ?: return null
        val keyId = keyIdHex.hexToBytes() ?: return null
        return groupId + keyId + aeadBlob
    }

    /**
     * Parse an envelope. Returns null for anything malformed (too
     * short, empty blob) — never throws on input. A parsed
     * envelope still has to decrypt: the key lookup and the GCM
     * tag are the real checks.
     */
    fun parseEnvelope(bytes: ByteArray): GroupEnvelope? {
        if (bytes.size <= ENVELOPE_HEADER_BYTES) return null
        val groupIdHex = bytes.copyOfRange(0, GROUP_ID_BYTES).toHex()
        val keyIdHex = bytes.copyOfRange(GROUP_ID_BYTES, ENVELOPE_HEADER_BYTES).toHex()
        val blob = bytes.copyOfRange(ENVELOPE_HEADER_BYTES, bytes.size)
        return GroupEnvelope(groupIdHex, keyIdHex, blob)
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }

    private fun String.hexToBytes(): ByteArray? {
        if (length % 2 != 0) return null
        return runCatching {
            ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(HEX_RADIX).toByte()
            }
        }.getOrNull()
    }
}
