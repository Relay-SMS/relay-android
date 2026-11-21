package com.filestech.sms.data.local.db.entity

/**
 * E2E encryption (schema v22) — one peer's place in the key-exchange state machine
 * (design §4.3). Stored BY NAME in `e2e_peers.state`, like the sticky category
 * (see [com.filestech.sms.data.local.db.MessageEnumConverters]): add entries, never
 * rename them. An unknown name — a row from a future build — reads as [UNKNOWN]
 * instead of crashing the read.
 *
 * The conversation's encryption state is derived from its single address's peer state
 * (1:1 only — groups have no peer row and stay plain).
 */
enum class E2ePeerState {
    /** Nothing has happened yet: no probe sent, no bundle received. */
    UNKNOWN,

    /** A probe is out (or a bundle reply was sent) and the exchange is in flight. */
    PROBING,

    /** A live session exists: messages to this peer go out encrypted. */
    ENCRYPTED,

    /** The probe budget is exhausted with no reply — the peer does not run Relay (yet). */
    NOT_RELAY,
}
