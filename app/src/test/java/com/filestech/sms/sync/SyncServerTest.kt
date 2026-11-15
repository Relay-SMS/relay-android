package com.filestech.sms.sync

import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The server's default-SMS re-check: [SyncServer.refreshHubStatus]
 * is what drives the desktop's warning banner — called instantly
 * from the default-SMS-change broadcast, and every 30 s by the
 * watchdog as a safety net. The broadcast itself cannot be tested
 * on the JVM (no system to send it), so these tests pin the
 * re-check/broadcast logic: broadcast on a change, silence when
 * nothing changed. The other three constructor dependencies are
 * never touched by the re-check, so relaxed mocks stand in for
 * them.
 */
class SyncServerTest {

    @Test fun `refreshHubStatus broadcasts the first time it is checked`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
    }

    @Test fun `refreshHubStatus stays silent when the role did not change`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
    }

    @Test fun `refreshHubStatus broadcasts again when the role flips`() = runTest {
        val source = FakeSyncMessageSource()
        val server = spyk(serverWith(source))
        server.refreshHubStatus()
        source.isDefaultSms = false
        server.refreshHubStatus()
        coVerify(exactly = 1) { server.broadcastHubStatus(true) }
        coVerify(exactly = 1) { server.broadcastHubStatus(false) }
    }

    private fun serverWith(source: FakeSyncMessageSource): SyncServer = SyncServer(
        store = mockk(),
        tlsProvider = mockk(),
        mdnsAdvertiser = mockk(),
        messageSource = source,
    )
}
