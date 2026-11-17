package com.filestech.sms.sync.client

import com.filestech.sms.sync.SyncProtocol
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Client mode — what the client makes of one incoming text frame.
 * The inverse of [SyncProtocol.Parsed] (which handles client→hub
 * frames): [Unknown] keeps the connection (a newer hub may send
 * types this build does not know yet); [Malformed] is ignored
 * mid-session, channel stays up (the desktop rule — the hub never
 * closes us for a frame it sent badly).
 *
 * The full server→client inventory is parsed here, including the
 * sync/send/contacts frames the C2 engine consumes — the transport
 * forwards typed frames and ignores what it does not handle itself.
 */
sealed interface ServerFrame {
    data class Challenge(val value: SyncProtocol.Challenge) : ServerFrame
    data class AuthOk(val value: SyncProtocol.AuthOk) : ServerFrame
    data class AuthFailed(val value: SyncProtocol.AuthFailed) : ServerFrame
    data class Ping(val value: SyncProtocol.Ping) : ServerFrame
    data class Pong(val value: SyncProtocol.Ping) : ServerFrame
    data object Revoked : ServerFrame
    data class Error(val value: SyncProtocol.Error) : ServerFrame
    data class SyncStarted(val value: SyncProtocol.SyncStarted) : ServerFrame
    data class Conversations(val value: SyncProtocol.Conversations) : ServerFrame
    data class MessagesBatch(val value: SyncProtocol.MessagesBatch) : ServerFrame
    data class SyncComplete(val value: SyncProtocol.SyncComplete) : ServerFrame
    data class MessageNew(val value: SyncProtocol.MessageNew) : ServerFrame
    data class MessageUpdated(val value: SyncProtocol.MessageUpdated) : ServerFrame
    data class ConversationUpdated(val value: SyncProtocol.ConversationUpdated) : ServerFrame
    data class ConversationDeleted(val value: SyncProtocol.ConversationDeleted) : ServerFrame
    data class SyncError(val value: SyncProtocol.SyncError) : ServerFrame
    data class HubStatus(val value: SyncProtocol.HubStatus) : ServerFrame
    data class MessageSent(val value: SyncProtocol.MessageSent) : ServerFrame
    data class MessageFailed(val value: SyncProtocol.MessageFailed) : ServerFrame
    data class ActionResult(val value: SyncProtocol.ActionResult) : ServerFrame
    data class AttachmentChunk(val value: SyncProtocol.AttachmentChunk) : ServerFrame
    data class AttachmentError(val value: SyncProtocol.AttachmentError) : ServerFrame
    data class AttachmentUploadReady(val value: SyncProtocol.AttachmentUploadReady) : ServerFrame
    data class AttachmentUploadDone(val value: SyncProtocol.AttachmentUploadDone) : ServerFrame
    data class AttachmentUploadError(val value: SyncProtocol.AttachmentUploadError) : ServerFrame
    data class MessageScheduled(val value: SyncProtocol.MessageScheduled) : ServerFrame
    data class ScheduleCancelled(val value: SyncProtocol.ScheduleCancelled) : ServerFrame
    data class Contacts(val value: SyncProtocol.Contacts) : ServerFrame
    data class ContactUpdated(val value: SyncProtocol.ContactUpdated) : ServerFrame
    data class ContactDeleted(val value: SyncProtocol.ContactDeleted) : ServerFrame
    data class ContactsError(val value: SyncProtocol.ContactsError) : ServerFrame
    data class ConversationCreated(val value: SyncProtocol.ConversationCreated) : ServerFrame
    data class Unknown(val type: String?) : ServerFrame
    data object Malformed : ServerFrame
}

/**
 * Client mode — parses one incoming text frame. Never throws: bad
 * JSON, a missing `type`, or a wrongly-shaped known message all come
 * back as [ServerFrame.Malformed]; a well-formed message of an
 * unknown type comes back as [ServerFrame.Unknown].
 */
object ServerFrameParser {

    fun parse(text: String): ServerFrame {
        val element = runCatching { SyncProtocol.json.parseToJsonElement(text) }.getOrNull()
            ?: return ServerFrame.Malformed
        val obj: JsonObject = runCatching { element.jsonObject }.getOrNull()
            ?: return ServerFrame.Malformed
        val type = obj["type"]?.jsonPrimitive?.content
        return when (type) {
            "challenge" -> decode<SyncProtocol.Challenge>(obj)?.let(ServerFrame::Challenge)
                ?: ServerFrame.Malformed
            "auth_ok" -> decode<SyncProtocol.AuthOk>(obj)?.let(ServerFrame::AuthOk)
                ?: ServerFrame.Malformed
            "auth_failed" -> decode<SyncProtocol.AuthFailed>(obj)?.let(ServerFrame::AuthFailed)
                ?: ServerFrame.Malformed
            "ping" -> decode<SyncProtocol.Ping>(obj)?.let(ServerFrame::Ping)
                ?: ServerFrame.Malformed
            "pong" -> decode<SyncProtocol.Ping>(obj)?.let(ServerFrame::Pong)
                ?: ServerFrame.Malformed
            "revoked" -> ServerFrame.Revoked
            "error" -> decode<SyncProtocol.Error>(obj)?.let(ServerFrame::Error)
                ?: ServerFrame.Malformed
            null -> ServerFrame.Malformed
            // The remaining frames live in [parseRemaining]: one `when`
            // for all of them pushed `parse` over the Detekt
            // complexity cap (40) — the same split as [SyncProtocol].
            else -> parseRemaining(type, obj)
        }
    }

    /**
     * The second half of [parse]: the sync and send-ack frames. The
     * attachment, schedule, contact and create-conversation frames
     * live in [parseLiveFrames]: one `when` for all of them pushed
     * this function over the Detekt complexity cap (40).
     */
    private fun parseRemaining(type: String, obj: JsonObject): ServerFrame = when (type) {
        "sync_started" -> decode<SyncProtocol.SyncStarted>(obj)?.let(ServerFrame::SyncStarted)
            ?: ServerFrame.Malformed
        "conversations" -> decode<SyncProtocol.Conversations>(obj)?.let(ServerFrame::Conversations)
            ?: ServerFrame.Malformed
        "messages_batch" -> decode<SyncProtocol.MessagesBatch>(obj)?.let(ServerFrame::MessagesBatch)
            ?: ServerFrame.Malformed
        "sync_complete" -> decode<SyncProtocol.SyncComplete>(obj)?.let(ServerFrame::SyncComplete)
            ?: ServerFrame.Malformed
        "message_new" -> decode<SyncProtocol.MessageNew>(obj)?.let(ServerFrame::MessageNew)
            ?: ServerFrame.Malformed
        "message_updated" -> decode<SyncProtocol.MessageUpdated>(obj)?.let(ServerFrame::MessageUpdated)
            ?: ServerFrame.Malformed
        "conversation_updated" -> decode<SyncProtocol.ConversationUpdated>(obj)
            ?.let(ServerFrame::ConversationUpdated) ?: ServerFrame.Malformed
        "conversation_deleted" -> decode<SyncProtocol.ConversationDeleted>(obj)
            ?.let(ServerFrame::ConversationDeleted) ?: ServerFrame.Malformed
        "sync_error" -> decode<SyncProtocol.SyncError>(obj)?.let(ServerFrame::SyncError)
            ?: ServerFrame.Malformed
        "hub_status" -> decode<SyncProtocol.HubStatus>(obj)?.let(ServerFrame::HubStatus)
            ?: ServerFrame.Malformed
        "message_sent" -> decode<SyncProtocol.MessageSent>(obj)?.let(ServerFrame::MessageSent)
            ?: ServerFrame.Malformed
        "message_failed" -> decode<SyncProtocol.MessageFailed>(obj)?.let(ServerFrame::MessageFailed)
            ?: ServerFrame.Malformed
        "action_result" -> decode<SyncProtocol.ActionResult>(obj)?.let(ServerFrame::ActionResult)
            ?: ServerFrame.Malformed
        else -> parseLiveFrames(type, obj)
    }

    /**
     * The third part of [parse]: the attachment, schedule, contact
     * and create-conversation frames. A well-formed message of an
     * unknown type comes back as [ServerFrame.Unknown], like in
     * [parse].
     */
    private fun parseLiveFrames(type: String, obj: JsonObject): ServerFrame = when (type) {
        "attachment_chunk" -> decode<SyncProtocol.AttachmentChunk>(obj)
            ?.let(ServerFrame::AttachmentChunk) ?: ServerFrame.Malformed
        "attachment_error" -> decode<SyncProtocol.AttachmentError>(obj)
            ?.let(ServerFrame::AttachmentError) ?: ServerFrame.Malformed
        "attachment_upload_ready" -> decode<SyncProtocol.AttachmentUploadReady>(obj)
            ?.let(ServerFrame::AttachmentUploadReady) ?: ServerFrame.Malformed
        "attachment_upload_done" -> decode<SyncProtocol.AttachmentUploadDone>(obj)
            ?.let(ServerFrame::AttachmentUploadDone) ?: ServerFrame.Malformed
        "attachment_upload_error" -> decode<SyncProtocol.AttachmentUploadError>(obj)
            ?.let(ServerFrame::AttachmentUploadError) ?: ServerFrame.Malformed
        "message_scheduled" -> decode<SyncProtocol.MessageScheduled>(obj)
            ?.let(ServerFrame::MessageScheduled) ?: ServerFrame.Malformed
        "schedule_cancelled" -> decode<SyncProtocol.ScheduleCancelled>(obj)
            ?.let(ServerFrame::ScheduleCancelled) ?: ServerFrame.Malformed
        "contacts" -> decode<SyncProtocol.Contacts>(obj)?.let(ServerFrame::Contacts)
            ?: ServerFrame.Malformed
        "contact_updated" -> decode<SyncProtocol.ContactUpdated>(obj)?.let(ServerFrame::ContactUpdated)
            ?: ServerFrame.Malformed
        "contact_deleted" -> decode<SyncProtocol.ContactDeleted>(obj)?.let(ServerFrame::ContactDeleted)
            ?: ServerFrame.Malformed
        "contacts_error" -> decode<SyncProtocol.ContactsError>(obj)?.let(ServerFrame::ContactsError)
            ?: ServerFrame.Malformed
        "conversation_created" -> decode<SyncProtocol.ConversationCreated>(obj)
            ?.let(ServerFrame::ConversationCreated) ?: ServerFrame.Malformed
        else -> ServerFrame.Unknown(type)
    }

    private inline fun <reified T> decode(obj: JsonObject): T? =
        runCatching { SyncProtocol.json.decodeFromJsonElement<T>(obj) }.getOrNull()
}

/**
 * Client mode — encoders for the client→server frames the hub never
 * sends (so [SyncProtocol] has no encoder for them): the `type` field
 * is merged in at encode time, the same way [SyncProtocol] does it.
 * `hello`, `ping` and `pong` already have encoders in [SyncProtocol]
 * and are used directly. C2/C3 add their frames here
 * (`sync_request`, `send_message`, …).
 */
object ClientFrames {

    /** Encodes an `auth` message with its `type` field, ready to send. */
    fun encodeAuth(value: SyncProtocol.Auth): String = withType(value, "auth").toString()

    /**
     * Client mode C2 — encodes a `sync_request` (`full` or `new_only`,
     * with the stored cursor when this is a delta). A null cursor is
     * omitted, which the hub reads as `"0"` (first sync).
     */
    fun encodeSyncRequest(mode: String, sinceMessageId: String?): String =
        withType(SyncProtocol.SyncRequest(mode, sinceMessageId), "sync_request").toString()

    /**
     * Client mode C2 — encodes a `messages_request`: one page of one
     * conversation, newest first below [beforeId] (`"0"` = newest
     * page), at most [limit] messages (the hub caps at 200).
     */
    fun encodeMessagesRequest(conversationId: String, beforeId: String?, limit: Int): String =
        withType(
            SyncProtocol.MessagesRequest(conversationId, beforeId, limit),
            "messages_request",
        ).toString()

    /** Client mode C3 — encodes a `send_message` (text send from a linked device). */
    fun encodeSendMessage(value: SyncProtocol.SendMessage): String =
        withType(value, "send_message").toString()

    /** Client mode C3 — encodes a `mark_read` (one conversation read). */
    fun encodeMarkRead(value: SyncProtocol.MarkRead): String =
        withType(value, "mark_read").toString()

    /** Client mode C3 — encodes a `mark_all_read` (every conversation read, no payload). */
    fun encodeMarkAllRead(): String =
        withType(SyncProtocol.MarkAllRead, "mark_all_read").toString()

    /** Client mode C3 — encodes a `set_pin`. */
    fun encodeSetPin(value: SyncProtocol.SetPin): String =
        withType(value, "set_pin").toString()

    /** Client mode C3 — encodes a `set_archived`. */
    fun encodeSetArchived(value: SyncProtocol.SetArchived): String =
        withType(value, "set_archived").toString()

    /** Client mode C3 — encodes a `set_muted`. */
    fun encodeSetMuted(value: SyncProtocol.SetMuted): String =
        withType(value, "set_muted").toString()

    /** Client mode C3 — encodes a `set_blocked`. */
    fun encodeSetBlocked(value: SyncProtocol.SetBlocked): String =
        withType(value, "set_blocked").toString()

    /** Client mode C3 — encodes a `set_reaction` (a null emoji removes the reaction). */
    fun encodeSetReaction(value: SyncProtocol.SetReaction): String =
        withType(value, "set_reaction").toString()

    /** Client mode C3 — encodes a `set_pinned_message` (a null message id unpins). */
    fun encodeSetPinnedMessage(value: SyncProtocol.SetPinnedMessage): String =
        withType(value, "set_pinned_message").toString()

    /** Client mode C3 — encodes a `create_conversation` (1:1 or group, with an optional name). */
    fun encodeCreateConversation(value: SyncProtocol.CreateConversation): String =
        withType(value, "create_conversation").toString()

    /** Client mode C3 — encodes a `schedule_message`. */
    fun encodeScheduleMessage(value: SyncProtocol.ScheduleMessage): String =
        withType(value, "schedule_message").toString()

    /** Client mode C3 — encodes a `cancel_scheduled`. */
    fun encodeCancelScheduled(value: SyncProtocol.CancelScheduled): String =
        withType(value, "cancel_scheduled").toString()

    /** Client mode C4 — encodes a `contacts_request` (re-fetch the contact snapshot). */
    fun encodeContactsRequest(): String =
        withType(SyncProtocol.ContactsRequest, "contacts_request").toString()

    /** Client mode C4 — encodes an `avatar_request` (one contact photo, by normalized number). */
    fun encodeAvatarRequest(value: SyncProtocol.AvatarRequest): String =
        withType(value, "avatar_request").toString()

    /** Client mode C5 — encodes an `attachment_request` (one attachment's bytes, by id + message). */
    fun encodeAttachmentRequest(value: SyncProtocol.AttachmentRequest): String =
        withType(value, "attachment_request").toString()

    /** Client mode C5 — encodes an `attachment_upload_start` (begin an upload). */
    fun encodeAttachmentUploadStart(value: SyncProtocol.AttachmentUploadStart): String =
        withType(value, "attachment_upload_start").toString()

    /** Client mode C5 — encodes an `attachment_upload_chunk` (one streamed upload chunk). */
    fun encodeAttachmentUploadChunk(value: SyncProtocol.AttachmentUploadChunk): String =
        withType(value, "attachment_upload_chunk").toString()

    private inline fun <reified T> withType(value: T, type: String): JsonObject {
        val obj = SyncProtocol.json.encodeToJsonElement(serializer(), value).jsonObject
        return JsonObject(obj + ("type" to JsonPrimitive(type)))
    }
}
