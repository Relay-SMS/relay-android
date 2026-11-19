package com.filestech.sms.sync.client

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.local.db.entity.ConversationEntity
import com.filestech.sms.data.local.db.entity.MessageEntity
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedConversation
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
        val sender = ClientActionSender(channel, conversationDao, messageDao, settings)
        return Harness(sender, channel, conversationDao, messageDao)
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
}

private fun String.asActionObject(): JsonObject = Json.parseToJsonElement(this).jsonObject

private fun JsonObject.actionType(): String? = this["type"]?.jsonPrimitive?.content
