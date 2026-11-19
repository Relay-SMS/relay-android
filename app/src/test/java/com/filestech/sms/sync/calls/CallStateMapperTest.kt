package com.filestech.sms.sync.calls

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Live calls — the call-state machine: one event per transition,
 * the ringing number carried onto the active and ended events of
 * the same call, duplicates swallowed.
 */
class CallStateMapperTest {

    @Test fun `ringing emits a ringing event with the number`() {
        val mapper = CallStateMapper()
        assertThat(mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L))
            .isEqualTo(CallEvent.Ringing(number = "+15551234567", timestampMs = 1000L))
    }

    @Test fun `a full incoming call maps ringing active ended with the same number`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 2000L))
            .isEqualTo(CallEvent.Active(number = "+15551234567", timestampMs = 2000L))
        assertThat(mapper.onState(TelephonyState.IDLE, "", 3000L))
            .isEqualTo(CallEvent.Ended(number = "+15551234567", timestampMs = 3000L))
    }

    @Test fun `a missed call ends with the ringing number`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        assertThat(mapper.onState(TelephonyState.IDLE, "", 2000L))
            .isEqualTo(CallEvent.Ended(number = "+15551234567", timestampMs = 2000L))
    }

    @Test fun `an outgoing call dials with no number`() {
        val mapper = CallStateMapper()
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 1000L))
            .isEqualTo(CallEvent.Dialing(number = "", timestampMs = 1000L))
        assertThat(mapper.onState(TelephonyState.IDLE, "", 2000L))
            .isEqualTo(CallEvent.Ended(number = "", timestampMs = 2000L))
    }

    @Test fun `ringing with an empty number still rings (no READ_CALL_LOG)`() {
        val mapper = CallStateMapper()
        assertThat(mapper.onState(TelephonyState.RINGING, "", 1000L))
            .isEqualTo(CallEvent.Ringing(number = "", timestampMs = 1000L))
    }

    @Test fun `a repeated ringing callback produces no second event`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        assertThat(mapper.onState(TelephonyState.RINGING, "+15551234567", 1500L)).isNull()
    }

    @Test fun `a blank re-delivered number never erases the remembered one`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        mapper.onState(TelephonyState.RINGING, "", 1500L)
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 2000L))
            .isEqualTo(CallEvent.Active(number = "+15551234567", timestampMs = 2000L))
    }

    @Test fun `idle when already idle produces no event`() {
        val mapper = CallStateMapper()
        assertThat(mapper.onState(TelephonyState.IDLE, "", 1000L)).isNull()
    }

    @Test fun `off-hook when already off-hook produces no event`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.OFFHOOK, "", 1000L)
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 2000L)).isNull()
    }

    @Test fun `call waiting rings again with the new caller`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        mapper.onState(TelephonyState.OFFHOOK, "", 2000L)
        assertThat(mapper.onState(TelephonyState.RINGING, "+15550001111", 3000L))
            .isEqualTo(CallEvent.Ringing(number = "+15550001111", timestampMs = 3000L))
    }

    @Test fun `the number does not leak into the next call`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        mapper.onState(TelephonyState.IDLE, "", 2000L)
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 3000L))
            .isEqualTo(CallEvent.Dialing(number = "", timestampMs = 3000L))
    }

    @Test fun `reset forgets the call in progress`() {
        val mapper = CallStateMapper()
        mapper.onState(TelephonyState.RINGING, "+15551234567", 1000L)
        mapper.reset()
        assertThat(mapper.onState(TelephonyState.IDLE, "", 2000L)).isNull()
        assertThat(mapper.onState(TelephonyState.OFFHOOK, "", 3000L))
            .isEqualTo(CallEvent.Dialing(number = "", timestampMs = 3000L))
    }
}
