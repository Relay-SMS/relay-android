package com.filestech.sms.domain.ownnumber

/**
 * Group-send own-number fix — just-in-time own-number detection
 * before a group MMS send.
 *
 * A group send with a blank "My phone number" (`send.userMsisdn`)
 * has been observed to stick on "sending" on the user's device. The
 * startup auto-fill ([com.filestech.sms.ownnumber.OwnNumberAutoFill])
 * normally closes that gap at launch, but a SIM that was not ready
 * at first launch leaves the field blank afterwards — so the send
 * path re-runs the detection itself, just in time, through this
 * port. The detection is the exact Detect-button path
 * (`SimSelfNumber.ownNumber(null)`), never a second implementation.
 *
 * Implemented in `:app` ([com.filestech.sms.ownnumber.OwnNumberEnsurerImpl]);
 * the use cases that send group MMS
 * ([com.filestech.sms.domain.usecase.EnvoyerMessageUseCase],
 * [com.filestech.sms.domain.usecase.SendVoiceMmsUseCase]) depend on
 * this port, never on the implementation.
 */
interface OwnNumberEnsurer {

    /**
     * Ensures an own number is on file for a group send.
     *
     * Returns `true` when a number is on file afterwards — already
     * set, just detected and persisted, or not applicable (client
     * mode: no SIM, no telephony send; unreadable settings: nothing
     * here can verify the number, so the send proceeds as it did
     * before this guard existed). Returns `false` only when the
     * number is blank AND the just-in-time detection found nothing:
     * the caller fails the send fast with
     * [com.filestech.sms.core.result.AppError.OwnNumberMissing]
     * instead of dispatching into a stuck "sending" row.
     *
     * Never throws except on cancellation: a throwing detector is a
     * failed detection, reported as `false` — never a crash on the
     * send path.
     */
    suspend fun ensureForGroupSend(): Boolean
}
