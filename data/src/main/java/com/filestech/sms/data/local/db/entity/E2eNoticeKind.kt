package com.filestech.sms.data.local.db.entity

/**
 * E2E encryption (schema v22) — the kind of an in-thread encryption notice
 * (design §6.4). Stored BY NAME in `e2e_notices.kind`, like the sticky category:
 * add entries, never rename them. An unknown name — a row from a future build —
 * reads as [ACTIVATED] instead of crashing the read (the safest fallback: the
 * notice still renders as an encryption event, not as a key-change warning).
 *
 * Notices are never sent to the peer and never backed up — but they
 * DO sync to the user's own linked devices over the encrypted device
 * link (round 42), so a desktop thread shows the same system rows as
 * the phone thread.
 */
enum class E2eNoticeKind {
    /** "Messages are now end-to-end encrypted." — the state flipped to ENCRYPTED. */
    ACTIVATED,

    /** The peer's identity key changed (peer was not verified). */
    KEY_CHANGED,

    /** The peer's identity key changed (peer WAS verified — re-compare the number). */
    KEY_CHANGED_VERIFIED,

    /**
     * Round 41 — a plain SMS text arrived from an ENCRYPTED peer, so
     * the peer lost Relay: "Encryption ended — NAME no longer uses
     * Relay." The peer drops to UNKNOWN and a re-probe goes out.
     */
    DEACTIVATED,

    /**
     * Round 49 — the group's first encrypted message (sent or
     * received): "Messages in this group are now end-to-end
     * encrypted." Groups have no single establishment event (the
     * exchange is distributed across members), so the first
     * encrypted message is the observable moment. Recorded once
     * per conversation.
     */
    GROUP_ACTIVATED,

    /**
     * Round 49 — a member of an encrypted group lost Relay (a plain
     * message arrived from them): "Encryption ended in this group
     * — NAME no longer uses Relay." The group drops to plain until
     * the member re-establishes; the 1:1 thread gets its own
     * DEACTIVATED notice in parallel.
     */
    GROUP_DEACTIVATED,

    /**
     * Round 49 — a group member's identity key changed (they were
     * not verified): "NAME's security code changed. Messages in
     * this group are still end-to-end encrypted." The member
     * distributes a fresh sender key; the 1:1 thread gets its own
     * KEY_CHANGED notice in parallel.
     */
    GROUP_KEY_CHANGED,

    /**
     * Round 49 — a group member's identity key changed (they WERE
     * verified): "NAME's security code changed. NAME was verified
     * — compare the safety number again to confirm it's still
     * them." Verification itself lives in the 1:1 thread; this
     * notice points there.
     */
    GROUP_KEY_CHANGED_VERIFIED,
}
