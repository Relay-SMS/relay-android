package com.filestech.sms.sync.calls

/**
 * One cellular call-state transition on the hub phone, as the
 * linked desktops see it. Produced by [CallStateMapper] from the
 * raw telephony state; encoded to wire frames by
 * [encodeCallEventFrames]. Pure Kotlin — no Android imports — so
 * the mapping stays unit-testable on the JVM.
 *
 * [number] is the other party's number as the platform reported
 * it. It is empty when the number is unknown: an outgoing call
 * (the platform only reports a number while ringing), a withheld
 * number, or a phone without READ_CALL_LOG (since Android 9 the
 * platform passes an empty number to listeners that lack it —
 * see [TelephonyCallStateObserver]).
 *
 * [timestampMs] is the epoch-millis moment the transition was
 * observed — the `timestamp` of the `incoming_call` frame.
 */
sealed interface CallEvent {
    val number: String
    val timestampMs: Long

    /** The phone started ringing — an incoming call. */
    data class Ringing(
        override val number: String,
        override val timestampMs: Long,
    ) : CallEvent

    /**
     * The phone went off-hook on an outgoing call (idle straight to
     * off-hook) — the dial is out, but the other person has not
     * answered yet. Android reports no answer moment for a dialed
     * call (off-hook covers dialing and talking alike), so the
     * desktops learn the answer from Bluetooth HFP instead; without
     * it they treat the dial as connected, the best available.
     */
    data class Dialing(
        override val number: String,
        override val timestampMs: Long,
    ) : CallEvent

    /** A call went off-hook on an answered incoming call. */
    data class Active(
        override val number: String,
        override val timestampMs: Long,
    ) : CallEvent

    /** The call ended (hung up, missed, or rejected). */
    data class Ended(
        override val number: String,
        override val timestampMs: Long,
    ) : CallEvent
}

/**
 * The three telephony call states, as [CallStateMapper] sees
 * them. The mapper takes this enum — not the raw
 * `TelephonyManager.CALL_STATE_*` ints — so it imports no Android
 * code; [TelephonyCallStateObserver] translates the platform ints
 * (0 idle, 1 ringing, 2 off-hook) at the seam.
 */
enum class TelephonyState {
    IDLE,
    RINGING,
    OFFHOOK,
}
