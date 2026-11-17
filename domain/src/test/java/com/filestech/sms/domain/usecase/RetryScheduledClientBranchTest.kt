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
 * Client mode C3 — the retry wire branch: a client retry is a FRESH
 * schedule (a new key — the hub's dedup answers a re-sent key with
 * the stored row, and a FAILED stored row never fires again); the
 * old row is deleted only once the new one is away. Hub mode is
 * unchanged.
 */
class RetryScheduledClientBranchTest {

    private class FakeRepo(rows: List<ScheduledMessage>) : ScheduledMessageRepository {
        private val byId = rows.associateBy { it.id }.toMutableMap()
        var nextId = 100L
        val deleted = mutableListOf<Long>()
        val rearmed = mutableListOf<Long>()

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
        ): Outcome<Long> {
            val id = nextId++
            byId[id] = ScheduledMessage(
                id = id,
                conversationId = conversationId,
                addresses = addresses,
                body = body,
                scheduledAt = scheduledAt,
                subId = subId,
                state = ScheduledMessage.State.PENDING,
                createdAt = System.currentTimeMillis(),
                clientMessageId = clientMessageId,
            )
            return Outcome.Success(id)
        }

        override suspend fun findByClientMessageId(clientMessageId: String): ScheduledMessage? =
            byId.values.firstOrNull { it.clientMessageId == clientMessageId }

        override suspend fun findById(id: Long): ScheduledMessage? = byId[id]
        override suspend fun cancel(id: Long): Outcome<Boolean> = Outcome.Success(true)
        override suspend fun markSent(id: Long) = Unit
        override suspend fun markFailed(id: Long) = Unit
        override suspend fun rearmPending(id: Long, scheduledAt: Long) {
            rearmed += id
        }

        override suspend fun delete(id: Long) {
            byId.remove(id)
            deleted += id
        }

        override suspend fun deleteWithAttachments(id: Long) = Unit
        override suspend fun clearAttachments(id: Long) = Unit

        fun row(id: Long) = byId[id]
    }

    private class FakeScheduler : ScheduledMessageScheduler {
        val scheduledAt = mutableListOf<Pair<Long, Long>>()
        override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) {
            scheduledAt += scheduledMessageId to epochMillis
        }

        override fun cancel(scheduledMessageId: Long) = Unit
    }

    private fun failedRow(id: Long, key: String?) = ScheduledMessage(
        id = id,
        conversationId = 9L,
        addresses = listOf(PhoneAddress.of("+33600000001")),
        body = "salut",
        scheduledAt = System.currentTimeMillis() - 60_000L,
        subId = null,
        state = ScheduledMessage.State.FAILED,
        createdAt = System.currentTimeMillis(),
        clientMessageId = key,
    )

    private fun useCases(
        repo: FakeRepo,
        scheduler: FakeScheduler,
        gateway: FakeClientSendGateway,
    ): RetryScheduledMessageUseCase {
        val schedule = ScheduleMessageUseCase(repo, scheduler, PassthroughAttachmentStore(), gateway)
        return RetryScheduledMessageUseCase(repo, scheduler, gateway, schedule)
    }

    @Test
    fun `client retry creates a fresh schedule with a new key and deletes the old row`() = runBlocking {
        val repo = FakeRepo(listOf(failedRow(5L, "old-key")))
        val scheduler = FakeScheduler()
        val gateway = FakeClientSendGateway(clientMode = true)
        val before = System.currentTimeMillis()

        val res = useCases(repo, scheduler, gateway).invoke(5L)

        assertThat(res).isEqualTo(Outcome.Success(Unit))
        assertThat(gateway.scheduleCalls).hasSize(1)
        val call = gateway.scheduleCalls.single()
        assertThat(call.clientMessageId).isNotEqualTo("old-key")
        assertThat(call.clientMessageId).isNotEmpty()
        assertThat(call.body).isEqualTo("salut")
        assertThat(call.sendAt).isGreaterThan(before)
        assertThat(call.sendAt).isLessThan(before + 120_000L)
        assertThat(repo.deleted).containsExactly(5L)
        assertThat(repo.row(5L)).isNull()
        assertThat(scheduler.scheduledAt).isEmpty()
    }

    @Test
    fun `client retry of a keyless row fails and keeps the row`() = runBlocking {
        val repo = FakeRepo(listOf(failedRow(5L, null)))
        val gateway = FakeClientSendGateway(clientMode = true)

        val res = useCases(repo, FakeScheduler(), gateway).invoke(5L)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gateway.scheduleCalls).isEmpty()
        assertThat(repo.deleted).isEmpty()
        assertThat(repo.row(5L)).isNotNull()
    }

    @Test
    fun `offline client retry keeps the failed row`() = runBlocking {
        val repo = FakeRepo(listOf(failedRow(5L, "old-key")))
        val gateway = FakeClientSendGateway(clientMode = true, scheduleResult = false)

        val res = useCases(repo, FakeScheduler(), gateway).invoke(5L)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        // The failed row itself is kept; the fresh row the offline
        // schedule just created IS deleted by the schedule path
        // (no wire schedule exists for it), so `deleted` is not
        // empty — it must only not contain the failed row.
        assertThat(repo.deleted).doesNotContain(5L)
        assertThat(repo.row(5L)).isNotNull()
    }

    /** Hub mode unchanged: rearm + local schedule, no frame. */
    @Test
    fun `hub retry rearms and schedules locally, no frame`() = runBlocking {
        val repo = FakeRepo(listOf(failedRow(5L, "old-key")))
        val scheduler = FakeScheduler()
        val gateway = FakeClientSendGateway(clientMode = false)

        val res = useCases(repo, scheduler, gateway).invoke(5L)

        assertThat(res).isEqualTo(Outcome.Success(Unit))
        assertThat(gateway.scheduleCalls).isEmpty()
        assertThat(repo.rearmed).containsExactly(5L)
        assertThat(scheduler.scheduledAt).hasSize(1)
        assertThat(repo.deleted).isEmpty()
    }
}
