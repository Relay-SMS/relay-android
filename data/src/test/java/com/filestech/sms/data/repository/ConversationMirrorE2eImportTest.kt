package com.filestech.sms.data.repository

import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.ScheduledMessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.Executor

/**
 * E2E bulk-import skip (design §3.5): ciphertext fragments sitting in
 * the system store are the wire form of an encrypted message, not
 * messages — a re-import (fresh install, resync) must never surface
 * `RLY1:` gibberish as text bubbles, and must never let a fragment
 * become the conversation preview.
 *
 * Strict MockK doubles (no Room) + the Room 2.8 `withTransaction`
 * stubs (see [ConversationRepositoryClientBranchTest]): the import
 * runs inside one transaction.
 */
class ConversationMirrorE2eImportTest {

    private companion object {
        const val ALICE = "+33612345678"
        const val THREAD_ID = 5L
        const val CONV_ID = 9L

        // A full-length handshake probe text (101 chars): the `RLYH:`
        // magic + the base64 of 70 zero bytes (94 `A`s + `==`).
        const val HANDSHAKE_PROBE_TEXT: String =
            "RLYH:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=="
    }

    private val database = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val contacts = mockk<ContactRepository>()

    private fun mirror() = ConversationMirror(
        database = dbWithTransaction(),
        conversationDao = conversationDao,
        messageDao = messageDao,
        scheduledMessageDao = mockk<ScheduledMessageDao>(),
        attachmentDao = mockk<AttachmentDao>(),
        contacts = contacts,
        phoneIdentity = mockk<PhoneIdentity>(),
        notifications = mockk<ConversationNotificationCanceller>(),
        io = Dispatchers.Unconfined,
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

    private fun convEntity() = ConversationEntity(
        id = CONV_ID,
        threadId = THREAD_ID,
        addressesCsv = ALICE,
        displayName = "Alice",
        lastMessageAt = 0L,
        lastMessagePreview = null,
    )

    private fun row(
        body: String,
        date: Long,
        direction: MessageDirection = MessageDirection.INCOMING,
        uri: String = "content://sms/$date",
    ) = MessageEntity(
        // `conversationId` carries the SYSTEM thread id on import rows
        // (set by TelephonyReader before we know our local convId).
        conversationId = THREAD_ID,
        telephonyUri = uri,
        address = ALICE,
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
    )

    private fun stubConversation() {
        coEvery { contacts.lookupByPhone(any()) } returns null
        coEvery { conversationDao.findByThreadId(THREAD_ID) } returns convEntity()
        coEvery { conversationDao.findById(CONV_ID) } returns convEntity()
        coEvery { conversationDao.update(any()) } just Runs
        coEvery { messageDao.insertAll(any()) } answers { List(firstArg<List<*>>().size) { it + 1L } }
    }

    @Test
    fun `fragments are skipped and plain rows import`() = runTest {
        stubConversation()
        val inserted = slot<List<MessageEntity>>()
        coEvery { messageDao.insertAll(capture(inserted)) } answers {
            List(firstArg<List<*>>().size) { it + 1L }
        }

        mirror().bulkImportFromTelephony(
            listOf(
                row("hello", date = 1000L),
                row("RLY1:abcdef:01/02:QUJDREVGR0g=", date = 1500L),
                row("RLY1:abcdef:02/02:S0tMTU5PUFF=", date = 1600L),
                row("reply", date = 2000L, direction = MessageDirection.OUTGOING),
            ),
        )

        assertThat(inserted.isCaptured).isTrue()
        assertThat(inserted.captured.map { it.body }).containsExactly("hello", "reply")
        assertThat(inserted.captured.all { it.conversationId == CONV_ID }).isTrue()
    }

    @Test
    fun `a newer fragment never becomes the conversation preview`() = runTest {
        stubConversation()
        val touched = slot<ConversationEntity>()
        coEvery { conversationDao.update(capture(touched)) } just Runs

        mirror().bulkImportFromTelephony(
            listOf(
                row("hello", date = 1000L),
                // The fragment is the NEWEST row in the group: without
                // the skip it would win the preview via `maxBy date`.
                row("RLY1:abcdef:01/01:QUJDREVGR0g=", date = 3000L),
            ),
        )

        assertThat(touched.isCaptured).isTrue()
        assertThat(touched.captured.lastMessagePreview).isEqualTo("hello")
        assertThat(touched.captured.lastMessageAt).isEqualTo(1000L)
    }

    @Test
    fun `an all-fragment group creates no conversation`() = runTest {
        stubConversation()

        mirror().bulkImportFromTelephony(
            listOf(
                row("RLY1:abcdef:01/02:QUJDREVGR0g=", date = 1000L),
                row("RLY1:abcdef:02/02:S0tMTU5PUFF=", date = 1100L),
            ),
        )

        coVerify(exactly = 0) { conversationDao.findByThreadId(any()) }
        coVerify(exactly = 0) { messageDao.insertAll(any()) }
        coVerify(exactly = 0) { conversationDao.update(any()) }
    }

    // ──────────────────── Handshake SMS (round 45) ────────────────────
    // A `RLYH:` text is the wire form of a probe or bundle, handled
    // live by the handshake path — never a bubble. The literal here
    // mirrors the `:app` magic, like the `RLY1:` literals above.

    @Test
    fun `handshake SMS are skipped and plain rows import`() = runTest {
        stubConversation()
        val inserted = slot<List<MessageEntity>>()
        coEvery { messageDao.insertAll(capture(inserted)) } answers {
            List(firstArg<List<*>>().size) { it + 1L }
        }

        mirror().bulkImportFromTelephony(
            listOf(
                row("hello", date = 1000L),
                row(HANDSHAKE_PROBE_TEXT, date = 1500L),
                row("reply", date = 2000L, direction = MessageDirection.OUTGOING),
            ),
        )

        assertThat(inserted.isCaptured).isTrue()
        assertThat(inserted.captured.map { it.body }).containsExactly("hello", "reply")
        assertThat(inserted.captured.all { it.conversationId == CONV_ID }).isTrue()
    }

    @Test
    fun `a newer handshake SMS never becomes the conversation preview`() = runTest {
        stubConversation()
        val touched = slot<ConversationEntity>()
        coEvery { conversationDao.update(capture(touched)) } just Runs

        mirror().bulkImportFromTelephony(
            listOf(
                row("hello", date = 1000L),
                // The handshake SMS is the NEWEST row in the group:
                // without the skip it would win the preview via
                // `maxBy date`.
                row(HANDSHAKE_PROBE_TEXT, date = 3000L),
            ),
        )

        assertThat(touched.isCaptured).isTrue()
        assertThat(touched.captured.lastMessagePreview).isEqualTo("hello")
        assertThat(touched.captured.lastMessageAt).isEqualTo(1000L)
    }

    @Test
    fun `an all-handshake group creates no conversation`() = runTest {
        stubConversation()

        mirror().bulkImportFromTelephony(
            listOf(
                row(HANDSHAKE_PROBE_TEXT, date = 1000L),
                row(HANDSHAKE_PROBE_TEXT, date = 1100L),
            ),
        )

        coVerify(exactly = 0) { conversationDao.findByThreadId(any()) }
        coVerify(exactly = 0) { messageDao.insertAll(any()) }
        coVerify(exactly = 0) { conversationDao.update(any()) }
    }
}
