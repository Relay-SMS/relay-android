package com.filestech.sms.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Slice 1 wire protocol — the JSON messages of SYNC_PROTOCOL.md, nothing
 * more. This file is standalone: it imports no app code, so the sync
 * layer can be lifted out of this codebase as-is (see the glue-points
 * section at the end of SYNC_PROTOCOL.md).
 *
 * Every message is a JSON object with a `type` field. The client speaks
 * first (`hello`), the server answers with a `challenge`, the client
 * proves it holds the linked device's private key (`auth`), and the
 * server answers `auth_ok` (or `auth_failed` and closes the socket).
 * After auth, the client sends `ping` every 30 s and the server answers
 * `pong`; a client silent for more than 90 s is dropped.
 *
 * Slice 2 adds the sync types below (`sync_request` … `sync_error`) in
 * the same envelope; the protocol version stays 1.
 *
 * Slice 3 adds the send + write-back types (`send_message` …
 * `action_result`) in the same envelope; the protocol version stays 1.
 */
object SyncProtocol {

    /** Fixed LAN port from the spec — discovery and the server agree on it. */
    const val PORT = 47831

    /** mDNS service type, instance name and hub name from the spec. */
    const val SERVICE_TYPE = "_relay._tcp."
    const val SERVICE_NAME = "Relay"
    const val HUB_NAME = "Relay"

    /** Protocol version carried in `hello` and in the mDNS TXT record. */
    const val PROTOCOL_VERSION = 1

    /** A socket that has not completed auth within this long is closed. */
    const val AUTH_TIMEOUT_MS = 10_000L

    /** The client pings this often; the server drops clients silent longer than [SILENCE_LIMIT_MS]. */
    const val PING_INTERVAL_MS = 30_000L
    const val SILENCE_LIMIT_MS = 90_000L

    /** How often the server sweeps for silent clients. */
    const val SILENCE_CHECK_MS = 30_000L

    /** Challenge, key and signature sizes from the spec. */
    const val CHALLENGE_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** TLS 1.2 minimum, per the spec. */
    val TLS_PROTOCOLS = listOf("TLSv1.2", "TLSv1.3")

    /** Slice 3 — the largest `send_message` body the server accepts, in UTF-8 bytes. */
    const val MAX_SEND_BODY_BYTES = 64 * 1024

    /** Lenient JSON: unknown fields are ignored, so a newer client stays readable. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Client → server, first message on a new socket. */
    @Serializable
    data class Hello(
        @SerialName("protocol") val protocol: Int,
        @SerialName("device_id") val deviceId: String,
        @SerialName("kind") val kind: String,
        @SerialName("name") val name: String,
    )

    /** Server → client: 32 random bytes, base64url, to be signed. */
    @Serializable
    data class Challenge(
        @SerialName("challenge") val challenge: String,
    )

    /** Client → server: Ed25519 signature over the raw challenge bytes, base64url. */
    @Serializable
    data class Auth(
        @SerialName("device_id") val deviceId: String,
        @SerialName("signature") val signature: String,
    )

    /** Server → client: auth succeeded. */
    @Serializable
    data class AuthOk(
        @SerialName("hub_id") val hubId: String,
        @SerialName("hub_name") val hubName: String,
    )

    /** Server → client: auth failed; the socket is closed after this. */
    @Serializable
    data class AuthFailed(
        @SerialName("reason") val reason: String,
    ) {
        companion object {
            const val UNKNOWN_DEVICE = "unknown_device"
            const val BAD_SIGNATURE = "bad_signature"
            const val REVOKED = "revoked"
            const val PROTOCOL = "protocol"
        }
    }

    /** Either way: heartbeat. `pong` echoes the `t` it received. */
    @Serializable
    data class Ping(
        @SerialName("t") val t: Long,
    )

    /** Either way: free-form error. Never carries secrets. */
    @Serializable
    data class Error(
        @SerialName("message") val message: String,
    )

    // -- Slice 2: sync types (SYNC_PROTOCOL.md, §3) --------------------

    /**
     * Client → server: starts (or restarts) a sync after auth.
     * [sinceMessageId] is the desktop's stored global cursor (max
     * message id it has stored), or `"0"` / omitted on a first sync;
     * ignored for [NEW_ONLY] (the cursor is set to "now").
     */
    @Serializable
    data class SyncRequest(
        @SerialName("mode") val mode: String,
        @SerialName("since_message_id") val sinceMessageId: String? = null,
    ) {
        companion object {
            /** The entire message history comes over. */
            const val FULL = "full"

            /** Only new messages from now on arrive (the list still syncs). */
            const val NEW_ONLY = "new_only"
        }
    }

    /**
     * Client → server: targeted fetch — gap-fill after a dropped
     * connection, repair of one conversation, or on-demand fetch.
     * [beforeId] fetches messages with id strictly below it, newest
     * first (`"0"` / omitted = newest); [limit] is capped at 200 by
     * the server.
     */
    @Serializable
    data class MessagesRequest(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("before_id") val beforeId: String? = null,
        @SerialName("limit") val limit: Int? = null,
    )

    /**
     * Server → client: first reply to `sync_request` — totals +
     * cursor. [hubIsDefaultSms] tells the desktop whether the hub app
     * is the phone's default SMS app: when it is not, new messages
     * only reach the mirror on app open or the periodic import, so
     * the desktop warns that live sync will lag.
     */
    @Serializable
    data class SyncStarted(
        @SerialName("mode") val mode: String,
        @SerialName("total_conversations") val totalConversations: Int,
        @SerialName("total_messages") val totalMessages: Long,
        @SerialName("cursor_message_id") val cursorMessageId: String,
        @SerialName("hub_is_default_sms") val hubIsDefaultSms: Boolean,
    )

    /** Server → client: one batch of the conversation snapshot (100 per batch). */
    @Serializable
    data class Conversations(
        @SerialName("conversations") val conversations: List<SyncedConversation>,
        @SerialName("has_more") val hasMore: Boolean,
    )

    /**
     * Server → client: one page of one conversation, newest first.
     * [nextBeforeId] is passed back as `before_id` for the next page;
     * absent when [hasMore] is false.
     */
    @Serializable
    data class MessagesBatch(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("messages") val messages: List<SyncedMessage>,
        @SerialName("has_more") val hasMore: Boolean,
        @SerialName("next_before_id") val nextBeforeId: String? = null,
    )

    /** Server → client: the initial stream is finished. */
    @Serializable
    data class SyncComplete(
        @SerialName("cursor_message_id") val cursorMessageId: String,
    )

    /** Server → client: one live message (a burst arrives as several frames). */
    @Serializable
    data class MessageNew(
        @SerialName("message") val message: SyncedMessage,
    )

    /**
     * Server → client: a synced message changed (status, reaction,
     * read, starred). Partial — only changed fields are present. A
     * removed reaction cannot ride as a null `reaction` (nulls are
     * omitted on encode), so it has its own flag: [reactionRemoved].
     */
    @Serializable
    data class MessageUpdated(
        @SerialName("message_id") val messageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("status") val status: String? = null,
        @SerialName("reaction") val reaction: String? = null,
        @SerialName("reaction_removed") val reactionRemoved: Boolean = false,
        @SerialName("read") val read: Boolean? = null,
        @SerialName("starred") val starred: Boolean? = null,
    )

    /** Server → client: a conversation row changed — the full row is sent. */
    @Serializable
    data class ConversationUpdated(
        @SerialName("conversation") val conversation: SyncedConversation,
    )

    /** Server → client: a conversation disappeared from the phone. */
    @Serializable
    data class ConversationDeleted(
        @SerialName("conversation_id") val conversationId: String,
    )

    /** Server → client: the phone cannot sync right now. */
    @Serializable
    data class SyncError(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /** READ_SMS missing on the phone. */
            const val HUB_NO_SMS_ACCESS = "hub_no_sms_access"

            /** Permission granted, but the phone's message import hasn't run yet. */
            const val HUB_MIRROR_EMPTY = "hub_mirror_empty"

            /** Anything else; [SyncError.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * Server → client: the hub's default-SMS status changed (the user
     * switched the default app in Settings). Pushed to every
     * authenticated session so the desktop's warning clears — or
     * appears — without waiting for the next sync; the server
     * re-checks the role every 30 s and pushes on change. The same
     * value rides on every `sync_started`, which is how a fresh
     * connection learns it first.
     */
    @Serializable
    data class HubStatus(
        @SerialName("is_default_sms") val isDefaultSms: Boolean,
    )

    // -- Slice 3: send + write-back types (SYNC_PROTOCOL.md, Slice 3) --

    /**
     * Client → server: send a text message in an existing conversation.
     * [clientMessageId] is a UUID v4 generated by the desktop per send
     * attempt — a retry of the same send REUSES it, which is what makes
     * the retry safe (the phone dedups on it; see SyncMessageSource).
     * [replyToMessageId] is a phone Room id for a quoted reply, or
     * absent. The phone appends the user's signature, exactly as its
     * own composer does — the desktop sends the body as typed.
     */
    @Serializable
    data class SendMessage(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("body") val body: String,
        @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
    )

    /** Client → server: mark one conversation read on the phone. */
    @Serializable
    data class MarkRead(
        @SerialName("conversation_id") val conversationId: String,
    )

    /** Client → server: mark every conversation read on the phone. No payload. */
    @Serializable
    data object MarkAllRead

    /**
     * Client → server: pin / unpin one conversation. The flag types are
     * separate (not one generic action type): each maps 1:1 to a
     * repository call and stays self-describing in logs.
     */
    @Serializable
    data class SetPin(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("value") val value: Boolean,
    )

    /** Client → server: archive / unarchive one conversation. */
    @Serializable
    data class SetArchived(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("value") val value: Boolean,
    )

    /** Client → server: mute / unmute one conversation. */
    @Serializable
    data class SetMuted(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("value") val value: Boolean,
    )

    /**
     * Client → server: react to a message, or remove the reaction
     * (`emoji` null / absent). A reaction from the desktop sends a
     * real SMS from the phone, exactly as reacting on the phone does.
     */
    @Serializable
    data class SetReaction(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("emoji") val emoji: String? = null,
    )

    /**
     * Client → server: pin a message in its thread, or unpin
     * (`message_id` null / absent).
     */
    @Serializable
    data class SetPinnedMessage(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("message_id") val messageId: String? = null,
    )

    /**
     * Server → client: the phone accepted and dispatched the send — the
     * ack to `send_message`, matched by [clientMessageId]. [messageId]
     * is the phone's Room id of the row the desktop shows (the sent row
     * for a 1:1 send, the group-thread row for a group send). [status]
     * is the phone-side status at ack time — normally `"pending"`; it
     * is NOT a delivery guarantee. [blockedCount] rides only when some
     * recipients were blocked but others were sent (group broadcast).
     */
    @Serializable
    data class MessageSent(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("status") val status: String,
        @SerialName("blocked_count") val blockedCount: Int = 0,
    )

    /**
     * Server → client: the phone refused or failed the send. The
     * desktop marks the optimistic row failed and offers a retry.
     * Refusals are never protocol violations — the socket stays up.
     */
    @Serializable
    data class MessageFailed(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /** The linked app doesn't hold the default SMS role — sending is impossible. */
            const val HUB_NOT_DEFAULT_SMS = "hub_not_default_sms"

            /** The conversation is gone, or is a vault conversation the desktop must never address. */
            const val UNKNOWN_CONVERSATION = "unknown_conversation"

            /** Every recipient is blocked. */
            const val BLOCKED_RECIPIENT = "blocked_recipient"

            /** The body is over the [MAX_SEND_BODY_BYTES] wire cap. */
            const val MESSAGE_TOO_LONG = "message_too_long"

            /** The radio refused synchronously, or the send could not be retried. */
            const val SEND_FAILED = "send_failed"

            /** Anything else; [MessageFailed.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * Server → client: the ack for every action type (`mark_read`,
     * `mark_all_read`, `set_pin`, `set_archived`, `set_muted`,
     * `set_reaction`, `set_pinned_message`). One result type for all of
     * them: [action] echoes the request type. [ok] `false` means the
     * phone did not apply it — the desktop reverts its optimistic
     * change. [messageId] is present for `set_reaction` /
     * `set_pinned_message`; [conversationId] is absent for
     * `mark_all_read`. Codes reuse the [MessageFailed] set where they
     * apply, plus `send_failed` for a reaction whose SMS didn't go out
     * (the local reaction state stays set on the phone in that case,
     * matching the phone's own behavior).
     */
    @Serializable
    data class ActionResult(
        @SerialName("action") val action: String,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("message_id") val messageId: String? = null,
        @SerialName("ok") val ok: Boolean,
        @SerialName("code") val code: String? = null,
        @SerialName("message") val message: String? = null,
    )

    /**
     * What the server makes of one incoming text frame. [Unknown] keeps
     * the connection (a newer client may send types this build does not
     * know yet); [Malformed] is a protocol violation.
     */
    sealed interface Parsed {
        data class Hello(val value: SyncProtocol.Hello) : Parsed
        data class Auth(val value: SyncProtocol.Auth) : Parsed
        data class Ping(val value: SyncProtocol.Ping) : Parsed
        data class Pong(val value: SyncProtocol.Ping) : Parsed
        data class SyncRequest(val value: SyncProtocol.SyncRequest) : Parsed
        data class MessagesRequest(val value: SyncProtocol.MessagesRequest) : Parsed
        data class SendMessage(val value: SyncProtocol.SendMessage) : Parsed
        data class MarkRead(val value: SyncProtocol.MarkRead) : Parsed
        data class MarkAllRead(val value: SyncProtocol.MarkAllRead) : Parsed
        data class SetPin(val value: SyncProtocol.SetPin) : Parsed
        data class SetArchived(val value: SyncProtocol.SetArchived) : Parsed
        data class SetMuted(val value: SyncProtocol.SetMuted) : Parsed
        data class SetReaction(val value: SyncProtocol.SetReaction) : Parsed
        data class SetPinnedMessage(val value: SyncProtocol.SetPinnedMessage) : Parsed
        data class Unknown(val type: String?) : Parsed
        data object Malformed : Parsed
    }

    /**
     * Parses one incoming text frame. Never throws: bad JSON, a missing
     * `type`, or a wrongly-shaped known message all come back as
     * [Parsed.Malformed]; a well-formed message of an unknown type comes
     * back as [Parsed.Unknown].
     */
    fun parse(text: String): Parsed {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Parsed.Malformed
        val obj: JsonObject = runCatching { element.jsonObject }.getOrNull()
            ?: return Parsed.Malformed
        val type = obj["type"]?.jsonPrimitive?.content
        return when (type) {
            "hello" -> decode<Hello>(obj)?.let(Parsed::Hello) ?: Parsed.Malformed
            "auth" -> decode<Auth>(obj)?.let(Parsed::Auth) ?: Parsed.Malformed
            "ping" -> decode<Ping>(obj)?.let(Parsed::Ping) ?: Parsed.Malformed
            "pong" -> decode<Ping>(obj)?.let(Parsed::Pong) ?: Parsed.Malformed
            "sync_request" -> decode<SyncRequest>(obj)?.let(Parsed::SyncRequest) ?: Parsed.Malformed
            "messages_request" -> decode<MessagesRequest>(obj)?.let(Parsed::MessagesRequest) ?: Parsed.Malformed
            "send_message" -> decode<SendMessage>(obj)?.let(Parsed::SendMessage) ?: Parsed.Malformed
            "mark_read" -> decode<MarkRead>(obj)?.let(Parsed::MarkRead) ?: Parsed.Malformed
            "mark_all_read" -> decode<MarkAllRead>(obj)?.let(Parsed::MarkAllRead) ?: Parsed.Malformed
            "set_pin" -> decode<SetPin>(obj)?.let(Parsed::SetPin) ?: Parsed.Malformed
            "set_archived" -> decode<SetArchived>(obj)?.let(Parsed::SetArchived) ?: Parsed.Malformed
            "set_muted" -> decode<SetMuted>(obj)?.let(Parsed::SetMuted) ?: Parsed.Malformed
            "set_reaction" -> decode<SetReaction>(obj)?.let(Parsed::SetReaction) ?: Parsed.Malformed
            "set_pinned_message" -> decode<SetPinnedMessage>(obj)?.let(Parsed::SetPinnedMessage) ?: Parsed.Malformed
            null -> Parsed.Malformed
            else -> Parsed.Unknown(type)
        }
    }

    private inline fun <reified T> decode(obj: JsonObject): T? =
        runCatching { json.decodeFromJsonElement<T>(obj) }.getOrNull()

    /** Encodes a message with its `type` field, ready to send. */
    fun encodeHello(value: Hello): String = withType(value, "hello").toString()

    fun encodeChallenge(value: Challenge): String = withType(value, "challenge").toString()

    fun encodeAuthOk(value: AuthOk): String = withType(value, "auth_ok").toString()

    fun encodeAuthFailed(value: AuthFailed): String = withType(value, "auth_failed").toString()

    fun encodePing(value: Ping): String = withType(value, "ping").toString()

    fun encodePong(value: Ping): String = withType(value, "pong").toString()

    /** The `revoked` message carries no payload, so it is a constant, not an encoder. */
    const val REVOKED_JSON = """{"type":"revoked"}"""

    fun encodeError(message: String): String = withType(Error(message), "error").toString()

    fun encodeSyncStarted(value: SyncStarted): String = withType(value, "sync_started").toString()

    fun encodeConversations(value: Conversations): String = withType(value, "conversations").toString()

    fun encodeMessagesBatch(value: MessagesBatch): String = withType(value, "messages_batch").toString()

    fun encodeSyncComplete(value: SyncComplete): String = withType(value, "sync_complete").toString()

    fun encodeMessageNew(value: MessageNew): String = withType(value, "message_new").toString()

    fun encodeMessageUpdated(value: MessageUpdated): String = withType(value, "message_updated").toString()

    fun encodeConversationUpdated(value: ConversationUpdated): String =
        withType(value, "conversation_updated").toString()

    fun encodeConversationDeleted(value: ConversationDeleted): String =
        withType(value, "conversation_deleted").toString()

    fun encodeSyncError(value: SyncError): String = withType(value, "sync_error").toString()

    fun encodeHubStatus(value: HubStatus): String = withType(value, "hub_status").toString()

    fun encodeMessageSent(value: MessageSent): String = withType(value, "message_sent").toString()

    fun encodeMessageFailed(value: MessageFailed): String = withType(value, "message_failed").toString()

    fun encodeActionResult(value: ActionResult): String = withType(value, "action_result").toString()

    /**
     * The `type` field is not part of the data classes (it is routing,
     * not payload), so it is merged in at encode time: the class is
     * encoded to a JSON object and `type` is added. A tiny extra step
     * that keeps the models honest.
     */
    private inline fun <reified T> withType(value: T, type: String): JsonObject {
        val obj = json.encodeToJsonElement(serializer(), value).jsonObject
        return JsonObject(obj + ("type" to JsonPrimitive(type)))
    }
}
