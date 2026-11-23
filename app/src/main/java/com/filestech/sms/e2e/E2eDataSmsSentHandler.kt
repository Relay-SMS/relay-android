package com.filestech.sms.e2e

import android.app.Activity
import android.telephony.SmsManager
import javax.inject.Inject

/**
 * Round 43 — the sent-intent verdict for a handshake data SMS: the
 * radio's answer to a probe or bundle send, delivered to
 * [E2eDataSmsSentReceiver].
 *
 * The send path used to be fire-and-forget, so a radio that
 * accepted the `sendDataMessage` call and then silently rejected
 * the send was invisible — the probe counted as sent, the budget
 * drained, and nothing ever arrived. The verdict separates that
 * from a carrier loss downstream: a rejection here means the send
 * never left the phone; RESULT_OK means it did, and a later loss
 * is the carrier's (or the peer's receiver).
 *
 * RESULT_OK records nothing — a send that left the phone needs no
 * trace. Any other code records under the kind's failure tag
 * (`e2e-probe-send` / `e2e-bundle-send`, the same tags the
 * synchronous send failure uses) with the result-code name. No
 * address in the log line (design §8.3): the port + the code are
 * enough.
 *
 * Pure decision logic (the [E2eFailureRecorder] is the only
 * dependency) so it is testable on plain JVM — the receiver stays
 * thin glue. Never throws: [E2eFailureRecorder.record] cannot
 * throw, and the mapping is total.
 */
class E2eDataSmsSentHandler @Inject constructor(
    private val recorder: E2eFailureRecorder,
) {

    fun handle(resultCode: Int, kind: DataSmsKind) {
        if (resultCode == Activity.RESULT_OK) return
        val name = resultName(resultCode)
        recorder.record(
            kind.failureTag,
            E2eCryptoException(
                "Data SMS send rejected by radio (port=${E2eHandshake.E2E_DATA_SMS_PORT}): $name",
            ),
        )
    }

    private fun resultName(resultCode: Int): String = when (resultCode) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "RESULT_ERROR_GENERIC_FAILURE"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "RESULT_ERROR_NO_SERVICE"
        SmsManager.RESULT_ERROR_NULL_PDU -> "RESULT_ERROR_NULL_PDU"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "RESULT_ERROR_RADIO_OFF"
        else -> "RESULT_UNKNOWN($resultCode)"
    }
}
