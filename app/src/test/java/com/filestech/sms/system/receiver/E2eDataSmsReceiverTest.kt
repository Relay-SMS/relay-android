package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.e2e.E2eHandshakeHandler
import dagger.Lazy
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * The client-mode gate on [E2eDataSmsReceiver]: a linked device drops
 * handshake data SMS silently instead of provisioning an E2E identity
 * it must never hold (no default-SMS role, no SMS_DELIVER — it cannot
 * do E2E messaging at all). Hub mode hands the packet to the handler
 * unchanged.
 *
 * Plain JVM, no Robolectric: the Telephony lookup is a static mock,
 * and the scope is the test scope so `runCurrent()` drives the
 * launched coroutine to completion deterministically. The receiver
 * itself is a mock running its real `onReceive` (see [receiver]):
 * `BroadcastReceiver()`'s constructor throws `Stub!` on the plain
 * JVM, so no real instance can be built here.
 *
 * Every test ends with a void assertion — JUnit silently skips a `@Test`
 * whose `runTest` body returns a value.
 */
class E2eDataSmsReceiverTest {

    /** A settings source fixed at [mode], always hydrated. */
    private class FakeSettings(mode: DeviceMode) : AppSettingsSource {
        private val value = AppSettings().copy(mode = mode)
        override val flow: Flow<AppSettings> = flowOf(value)
        override val state: StateFlow<AppSettings> = MutableStateFlow(value)
        override suspend fun hydratedOrNull(): AppSettings = value
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    /**
     * A receiver wired by hand (Hilt does this on device): the handler
     * and the settings source are fakes, the scope is the test scope.
     *
     * A mock, not a spy: `BroadcastReceiver()`'s constructor throws
     * `Stub!` on the plain JVM, so no real instance can be built here.
     * MockK allocates the instance without running any constructor;
     * `onReceive` and the four injected-property accessors run their
     * real implementations via `callOriginal()`, and only `goAsync()`
     * is stubbed (it would throw `Stub!` too).
     */
    private fun receiver(
        mode: DeviceMode,
        handler: E2eHandshakeHandler,
        scope: CoroutineScope,
    ): E2eDataSmsReceiver {
        val receiver = mockk<E2eDataSmsReceiver>()
        every { receiver.handshakeHandlerLazy } answers { callOriginal() }
        every { receiver.handshakeHandlerLazy = any() } answers { callOriginal() }
        every { receiver.settingsLazy } answers { callOriginal() }
        every { receiver.settingsLazy = any() } answers { callOriginal() }
        every { receiver.scope } answers { callOriginal() }
        every { receiver.scope = any() } answers { callOriginal() }
        every { receiver.ioDispatcher } answers { callOriginal() }
        every { receiver.ioDispatcher = any() } answers { callOriginal() }
        every { receiver.onReceive(any(), any()) } answers { callOriginal() }
        val pending = mockk<BroadcastReceiver.PendingResult>()
        every { pending.finish() } just Runs
        every { receiver.goAsync() } returns pending
        receiver.handshakeHandlerLazy = Lazy { handler }
        receiver.settingsLazy = Lazy { FakeSettings(mode) }
        receiver.scope = scope
        receiver.ioDispatcher = Dispatchers.Unconfined
        return receiver
    }

    /** An intent carrying one data SMS from [address] with [data] as its user data. */
    private fun dataSmsIntent(address: String, data: ByteArray): Intent {
        val sms = mockk<SmsMessage>()
        every { sms.displayOriginatingAddress } returns address
        every { sms.userData } returns data
        mockkStatic(Telephony.Sms.Intents::class)
        every { Telephony.Sms.Intents.getMessagesFromIntent(any()) } returns arrayOf(sms)
        val intent = mockk<Intent>()
        every { intent.action } returns Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION
        every { intent.getIntExtra(any(), any()) } returns -1
        return intent
    }

    @Test
    fun `client mode drops the handshake packet without calling the handler`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true
        val intent = dataSmsIntent("+33612345678", byteArrayOf(1, 2, 3))

        receiver(DeviceMode.CLIENT, handler, this).onReceive(mockk(), intent)
        runCurrent()

        // The packet is dropped silently: no identity is provisioned,
        // no bundle reply goes out.
        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }

    @Test
    fun `hub mode hands the packet to the handler unchanged`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        coEvery { handler.handleDataSms(any(), any(), any(), any()) } returns true
        val data = byteArrayOf(9, 8, 7)
        val intent = dataSmsIntent("+33612345678", data)

        receiver(DeviceMode.HUB, handler, this).onReceive(mockk(), intent)
        runCurrent()

        // The fourth argument is `now`, defaulted to the current time
        // at the call site, so it can only be matched with `any()` —
        // an `eq` on the default would compare two different instants
        // (and an `exactly = 0` check with the default could false-pass).
        coVerify(exactly = 1) {
            handler.handleDataSms("+33612345678", data, null, any())
        }
    }

    @Test
    fun `a non-data-sms intent is ignored before any mode read`() = runTest {
        val handler = mockk<E2eHandshakeHandler>()
        val intent = mockk<Intent>()
        every { intent.action } returns "android.intent.action.SMS_RECEIVED"

        receiver(DeviceMode.HUB, handler, this).onReceive(mockk(), intent)
        runCurrent()

        coVerify(exactly = 0) { handler.handleDataSms(any(), any(), any(), any()) }
    }
}
