package com.filestech.sms.e2e

import android.content.Context
import android.telephony.SmsManager
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The handshake data-SMS sender: a send the radio refuses is
 * recorded in the local error log (tag `e2e-probe-send`) with
 * the exception's class only — never its message, so a
 * destination address can never leak into the log — and a
 * successful send records nothing.
 *
 * Plain-JVM note: `Build.VERSION.SDK_INT` is 0 under the unit
 * tests, so the sender takes the legacy branch
 * ([SmsManager.getDefault]), statically mocked here.
 */
@Suppress("DEPRECATION")
class E2eDataSmsSenderTest {

    private lateinit var recorder: E2eFailureRecorder
    private lateinit var manager: SmsManager
    private lateinit var sender: E2eDataSmsSenderImpl

    @BeforeEach
    fun setUp() {
        mockkStatic(SmsManager::class)
        val wireFormatter = mockk<PhoneNumberWireFormatter> {
            every { toWireFormat(any(), any()) } returns WIRE_NUMBER
        }
        recorder = mockk(relaxed = true)
        manager = mockk(relaxed = true)
        every { SmsManager.getDefault() } returns manager
        sender = E2eDataSmsSenderImpl(mockk<Context>(), wireFormatter, recorder)
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SmsManager::class)
    }

    @Test
    fun `send failure returns false and records a sanitized probe-send entry`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalStateException("radio off")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        val recorded = throwable.captured
        assertThat(recorded).isInstanceOf(E2eCryptoException::class.java)
        assertThat(recorded.message).contains("IllegalStateException")
        assertThat(recorded.message).contains("47832")
        assertThat(recorded.message).doesNotContain(ADDRESS)
        assertThat(recorded.message).doesNotContain(WIRE_NUMBER)
        assertThat(recorded.cause).isNull()
    }

    @Test
    fun `send failure never records the destination address`() {
        every {
            manager.sendDataMessage(any(), any(), any(), any(), any(), any())
        } throws IllegalArgumentException("Invalid destinationAddress: $WIRE_NUMBER")

        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isFalse()
        val throwable = slot<Throwable>()
        verify(exactly = 1) { recorder.record("e2e-probe-send", capture(throwable)) }
        assertThat(throwable.captured.message).doesNotContain(WIRE_NUMBER)
        assertThat(throwable.captured.message).doesNotContain("Invalid destinationAddress")
        assertThat(throwable.captured.message).contains("IllegalArgumentException")
    }

    @Test
    fun `successful send returns true and records nothing`() {
        val sent = sender.sendDataMessage(
            ADDRESS,
            null,
            E2eHandshake.E2E_DATA_SMS_PORT,
            byteArrayOf(1),
        )

        assertThat(sent).isTrue()
        verify(exactly = 0) { recorder.record(any(), any()) }
    }

    private companion object {
        const val ADDRESS = "5551234567"
        const val WIRE_NUMBER = "+15551234567"
    }
}
