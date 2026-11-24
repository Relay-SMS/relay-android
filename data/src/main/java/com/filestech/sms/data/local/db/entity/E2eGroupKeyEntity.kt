package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity

/**
 * E2E encrypted groups (schema v24, round 49) — one row per sender
 * key: our own key for a group, or one member's key for it (design
 * §13). A sender key is a 32-byte AES-256-GCM key its owner
 * generated for ONE group (identified by [groupId], the hash of the
 * full member list) and distributed to the other members over their
 * pairwise vodozemac sessions. A group message is encrypted with
 * the sender's sender key; recipients decrypt with that member's
 * key for the group.
 *
 * The primary key is the triple (group, sender, key id): a member
 * re-keys — a new key id, a new generation — when the member list
 * changes (a new group id, so a fresh key) or after an identity
 * change (same group, a new key under a new generation). Old
 * generations are KEPT (up to 3 per sender per group): a message
 * encrypted under an old key that is still in flight must decrypt
 * when it lands. [isCurrent] marks the sender's current key for
 * the group — the one new distributions carry and the one the
 * re-key logic compares generations against.
 *
 * [membersCsv] is the member list the key's distribution carried
 * (canonical addresses, `;`-separated, including the sender and
 * ourselves): the receiver matches an incoming group message to
 * its conversation from this list (the conversation holds one
 * member fewer — ourselves — so the match is the minus-one rule,
 * like the group-MMS path). Every key row for one group carries
 * the same list; the store reads it from any row.
 *
 * [senderCurveKeyB64] is the sender's Curve25519 identity key
 * (base64) as known when the key was stored: after an identity
 * change the sender distributes a FRESH key at generation 1, and
 * without this column the generation check would reject it as
 * older than the pre-change key. A changed identity key accepts
 * any generation (the old keys stay for in-flight decrypt). Null
 * when the peer row held no keys at receipt (defensive — a
 * distribution arrives over a session, so keys are recorded).
 *
 * The key is stored base64'd in SQLCipher: the whole database is
 * encrypted at rest, so no extra pickle wrap is needed (like the
 * per-file media keys — unlike the Olm Account/session pickles,
 * which are Keystore-wrapped because they are the long-lived
 * identity).
 */
@Entity(
    tableName = "e2e_group_keys",
    primaryKeys = ["group_id", "sender_address", "key_id"],
)
data class E2eGroupKeyEntity(
    /** The group id: 32 hex chars (first 16 bytes of the member-list hash). */
    @ColumnInfo(name = "group_id")
    val groupId: String,
    /** The key owner's canonical address (ours for our own key). */
    @ColumnInfo(name = "sender_address")
    val senderAddress: String,
    /** The key id: 16 hex chars, random per key. */
    @ColumnInfo(name = "key_id")
    val keyId: String,
    /** The 32-byte AES-256-GCM sender key, base64. */
    @ColumnInfo(name = "key_b64") val keyB64: String,
    /** The full member list the distribution carried (`;`-separated, canonical). */
    @ColumnInfo(name = "members_csv") val membersCsv: String,
    /** The generation: 1 for a group's first key, +1 per re-key. */
    @ColumnInfo(name = "key_generation") val generation: Int,
    /** The sender's Curve25519 identity key (base64) at receipt; null when unknown. */
    @ColumnInfo(name = "sender_curve_key_b64") val senderCurveKeyB64: String?,
    /** True when this is OUR key for the group. */
    @ColumnInfo(name = "is_own", defaultValue = "0") val isOwn: Boolean = false,
    /** True when this is the sender's current key for the group. */
    @ColumnInfo(name = "is_current", defaultValue = "0") val isCurrent: Boolean = false,
    /** Epoch ms the key was created (ours) or received (theirs). */
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
