package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.R
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedMessage
import com.filestech.sms.system.notifications.ActiveConversationTracker
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Client mode C4 — the notification relay's own gates: only live
 * incoming messages notify, own-send echoes are suppressed by
 * `client_message_id`, unknown conversations and open conversations
 * are skipped, and text-less messages get a media label. The
 * mute / category / preview gates live inside the notifier and are
 * NOT re-tested here; the batch-never-notifies rule is an engine
 * test (the engine never calls the relay for a batch).
 */
class ClientNotificationRelayTest {

    private class Fixture {
        val messageDao = mockk<MessageDao>(relaxed = true)
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val tracker = ActiveConversationTracker()
        val notifier = mockk<IncomingMessageNotifier>(relaxed = true)
        val context = mockk<Context>()

        init {
            coEvery { messageDao.findById(LOCAL_MESSAGE_ID) } returns msgRow()
            coEvery { conversationDao.findById(LOCAL_CONVERSATION_ID) } returns convRow()
            every { context.getString(R.string.client_notif_media_photo) } returns "Photo"
            every { context.getString(R.string.client_notif_media_video) } returns "Video"
            every { context.getString(R.string.client_notif_media_voice) } returns "Voice message"
            every { context.getString(R.string.client_notif_media_audio) } returns "Audio"
            every { context.getString(R.string.client_notif_media_file) } returns "File"
            every { context.getString(R.string.client_notif_media_gif) } returns "GIF"
        }

        val relay = ClientNotificationRelay(
            messageDao,
            conversationDao,
            tracker,
            notifier,
            context,
        )
    }

    @Test fun `incoming text message notifies with the body and local ids`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(wireMessage(), SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true))
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "hello",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `outgoing message never notifies`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(direction = "outgoing"),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify(exactly = 0) { f.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test fun `own-send echo never notifies`() = runTest {
        val f = Fixture()
        coEvery { f.messageDao.findByClientMessageId("key-1") } returns
            msgRow(clientMessageId = "key-1")
        // Incoming on the wire on purpose: the direction gate alone
        // would suppress a real echo (outgoing), so this pins the
        // key check itself — a reconciled row found by its key.
        f.relay.onLiveMessage(
            wireMessage(clientMessageId = "key-1"),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = false),
        )
        coVerify(exactly = 0) { f.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test fun `a keyed message that inserted fresh is not an echo`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(clientMessageId = "key-1"),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "hello",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `unknown conversation never notifies`() = runTest {
        val f = Fixture()
        coEvery { f.conversationDao.findById(LOCAL_CONVERSATION_ID) } returns null
        f.relay.onLiveMessage(wireMessage(), SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true))
        coVerify(exactly = 0) { f.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test fun `missing message row never notifies`() = runTest {
        val f = Fixture()
        coEvery { f.messageDao.findById(LOCAL_MESSAGE_ID) } returns null
        f.relay.onLiveMessage(wireMessage(), SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true))
        coVerify(exactly = 0) { f.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test fun `open conversation never notifies`() = runTest {
        val f = Fixture()
        f.tracker.setActive(LOCAL_CONVERSATION_ID)
        f.relay.onLiveMessage(wireMessage(), SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true))
        coVerify(exactly = 0) { f.notifier.notifyIncoming(any(), any(), any(), any()) }
    }

    @Test fun `text-less photo message notifies with the Photo label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("image/jpeg"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "Photo",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `text-less video message notifies with the Video label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("video/mp4"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "Video",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `text-less voice message notifies with the Voice message label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("audio/amr"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "Voice message",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `text-less audio message notifies with the Audio label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("audio/mpeg"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "Audio",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `text-less GIF notifies with the GIF label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("image/gif"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "GIF",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `text-less file notifies with the File label`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "", attachments = listOf(attachment("application/pdf"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "File",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    @Test fun `a message with text keeps its text even with attachments`() = runTest {
        val f = Fixture()
        f.relay.onLiveMessage(
            wireMessage(body = "look", attachments = listOf(attachment("image/jpeg"))),
            SyncedMessageResult(LOCAL_MESSAGE_ID, inserted = true),
        )
        coVerify {
            f.notifier.notifyIncoming(
                "+33612345678",
                "look",
                LOCAL_MESSAGE_ID,
                LOCAL_CONVERSATION_ID,
            )
        }
    }

    // -- mediaLabelFor (pure) -----------------------------------------

    @Test fun `media label is null without attachments`() {
        assertThat(mediaLabelFor(emptyList())).isNull()
    }

    @Test fun `media label reads the first attachment only`() {
        assertThat(
            mediaLabelFor(listOf(attachment("video/mp4"), attachment("image/jpeg"))),
        ).isEqualTo(MediaLabel.VIDEO)
    }

    @Test fun `media label treats 3gpp audio as a voice message`() {
        assertThat(mediaLabelFor(listOf(attachment("audio/3gpp")))).isEqualTo(MediaLabel.VOICE)
    }

    @Test fun `media label is case-insensitive on the MIME type`() {
        assertThat(mediaLabelFor(listOf(attachment("IMAGE/JPEG")))).isEqualTo(MediaLabel.PHOTO)
    }

    @Test fun `media label falls back to File for an unknown MIME type`() {
        assertThat(mediaLabelFor(listOf(attachment("x-weird/thing")))).isEqualTo(MediaLabel.FILE)
    }

    private companion object {
        const val LOCAL_MESSAGE_ID = 11L
        const val LOCAL_CONVERSATION_ID = 7L

        fun msgRow(clientMessageId: String? = null) = MessageEntity(
            id = LOCAL_MESSAGE_ID,
            conversationId = LOCAL_CONVERSATION_ID,
            telephonyUri = null,
            address = "+33612345678",
            body = "hello",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = 1_000L,
            dateSent = null,
            clientMessageId = clientMessageId,
        )

        fun convRow() = ConversationEntity(
            id = LOCAL_CONVERSATION_ID,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = "Alice",
            lastMessageAt = 0L,
            lastMessagePreview = null,
        )

        fun wireMessage(
            body: String = "hello",
            direction: String = "incoming",
            attachments: List<SyncedAttachment> = emptyList(),
            clientMessageId: String? = null,
        ) = SyncedMessage(
            id = "99",
            conversationId = "70",
            address = "+33612345678",
            body = body,
            type = "sms",
            direction = direction,
            date = 1_700_000_000_000,
            dateSent = null,
            read = false,
            starred = false,
            status = "received",
            errorCode = null,
            replyToMessageId = null,
            reaction = null,
            attachments = attachments,
            clientMessageId = clientMessageId,
        )

        fun attachment(mimeType: String) = SyncedAttachment(
            id = "5",
            mimeType = mimeType,
            fileName = null,
            sizeBytes = 100,
            width = null,
            height = null,
            durationMs = null,
        )
    }
}
