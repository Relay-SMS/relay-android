package com.filestech.sms.e2e

import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The handshake-SMS branch of [com.filestech.sms.system.receiver
 * .SmsDeliverReceiver], tested through [E2eHandshakeSmsGate] — the
 * receiver delegates every decision to it (same extraction as
 * [E2eDataSmsGate]: the receiver is a Hilt entry point and cannot be
 * exercised on plain JVM).
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eHandshakeSmsGateTest {

    /** A settings source fixed at [mode], always hydrated. */
    private class FakeSettings(private val mode: DeviceMode) : AppSettingsSource {
        private val value = AppSettings().copy(mode = mode)
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun gate(
        mode: DeviceMode,
        reader: TelephonyReader,
        handler: E2eHandshakeHandler,
    ) = E2eHandshakeSmsGate(reader, FakeSettings(mode), handler)

    /**
     * A well-formed handshake SMS body. The handler is mocked, so
     * the packet bytes need not decode — only the size matters for
     * [E2eHandshakeSms.isHandshakeSms].
     */
    private fun handshakeBody(): String =
        E2eHandshakeSms.encode(ByteArray(E2eHandshake.PROBE_SIZE))

    @Test
    fun `hub mode passes processing time to the handler, not the carrier timestamp`() =
        runTest {
            val reader = mockk<TelephonyReader>()
            every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
            val handler = mockk<E2eHandshakeHandler>()
            coEvery { handler.handleSms(any(), any(), any(), any()) } returns true
            // The symptom (round 46): the SMSC clock ran AHEAD of the
            // phone clock, so a carrier timestamp passed as `now`
            // dated the ACTIVATED notice after a message sent later —
            // and the sent message rendered above the notice.
            val carrierTs = System.currentTimeMillis() + CARRIER_SKEW_MS
            val body = handshakeBody()

            val before = System.currentTimeMillis()
            val handled = gate(DeviceMode.HUB, reader, handler)
                .handle(ADDRESS, body, carrierTs, 3)
            val after = System.currentTimeMillis()

            assertThat(handled).isTrue()
            // The inbox row keeps the carrier date — it is a telephony
            // record — but the handler's `now` is processing time.
            verify(exactly = 1) { reader.insertInboxSms(ADDRESS, body, carrierTs, 3) }
            val nowSlot = slot<Long>()
            coVerify(exactly = 1) {
                handler.handleSms(ADDRESS, body, 3, capture(nowSlot))
            }
            assertThat(nowSlot.captured).isAtLeast(before)
            assertThat(nowSlot.captured).isAtMost(after)
        }

    @Test
    fun `a non-handshake text is ignored before any write or handler call`() = runTest {
        val reader = mockk<TelephonyReader>()
        val handler = mockk<E2eHandshakeHandler>()

        val handled = gate(DeviceMode.HUB, reader, handler)
            .handle(ADDRESS, "hello", NOW, null)

        assertThat(handled).isFalse()
        verify(exactly = 0) { reader.insertInboxSms(any(), any(), any(), any()) }
        coVerify(exactly = 0) { handler.handleSms(any(), any(), any(), any()) }
    }

    @Test
    fun `client mode writes the inbox row but never calls the handler`() = runTest {
        val reader = mockk<TelephonyReader>()
        every { reader.insertInboxSms(any(), any(), any(), any()) } returns null
        val handler = mockk<E2eHandshakeHandler>()
        val body = handshakeBody()

        val handled = gate(DeviceMode.CLIENT, reader, handler)
            .handle(ADDRESS, body, NOW, null)

        // Swallowed: written to the system inbox, never a bubble, no
        // identity provisioned.
        assertThat(handled).isTrue()
        verify(exactly = 1) { reader.insertInboxSms(ADDRESS, body, NOW, null) }
        coVerify(exactly = 0) { handler.handleSms(any(), any(), any(), any()) }
    }

    companion object {
        private const val ADDRESS = "+33612345678"
        private const val NOW = 1_800_000_000_000L

        /** How far ahead of the phone clock the SMSC clock runs in the regression test. */
        private const val CARRIER_SKEW_MS = 3_600_000L
    }
}
