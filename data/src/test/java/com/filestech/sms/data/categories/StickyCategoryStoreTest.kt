package com.filestech.sms.data.categories

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 35 — the sticky category store: receive-time filing, the
 * un-stick on reply, and the one-shot backfill.
 *
 * Strict MockK doubles (no Room): an unexpected `setStickyCategory`
 * call throws instead of silently satisfying the store, so the
 * "never stores" cases fail loudly if the guard breaks. Every test
 * ends with a void assertion (Truth / coVerify return Unit) — JUnit
 * silently skips a `@Test` whose `runTest` body returns a value.
 */
class StickyCategoryStoreTest {

    private val conversationDao = mockk<ConversationDao>()
    private val messageDao = mockk<MessageDao>()
    private val store = StickyCategoryStore(conversationDao, messageDao)

    private val sender = PhoneAddress.of("+15551234567")

    private fun conv(
        displayName: String? = null,
        addressesCsv: String = "+15551234567",
        lastMessageAt: Long = 0L,
        stickyCategory: MessageCategory? = null,
    ) = ConversationEntity(
        id = 9L,
        threadId = null,
        addressesCsv = addressesCsv,
        displayName = displayName,
        lastMessageAt = lastMessageAt,
        lastMessagePreview = null,
        stickyCategory = stickyCategory,
    )

    private fun message(id: Long, body: String, date: Long) = MessageEntity(
        id = id,
        conversationId = 9L,
        telephonyUri = null,
        address = "+15551234567",
        body = body,
        type = MessageType.SMS,
        direction = MessageDirection.INCOMING,
        date = date,
        dateSent = null,
        status = MessageStatus.RECEIVED,
    )

    @Test
    fun `incoming promo from a stranger stores promotional`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv()
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) } just Runs

        store.onIncomingMessage(9L, "Limited time offer! Sale ends Sunday!", sender, 1000L)

        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `incoming plain message after promo keeps promotional without a write`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv(stickyCategory = MessageCategory.PROMOTIONAL)

        store.onIncomingMessage(9L, "Hey, just checking in", sender, 1000L)

        // The value is unchanged, so no write happens at all — and the
        // strict mock would throw if the store wrote anyway.
        coVerify(exactly = 0) { conversationDao.setStickyCategory(9L, MessageCategory.PERSONAL) }
    }

    @Test
    fun `incoming otp overwrites promotional`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv(stickyCategory = MessageCategory.PROMOTIONAL)
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.OTP) } just Runs

        store.onIncomingMessage(9L, "Your verification code is 123456", sender, 1000L)

        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.OTP) }
    }

    @Test
    fun `incoming plain message stores personal when nothing is stored`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv()
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.PERSONAL) } just Runs

        store.onIncomingMessage(9L, "Hey, just checking in", sender, 1000L)

        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.PERSONAL) }
    }

    @Test
    fun `incoming message from a contact never stores`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv(displayName = "Sam")

        store.onIncomingMessage(9L, "Limited time offer! Sale ends Sunday!", sender, 1000L)

        coVerify(exactly = 0) { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `incoming message to a group never stores`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv(
            addressesCsv = "+15551234567;+15559876543",
        )

        store.onIncomingMessage(9L, "Limited time offer! Sale ends Sunday!", sender, 1000L)

        coVerify(exactly = 0) { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `stale incoming message does not re-file`() = runTest {
        coEvery { conversationDao.findById(9L) } returns conv(lastMessageAt = 2000L)

        // A late-arriving old promo (sent before the user's reply, which
        // cleared the sticky value) must not re-stick the thread.
        store.onIncomingMessage(9L, "Limited time offer! Sale ends Sunday!", sender, 1000L)

        coVerify(exactly = 0) { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `outgoing message clears the sticky category`() = runTest {
        coEvery { conversationDao.setStickyCategory(9L, null) } just Runs

        store.onOutgoingMessage(9L)

        coVerify { conversationDao.setStickyCategory(9L, null) }
    }

    @Test
    fun `backfill files the hero homes sequence as promotional`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(conv())
        // Newest first: a truly plain follow-up (one that live-classifies
        // Personal), then the pitch that reveals the thread — the backfill
        // must file on the earlier message, not the latest.
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(2L, "Hey, just checking in, let me know what you think", 2000L),
            message(
                1L,
                "Hey! Just passing through and saw 123 Main St. I'm a local investor and " +
                    "reaching out to see if it's available to own? -Hero Homes",
                1000L,
            ),
        )
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) } just Runs

        val stored = store.backfill()

        assertThat(stored).isEqualTo(1)
        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `backfill stores otp when the latest incoming is otp`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(conv())
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(2L, "Your verification code is 123456", 2000L),
            message(1L, "Limited time offer! Sale ends Sunday!", 1000L),
        )
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.OTP) } just Runs

        val stored = store.backfill()

        assertThat(stored).isEqualTo(1)
        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.OTP) }
    }

    @Test
    fun `backfill stores transactions when the latest incoming is a delivery`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(conv())
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(1L, "Your package is out for delivery today.", 1000L),
        )
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.TRANSACTIONS) } just Runs

        val stored = store.backfill()

        assertThat(stored).isEqualTo(1)
        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.TRANSACTIONS) }
    }

    @Test
    fun `backfill stores nothing for an all plain thread`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(conv())
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(2L, "Hey, just checking in", 2000L),
            message(1L, "See you Saturday!", 1000L),
        )

        val stored = store.backfill()

        assertThat(stored).isEqualTo(0)
    }

    @Test
    fun `backfill stores nothing when the window is empty`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(conv())
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns emptyList()

        val stored = store.backfill()

        assertThat(stored).isEqualTo(0)
    }

    @Test
    fun `backfill skips contacts without querying their messages`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(
            conv(displayName = "Sam"),
        )

        val stored = store.backfill()

        assertThat(stored).isEqualTo(0)
        coVerify(exactly = 0) { messageDao.findRecentIncomingSinceLastOutgoing(any(), any()) }
    }

    @Test
    fun `backfill re-examines a thread stored as personal when an older message is now promotional`() = runTest {
        // Spam follow-up — the classifier gained new signals after the
        // first backfill pass, so a thread a plain follow-up filed as
        // Personal may hold an older message that is Promotional under
        // the new rules. The Roku scam (Promotional under the new
        // signals) plus a plain follow-up must re-file Promotional.
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(
            conv(stickyCategory = MessageCategory.PERSONAL),
        )
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(2L, "Hey, just checking in, let me know what you think", 2000L),
            message(
                1L,
                "Dear Customer, Your Roku TV subscription will expire on May 5th. " +
                    "Please call us at 818-937-3504 to continue your services. Thank you",
                1000L,
            ),
        )
        coEvery { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) } just Runs

        val stored = store.backfill()

        assertThat(stored).isEqualTo(1)
        coVerify { conversationDao.setStickyCategory(9L, MessageCategory.PROMOTIONAL) }
    }

    @Test
    fun `backfill leaves an all plain personal stored thread untouched`() = runTest {
        // Re-examination never downgrades: an all-plain thread stored
        // Personal stays Personal, with no write at all.
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(
            conv(stickyCategory = MessageCategory.PERSONAL),
        )
        coEvery { messageDao.findRecentIncomingSinceLastOutgoing(9L, 10) } returns listOf(
            message(2L, "Hey, just checking in", 2000L),
            message(1L, "See you Saturday!", 1000L),
        )

        val stored = store.backfill()

        assertThat(stored).isEqualTo(0)
        // No setStickyCategory stub: the strict mock would throw if
        // the store wrote anyway.
    }

    @Test
    fun `backfill skips conversations already filed promotional`() = runTest {
        coEvery { conversationDao.snapshotOneToOneConversations() } returns listOf(
            conv(stickyCategory = MessageCategory.PROMOTIONAL),
        )

        val stored = store.backfill()

        assertThat(stored).isEqualTo(0)
        coVerify(exactly = 0) { messageDao.findRecentIncomingSinceLastOutgoing(any(), any()) }
    }
}
