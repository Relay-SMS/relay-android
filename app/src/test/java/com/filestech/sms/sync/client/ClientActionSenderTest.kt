package com.filestech.sms.sync.client

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.dao.NoteDao
import com.filestech.sms.data.local.db.dao.NoteMessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.data.local.db.entity.NoteEntity
import com.filestech.sms.data.local.db.entity.NoteMessageEntity
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedNote
import com.filestech.sms.sync.SyncedNoteMessage
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — the write-back sender, driven without a socket: a
 * fake channel records every outbound frame, mock DAOs resolve the
 * local ids to hub ids, and the revert lambdas are plain counters.
 */
class ClientActionSenderTest {

    private class FakeChannel : ClientSyncChannel {
        override val state: StateFlow<ClientConnectionState> =
            MutableStateFlow(ClientConnectionState.Disconnected)
        override val frames: SharedFlow<ServerFrame> =
            MutableSharedFlow(extraBufferCapacity = 64)
        val sent = mutableListOf<String>()
        var sendResult = true

        override suspend fun send(text: String): Boolean {
            sent += text
            return sendResult
        }
    }

    private class Harness(
        val sender: ClientActionSender,
        val channel: FakeChannel,
        val conversationDao: ConversationDao,
        val messageDao: MessageDao,
        /** Round 36 phase 2 — the note DAOs (the note action tests seed them). */
        val noteDao: NoteDao,
        val noteMessageDao: NoteMessageDao,
    )

    private fun harness(channelOnline: Boolean = true): Harness {
        val channel = FakeChannel().also { it.sendResult = channelOnline }
        val conversationDao = mockk<ConversationDao>(relaxed = true)
        val messageDao = mockk<MessageDao>(relaxed = true)
        coEvery { conversationDao.findById(7L) } returns ConversationEntity(
            id = 7L,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = null,
            hubConversationId = 7L,
            lastMessageAt = 1000L,
            lastMessagePreview = null,
        )
        coEvery { messageDao.findById(50L) } returns MessageEntity(
            id = 50L,
            conversationId = 7L,
            telephonyUri = null,
            address = "+33612345678",
            body = "hello",
            type = MessageType.SMS,
            direction = MessageDirection.INCOMING,
            date = 1700000000000,
            dateSent = null,
            read = true,
            status = MessageStatus.RECEIVED,
            hubMessageId = 50L,
        )
        val settings = mockk<AppSettingsSource>()
        coEvery { settings.hydratedOrNull() } returns AppSettings(mode = DeviceMode.CLIENT)
        // Round 36 phase 2 — the note DAOs resolve local ids to hub
        // ids (note 3 ↔ hub 30, entry 40 ↔ hub 400).
        val noteDao = mockk<NoteDao>(relaxed = true)
        val noteMessageDao = mockk<NoteMessageDao>(relaxed = true)
        coEvery { noteDao.findById(3L) } returns NoteEntity(
            id = 3L,
            uuid = "note-uuid",
            title = "Shopping",
            createdAt = 1000L,
            updatedAt = 1000L,
            lastMessageAt = null,
            lastMessagePreview = null,
            hubNoteId = 30L,
        )
        coEvery { noteMessageDao.findById(40L) } returns NoteMessageEntity(
            id = 40L,
            uuid = "msg-uuid",
            noteId = 3L,
            body = "milk",
            date = 1000L,
            hubNoteMessageId = 400L,
        )
        coEvery { noteDao.findByHubId(30L) } returns NoteEntity(
            id = 3L,
            uuid = "note-uuid",
            title = "Shopping",
            createdAt = 1000L,
            updatedAt = 1000L,
            lastMessageAt = null,
            lastMessagePreview = null,
            hubNoteId = 30L,
        )
        val sender = ClientActionSender(channel, conversationDao, messageDao, settings, noteDao, noteMessageDao)
        return Harness(sender, channel, conversationDao, messageDao, noteDao, noteMessageDao)
    }

    private fun sentFrame(h: Harness): JsonObject {
        assertThat(h.channel.sent).hasSize(1)
        return h.channel.sent.single().asActionObject()
    }

    // -- frames -----------------------------------------------------

    @Test fun `mark_read sends the hub conversation id`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.MarkRead(7L))).isTrue()

        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("mark_read")
        assertThat(frame["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
    }

    @Test fun `mark_all_read sends an empty frame`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.MarkAllRead)).isTrue()

        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("mark_all_read")
        assertThat(frame.size).isEqualTo(1)
    }

    @Test fun `set_pin, set_archived, set_muted and set_blocked carry the value`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.SetPin(7L, true))).isTrue()
        assertThat(h.sender.sendAction(ClientAction.SetArchived(7L, false))).isTrue()
        assertThat(h.sender.sendAction(ClientAction.SetMuted(7L, true))).isTrue()
        assertThat(h.sender.sendAction(ClientAction.SetBlocked(7L, false))).isTrue()

        assertThat(h.channel.sent).hasSize(4)
        val frames = h.channel.sent.map { it.asActionObject() }
        assertThat(frames[0].actionType()).isEqualTo("set_pin")
        assertThat(frames[0]["value"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(frames[1].actionType()).isEqualTo("set_archived")
        assertThat(frames[1]["value"]?.jsonPrimitive?.boolean).isFalse()
        assertThat(frames[2].actionType()).isEqualTo("set_muted")
        assertThat(frames[2]["value"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(frames[3].actionType()).isEqualTo("set_blocked")
        assertThat(frames[3]["value"]?.jsonPrimitive?.boolean).isFalse()
        frames.forEach {
            assertThat(it["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
        }
    }

    @Test fun `set_reaction sends hub ids and the emoji`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.SetReaction(7L, 50L, "❤️"))).isTrue()

        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("set_reaction")
        assertThat(frame["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(frame["message_id"]?.jsonPrimitive?.content).isEqualTo("50")
        assertThat(frame["emoji"]?.jsonPrimitive?.content).isEqualTo("❤️")
    }

    @Test fun `set_pinned_message sends the hub message id, or nothing to unpin`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.SetPinnedMessage(7L, 50L))).isTrue()
        assertThat(h.sender.sendAction(ClientAction.SetPinnedMessage(7L, null))).isTrue()

        assertThat(h.channel.sent).hasSize(2)
        val pin = h.channel.sent[0].asActionObject()
        assertThat(pin.actionType()).isEqualTo("set_pinned_message")
        assertThat(pin["message_id"]?.jsonPrimitive?.content).isEqualTo("50")
        val unpin = h.channel.sent[1].asActionObject()
        assertThat(unpin.actionType()).isEqualTo("set_pinned_message")
        assertThat(unpin.containsKey("message_id")).isFalse()
    }

    // -- revert -----------------------------------------------------

    @Test fun `an offline action runs the revert immediately and returns false`() = runTest {
        val h = harness(channelOnline = false)
        var reverted = 0

        val sent = h.sender.sendAction(ClientAction.SetPin(7L, true)) { reverted++ }

        assertThat(sent).isFalse()
        assertThat(reverted).isEqualTo(1)
    }

    @Test fun `an unresolvable id runs the revert immediately and returns false`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findById(9L) } returns null
        var reverted = 0

        val sent = h.sender.sendAction(ClientAction.SetPin(9L, true)) { reverted++ }

        assertThat(sent).isFalse()
        assertThat(reverted).isEqualTo(1)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `ok false reverts the optimistic change, ok true does not`() = runTest {
        val h = harness()
        var pinReverted = 0
        var muteReverted = 0

        h.sender.sendAction(ClientAction.SetPin(7L, true)) { pinReverted++ }
        h.sender.sendAction(ClientAction.SetMuted(7L, true)) { muteReverted++ }
        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_pin", "7", null, null, false, "internal", "boom"),
        )
        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_muted", "7", null, null, true, null, null),
        )

        assertThat(pinReverted).isEqualTo(1)
        assertThat(muteReverted).isEqualTo(0)
    }

    @Test fun `a refused set_reaction with send_failed does NOT revert`() = runTest {
        val h = harness()
        var reverted = 0

        h.sender.sendAction(ClientAction.SetReaction(7L, 50L, "❤️")) { reverted++ }
        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_reaction", "7", "50", null, false, "send_failed", null),
        )

        assertThat(reverted).isEqualTo(0)
    }

    @Test fun `a refused set_reaction with another code DOES revert`() = runTest {
        val h = harness()
        var reverted = 0

        h.sender.sendAction(ClientAction.SetReaction(7L, 50L, "❤️")) { reverted++ }
        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_reaction", "7", "50", null, false, "internal", "boom"),
        )

        assertThat(reverted).isEqualTo(1)
    }

    @Test fun `an action_result for an unknown action is ignored`() = runTest {
        val h = harness()

        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_pin", "7", null, null, false, "internal", "boom"),
        )

        assertThat(h.channel.sent).isEmpty()
    }

    // -- create -----------------------------------------------------

    @Test fun `create_conversation awaits conversation_created and returns the local id`() = runTest {
        val h = harness()
        coEvery { h.conversationDao.findByHubConversationId(7L) } returns ConversationEntity(
            id = 11L,
            threadId = null,
            addressesCsv = "+33612345678",
            displayName = null,
            hubConversationId = 7L,
            lastMessageAt = 1000L,
            lastMessagePreview = null,
        )

        val deferred = async {
            h.sender.createConversation(listOf("+33612345678"), "Weekend")
        }
        runCurrent()
        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("create_conversation")
        assertThat(frame["custom_name"]?.jsonPrimitive?.content).isEqualTo("Weekend")

        h.sender.onConversationCreated(
            SyncProtocol.ConversationCreated(
                SyncedConversation(
                    id = "7",
                    threadId = null,
                    addresses = listOf("+33612345678"),
                    displayName = "Weekend",
                    customName = "Weekend",
                    title = "Weekend",
                    lastMessageAt = 1700000000000,
                    lastMessagePreview = null,
                    unread = 0,
                    pinned = false,
                    archived = false,
                    muted = false,
                    blocked = false,
                    messageCount = 0,
                    pinnedMessageId = null,
                    pinnedMessageAt = null,
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(11L))
    }

    @Test fun `a blank name sends no custom_name`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createConversation(listOf("+33612345678"), "   ") }
        runCurrent()

        assertThat(sentFrame(h).containsKey("custom_name")).isFalse()
        h.sender.onSessionEnded()
        advanceUntilIdle()
        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.ClientOffline))
    }

    @Test fun `a refused create fails with the hub reason`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createConversation(listOf("+33612345678"), null) }
        runCurrent()
        h.sender.onActionResult(
            SyncProtocol.ActionResult("create_conversation", null, null, null, false, "invalid_request", "bad address"),
        )
        advanceUntilIdle()

        val result = deferred.await()
        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat((result as Outcome.Failure).error)
            .isEqualTo(AppError.Validation("bad address"))
    }

    @Test fun `a create that hears nothing back times out`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createConversation(listOf("+33612345678"), null) }
        runCurrent()
        advanceTimeBy(ClientActionSender.CREATE_TIMEOUT_MS + 1)
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.Network()))
    }

    @Test fun `a session end fails a pending create offline`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createConversation(listOf("+33612345678"), null) }
        runCurrent()
        h.sender.onSessionEnded()
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.ClientOffline))
    }

    @Test fun `create_conversation with no recipients fails without a frame`() = runTest {
        val h = harness()

        val result = h.sender.createConversation(emptyList(), null)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `an offline create fails fast without waiting`() = runTest {
        val h = harness(channelOnline = false)

        val result = h.sender.createConversation(listOf("+33612345678"), null)

        assertThat(result).isEqualTo(Outcome.Failure(AppError.ClientOffline))
    }

    // -- dial -------------------------------------------------------

    @Test fun `dial sends a dial_request frame and completes on dial_result`() = runTest {
        val h = harness()

        val deferred = async { h.sender.dial("+33612345678") }
        runCurrent()
        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("dial_request")
        assertThat(frame["number"]?.jsonPrimitive?.content).isEqualTo("+33612345678")

        h.sender.onDialResult(SyncProtocol.DialResult(ok = true))
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(Unit))
    }

    @Test fun `a refused dial fails with the hub error code`() = runTest {
        val h = harness()

        val deferred = async { h.sender.dial("+33612345678") }
        runCurrent()
        h.sender.onDialResult(
            SyncProtocol.DialResult(ok = false, error = SyncProtocol.DialResult.PERMISSION_DENIED),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(
            Outcome.Failure(AppError.Validation(SyncProtocol.DialResult.PERMISSION_DENIED)),
        )
    }

    @Test fun `a dial that hears nothing back times out`() = runTest {
        val h = harness()

        val deferred = async { h.sender.dial("+33612345678") }
        runCurrent()
        advanceTimeBy(ClientActionSender.DIAL_TIMEOUT_MS + 1)
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.Network()))
    }

    @Test fun `a session end fails a pending dial offline`() = runTest {
        val h = harness()

        val deferred = async { h.sender.dial("+33612345678") }
        runCurrent()
        h.sender.onSessionEnded()
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.ClientOffline))
    }

    @Test fun `dial with a blank number fails without a frame`() = runTest {
        val h = harness()

        val result = h.sender.dial("   ")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `an offline dial fails fast without waiting`() = runTest {
        val h = harness(channelOnline = false)

        val result = h.sender.dial("+33612345678")

        assertThat(result).isEqualTo(Outcome.Failure(AppError.ClientOffline))
    }
    // -- Round 36 phase 2: notes --------------------------------------

    @Test fun `set_note_pin and set_note_archived carry the hub note id and value`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.SetNotePin(3L, true))).isTrue()
        assertThat(h.sender.sendAction(ClientAction.SetNoteArchived(3L, false))).isTrue()

        assertThat(h.channel.sent).hasSize(2)
        val frames = h.channel.sent.map { it.asActionObject() }
        assertThat(frames[0].actionType()).isEqualTo("set_note_pin")
        assertThat(frames[0]["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frames[0]["value"]?.jsonPrimitive?.boolean).isTrue()
        assertThat(frames[1].actionType()).isEqualTo("set_note_archived")
        assertThat(frames[1]["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frames[1]["value"]?.jsonPrimitive?.boolean).isFalse()
    }

    @Test fun `rename_note sends the hub note id and title`() = runTest {
        val h = harness()

        assertThat(h.sender.sendAction(ClientAction.RenameNote(3L, "New name"))).isTrue()

        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("rename_note")
        assertThat(frame["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frame["title"]?.jsonPrimitive?.content).isEqualTo("New name")
    }

    @Test fun `edit_note_message sends hub ids and the new body`() = runTest {
        val h = harness()

        assertThat(
            h.sender.sendAction(ClientAction.EditNoteMessage(3L, 40L, "milk, eggs")),
        ).isTrue()

        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("edit_note_message")
        assertThat(frame["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frame["message_id"]?.jsonPrimitive?.content).isEqualTo("400")
        assertThat(frame["body"]?.jsonPrimitive?.content).isEqualTo("milk, eggs")
    }

    @Test fun `a refused note action reverts, an accepted one does not`() = runTest {
        val h = harness()
        var pinReverted = 0
        var renameReverted = 0

        h.sender.sendAction(ClientAction.SetNotePin(3L, true)) { pinReverted++ }
        h.sender.sendAction(ClientAction.RenameNote(3L, "New")) { renameReverted++ }
        h.sender.onActionResult(
            SyncProtocol.ActionResult("set_note_pin", "30", null, null, false, "internal", "boom"),
        )
        h.sender.onActionResult(
            SyncProtocol.ActionResult("rename_note", "30", null, null, true, null, null),
        )

        assertThat(pinReverted).isEqualTo(1)
        assertThat(renameReverted).isEqualTo(0)
    }

    @Test fun `note_message_updated drops the pending edit revert`() = runTest {
        val h = harness()
        var reverted = 0

        h.sender.sendAction(ClientAction.EditNoteMessage(3L, 40L, "milk, eggs")) { reverted++ }
        // The broadcast lands first: the edit is on the hub, so the
        // revert is dropped (not run). A late `action_result`
        // `ok:false` then finds nothing to revert.
        h.sender.onNoteMessageUpdated(
            SyncProtocol.NoteMessageUpdated(
                SyncedNoteMessage(
                    id = "400",
                    noteId = "30",
                    body = "milk, eggs",
                    date = 1000L,
                    attachments = emptyList(),
                    edited = true,
                ),
            ),
        )
        h.sender.onActionResult(
            SyncProtocol.ActionResult(
                "edit_note_message",
                "30",
                "400",
                null,
                false,
                "internal",
                "boom",
            ),
        )

        assertThat(reverted).isEqualTo(0)
    }

    @Test fun `create_note awaits note_created and returns the local id`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createNote("Ideas") }
        runCurrent()
        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("create_note")
        assertThat(frame["title"]?.jsonPrimitive?.content).isEqualTo("Ideas")
        assertThat(frame["client_note_id"]?.jsonPrimitive?.content).isNotEmpty()

        h.sender.onNoteCreated(
            SyncProtocol.NoteCreated(
                SyncedNote(
                    id = "30",
                    title = "Ideas",
                    createdAt = 1000L,
                    updatedAt = 1000L,
                    lastMessageAt = null,
                    lastMessagePreview = null,
                    pinned = false,
                    archived = false,
                    messageCount = 0,
                ),
            ),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(3L))
    }

    @Test fun `a refused create_note fails with the hub reason`() = runTest {
        val h = harness()

        val deferred = async { h.sender.createNote("Ideas") }
        runCurrent()
        h.sender.onActionResult(
            SyncProtocol.ActionResult("create_note", null, null, null, false, "internal", "boom"),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.Validation("boom")))
    }

    @Test fun `create_note with a blank title fails without a frame`() = runTest {
        val h = harness()

        val result = h.sender.createNote("   ")

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `delete_note awaits the action_result`() = runTest {
        val h = harness()

        val deferred = async { h.sender.deleteNote(3L) }
        runCurrent()
        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("delete_note")
        assertThat(frame["note_id"]?.jsonPrimitive?.content).isEqualTo("30")

        h.sender.onActionResult(
            SyncProtocol.ActionResult("delete_note", "30", null, null, true, null, null),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(Unit))
    }

    @Test fun `delete_note_message awaits the action_result`() = runTest {
        val h = harness()

        val deferred = async { h.sender.deleteNoteMessage(3L, 40L) }
        runCurrent()
        val frame = sentFrame(h)
        assertThat(frame.actionType()).isEqualTo("delete_note_message")
        assertThat(frame["note_id"]?.jsonPrimitive?.content).isEqualTo("30")
        assertThat(frame["message_id"]?.jsonPrimitive?.content).isEqualTo("400")

        h.sender.onActionResult(
            SyncProtocol.ActionResult(
                "delete_note_message",
                "30",
                "400",
                null,
                true,
                null,
                null,
            ),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Success(Unit))
    }

    @Test fun `a refused delete fails`() = runTest {
        val h = harness()

        val deferred = async { h.sender.deleteNote(3L) }
        runCurrent()
        h.sender.onActionResult(
            SyncProtocol.ActionResult("delete_note", "30", null, null, false, "unknown_note", "gone"),
        )
        advanceUntilIdle()

        assertThat(deferred.await()).isEqualTo(Outcome.Failure(AppError.Validation("gone")))
    }

    @Test fun `delete of an unsynced note fails without a frame`() = runTest {
        val h = harness()
        coEvery { h.noteDao.findById(9L) } returns null

        val result = h.sender.deleteNote(9L)

        assertThat(result).isInstanceOf(Outcome.Failure::class.java)
        assertThat(h.channel.sent).isEmpty()
    }

    @Test fun `a second delete of the same note awaits the first instead of sending twice`() = runTest {
        val h = harness()

        val first = async { h.sender.deleteNote(3L) }
        runCurrent()
        val second = async { h.sender.deleteNote(3L) }
        runCurrent()
        assertThat(h.channel.sent).hasSize(1)

        h.sender.onActionResult(
            SyncProtocol.ActionResult("delete_note", "30", null, null, true, null, null),
        )
        advanceUntilIdle()

        assertThat(first.await()).isEqualTo(Outcome.Success(Unit))
        assertThat(second.await()).isEqualTo(Outcome.Success(Unit))
    }
}

private fun String.asActionObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.actionType(): String? = this["type"]?.jsonPrimitive?.content
