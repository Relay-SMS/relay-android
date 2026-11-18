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
import com.filestech.sms.domain.reaction.IncomingReactionDecoder.DecodedReaction.Kind
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Own-reaction fold: our sent tapback must fold onto its target on
 * re-import (fresh install) or the sent-box row surfaces as a stray
 * `Liked "…"` bubble. Since our own reactions now send for our own
 * texts too, an own tapback whose target is one of OUR outgoing
 * messages must fold — with their incoming messages still winning
 * when both match.
 *
 * Strict MockK doubles (no Room): the fold only touches the two
 * DAOs, the phone identity, and — for incoming tapbacks — the
 * conversation touch. A strict double called unexpectedly fails the
 * test instead of silently satisfying the fold.
 */
class ConversationMirrorOwnReactionFoldTest {

    private val db = mockk<AppDatabase>()
    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val phoneIdentity = mockk<PhoneIdentity>()

    private fun mirror() = ConversationMirror(
        database = db,
        conversationDao = conversationDao,
        messageDao = messageDao,
        scheduledMessageDao = mockk<ScheduledMessageDao>(),
        attachmentDao = mockk<AttachmentDao>(),
        contacts = mockk<ContactRepository>(),
        phoneIdentity = phoneIdentity,
        notifications = mockk<ConversationNotificationCanceller>(),
        io = Dispatchers.Unconfined,
    )

    private fun convEntity() = ConversationEntity(
        id = 9L,
        threadId = null,
        addressesCsv = "+33600000001",
        displayName = null,
        lastMessageAt = 0L,
        lastMessagePreview = null,
    )

    private fun messageEntity(
        id: Long,
        direction: MessageDirection,
        body: String,
        date: Long = 1000L,
        reactionEmoji: String? = null,
    ) = MessageEntity(
        id = id,
        conversationId = 9L,
        telephonyUri = null,
        address = "+33600000001",
        body = body,
        type = MessageType.SMS,
        direction = direction,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
        reactionEmoji = reactionEmoji,
    )

    private fun stubConversation() {
        coEvery { phoneIdentity.snapshot() } returns PhoneIdentity.Snapshot("US")
        coEvery { conversationDao.findByAddressesCsv(any()) } returns convEntity()
        coEvery { conversationDao.snapshotGroupConversations() } returns emptyList()
        coEvery { messageDao.setReaction(any(), any()) } just Runs
    }

    @Test
    fun `own tapback folds onto our own outgoing message when no incoming matches`() = runTest {
        stubConversation()
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReaction(42L, "👍") }
    }

    @Test
    fun `own tapback still prefers their incoming message when both match`() = runTest {
        stubConversation()
        val theirs = messageEntity(
            id = 43L,
            direction = MessageDirection.INCOMING,
            body = "salut",
            date = 2000L,
        )
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(theirs, ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(43L)
        coVerify { messageDao.setReaction(43L, "👍") }
    }

    @Test
    fun `own tapback removal clears our own badge`() = runTest {
        stubConversation()
        val ours = messageEntity(
            id = 42L,
            direction = MessageDirection.OUTGOING,
            body = "salut",
            reactionEmoji = "👍",
        )
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isRemoval = true,
            isOwnReaction = true,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReaction(42L, null) }
    }

    @Test
    fun `incoming tapback still matches any direction`() = runTest {
        stubConversation()
        // An incoming tapback bumps the conversation: the touch reads
        // the row back and writes it.
        coEvery { conversationDao.findById(9L) } returns convEntity()
        coEvery { conversationDao.update(any()) } just Runs
        val ours = messageEntity(id = 42L, direction = MessageDirection.OUTGOING, body = "salut")
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns listOf(ours)

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = false,
        )

        assertThat(applied).isNotNull()
        assertThat(applied!!.targetMessageId).isEqualTo(42L)
        coVerify { messageDao.setReaction(42L, "👍") }
    }

    @Test
    fun `own tapback with no match at all returns null`() = runTest {
        stubConversation()
        coEvery { messageDao.findRecentForConversation(9L, any()) } returns emptyList()

        val applied = mirror().applyIncomingReaction(
            address = "+33600000001",
            emoji = "👍",
            bodyPrefix = "salut",
            kind = Kind.Tapback,
            wasTruncated = false,
            isOwnReaction = true,
        )

        assertThat(applied).isNull()
        coVerify(exactly = 0) { messageDao.setReaction(any(), any()) }
    }
}
