package com.filestech.sms.e2e

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * The sender-key distribution message (round 49, design §13) —
 * the FIXED contract between the distributor and the receiver. Do
 * not change the JSON shape without changing both halves.
 *
 * A sender key is a 32-byte AES-256-GCM key its owner generated
 * for ONE group. It is distributed to each other member over
 * their pairwise vodozemac session, as ONE ordinary encrypted
 * text message (payload type `0x04` SENDER_KEY — see
 * [E2eWireFormat.PayloadType]), exactly like the round-48 media
 * key message: the distribution has no message row, uses a
 * random fragment-set id and the diagnostic-only sent intent
 * ([E2eSendManager.sendSenderKey]). After Olm decryption, the
 * plaintext is UTF-8 JSON:
 *
 *     {"v":1,
 *      "group":"<32 hex group id>",
 *      "kid":"<16 hex key id>",
 *      "key":"<base64, 32-byte sender key>",
 *      "members":["<canonical>", ...],
 *      "gen":1}
 *
 * - [SenderKeyDistribution.groupId] is the group id
 *   ([E2eGroupWireFormat.deriveGroupIdHex]): the hash of the
 *   full member list. The receiver verifies it matches the hash
 *   of [SenderKeyDistribution.members] — a distribution whose
 *   id and list disagree is dropped (a stale or forged list
 *   must not route messages into the wrong conversation).
 * - [SenderKeyDistribution.keyId] identifies this key among the
 *   sender's generations for the group: a group message envelope
 *   carries it in the clear so the receiver finds the right key
 *   (in-flight messages under an old key still decrypt — old
 *   generations are kept).
 * - [SenderKeyDistribution.members] is the FULL member list
 *   (canonical, sorted, including the sender and ourselves): the
 *   receiver stores it on the key row and matches incoming group
 *   messages to their conversation from it (the conversation
 *   holds one member fewer — ourselves — so the match is the
 *   minus-one rule, like the group-MMS path). At least 3 members
 *   — a group is two other people plus ourselves; fewer is not
 *   a group, and the distribution is dropped.
 * - [SenderKeyDistribution.generation] orders the sender's keys
 *   for the group: a distribution only becomes current when its
 *   generation beats the stored one — except after an identity
 *   change, when the sender distributes a fresh key at
 *   generation 1 (the store compares identity keys for that
 *   case; see [E2eGroupSenderKeyStore]).
 *
 * The key request (payload type `0x07` GROUP_KEY_REQUEST) is the
 * other half of the pair: a member that received a group message
 * under an unknown key asks the sender to re-distribute:
 *
 *     {"v":1,"group":"<32 hex group id>"}
 *
 * The responder re-sends its CURRENT key for that group to the
 * requester, rate-limited to one re-send per member per day (the
 * distribution record is the clock). A request for a group the
 * responder has no key for — or from an address that is not a
 * member — is ignored.
 *
 * Pure Kotlin, no Android APIs — fully unit-testable on the JVM.
 * [decodeDistribution] / [decodeKeyRequest] never throw:
 * anything malformed returns null, and the receiver drops it
 * silently (a malformed distribution is not a message).
 */
object E2eGroupSenderKeyMessage {

    /** JSON format version — the only version that exists. */
    const val FORMAT_VERSION: Int = 1

    /** Minimum members in a distribution: two others + ourselves. */
    const val MIN_MEMBERS: Int = 3

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * One sender's key for one group, as distributed. A plain
     * class, not a data class: data-class equality on [key]
     * ([ByteArray]) is referential, which would silently break
     * key comparisons (see [E2eMediaKeyMessage.MediaKey]).
     * [toString] never prints the key.
     */
    class SenderKeyDistribution(
        /** The group id (32 hex chars) — must hash from [members]. */
        val groupId: String,
        /** The key id (16 hex chars) — selects this generation. */
        val keyId: String,
        /** The raw 32-byte AES-256-GCM sender key. */
        val key: ByteArray,
        /** The full member list (canonical, sorted, including ourselves). */
        val members: List<String>,
        /** The generation: 1 for a group's first key, +1 per re-key. */
        val generation: Int,
    ) {

        init {
            require(E2eGroupWireFormat.isValidGroupIdHex(groupId)) {
                "group id must be 32 hex chars"
            }
            require(E2eGroupWireFormat.isValidKeyIdHex(keyId)) {
                "key id must be 16 hex chars"
            }
            require(key.size == E2eGroupWireFormat.SENDER_KEY_BYTES) {
                "sender key must be ${E2eGroupWireFormat.SENDER_KEY_BYTES} bytes"
            }
            require(members.size >= MIN_MEMBERS) {
                "a group needs at least $MIN_MEMBERS members"
            }
            require(generation >= 1) { "generation must be at least 1" }
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SenderKeyDistribution) return false
            return groupId == other.groupId &&
                keyId == other.keyId &&
                key.contentEquals(other.key) &&
                members == other.members &&
                generation == other.generation
        }

        override fun hashCode(): Int {
            var result = groupId.hashCode()
            result = 31 * result + keyId.hashCode()
            result = 31 * result + key.contentHashCode()
            result = 31 * result + members.hashCode()
            result = 31 * result + generation
            return result
        }

        override fun toString(): String =
            "SenderKeyDistribution(groupId=$groupId, keyId=$keyId, key=<32-byte key>, " +
                "members=<${members.size} members>, generation=$generation)"
    }

    /** Encode a distribution as its JSON wire form. */
    fun encodeDistribution(distribution: SenderKeyDistribution): String {
        return json.encodeToString(
            DistributionJson.serializer(),
            DistributionJson(
                v = FORMAT_VERSION,
                group = distribution.groupId,
                kid = distribution.keyId,
                key = Base64.getEncoder().encodeToString(distribution.key),
                members = distribution.members,
                gen = distribution.generation,
            ),
        )
    }

    /**
     * Decode a distribution's JSON plaintext. Returns null for
     * anything malformed — bad JSON, an unknown version, a
     * non-hex group/key id, a key that is not base64 for exactly
     * 32 bytes, fewer than [MIN_MEMBERS] members, a generation
     * below 1, or a group id that does not hash from the member
     * list. Never throws on input.
     */
    fun decodeDistribution(text: String): SenderKeyDistribution? {
        val parsed = runCatching {
            json.decodeFromString(DistributionJson.serializer(), text)
        }.getOrNull() ?: return null
        val keyBytes = runCatching { Base64.getDecoder().decode(parsed.key) }.getOrNull()
        val members = canonicalMembersIfValid(parsed, keyBytes) ?: return null
        // Unreachable when members is non-null (the key check is
        // part of the validation above), but the compiler cannot
        // see that — so the null stays explicit.
        val key = keyBytes ?: return null
        return SenderKeyDistribution(
            groupId = parsed.group,
            keyId = parsed.kid,
            key = key,
            members = members,
            generation = parsed.gen,
        )
    }

    /**
     * Validate a parsed distribution and return its canonical
     * member list, or null when it fails any check: an unknown
     * version, a non-hex group/key id, a key that is not exactly
     * 32 bytes, fewer than [MIN_MEMBERS] members, a generation
     * below 1, or a group id that does not hash from the member
     * list (a stale or forged list must not route messages into
     * the wrong conversation). Canonicalization can only shrink
     * the list — duplicates collapse — so the size is re-checked
     * after it.
     */
    private fun canonicalMembersIfValid(
        parsed: DistributionJson,
        keyBytes: ByteArray?,
    ): List<String>? {
        // Grouped into named pairs so no expression exceeds three
        // terms (ComplexCondition threshold 4 — see
        // E2eMediaKeyMessage.decode for the same shape).
        val versionOk = parsed.v == FORMAT_VERSION
        val groupOk = E2eGroupWireFormat.isValidGroupIdHex(parsed.group)
        val keyIdOk = E2eGroupWireFormat.isValidKeyIdHex(parsed.kid)
        val keyOk = keyBytes != null && keyBytes.size == E2eGroupWireFormat.SENDER_KEY_BYTES
        val membersOk = parsed.members.size >= MIN_MEMBERS
        val genOk = parsed.gen >= 1
        val headerOk = versionOk && groupOk && keyIdOk
        val payloadOk = keyOk && membersOk && genOk
        if (!headerOk || !payloadOk) return null
        val members = E2eGroupWireFormat.canonicalMembers(parsed.members)
        if (members.size < MIN_MEMBERS) return null
        if (E2eGroupWireFormat.deriveGroupIdHex(members) != parsed.group) return null
        return members
    }

    /** Encode a key request as its JSON wire form. */
    fun encodeKeyRequest(groupId: String): String {
        return json.encodeToString(
            KeyRequestJson.serializer(),
            KeyRequestJson(v = FORMAT_VERSION, group = groupId),
        )
    }

    /**
     * Decode a key request's JSON plaintext: the group id, or
     * null for anything malformed (bad JSON, an unknown version,
     * a non-hex group id). Never throws on input.
     */
    fun decodeKeyRequest(text: String): String? {
        val parsed = runCatching {
            json.decodeFromString(KeyRequestJson.serializer(), text)
        }.getOrNull() ?: return null
        if (parsed.v != FORMAT_VERSION) return null
        return parsed.group.takeIf(E2eGroupWireFormat::isValidGroupIdHex)
    }

    /**
     * The distribution JSON wire shape. Field names are the
     * contract — `v`, `group`, `kid`, `key`, `members`, `gen`.
     */
    @Serializable
    private data class DistributionJson(
        val v: Int,
        val group: String,
        val kid: String,
        val key: String,
        val members: List<String>,
        val gen: Int,
    )

    /** The key-request JSON wire shape: `v`, `group`. */
    @Serializable
    private data class KeyRequestJson(
        val v: Int,
        val group: String,
    )
}
