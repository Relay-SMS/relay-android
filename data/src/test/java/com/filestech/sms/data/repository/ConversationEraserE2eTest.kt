package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.E2eNoticeDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.sms.SystemCopyEraser
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.scheduler.ScheduledMessageScheduler
import com.filestech.sms.security.VaultPurgeBarrier
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * E2E eraser wiring: a conversation's encryption notices leave with it,
 * in the same final transaction as the conversation row itself.
 *
 * The eraser's other collaborators are relaxed mocks — this test locks
 * the notice wiring, not the whole deletion lifecycle (an empty
 * conversation, ordinary mode, nothing to sweep).
 */
class ConversationEraserE2eTest {

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>(relaxed = true)
    private val messageDao = mockk<MessageDao>(relaxed = true)
    private val noticeDao = mockk<E2eNoticeDao>()

    private fun eraser() = ConversationEraser(
        database = dbWithTransaction(),
        conversationDao = conversationDao,
        messageDao = messageDao,
        systemCopy = mockk<SystemCopyEraser>(relaxed = true),
        scheduledDao = mockk<ScheduledMessageDao>(relaxed = true),
        scheduler = mockk<ScheduledMessageScheduler>(relaxed = true),
        attachmentDao = mockk<AttachmentDao>(relaxed = true),
        fichiers = mockk<FichiersDePiecesJointes>(relaxed = true),
        barriere = mockk<VaultPurgeBarrier>(relaxed = true),
        notifications = mockk<ConversationNotificationCanceller>(relaxed = true),
        noticeDao = noticeDao,
    )

    /**
     * Room 2.8's `withTransaction` does NOT consult `inTransaction()`:
     * it resolves the transaction executor, reads the suspending
     * transaction ThreadLocal, then runs begin/setSuccessful/end
     * around the block on the executor. All five stubs, or the first
     * transaction path throws MockKException.
     */
    private fun dbWithTransaction(): AppDatabase {
        every { database.transactionExecutor } returns Executor { it.run() }
        every { database.suspendingTransactionContext } returns ThreadLocal()
        every { database.beginTransaction() } just Runs
        every { database.setTransactionSuccessful() } just Runs
        every { database.endTransaction() } just Runs
        return database
    }

    @Test
    fun `erasing a conversation deletes its encryption notices`() = runTest {
        coEvery { noticeDao.deleteForConversation(11L) } returns 2
        coEvery { messageDao.findByConversation(11L) } returns emptyList()
        coEvery { messageDao.findTransactionKeysForConversation(11L) } returns emptyList()

        val issue = eraser().erase(11L)

        assertThat(issue.conservee).isFalse()
        coVerify(exactly = 1) { noticeDao.deleteForConversation(11L) }
        coVerify(exactly = 1) { conversationDao.delete(11L) }
    }
}
