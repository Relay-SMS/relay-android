package com.filestech.sms.data.local.db.entity

/**
 * E2E encryption (schema v22) — the kind of an in-thread encryption notice
 * (design §6.4). Stored BY NAME in `e2e_notices.kind`, like the sticky category:
 * add entries, never rename them. An unknown name — a row from a future build —
 * reads as [ACTIVATED] instead of crashing the read (the safest fallback: the
 * notice still renders as an encryption event, not as a key-change warning).
 *
 * Notices are local-only: never sent, never synced, never backed up.
 */
enum class E2eNoticeKind {
    /** "Messages are now end-to-end encrypted." — the state flipped to ENCRYPTED. */
    ACTIVATED,

    /** The peer's identity key changed (peer was not verified). */
    KEY_CHANGED,

    /** The peer's identity key changed (peer WAS verified — re-compare the number). */
    KEY_CHANGED_VERIFIED,
}
