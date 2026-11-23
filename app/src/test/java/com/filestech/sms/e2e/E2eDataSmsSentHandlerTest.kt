package com.filestech.sms.e2e

import android.app.Activity
import android.telephony.SmsManager
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * Round 43 — the sent-intent verdict: RESULT_OK records nothing
 * (the send left the phone), any other code records under the
 * kind's failure tag with the result-code name — the same shape
 * as the synchronous send failure, so the two read alike in the
 * error log.
 */
class E2eDataSmsSentHandlerTest {

    private val recorder = mockk<E2eFailureRecorder>(relaxed = true)
    private val handler = E2eDataSmsSentHandler(recorder)

    /** A send the radio accepted needs no trace, for either kind. */
    @Test
    fun `RESULT_OK records nothing for either kind`() {
        handler.handle(Activity.RESULT_OK, DataSmsKind.PROBE)
        handler.handle(Activity.RESULT_OK, DataSmsKind.BUNDLE)

        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    /** A generic failure records e2e-probe-send with the code name and the port. */
    @Test
    fun `generic failure records e2e-probe-send with the code name`() {
        handler.handle(SmsManager.RESULT_ERROR_GENERIC_FAILURE, DataSmsKind.PROBE)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        val recorded = throwable.captured
        assertThat(recorded).isInstanceOf(E2eCryptoException::class.java)
        assertThat(recorded.message).contains("RESULT_ERROR_GENERIC_FAILURE")
        assertThat(recorded.message).contains("47832")
        assertThat(recorded.cause).isNull()
    }

    /** A no-service failure on a bundle records e2e-bundle-send, not e2e-probe-send. */
    @Test
    fun `no-service records e2e-bundle-send with the code name`() {
        handler.handle(SmsManager.RESULT_ERROR_NO_SERVICE, DataSmsKind.BUNDLE)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-bundle-send", capture(throwable)) }
        verify(exactly = 0) { recorder.record("e2e-probe-send", any()) }
        assertThat(throwable.captured.message).contains("RESULT_ERROR_NO_SERVICE")
        assertThat(throwable.captured.message).contains("47832")
    }

    /** Radio-off and null-PDU map to their own names. */
    @Test
    fun `radio-off and null-pdu map to their names`() {
        handler.handle(SmsManager.RESULT_ERROR_RADIO_OFF, DataSmsKind.PROBE)
        handler.handle(SmsManager.RESULT_ERROR_NULL_PDU, DataSmsKind.PROBE)

        val throwables = mutableListOf<Throwable>()
        verify(exactly = 2) { recorder.record("e2e-probe-send", capture(throwables)) }
        assertThat(throwables[0].message).contains("RESULT_ERROR_RADIO_OFF")
        assertThat(throwables[1].message).contains("RESULT_ERROR_NULL_PDU")
    }

    /** An unknown code still records, with the numeric code in the name. */
    @Test
    fun `an unknown code records RESULT_UNKNOWN with the code`() {
        handler.handle(99, DataSmsKind.PROBE)

        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        assertThat(throwable.captured.message).contains("RESULT_UNKNOWN(99)")
    }
}
