package com.filestech.sms.sync.client

/**
 * Client mode — the phase of one live call, as the UI sees it. The
 * phone reports every transition (`incoming_call` / `call_state`);
 * the tablet shows the call and can text or dial back — it never
 * answers or ends a call (a linked device is not the default
 * dialer, and there is no public API for answering or for call
 * audio on a linked device).
 */
enum class ClientCallPhase {
    /** The phone is ringing (an incoming call). */
    RINGING,

    /** This device asked the hub to dial; the phone has not answered yet. */
    DIALING,

    /** The call is off-hook (answered, or a dial that landed). */
    ACTIVE,
}

/**
 * Client mode — one live call: the number the hub reported ("" when
 * the hub could not report one — a withheld number, or a phone
 * without the call-log permission), which way the call goes, and
 * its phase. Null in [ClientSyncUiState] when no call is live.
 *
 * The engine owns this: `incoming_call` / `call_state` `ringing`
 * set it, `call_state` `active` / `dialing` moves it off-hook,
 * `call_state` `ended` (or a dropped session) clears it, and a dial
 * this device asked for sets it to [ClientCallPhase.DIALING] until
 * the phone's `call_state` follows.
 */
data class ClientCallState(
    val number: String,
    val incoming: Boolean,
    val phase: ClientCallPhase,
)
