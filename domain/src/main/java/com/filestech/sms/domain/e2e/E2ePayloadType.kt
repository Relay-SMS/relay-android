package com.filestech.sms.domain.e2e

/**
 * The kind of content inside an encrypted payload (design §3.2), at the
 * domain level. Mirrors the wire payload type without letting `:domain`
 * depend on `:app`: the send gate carries it from the use cases to the
 * send manager, which maps it onto the wire byte.
 *
 * - [TEXT] — an ordinary message.
 * - [REACTION] — the formatted reaction body (the tapback string a plain
 *   reaction SMS would have contained), encrypted so a reaction does not
 *   leak as plain SMS. The receiver decrypts and folds it onto its
 *   target exactly like a plain tapback — no new message row.
 * - [MEDIA_KEY] — round 48: a media key message (the per-file AES key +
 *   metadata for one encrypted MMS attachment, as JSON). Only ever
 *   sent to a media-capable peer — an old build would turn the
 *   unknown wire type into an undecryptable placeholder.
 * - [SENDER_KEY] — round 49: a sender-key distribution (one member's
 *   per-group AES key + the member list, as JSON), sent 1:1 over the
 *   pairwise session to a group-capable peer. Never a message row.
 * - [GROUP_KEY_REQUEST] — round 49: a key request (the group id, as
 *   JSON), sent 1:1 over the pairwise session when a group message
 *   arrives under an unknown key. Never a message row.
 */
enum class E2ePayloadType {
    TEXT,
    REACTION,
    MEDIA_KEY,
    SENDER_KEY,
    GROUP_KEY_REQUEST,
}
