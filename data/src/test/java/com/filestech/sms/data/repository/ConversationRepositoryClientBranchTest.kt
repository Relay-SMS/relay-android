package com.filestech.sms.data.repository

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.security.AppLockManager
import com.filestech.sms.security.VaultSessionState
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * Client mode C3 — the repository write-back branches: in client
 * mode every action applies optimistically, then sends its frame
 * (and reverts on `ok:false`); the system `content://sms` provider
 * is never touched. Hub mode is unchanged.
 */
class ConversationRepositoryClientBranchTest {

    /** A recording action gateway (the domain test fakes are not visible from this module). */
    private class FakeGateway(
        private val clientMode: Boolean,
        var sendResult: Boolean = true,
        var createResult: Outcome<Long> = Outcome.Success(42L),
    ) : ClientActionGateway {
        val actions = mutableListOf<ClientAction>()
        val reverts = mutableListOf<(suspend () -> Unit)?>()
        val createCalls = mutableListOf<Pair<List<String>, String?>>()

        override suspend fun isClientMode(): Boolean = clientMode

        override suspend fun sendAction(action: ClientAction, revert: (suspend () -> Unit)?): Boolean {
            actions += action
            reverts += revert
            // Mirrors the real sender: a frame that cannot go out
            // runs the revert immediately.
            if (!sendResult) revert?.invoke()
            return sendResult
        }

        override suspend fun createConversation(addresses: List<String>, customName: String?): Outcome<Long> {
            createCalls += addresses to customName
            return createResult
        }

        /** Simulates the hub answering `ok:false` for the last action. */
        suspend fun revertLast() {
            reverts.lastOrNull()?.invoke()
        }
    }

    private fun convRow(
        id: Long = 7L,
        hubId: Long? = 70L,
        pinned: Boolean = false,
        archived: Boolean = false,
        muted: Boolean = false,
        pinnedMessageId: Long? = null,
        pinnedMessageAt: Long? = null,
    ) = ConversationEntity(
        id = id,
        threadId = null,
        addressesCsv = "+33600000001",
        displayName = "Alice",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 2,
        pinned = pinned,
        archived = archived,
        muted = muted,
        pinnedMessageId = pinnedMessageId,
        pinnedMessageAt = pinnedMessageAt,
        hubConversationId = hubId,
    )

    private fun msgRow(
        id: Long = 11L,
        conversationId: Long = 7L,
        reaction: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = conversationId,
        telephonyUri = null,
        address = "+33600000001",
        body = "salut",
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = 1_000L,
        dateSent = null,
        read = false,
        reactionEmoji = reaction,
    )

    private class Harness(
        val conversationDao: ConversationDao,
        val messageDao: MessageDao,
        val telephonyReader: TelephonyReader,
        val notifier: ConversationNotificationCanceller,
        val gateway: FakeGateway,
        val repo: ConversationRepositoryImpl,
    )

    private fun harness(clientMode: Boolean, sendResult: Boolean = true): Harness {
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        val telephonyReader = mockk<TelephonyReader>(relaxed = true)
        val notifier = mockk<ConversationNotificationCanceller>(relaxed = true)
        val gateway = FakeGateway(clientMode = clientMode, sendResult = sendResult)
        val database = mockk<AppDatabase>()
        // Room 2.8's `withTransaction` (verified from the 2.8.5 jar)
        // does NOT consult `inTransaction()` on the database: it
        // resolves the transaction executor, reads the suspending
        // transaction ThreadLocal, then runs
        // begin/setSuccessful/end around the block on the executor.
        // A strict mock needs all five stubbed, or the first
        // transaction path throws MockKException. The direct executor
        // runs the block on the calling thread.
        every { database.transactionExecutor } returns Executor { it.run() }
        every { database.suspendingTransactionContext } returns ThreadLocal()
        every { database.beginTransaction() } just Runs
        every { database.setTransactionSuccessful() } just Runs
        every { database.endTransaction() } just Runs
        val repo = ConversationRepositoryImpl(
            database = database,
            conversationDao = conversationDao,
            messageDao = messageDao,
            attachmentDao = mockk(relaxed = true),
            appLock = mockk<AppLockManager>(relaxed = true),
            vaultSession = VaultSessionState(),
            blockedRepo = mockk(relaxed = true),
            notifier = notifier,
            contactRepo = mockk(relaxed = true),
            telephonyReader = telephonyReader,
            phoneIdentity = mockk(relaxed = true),
            eraser = mockk(relaxed = true),
            io = Dispatchers.Unconfined,
            clientActions = gateway,
        )
        return Harness(conversationDao, messageDao, telephonyReader, notifier, gateway, repo)
    }

    @Test
    fun `client markRead marks locally, sends mark_read, never touches the system provider`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.messageDao.findUnreadIds(7L) } returns listOf(11L, 12L)

        h.repo.markRead(7L)

        coVerify { h.messageDao.markConversationRead(7L) }
        coVerify { h.conversationDao.clearUnread(7L) }
        assertThat(h.gateway.actions).containsExactly(ClientAction.MarkRead(7L))
        verify(exactly = 0) { h.telephonyReader.markConversationReadInSystem(any()) }
        verify { h.notifier.cancelAllForConversation(7L) }
    }

    @Test
    fun `client markRead refused by the hub restores the exact unread set`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.messageDao.findUnreadIds(7L) } returns listOf(11L, 12L)

        h.repo.markRead(7L)
        h.gateway.revertLast()

        coVerify { h.messageDao.setRead(11L, false) }
        coVerify { h.messageDao.setRead(12L, false) }
        coVerify { h.conversationDao.recomputeUnreadCount(7L) }
    }

    @Test
    fun `offline client markRead reverts immediately`() = runTest {
        val h = harness(clientMode = true, sendResult = false)
        coEvery { h.messageDao.findUnreadIds(7L) } returns listOf(11L)

        h.repo.markRead(7L)

        coVerify { h.messageDao.setRead(11L, false) }
        coVerify { h.conversationDao.recomputeUnreadCount(7L) }
    }

    @Test
    fun `hub markUnread flips the latest incoming message and recomputes`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.messageDao.findLatestIncomingId(7L) } returns 11L

        h.repo.markUnread(7L)

        coVerify { h.messageDao.setRead(11L, false) }
        coVerify { h.conversationDao.recomputeUnreadCount(7L) }
        coVerify(exactly = 0) { h.conversationDao.setUnreadCount(any(), any()) }
        verify(exactly = 0) { h.notifier.cancelAllForConversation(any()) }
    }

    @Test
    fun `hub markUnread with no incoming message sets the count directly`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.messageDao.findLatestIncomingId(7L) } returns null

        h.repo.markUnread(7L)

        coVerify { h.conversationDao.setUnreadCount(7L, 1) }
        coVerify(exactly = 0) { h.messageDao.setRead(any(), any()) }
        coVerify(exactly = 0) { h.conversationDao.recomputeUnreadCount(any()) }
    }

    @Test
    fun `client markUnread is local-only and sends no frame`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.messageDao.findLatestIncomingId(7L) } returns 11L

        h.repo.markUnread(7L)

        coVerify { h.messageDao.setRead(11L, false) }
        coVerify { h.conversationDao.recomputeUnreadCount(7L) }
        assertThat(h.gateway.actions).isEmpty()
        verify(exactly = 0) { h.telephonyReader.markConversationReadInSystem(any()) }
    }

    @Test
    fun `markUnread then markRead clears the badge again`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.messageDao.findLatestIncomingId(7L) } returns 11L

        h.repo.markUnread(7L)
        h.repo.markRead(7L)

        coVerify { h.messageDao.setRead(11L, false) }
        coVerify { h.messageDao.markConversationRead(7L) }
        coVerify { h.conversationDao.clearUnread(7L) }
    }

    @Test
    fun `client markAllRead sends mark_all_read, no system propagation`() = runTest {
        val h = harness(clientMode = true)

        h.repo.markAllRead()

        assertThat(h.gateway.actions).containsExactly(ClientAction.MarkAllRead)
        coVerify { h.messageDao.markAllIncomingAsRead() }
        verify(exactly = 0) { h.telephonyReader.markAllIncomingReadInSystem() }
    }

    @Test
    fun `client setPinned sends set_pin and reverts on refusal`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.conversationDao.findById(7L) } returns convRow(pinned = false)

        h.repo.setPinned(7L, true)
        assertThat(h.gateway.actions).containsExactly(ClientAction.SetPin(7L, true))
        coVerify { h.conversationDao.setPinned(7L, true) }

        h.gateway.revertLast()
        coVerify { h.conversationDao.setPinned(7L, false) }
    }

    @Test
    fun `client setArchived and setMuted send their frames`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.conversationDao.findById(7L) } returns convRow()

        h.repo.setArchived(7L, true)
        h.repo.setMuted(7L, true)

        assertThat(h.gateway.actions).containsExactly(
            ClientAction.SetArchived(7L, true),
            ClientAction.SetMuted(7L, true),
        ).inOrder()
    }

    @Test
    fun `client setReaction sends set_reaction with local ids and returns the transition`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.messageDao.findById(11L) } returns msgRow(reaction = null)

        val res = h.repo.setReaction(11L, "❤️")

        assertThat(res).isEqualTo(SetReactionResult.First(11L, "❤️"))
        assertThat(h.gateway.actions).containsExactly(ClientAction.SetReaction(7L, 11L, "❤️"))
        coVerify { h.messageDao.setReaction(11L, "❤️") }
    }

    @Test
    fun `offline client setReaction reverts the badge and returns Noop`() = runTest {
        val h = harness(clientMode = true, sendResult = false)
        coEvery { h.messageDao.findById(11L) } returns msgRow(reaction = null)

        val res = h.repo.setReaction(11L, "❤️")

        assertThat(res).isEqualTo(SetReactionResult.Noop)
        // The badge went on, then the revert took it back off.
        coVerify { h.messageDao.setReaction(11L, "❤️") }
        coVerify { h.messageDao.setReaction(11L, null) }
    }

    @Test
    fun `client setReaction refused by the hub restores the previous emoji`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.messageDao.findById(11L) } returns msgRow(reaction = "👍")

        val res = h.repo.setReaction(11L, "❤️")
        assertThat(res).isEqualTo(SetReactionResult.Changed("👍", "❤️"))

        h.gateway.revertLast()
        coVerify { h.messageDao.setReaction(11L, "👍") }
    }

    @Test
    fun `client setPinnedMessage sends the frame, unpin sends a null id`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.conversationDao.findById(7L) } returns convRow()

        h.repo.setPinnedMessage(7L, 11L, 5_000L)
        h.repo.setPinnedMessage(7L, null, null)

        assertThat(h.gateway.actions).containsExactly(
            ClientAction.SetPinnedMessage(7L, 11L),
            ClientAction.SetPinnedMessage(7L, null),
        ).inOrder()
    }

    @Test
    fun `client findOrCreate returns a local conversation without a frame`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.conversationDao.findByAddressesCsv(any()) } returns convRow()

        val res = h.repo.findOrCreate(listOf(PhoneAddress.of("+33600000001")))

        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat((res as Outcome.Success).value.id).isEqualTo(7L)
        assertThat(h.gateway.createCalls).isEmpty()
    }

    @Test
    fun `client findOrCreate creates via wire when absent`() = runTest {
        val h = harness(clientMode = true)
        coEvery { h.conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { h.conversationDao.snapshotOneToOneConversations() } returns emptyList()
        coEvery { h.conversationDao.findById(42L) } returns convRow(id = 42L)

        val res = h.repo.findOrCreate(listOf(PhoneAddress.of("+33600000001")))

        assertThat(h.gateway.createCalls).containsExactly(listOf("+33600000001") to null)
        assertThat(res).isInstanceOf(Outcome.Success::class.java)
        assertThat((res as Outcome.Success).value.id).isEqualTo(42L)
    }

    @Test
    fun `offline client findOrCreate fails without creating anything`() = runTest {
        val h = harness(clientMode = true)
        h.gateway.createResult = Outcome.Failure(AppError.ClientOffline)
        coEvery { h.conversationDao.findByAddressesCsv(any()) } returns null
        coEvery { h.conversationDao.snapshotOneToOneConversations() } returns emptyList()

        val res = h.repo.findOrCreate(listOf(PhoneAddress.of("+33600000001")))

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        coVerify(exactly = 0) { h.conversationDao.insert(any<ConversationEntity>()) }
    }

    /** Hub mode unchanged: markRead propagates to the system and sends no frame. */
    @Test
    fun `hub markRead propagates to the system and sends no frame`() = runTest {
        val h = harness(clientMode = false)
        coEvery { h.conversationDao.findThreadIdById(7L) } returns 77L

        h.repo.markRead(7L)

        assertThat(h.gateway.actions).isEmpty()
        verify { h.telephonyReader.markConversationReadInSystem(77L) }
    }

    /** Hub mode unchanged: setPinned writes locally and sends no frame. */
    @Test
    fun `hub setPinned sends no frame`() = runTest {
        val h = harness(clientMode = false)

        h.repo.setPinned(7L, true)

        assertThat(h.gateway.actions).isEmpty()
        coVerify { h.conversationDao.setPinned(7L, true) }
    }
}
