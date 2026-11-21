package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.e2e.E2eSendGate
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.SendErrorCode
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.domain.sender.SmsSender
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * E2E retry (design §3.4): retrying a failed ENCRYPTED message re-sends
 * the full fragment set with the same message id — through the gate,
 * never through the plain sender. A failed encrypted message is NEVER
 * retried in plain: the mirror holds the plaintext, and a plain retry
 * would leak it on the air.
 *
 * The gate is mocked here: the byte-identical retry (stored ciphertext
 * re-fragmented under the same id) is covered by the send manager's own
 * tests. What this file locks is the USE-CASE side: the dispatch
 * decision, the exact arguments, and the unchanged plain path.
 */
class RetrySendE2eTest {

    private val repo = mockk<ConversationRepository>()
    private val sender = mockk<SmsSender>()
    private val mirror = mockk<OutgoingMessageMirror>()
    private val blocked = mockk<BlockedNumberRepository>()
    private val gate = mockk<E2eSendGate>()

    private fun useCase() = RetrySendUseCase(repo, sender, mirror, blocked, gate)

    private fun ligne(encrypted: Boolean) = Message(
        id = 42L,
        conversationId = 1L,
        address = "+33611111111",
        body = "secret",
        type = Message.Type.SMS,
        direction = Message.Direction.OUTGOING,
        date = 1_700_000_000_000L,
        dateSent = null,
        read = true,
        starred = false,
        status = Message.Status.FAILED,
        errorCode = SendErrorCode.SYNCHRONOUS,
        attachmentsCount = 0,
        subId = 3,
        scheduledAt = null,
        encrypted = encrypted,
    )

    @Test
    fun `an encrypted failure retries through the gate, never plain`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligne(encrypted = true)
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns 2
        coEvery {
            gate.sendEncrypted(42L, "+33611111111", "secret", 1_700_000_000_000L, 3, false, 2)
        } returns Outcome.Success(Unit)

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Success(Unit))
        coVerify(exactly = 1) {
            gate.sendEncrypted(42L, "+33611111111", "secret", 1_700_000_000_000L, 3, false, 2)
        }
        // The plaintext in the mirror must never reach the plain sender.
        coVerify(exactly = 0) { sender.send(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `a plain failure retries exactly as before, gate untouched`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligne(encrypted = false)
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns 2
        every { sender.send(42L, "+33611111111", "secret", 3, any(), attempt = 2) } returns
            Outcome.Success(Unit)

        val issue = useCase().invoke(42L)

        assertThat(issue).isEqualTo(Outcome.Success(Unit))
        coVerify(exactly = 1) { sender.send(42L, "+33611111111", "secret", 3, any(), attempt = 2) }
        coVerify(exactly = 0) {
            gate.sendEncrypted(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `an encrypted gate failure marks the row failed`() = runTest {
        coEvery { repo.findMessageForResend(42L) } returns ligne(encrypted = true)
        coEvery { blocked.isBlocked(any()) } returns false
        coEvery { mirror.resetOutgoingForRetry(42L) } returns 1
        coEvery {
            gate.sendEncrypted(any(), any(), any(), any(), any(), any(), any())
        } returns Outcome.Failure(AppError.Telephony("radio down"))
        coEvery { mirror.updateOutgoingStatus(any(), any(), any(), any()) } returns true

        val issue = useCase().invoke(42L)

        assertThat(issue).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 1) {
            mirror.updateOutgoingStatus(
                42L,
                MessageStatus.FAILED,
                errorCode = SendErrorCode.SYNCHRONOUS,
                attempt = 1,
            )
        }
    }
}
