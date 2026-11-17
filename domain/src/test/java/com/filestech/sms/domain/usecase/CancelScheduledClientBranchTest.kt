package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — the cancel wire branch: in client mode the
 * `cancel_scheduled` frame goes first (an offline cancel keeps the
 * row PENDING — the schedule still lives on the hub); hub mode is
 * unchanged.
 */
class CancelScheduledClientBranchTest {

    private class FakeRepo(rows: List<ScheduledMessage>) : ScheduledMessageRepository {
        private val byId = rows.associateBy { it.id }.toMutableMap()
        val cancelled = mutableListOf<Long>()

        override fun observePending(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override fun observeFailed(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override suspend fun allUnsettled(): List<ScheduledMessage> = byId.values.toList()
        override suspend fun schedule(
            conversationId: Long?,
            addresses: List<PhoneAddress>,
            body: String,
            scheduledAt: Long,
            subId: Int?,
            attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
            clientMessageId: String?,
        ): Outcome<Long> = Outcome.Success(1L)

        override suspend fun findByClientMessageId(clientMessageId: String): ScheduledMessage? =
            byId.values.firstOrNull { it.clientMessageId == clientMessageId }

        override suspend fun findById(id: Long): ScheduledMessage? = byId[id]
        override suspend fun cancel(id: Long): Outcome<Boolean> {
            cancelled += id
            return Outcome.Success(true)
        }

        override suspend fun markSent(id: Long) = Unit
        override suspend fun markFailed(id: Long) = Unit
        override suspend fun rearmPending(id: Long, scheduledAt: Long) = Unit
        override suspend fun delete(id: Long) = Unit
        override suspend fun deleteWithAttachments(id: Long) = Unit
        override suspend fun clearAttachments(id: Long) = Unit
    }

    private class FakeScheduler : ScheduledMessageScheduler {
        val cancelled = mutableListOf<Long>()
        override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) = Unit
        override fun cancel(scheduledMessageId: Long) {
            cancelled += scheduledMessageId
        }
    }

    private fun row(id: Long, key: String?) = ScheduledMessage(
        id = id,
        conversationId = 9L,
        addresses = listOf(PhoneAddress.of("+33600000001")),
        body = "salut",
        scheduledAt = System.currentTimeMillis() + 3_600_000L,
        subId = null,
        state = ScheduledMessage.State.PENDING,
        createdAt = System.currentTimeMillis(),
        clientMessageId = key,
    )

    @Test
    fun `client cancel sends the frame, then cancels locally`() = runBlocking {
        val repo = FakeRepo(listOf(row(5L, "key-5")))
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = CancelScheduledMessageUseCase(repo, FakeScheduler(), gateway)

        val res = useCase(5L)

        assertThat(res).isEqualTo(Outcome.Success(true))
        assertThat(gateway.cancelCalls).containsExactly("key-5")
        assertThat(repo.cancelled).containsExactly(5L).inOrder()
    }

    @Test
    fun `offline client cancel fails and the row stays pending`() = runBlocking {
        val repo = FakeRepo(listOf(row(5L, "key-5")))
        val gateway = FakeClientSendGateway(clientMode = true, cancelResult = false)
        val useCase = CancelScheduledMessageUseCase(repo, FakeScheduler(), gateway)

        val res = useCase(5L)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(gateway.cancelCalls).containsExactly("key-5")
        assertThat(repo.cancelled).isEmpty()
    }

    @Test
    fun `client cancel of a keyless row cancels locally only, no frame`() = runBlocking {
        val repo = FakeRepo(listOf(row(5L, null)))
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = CancelScheduledMessageUseCase(repo, FakeScheduler(), gateway)

        val res = useCase(5L)

        assertThat(res).isEqualTo(Outcome.Success(true))
        assertThat(gateway.cancelCalls).isEmpty()
        assertThat(repo.cancelled).containsExactly(5L).inOrder()
    }

    /** Hub mode unchanged: no frame, local cancel only. */
    @Test
    fun `hub cancel never sends a frame`() = runBlocking {
        val repo = FakeRepo(listOf(row(5L, "key-5")))
        val gateway = FakeClientSendGateway(clientMode = false)
        val useCase = CancelScheduledMessageUseCase(repo, FakeScheduler(), gateway)

        val res = useCase(5L)

        assertThat(res).isEqualTo(Outcome.Success(true))
        assertThat(gateway.cancelCalls).isEmpty()
        assertThat(repo.cancelled).containsExactly(5L).inOrder()
    }
}
