package com.filestech.sms.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.filestech.sms.data.local.db.AppDatabase
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.ScheduledMessageEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.notification.ConversationNotificationCanceller
import com.filestech.sms.domain.repository.ContactRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Client mode C2 — the mirror upserts ([ConversationMirror]
 * `upsertSyncedConversation` / `upsertSyncedMessage`) against a
 * REAL in-memory Room database, because the dedup invariant lives
 * in the database: the hub-id lookup plus the UNIQUE index is what
 * makes a re-sync update instead of duplicating, and a fake DAO
 * would only test the fake.
 *
 * CI-ONLY: the local harness compiles with kotlinc and no KSP, so
 * the generated `AppDatabase_Impl` does not exist there and Room
 * cannot open the database — this file fails at runtime in the
 * harness (and the harness `robo` run already fails to compile on
 * the pre-existing `TextScalePersistenceTest` breakage). Gradle +
 * KSP generate the implementation, so CI runs it. If the
 * coordinator would rather not carry a harness-red test, delete
 * this file — the UNIQUE-index half of the invariant is proven by
 * `MigrationTest.migrate17To18`, and the re-sync-no-duplicates half
 * is a device check for Kristin.
 *
 * JUnit 4 under Robolectric, like `ContactCardSourceTest` (the
 * vintage engine runs it). The mirror's contact/phone-identity
 * collaborators are relaxed MockK mocks: the upserts only need a
 * display-name lookup that returns null and an identity match that
 * returns false.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class ConversationMirrorSyncTest {

    private lateinit var db: AppDatabase
    private lateinit var mirror: ConversationMirror

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mirror = ConversationMirror(
            db,
            db.conversationDao(),
            db.messageDao(),
            db.scheduledMessageDao(),
            db.attachmentDao(),
            mockk<ContactRepository>(relaxed = true),
            mockk<PhoneIdentity>(relaxed = true),
            mockk<ConversationNotificationCanceller>(relaxed = true),
            Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test fun `upserting the same conversation twice keeps one row`() = runTest {
        val first = mirror.upsertSyncedConversation(convData(7))
        val second = mirror.upsertSyncedConversation(convData(7))
        assertThat(first.localId).isEqualTo(second.localId)
        assertThat(first.created).isTrue()
        assertThat(second.created).isFalse()
        assertThat(db.conversationDao().hubConversationIds()).containsExactly(7L).inOrder()
    }

    @Test fun `a re-sync updates the row instead of duplicating it`() = runTest {
        val first = mirror.upsertSyncedConversation(convData(7, preview = "a", unread = 2))
        val second = mirror.upsertSyncedConversation(convData(7, preview = "b", unread = 0))
        assertThat(first.localId).isEqualTo(second.localId)
        val row = db.conversationDao().findById(first.localId)
        assertThat(row?.lastMessagePreview).isEqualTo("b")
        assertThat(row?.unreadCount).isEqualTo(0)
    }

    @Test fun `upserting the same message twice keeps one row`() = runTest {
        val conv = mirror.upsertSyncedConversation(convData(7))
        val first = mirror.upsertSyncedMessage(msgData(50, 7))
        val second = mirror.upsertSyncedMessage(msgData(50, 7))
        assertThat(first.localId).isEqualTo(second.localId)
        assertThat(first.inserted).isTrue()
        assertThat(second.inserted).isFalse()
        assertThat(db.messageDao().countForConversation(conv.localId)).isEqualTo(1)
        assertThat(db.messageDao().findByHubMessageId(50)?.id).isEqualTo(first.localId)
    }

    /**
     * Client mode C3 — the hub echoes the send key on the sent row, so
     * a live `message_new` for our own send lands on the optimistic
     * row instead of duplicating it: one row, the hub's values win,
     * the local id stays stable.
     */
    @Test fun `a hub echo with the send key reconciles the optimistic row, never duplicates`() = runTest {
        val conv = mirror.upsertSyncedConversation(convData(7))
        val optimisticId = db.messageDao().insert(
            MessageEntity(
                conversationId = conv.localId,
                telephonyUri = null,
                address = "+33612345678",
                body = "hello",
                type = MessageType.SMS,
                direction = MessageDirection.OUTGOING,
                date = 1700000000000,
                dateSent = null,
                read = true,
                status = MessageStatus.PENDING,
                clientMessageId = "key-1",
            ),
        )

        val result = mirror.upsertSyncedMessage(
            msgData(
                50,
                7,
                clientMessageId = "key-1",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
            ),
        )

        assertThat(result.inserted).isFalse()
        assertThat(result.localId).isEqualTo(optimisticId)
        assertThat(db.messageDao().countForConversation(conv.localId)).isEqualTo(1)
        val row = db.messageDao().findById(optimisticId)
        assertThat(row?.hubMessageId).isEqualTo(50L)
        assertThat(row?.status).isEqualTo(MessageStatus.SENT)
        assertThat(row?.clientMessageId).isEqualTo("key-1")
    }

    @Test fun `a message for an unknown conversation creates a stub the real frame adopts`() =
        runTest {
            val msg = mirror.upsertSyncedMessage(msgData(51, 99))
            assertThat(msg.inserted).isTrue()
            val stub = db.conversationDao().findByHubConversationId(99)
            assertThat(stub).isNotNull()
            val conv = mirror.upsertSyncedConversation(convData(99))
            assertThat(conv.localId).isEqualTo(stub!!.id)
            assertThat(db.conversationDao().findById(conv.localId)?.lastMessagePreview)
                .isEqualTo("hi")
        }

    @Test fun `a false friend is not stolen`() = runTest {
        val a = mirror.upsertSyncedConversation(
            convData(7, addresses = listOf("+33611111111")),
        )
        val b = mirror.upsertSyncedConversation(
            convData(8, addresses = listOf("+33611111111")),
        )
        assertThat(a.localId).isNotEqualTo(b.localId)
        assertThat(db.conversationDao().hubConversationIds().toSet()).isEqualTo(setOf(7L, 8L))
    }

    @Test fun `an unresolved pin is reported, then heals when the message lands`() = runTest {
        val conv = mirror.upsertSyncedConversation(
            convData(7, pinnedHubId = 50, pinnedAt = 12345L),
        )
        assertThat(conv.unresolvedPinnedHubMessageId).isEqualTo(50L)
        val msg = mirror.upsertSyncedMessage(msgData(50, 7))
        val again = mirror.upsertSyncedConversation(
            convData(7, pinnedHubId = 50, pinnedAt = 12345L),
        )
        assertThat(again.unresolvedPinnedHubMessageId).isNull()
        val row = db.conversationDao().findById(conv.localId)
        assertThat(row?.pinnedMessageId).isEqualTo(msg.localId)
        assertThat(row?.pinnedMessageAt).isEqualTo(12345L)
    }

    @Test fun `an unresolved reply target is reported, then heals on re-delivery`() = runTest {
        mirror.upsertSyncedConversation(convData(7))
        val reply = mirror.upsertSyncedMessage(msgData(70, 7, replyTo = 60))
        assertThat(reply.unresolvedReplyToHubMessageId).isEqualTo(60L)
        val target = mirror.upsertSyncedMessage(msgData(60, 7))
        val again = mirror.upsertSyncedMessage(msgData(70, 7, replyTo = 60))
        assertThat(again.unresolvedReplyToHubMessageId).isNull()
        assertThat(db.messageDao().findByHubMessageId(70)?.replyToMessageId)
            .isEqualTo(target.localId)
    }

    @Test fun `attachments upsert with the message, metadata only`() = runTest {
        mirror.upsertSyncedConversation(convData(7))
        val attachment = SyncedAttachmentData(
            hubAttachmentId = 9,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
            sizeBytes = 1234,
            width = 800,
            height = 600,
            durationMs = null,
        )
        val msg = mirror.upsertSyncedMessage(msgData(50, 7, attachments = listOf(attachment)))
        val stored = db.attachmentDao().findByHubAttachmentId(9)
        assertThat(stored).isNotNull()
        assertThat(stored!!.messageId).isEqualTo(msg.localId)
        assertThat(stored.mimeType).isEqualTo("image/jpeg")
        // C2 stores metadata only — no bytes, so no local file yet.
        assertThat(stored.localUri).isEqualTo("")
        assertThat(db.messageDao().findByHubMessageId(50)?.attachmentsCount).isEqualTo(1)
    }

    /**
     * Client mode C5 — the hub echoes our media send: the optimistic
     * attachment rows (no hub id, pointing at the durable copies)
     * are deleted, and their files are linked onto the synced rows
     * by position + size — so the sent photo keeps showing without
     * a download, and the message never carries duplicated rows.
     */
    @Test fun `reconcile links optimistic files to synced attachments`() = runTest {
        val conv = mirror.upsertSyncedConversation(convData(7))
        val optimisticId = db.messageDao().insert(
            MessageEntity(
                conversationId = conv.localId,
                telephonyUri = null,
                address = "+33612345678",
                body = "",
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = 1700000000000,
                dateSent = null,
                read = true,
                status = MessageStatus.PENDING,
                attachmentsCount = 1,
                clientMessageId = "key-1",
            ),
        )
        db.attachmentDao().insert(
            AttachmentEntity(
                messageId = optimisticId,
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                sizeBytes = 1234,
                localUri = "/files/mms_attachments/out-1.jpg",
                width = 800,
                height = 600,
            ),
        )

        val result = mirror.upsertSyncedMessage(
            msgData(
                50,
                7,
                type = MessageType.MMS,
                clientMessageId = "key-1",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                attachments = listOf(
                    SyncedAttachmentData(
                        hubAttachmentId = 9,
                        mimeType = "image/jpeg",
                        fileName = "photo.jpg",
                        sizeBytes = 1234,
                        width = 800,
                        height = 600,
                        durationMs = null,
                    ),
                ),
            ),
        )

        assertThat(result.localId).isEqualTo(optimisticId)
        val rows = db.attachmentDao().findForMessage(optimisticId)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().hubAttachmentId).isEqualTo(9L)
        assertThat(rows.single().localUri).isEqualTo("/files/mms_attachments/out-1.jpg")
    }

    @Test fun `reconcile with a size mismatch links nothing`() = runTest {
        val conv = mirror.upsertSyncedConversation(convData(7))
        val optimisticId = db.messageDao().insert(
            MessageEntity(
                conversationId = conv.localId,
                telephonyUri = null,
                address = "+33612345678",
                body = "",
                type = MessageType.MMS,
                direction = MessageDirection.OUTGOING,
                date = 1700000000000,
                dateSent = null,
                read = true,
                status = MessageStatus.PENDING,
                attachmentsCount = 1,
                clientMessageId = "key-1",
            ),
        )
        db.attachmentDao().insert(
            AttachmentEntity(
                messageId = optimisticId,
                mimeType = "image/jpeg",
                fileName = "photo.jpg",
                sizeBytes = 1234,
                localUri = "/files/mms_attachments/out-1.jpg",
            ),
        )

        mirror.upsertSyncedMessage(
            msgData(
                50,
                7,
                type = MessageType.MMS,
                clientMessageId = "key-1",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                attachments = listOf(
                    SyncedAttachmentData(
                        hubAttachmentId = 9,
                        mimeType = "image/jpeg",
                        fileName = "photo.jpg",
                        sizeBytes = 9999,
                        width = 800,
                        height = 600,
                        durationMs = null,
                    ),
                ),
            ),
        )

        // The optimistic row is gone, the synced row keeps a blank
        // URI — the bubble falls back to download-on-demand.
        val rows = db.attachmentDao().findForMessage(optimisticId)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().hubAttachmentId).isEqualTo(9L)
        assertThat(rows.single().localUri).isEqualTo("")
    }

    @Test fun `re-delivery never duplicates attachments and never drops a downloaded file`() = runTest {
        mirror.upsertSyncedConversation(convData(7))
        val attachment = SyncedAttachmentData(
            hubAttachmentId = 9,
            mimeType = "image/jpeg",
            fileName = "photo.jpg",
            sizeBytes = 1234,
            width = 800,
            height = 600,
            durationMs = null,
        )
        val msg = mirror.upsertSyncedMessage(msgData(50, 7, attachments = listOf(attachment)))
        // The download landed between the deliveries.
        val stored = db.attachmentDao().findByHubAttachmentId(9)!!
        db.attachmentDao().updateLocalUri(stored.id, "/files/mms_attachments/9.jpg")

        mirror.upsertSyncedMessage(msgData(50, 7, attachments = listOf(attachment)))

        val rows = db.attachmentDao().findForMessage(msg.localId)
        assertThat(rows).hasSize(1)
        assertThat(rows.single().hubAttachmentId).isEqualTo(9L)
        assertThat(rows.single().localUri).isEqualTo("/files/mms_attachments/9.jpg")
    }

    @Test fun `deleteSynced clears synced rows but keeps hub rows and schedules`() = runTest {
        val conv = mirror.upsertSyncedConversation(convData(7))
        mirror.upsertSyncedMessage(msgData(50, 7))
        assertThat(conv.localId).isGreaterThan(0L)
        // A hub-mode row (no hub id) and a schedule this device created.
        val hubConvId = db.conversationDao().insert(
            ConversationEntity(
                threadId = null,
                addressesCsv = "+33699999999",
                displayName = null,
                lastMessageAt = 0,
                lastMessagePreview = null,
            ),
        )
        val schedId = db.scheduledMessageDao().upsert(
            ScheduledMessageEntity(
                conversationId = null,
                addressesCsv = "+33699999999",
                body = "later",
                scheduledAt = 1800000000000,
                createdAt = 1700000000000,
            ),
        )
        // Exactly what `ClientCacheWiper.wipeMessageCache` calls.
        db.conversationDao().deleteSynced()
        db.messageDao().deleteSynced()
        assertThat(db.conversationDao().findByHubConversationId(7)).isNull()
        assertThat(db.messageDao().findByHubMessageId(50)).isNull()
        assertThat(db.conversationDao().findById(hubConvId)).isNotNull()
        assertThat(db.scheduledMessageDao().findById(schedId)).isNotNull()
    }

    private fun convData(
        hubId: Long,
        addresses: List<String> = listOf("+33612345678"),
        preview: String? = "hi",
        unread: Int = 0,
        pinnedHubId: Long? = null,
        pinnedAt: Long? = null,
    ) = SyncedConversationData(
        hubConversationId = hubId,
        title = "Alice",
        displayName = "Alice",
        customName = null,
        addresses = addresses,
        lastMessagePreview = preview,
        lastMessageAt = 1700000000000,
        unreadCount = unread,
        pinned = false,
        muted = false,
        archived = false,
        pinnedMessageHubId = pinnedHubId,
        pinnedMessageAt = pinnedAt,
    )

    private fun msgData(
        hubId: Long,
        hubConvId: Long,
        body: String = "hello",
        replyTo: Long? = null,
        attachments: List<SyncedAttachmentData> = emptyList(),
        // Client mode C3 — the send key + direction/status of the hub
        // echo, for the optimistic-row reconcile test.
        clientMessageId: String? = null,
        direction: MessageDirection = MessageDirection.INCOMING,
        status: MessageStatus = MessageStatus.RECEIVED,
        // Client mode C5 — the echo's type (MMS for a media send).
        type: MessageType = MessageType.SMS,
    ) = SyncedMessageData(
        hubMessageId = hubId,
        hubConversationId = hubConvId,
        address = "+33612345678",
        body = body,
        type = type,
        direction = direction,
        date = 1700000000000,
        dateSent = null,
        read = true,
        starred = false,
        status = status,
        errorCode = null,
        replyToHubMessageId = replyTo,
        reaction = null,
        clientMessageId = clientMessageId,
        attachments = attachments,
    )
}
