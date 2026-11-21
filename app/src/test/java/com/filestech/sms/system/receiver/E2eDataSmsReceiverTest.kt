package com.filestech.sms.system.receiver

import android.provider.Telephony
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.e2e.E2eDataSmsGate
import com.filestech.sms.e2e.E2eHandshakeHandler
import com.filestech.sms.e2e.ParsedDataSms
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The client-mode gate on [E2eDataSmsReceiver], tested through
 * [E2eDataSmsGate] — the receiver delegates every decision to it.
 *
 * The receiver itself cannot be exercised on plain JVM: it is a Hilt
 * entry point, and Hilt weaves an `inject(context)` call into its real
 * `onReceive` — a mocked `Context` has no application component, so
 * calling it under MockK throws `MockKException` (CI run 44935640392:
 * all three tests failed at the `callOriginal()` stub, while the same
 * tests passed locally where no Hilt transform runs). The gate holds
 * the whole decision, so testing it directly covers the behavior.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eDataSmsReceiverTest {

    /**
     * A settings source fixed at [mode], always hydrated. A null mode
     * fails on any read — for the test that must return before the
     * settings are consulted at all.
     */
    private class FakeSettings(private val mode: DeviceMode?) : AppSettingsSource {
        private val value = AppSettings().copy(mode = mode ?: DeviceMode.HUB)
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings =
            mode?.let { AppSettings().copy(mode = it) }
                ?: error("settings must not be read for this action")
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    private fun gate(mode: DeviceMode?, handler: E2eHandshakeHandler) = E2eDataSmsGate(
        Lazy { FakeSettings(mode) },
        Lazy { handler },
        Dispatchers.Unconfined,
    )

    @Test
    fun `client mode drops the handshake packet without parsing it or calling the handler`() =
        runTest {
            val handler = mockk<E2eHandshakeHandler>()
            coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true

            val handled = gate(DeviceMode.CLIENT, handler).handle(
                Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
                null,
            ) { error("the packet must not be parsed in client mode") }

            // The packet is dropped silently: no identity is
            // provisioned, no bundle reply goes out.
            assertThat(handled).isFalse()
            coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
        }

    @Test
    fun `hub mode hands the packet to the handler unchanged`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true
        val data = byteArrayOf(9, 8, 7)

        val handled = gate(DeviceMode.HUB, handler).handle(
            Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION,
            3,
        ) { ParsedDataSms("+33612345678", data) }

        // The fourth argument is `now`, defaulted to the current time
        // at the call site, so it can only be matched with `any()` —
        // an `eq` on the default would compare two different instants
        // (and an `exactly = 0` check with the default could false-pass).
        assertThat(handled).isTrue()
        coVerify(exactly = 1) {
            handler.handleDataSms("+33612345678", data, 3, any())
        }
    }

    @Test
    fun `a non-data-sms intent is ignored before any mode read`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()

        val handled = gate(null, handler).handle(
            "android.intent.action.SMS_RECEIVED",
            null,
        ) { error("the packet must not be parsed for this action") }

        assertThat(handled).isFalse()
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }
}
