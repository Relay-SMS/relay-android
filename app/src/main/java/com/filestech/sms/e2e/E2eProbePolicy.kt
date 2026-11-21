package com.filestech.sms.e2e

/**
 * Probe eligibility (design §2.4): which conversations may be probed for
 * Relay. A probe costs a segment, so probing is limited to conversations
 * where the other side could plausibly run Relay:
 *
 * - 1:1 conversations only (exactly one address);
 * - the address must be a real phone number — never short codes or
 *   alphanumeric senders (they cannot run Relay);
 * - never a blocked contact.
 *
 * The state machine ([E2ePeerStateMachine]) owns the attempt budget; this
 * file owns only the "is this conversation probeable at all" question.
 *
 * Pure Kotlin, no Android APIs (no `PhoneNumberUtils` — it is a stub on
 * the plain JVM, which would make this untestable there). Addresses are
 * the normalized numbers the app stores, so the check is deliberately
 * narrow: optional `+`, digits, and the formatting characters a stored
 * number may still carry.
 */
object E2eProbePolicy {

    /**
     * Minimum digits for a probeable number. Short codes are 3–6 digits;
     * a real phone number has at least 7. (E.164 caps at 15.)
     */
    const val MIN_PHONE_DIGITS: Int = 7

    /** Maximum digits for a probeable number (E.164). */
    const val MAX_PHONE_DIGITS: Int = 15

    /**
     * Minimum spacing between two bundle replies to the same sender
     * (design §2.3): at most one bundle per sender per day, to protect
     * the one-time-key pool and the bill against probe spam. The caller
     * persists the last-reply time; see [canReplyWithBundle].
     */
    const val MIN_BUNDLE_REPLY_SPACING_MS: Long = 24L * 60 * 60 * 1000

    private val FORMATTING_CHARS: Set<Char> = setOf(' ', '-', '(', ')', '.')

    /**
     * Whether this conversation may be probed: exactly one address, a
     * real phone number, and not blocked.
     */
    fun canProbe(addresses: Collection<String>, isBlocked: Boolean): Boolean =
        !isBlocked && addresses.size == 1 && isProbeableAddress(addresses.first())

    /** Whether this single address could belong to a Relay user. */
    fun isProbeableAddress(address: String): Boolean {
        val stripped = address.filter { it !in FORMATTING_CHARS }
        // removePrefix drops at most one leading '+': a second '+' (or one
        // in the middle) stays in `digits` and fails the digit check below.
        val digits = stripped.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return false
        return digits.length in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS
    }

    /**
     * Whether we may answer this sender with a bundle now: at most one
     * bundle per sender per day. [lastReplyAtMs] is the epoch time of
     * our last bundle reply to them, or null if we never replied.
     */
    fun canReplyWithBundle(lastReplyAtMs: Long?, nowMs: Long): Boolean =
        lastReplyAtMs == null || nowMs - lastReplyAtMs >= MIN_BUNDLE_REPLY_SPACING_MS
}
