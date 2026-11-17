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
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Client mode C3 — the schedule wire branch: in client mode the
 * schedule lives on the hub phone (a `schedule_message` frame + a
 * local record, never armed locally); hub mode is unchanged.
 */
class ScheduleMessageClientBranchTest {

    private class FakeRepo : ScheduledMessageRepository {
        val rows = mutableMapOf<Long, ScheduledMessage>()
        var nextId = 1L
        val deleted = mutableListOf<Long>()

        override fun observePending(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override fun observeFailed(): Flow<List<ScheduledMessage>> = flowOf(emptyList())
        override suspend fun allUnsettled(): List<ScheduledMessage> = rows.values.toList()
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
            rows[id] = ScheduledMessage(
                id = id,
                conversationId = conversationId,
                addresses = addresses,
                body = body,
                scheduledAt = scheduledAt,
                subId = subId,
                state = ScheduledMessage.State.PENDING,
                createdAt = System.currentTimeMillis(),
                attachments = attachments,
                clientMessageId = clientMessageId,
            )
            return Outcome.Success(id)
        }

        override suspend fun findByClientMessageId(clientMessageId: String): ScheduledMessage? =
            rows.values.firstOrNull { it.clientMessageId == clientMessageId }

        override suspend fun findById(id: Long): ScheduledMessage? = rows[id]
        override suspend fun cancel(id: Long): Outcome<Boolean> = Outcome.Success(true)
        override suspend fun markSent(id: Long) = Unit
        override suspend fun markFailed(id: Long) = Unit
        override suspend fun rearmPending(id: Long, scheduledAt: Long) = Unit
        override suspend fun delete(id: Long) {
            rows.remove(id)
            deleted += id
        }

        override suspend fun deleteWithAttachments(id: Long) = Unit
        override suspend fun clearAttachments(id: Long) = Unit
    }

    private class FakeScheduler : ScheduledMessageScheduler {
        val scheduledAt = mutableListOf<Pair<Long, Long>>()
        override fun scheduleAt(scheduledMessageId: Long, epochMillis: Long) {
            scheduledAt += scheduledMessageId to epochMillis
        }

        override fun cancel(scheduledMessageId: Long) = Unit
    }

    private val seul = listOf(PhoneAddress.of("+33600000001"))
    private val future = System.currentTimeMillis() + 3_600_000L

    @Test
    fun `client schedule sends the wire frame with a fresh key and never arms locally`() = runBlocking {
        val repo = FakeRepo()
        val scheduler = FakeScheduler()
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = ScheduleMessageUseCase(repo, scheduler, PassthroughAttachmentStore(), gateway)

        val res = useCase(9L, seul, "salut plus tard", future, null)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        val rowId = (res as Outcome.Success).value
        assertThat(gateway.scheduleCalls).hasSize(1)
        val call = gateway.scheduleCalls.single()
        assertThat(call.localConversationId).isEqualTo(9L)
        assertThat(call.body).isEqualTo("salut plus tard")
        assertThat(call.sendAt).isEqualTo(future)
        assertThat(call.clientMessageId).isNotEmpty()
        assertThat(repo.rows[rowId]?.clientMessageId).isEqualTo(call.clientMessageId)
        assertThat(scheduler.scheduledAt).isEmpty()
    }

    @Test
    fun `client schedule passes a given key through`() = runBlocking {
        val repo = FakeRepo()
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = ScheduleMessageUseCase(repo, FakeScheduler(), PassthroughAttachmentStore(), gateway)

        useCase(9L, seul, "salut", future, null, clientMessageId = "sched-key-1")

        assertThat(gateway.scheduleCalls.single().clientMessageId).isEqualTo("sched-key-1")
    }

    @Test
    fun `offline client schedule deletes the local row and fails`() = runBlocking {
        val repo = FakeRepo()
        val gateway = FakeClientSendGateway(clientMode = true, scheduleResult = false)
        val useCase = ScheduleMessageUseCase(repo, FakeScheduler(), PassthroughAttachmentStore(), gateway)

        val res = useCase(9L, seul, "salut", future, null)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(repo.rows).isEmpty()
        assertThat(repo.deleted).hasSize(1)
    }

    @Test
    fun `client schedule with attachments fails cleanly, no row, no frame`(@TempDir dir: File) = runBlocking {
        val repo = FakeRepo()
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = ScheduleMessageUseCase(repo, FakeScheduler(), PassthroughAttachmentStore(), gateway)
        val photo = listOf(
            SendMediaMmsUseCase.AttachmentPayload(
                file = File(dir, "p.jpg").apply { writeBytes(ByteArray(64) { 1 }) },
                mimeType = "image/jpeg",
            ),
        )

        val res = useCase(9L, seul, "", future, null, attachments = photo)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientMediaUnsupported))
        assertThat(repo.rows).isEmpty()
        assertThat(gateway.scheduleCalls).isEmpty()
    }

    @Test
    fun `client schedule with blank body fails, no row, no frame`() = runBlocking {
        val repo = FakeRepo()
        val gateway = FakeClientSendGateway(clientMode = true)
        val useCase = ScheduleMessageUseCase(repo, FakeScheduler(), PassthroughAttachmentStore(), gateway)

        val res = useCase(9L, seul, "   ", future, null)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat(repo.rows).isEmpty()
        assertThat(gateway.scheduleCalls).isEmpty()
    }

    /** Hub mode unchanged: the row is armed locally, no frame. */
    @Test
    fun `hub schedule arms locally and never sends a frame`() = runBlocking {
        val repo = FakeRepo()
        val scheduler = FakeScheduler()
        val gateway = FakeClientSendGateway(clientMode = false)
        val useCase = ScheduleMessageUseCase(repo, scheduler, PassthroughAttachmentStore(), gateway)

        val res = useCase(9L, seul, "salut", future, null)

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat(gateway.scheduleCalls).isEmpty()
        assertThat(scheduler.scheduledAt).hasSize(1)
        assertThat(scheduler.scheduledAt.single().first).isEqualTo((res as Outcome.Success).value)
    }
}
