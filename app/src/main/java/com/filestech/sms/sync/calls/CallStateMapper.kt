package com.filestech.sms.sync.calls

/**
 * The pure call-state machine: turns the platform's raw call-state
 * callbacks into [CallEvent]s, one per transition. The platform
 * reports the number only while ringing, so the mapper remembers
 * the last ringing number and carries it onto the ACTIVE and ENDED
 * events of the same call; an outgoing call (idle straight to
 * off-hook) has no number, so its events carry "".
 *
 * Duplicate callbacks for the state the mapper already holds
 * produce no event — the platform may re-deliver a state, and a
 * second RINGING for the same incoming call must not re-announce
 * it. A RINGING that arrives while a call is active (call
 * waiting) IS a transition, so it announces the new caller.
 *
 * Pure Kotlin, no Android imports: the unit tests drive it
 * directly, and [TelephonyCallStateObserver] feeds it the
 * platform callbacks.
 */
class CallStateMapper {

    private var lastState: TelephonyState = TelephonyState.IDLE

    /** The number of the call in progress, "" when unknown. */
    private var lastNumber: String = ""

    /**
     * Feeds one platform callback. Returns the [CallEvent] for the
     * transition, or null when the state did not change.
     * [incomingNumber] is only meaningful while ringing; a blank
     * number there never erases a number already remembered (the
     * platform re-delivers RINGING with an empty number on some
     * devices).
     */
    fun onState(
        state: TelephonyState,
        incomingNumber: String,
        nowMs: Long,
    ): CallEvent? = when (state) {
        TelephonyState.RINGING -> {
            if (incomingNumber.isNotBlank()) lastNumber = incomingNumber
            if (lastState == TelephonyState.RINGING) {
                null
            } else {
                lastState = TelephonyState.RINGING
                CallEvent.Ringing(number = lastNumber, timestampMs = nowMs)
            }
        }
        TelephonyState.OFFHOOK -> {
            if (lastState == TelephonyState.OFFHOOK) {
                null
            } else {
                lastState = TelephonyState.OFFHOOK
                CallEvent.Active(number = lastNumber, timestampMs = nowMs)
            }
        }
        TelephonyState.IDLE -> {
            if (lastState == TelephonyState.IDLE) {
                null
            } else {
                val number = lastNumber
                lastState = TelephonyState.IDLE
                lastNumber = ""
                CallEvent.Ended(number = number, timestampMs = nowMs)
            }
        }
    }

    /** Forgets the call in progress (observer stop). */
    fun reset() {
        lastState = TelephonyState.IDLE
        lastNumber = ""
    }
}
