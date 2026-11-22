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
 *
 * Slice 4 adds the attachment-byte types (`attachment_request` …
 * `attachment_upload_error`) in the same envelope, and extends
 * `send_message` with an optional `attachments` array; the protocol
 * version stays 1.
 *
 * Phase 2 adds the scheduled-send types (`schedule_message`,
 * `message_scheduled`) in the same envelope; failures reuse
 * `message_failed`. The protocol version stays 1.
 *
 * Phase 2 also adds the scheduled-cancel types (`cancel_scheduled`,
 * `schedule_cancelled`) in the same envelope; failures reuse
 * `message_failed` with an empty `conversation_id` (a cancel
 * carries no conversation — the desktop matches by
 * `client_message_id`). The protocol version stays 1.
 *
 * Slice 5 adds the contact-sync types (`contacts_request` …
 * `contacts_error`) in the same envelope, plus one optional field
 * on `sync_started` (`total_contacts`); avatars travel through the
 * Slice 4 chunk frames under the `avatar:<number>` id, with their
 * own request type (`avatar_request`). The protocol version stays 1.
 *
 * Create conversation adds the desktop's new-conversation types
 * (`create_conversation`, `conversation_created`) in the same
 * envelope; failures reuse `action_result`. The protocol version
 * stays 1.
 *
 * Round 3 adds the contact-photo type (`set_contact_photo`) in the
 * same envelope; the answer reuses `action_result` (with the
 * address echoed, so the desktop attributes the reply to one pick).
 * The protocol version stays 1.
 *
 * Live calls add the call-signaling types (`incoming_call`,
 * `call_state`, `dial_request`, `dial_result`) in the same
 * envelope; the audio itself travels over Bluetooth HFP on the
 * desktop side — these frames are signaling + dial only. The
 * protocol version stays 1.
 *
 * Category mutes add the mute-sync types (`category_mutes`,
 * `set_category_muted`) in the same envelope, plus one optional
 * field on `action_result` (`category`, the echo for
 * `set_category_muted`). The protocol version stays 1.
 *
 * Round 42 adds the E2E safety-number + notice types
 * (`e2e_safety_numbers`, `e2e_notices`, `set_e2e_verified`,
 * `retry_e2e_probe`) in the same envelope; the answers reuse
 * `action_result` (with the address echoed, like
 * `set_contact_photo`). The protocol version stays 1.
 */
object SyncProtocol {

    /** Fixed LAN port from the spec — discovery and the server agree on it. */
    const val PORT = 47831

    /** mDNS service type from the spec. */
    const val SERVICE_TYPE = "_relay._tcp."

    /**
     * The hub name when the device model is unknown: the fallback
     * [LinkedDeviceStore.hubDisplayName] returns, and the name
     * clients saw before hub names became the device model.
     */
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

    /**
     * Round 3 — the largest `set_contact_photo` photo the server
     * accepts, in decoded bytes. A 512 px JPEG is tens of KB; the
     * cap guards against an abusive frame, not a real photo.
     */
    const val MAX_CONTACT_PHOTO_BYTES = 512 * 1024

    /** Slice 4 — one attachment chunk carries this many raw bytes (~87 KB as base64url in JSON). */
    const val ATTACHMENT_CHUNK_BYTES = 64 * 1024

    /**
     * Slice 4 — the phone refuses a download whose stored size exceeds
     * this. A guard against a corrupt row claiming gigabytes, not a
     * limit anyone should hit (real MMS parts are far smaller).
     */
    const val MAX_ATTACHMENT_DOWNLOAD_BYTES = 25L * 1024 * 1024

    /**
     * Round 36 phase 2 — the largest `attachment_upload_start`
     * with `kind: "note"` the phone accepts, in decoded bytes.
     * Notes store originals (unlike MMS, which is capped at the
     * 280 KB carrier payload), so the note cap matches the
     * download cap and the note thread's own staging cap (25 MB)
     * instead. An upload without `kind` keeps the MMS cap.
     */
    const val MAX_NOTE_ATTACHMENT_UPLOAD_BYTES = 25L * 1024 * 1024

    /**
     * Round 36 phase 2 — the prefix on a note attachment's
     * download ids: `attachment_id` and `message_id` of an
     * `attachment_request` for a note attachment are
     * `"note:<hub id>"` (hub note-attachment ids and MMS
     * attachment ids are independent autoincrement sequences, so
     * a bare id is ambiguous across the two tables). Chunk frames
     * echo the prefixed id, so the client routes them back to the
     * note download. Mirrors the `avatar:<number>` convention.
     */
    const val NOTE_ATTACHMENT_ID_PREFIX = "note:"

    /**
     * Round 36 phase 2 — the `kind` value on an
     * `attachment_upload_start` for a note attachment. A note
     * upload is capped at [MAX_NOTE_ATTACHMENT_UPLOAD_BYTES];
     * an upload without `kind` keeps the MMS cap.
     */
    const val UPLOAD_KIND_NOTE = "note"

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
     * [sinceNoteMessageId] is the same cursor for note messages
     * (round 36 phase 2): the note LIST always streams in full, but
     * the per-note message pages page on this cursor.
     */
    @Serializable
    data class SyncRequest(
        @SerialName("mode") val mode: String,
        @SerialName("since_message_id") val sinceMessageId: String? = null,
        @SerialName("since_note_message_id") val sinceNoteMessageId: String? = null,
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
     * the desktop warns that live sync will lag. [hubTunnelIp] is
     * the phone's tunnel IPv4 address, when a VPN tunnel is the
     * phone's active network — the address the desktop dials to
     * reach the phone through the tunnel; absent (null) means no
     * tunnel path right now, and the desktop keeps dialing the LAN
     * address (and keeps any tunnel address it stored earlier — the
     * tunnel may come back with the same address).
     *
     * Slice 5 — [totalContacts] is the contact snapshot size (the
     * `contacts` batches that follow `sync_started` carry that many
     * contacts). Optional, default 0: 0 means "no contacts synced"
     * (none on the phone, or the contacts read failed — the failure
     * itself rides as `contacts_error`, never as `sync_error`, so
     * message sync proceeds regardless).
     *
     * Round 36 phase 2 — [cursorNoteMessageId] is the note cursor
     * (the max note-message id on the phone when the stream
     * started) and [totalNotes] the note count; both default to
     * their empty values so an older hub's frame still parses.
     */
    @Serializable
    data class SyncStarted(
        @SerialName("mode") val mode: String,
        @SerialName("total_conversations") val totalConversations: Int,
        @SerialName("total_messages") val totalMessages: Long,
        @SerialName("cursor_message_id") val cursorMessageId: String,
        @SerialName("hub_is_default_sms") val hubIsDefaultSms: Boolean,
        @SerialName("hub_tunnel_ip") val hubTunnelIp: String? = null,
        @SerialName("total_contacts") val totalContacts: Int = 0,
        @SerialName("cursor_note_message_id") val cursorNoteMessageId: String = "0",
        @SerialName("total_notes") val totalNotes: Int = 0,
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

    /**
     * Server → client: the initial stream is finished.
     * [cursorNoteMessageId] (round 36 phase 2) is the note cursor
     * the client persists alongside the message cursor; `"0"` when
     * the phone holds no note messages yet.
     */
    @Serializable
    data class SyncComplete(
        @SerialName("cursor_message_id") val cursorMessageId: String,
        @SerialName("cursor_note_message_id") val cursorNoteMessageId: String = "0",
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
     * connection learns it first. [tunnelIp] is the phone's tunnel
     * IPv4 address, pushed the same way when the tunnel comes up or
     * goes down mid-session; absent (null) means no tunnel path
     * right now, and the desktop keeps any tunnel address it stored
     * earlier.
     */
    @Serializable
    data class HubStatus(
        @SerialName("is_default_sms") val isDefaultSms: Boolean,
        @SerialName("tunnel_ip") val tunnelIp: String? = null,
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
     *
     * Slice 4 — [attachments] carries the media of the send (upload
     * references and/or stickers). Absent for a text send, which is
     * byte-identical to Slice 3.
     */
    @Serializable
    data class SendMessage(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("body") val body: String,
        @SerialName("reply_to_message_id") val replyToMessageId: String? = null,
        @SerialName("attachments") val attachments: List<SendAttachment>? = null,
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

    /** Client → server: block / unblock one conversation. */
    @Serializable
    data class SetBlocked(
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

            /**
             * Slice 4 — the staged upload is gone (TTL sweep, cache
             * clear); the desktop re-uploads, then re-sends with the
             * same key.
             */
            const val UPLOAD_EXPIRED = "upload_expired"

            /** Anything else; [MessageFailed.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"

            /**
             * The request itself is unusable — an empty address list
             * on `create_conversation` (every address blank, or none
             * at all). A client bug, not a phone failure; the
             * desktop shows the message and lets the user pick again.
             */
            const val INVALID_REQUEST = "invalid_request"

            /**
             * Round 3 — `set_contact_photo`: no contact exists for
             * the number, so there is nothing to attach the photo
             * to. The phone never creates a contact for a photo set.
             */
            const val UNKNOWN_CONTACT = "unknown_contact"

            /**
             * Round 3 — `set_contact_photo`: the phone lacks
             * WRITE_CONTACTS, so it cannot write the photo. The
             * user grants it from the Linked devices screen (or
             * the phone's app settings) and picks again.
             */
            const val HUB_NO_CONTACTS_WRITE = "hub_no_contacts_write"

            /** Round 3 — `set_contact_photo`: the photo exceeds [MAX_CONTACT_PHOTO_BYTES]. */
            const val PHOTO_TOO_LARGE = "photo_too_large"
        }
    }

    /**
     * Server → client: the ack for every action type (`mark_read`,
     * `mark_all_read`, `set_pin`, `set_archived`, `set_muted`,
     * `set_blocked`, `set_reaction`, `set_pinned_message`). One result type for all of
     * them: [action] echoes the request type. [ok] `false` means the
     * phone did not apply it — the desktop reverts its optimistic
     * change. [messageId] is present for `set_reaction` /
     * `set_pinned_message`; [conversationId] is absent for
     * `mark_all_read`. [address] is present for `set_contact_photo`
     * (round 3): the reply echoes the number, so the desktop
     * attributes it to exactly one in-flight pick — and unlike a
     * write-back, a refusal does NOT revert the desktop's local
     * photo choice. [address] is also present for
     * `set_e2e_verified` / `retry_e2e_probe` (round 42): the reply
     * echoes the peer's number, so the desktop attributes it to
     * the peer it acted on. [category] is present for `set_category_muted`
     * (category mutes): the reply echoes the category, so the
     * desktop attributes it to the category it changed. Codes reuse
     * the [MessageFailed] set where they apply, plus `send_failed`
     * for a reaction whose SMS didn't go out (the local reaction
     * state stays set on the phone in that case, matching the
     * phone's own behavior).
     */
    @Serializable
    data class ActionResult(
        @SerialName("action") val action: String,
        @SerialName("conversation_id") val conversationId: String? = null,
        @SerialName("message_id") val messageId: String? = null,
        @SerialName("address") val address: String? = null,
        @SerialName("ok") val ok: Boolean,
        @SerialName("code") val code: String? = null,
        @SerialName("message") val message: String? = null,
        // Last on purpose: the client tests construct this type
        // positionally, and a new field ahead of `ok` would
        // rebind their arguments. (JSON key order is irrelevant
        // to the desktop — it reads by key.)
        @SerialName("category") val category: String? = null,
    )

    // -- Slice 4: attachment bytes (SYNC_PROTOCOL.md, Slice 4) --------

    /**
     * Client → server: fetch one attachment's bytes. [messageId] is
     * the membership guard: the phone verifies the attachment belongs
     * to that message and the message to a non-vault conversation — a
     * stale or malicious client must not pull vault bytes by id.
     */
    @Serializable
    data class AttachmentRequest(
        @SerialName("attachment_id") val attachmentId: String,
        @SerialName("message_id") val messageId: String,
    )

    /**
     * Server → client: one slice of an attachment's bytes — at most
     * [ATTACHMENT_CHUNK_BYTES] raw bytes as base64url, [offset] the
     * raw byte offset (chunks are sequential per attachment, so the
     * desktop appends in arrival order and checks `offset` against
     * the bytes accumulated so far). [sha256] (hex) and [sizeBytes]
     * (actual bytes read) appear only on the final chunk: the phone
     * hashes while streaming, so the digest is only known at the end.
     * A file whose size is an exact multiple of the chunk size ends
     * with one empty final chunk.
     */
    @Serializable
    data class AttachmentChunk(
        @SerialName("attachment_id") val attachmentId: String,
        @SerialName("offset") val offset: Long,
        @SerialName("data_base64url") val dataBase64Url: String,
        @SerialName("final") val isFinal: Boolean,
        @SerialName("sha256") val sha256: String? = null,
        @SerialName("size_bytes") val sizeBytes: Long? = null,
    )

    /** Server → client: the download failed, instead of chunks. */
    @Serializable
    data class AttachmentError(
        @SerialName("attachment_id") val attachmentId: String,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /**
             * No such row, or it belongs to a different message / a
             * vault conversation — same answer for all three, so ids
             * can't be probed.
             */
            const val UNKNOWN_ATTACHMENT = "unknown_attachment"

            /** The row exists, but the bytes don't (file gone, part URI won't open). */
            const val ATTACHMENT_UNREADABLE = "attachment_unreadable"

            /** Over the [MAX_ATTACHMENT_DOWNLOAD_BYTES] cap. */
            const val ATTACHMENT_TOO_LARGE = "attachment_too_large"

            /** Anything else; [AttachmentError.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * Client → server: begin an upload. [clientMessageId] keys the
     * whole upload session (one send's attachments share it);
     * [index] is the attachment's position in the send (0-based).
     * The phone answers `attachment_upload_ready` or
     * `attachment_upload_error` — the desktop sends no chunks before
     * `ready`. Round 36 phase 2 — [kind] is `"note"` for a note
     * attachment (capped at [MAX_NOTE_ATTACHMENT_UPLOAD_BYTES]);
     * absent means an MMS send (capped at the 280 KB carrier
     * payload). The chunks carry no kind — the start frame sets it
     * for the whole upload session.
     */
    @Serializable
    data class AttachmentUploadStart(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("index") val index: Int,
        @SerialName("mime_type") val mimeType: String,
        @SerialName("file_name") val fileName: String? = null,
        @SerialName("size_bytes") val sizeBytes: Long,
        @SerialName("sha256") val sha256: String,
        @SerialName("kind") val kind: String? = null,
    )

    /**
     * Client → server: one upload chunk, streamed — same
     * [ATTACHMENT_CHUNK_BYTES] chunking and `offset` rule as the
     * download. No per-chunk ack: chunks stream over TCP; only a
     * disconnect can lose one, and a disconnect fails the send
     * anyway.
     */
    @Serializable
    data class AttachmentUploadChunk(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("index") val index: Int,
        @SerialName("offset") val offset: Long,
        @SerialName("data_base64url") val dataBase64Url: String,
        @SerialName("final") val isFinal: Boolean,
    )

    /** Server → client: the upload was validated and the staging file created. */
    @Serializable
    data class AttachmentUploadReady(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("index") val index: Int,
    )

    /** Server → client: all chunks arrived and size + SHA-256 verified. */
    @Serializable
    data class AttachmentUploadDone(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("index") val index: Int,
    )

    /** Server → client: the upload failed. */
    @Serializable
    data class AttachmentUploadError(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("index") val index: Int,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /**
             * Over the upload cap — the 280 KB MMS cap for a send
             * upload, [MAX_NOTE_ATTACHMENT_UPLOAD_BYTES] for a note
             * upload (`kind: "note"`).
             */
            const val UPLOAD_TOO_LARGE = "upload_too_large"

            /** SHA-256 mismatch at `final` — the partial is deleted. */
            const val UPLOAD_BAD_CHECKSUM = "upload_bad_checksum"

            /** Anything else; [AttachmentUploadError.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * One entry of `send_message.attachments` (Slice 4): exactly one
     * of an upload reference ([uploadIndex] + [mimeType] + optional
     * [fileName], [width], [height], [durationMs]) or a sticker
     * ([stickerEmoji] — the phone renders it, no bytes cross). One
     * flat data class with nullable fields, no polymorphic
     * serializer: an entry with neither or both is a client bug and
     * the send fails with `message_failed` `internal`.
     */
    @Serializable
    data class SendAttachment(
        @SerialName("upload_index") val uploadIndex: Int? = null,
        @SerialName("sticker_emoji") val stickerEmoji: String? = null,
        @SerialName("mime_type") val mimeType: String? = null,
        @SerialName("file_name") val fileName: String? = null,
        @SerialName("width") val width: Int? = null,
        @SerialName("height") val height: Int? = null,
        @SerialName("duration_ms") val durationMs: Long? = null,
    )

    // -- Phase 2: scheduled sends (SYNC_PROTOCOL.md, Phase 2) ----------

    /**
     * Client → server: schedule a text message in an existing
     * conversation for [sendAt] (epoch millis). [clientMessageId] is
     * a UUID v4 generated by the desktop per schedule attempt — a
     * retry of the same schedule REUSES it, which is what makes the
     * retry safe (the phone dedups on it; see SyncMessageSource).
     * The phone resolves the recipients from the conversation, exactly
     * as for `send_message`, and schedules through its own scheduler.
     */
    @Serializable
    data class ScheduleMessage(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("body") val body: String,
        @SerialName("send_at") val sendAt: Long,
    )

    /**
     * Server → client: the phone scheduled the message — the ack to
     * `schedule_message`, matched by [clientMessageId]. [messageId]
     * is the phone's scheduled-row id for a fresh or pending
     * schedule, or the sent message row's id when the key already
     * fired (the dedup returning the stored row). [sendAt] echoes
     * the scheduled time in epoch millis. The message itself arrives
     * later via `message_new`, carrying [clientMessageId] so the
     * desktop reconciles it with the scheduled row.
     */
    @Serializable
    data class MessageScheduled(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("send_at") val sendAt: Long,
    )

    /**
     * Client → server: cancel the scheduled message keyed by
     * [clientMessageId]. Idempotent: an unknown key (already
     * cancelled, deleted, or never scheduled) and a key whose
     * message already fired both ack as cancelled — the schedule is
     * gone either way. After a cancel the key is free: a later
     * `schedule_message` with the same key creates a fresh schedule.
     */
    @Serializable
    data class CancelScheduled(
        @SerialName("client_message_id") val clientMessageId: String,
    )

    /**
     * Server → client: the scheduled message is cancelled — the ack
     * to `cancel_scheduled`, matched by [clientMessageId]. A genuine
     * internal failure answers `message_failed` instead, with an
     * empty `conversation_id` (a cancel carries no conversation).
     */
    @Serializable
    data class ScheduleCancelled(
        @SerialName("client_message_id") val clientMessageId: String,
    )

    // -- Slice 5: contact sync (SYNC_PROTOCOL.md, Slice 5) -----------

    /**
     * Client → server: re-fetch the full contact snapshot without
     * touching messages (Settings retry, or the engine's repair path
     * after a `contacts_error`). No payload. The phone answers with
     * `contacts` batches, or `contacts_error`.
     */
    @Serializable
    data object ContactsRequest

    /**
     * Client → server: one contact photo. [address] is the normalized
     * number, exactly as it appears in the CONTACT payload — the
     * phone resolves it with the same `PhoneLookup` the UI uses. The
     * answer reuses the Slice 4 frames: `attachment_chunk` ×N under
     * the `avatar:<number>` id, or `attachment_error`
     * `unknown_attachment` (one code for no contact / no photo /
     * unreadable stream — the paired desktop already knows
     * `has_photo` from the CONTACT payload, so the error reveals
     * nothing new).
     */
    @Serializable
    data class AvatarRequest(
        @SerialName("address") val address: String,
    )

    /**
     * Server → client: one batch of the contact snapshot (100 per
     * batch), display-name order. The desktop upserts each batch;
     * when a batch has `has_more: false` the snapshot is complete
     * and the desktop prunes local numbers absent from it. An empty
     * snapshot still sends one empty batch — the prune signal.
     */
    @Serializable
    data class Contacts(
        @SerialName("contacts") val contacts: List<SyncedContact>,
        @SerialName("has_more") val hasMore: Boolean,
    )

    /**
     * Server → client: one contact changed (live delta) or was
     * added. Upsert by phones. [removedPhones] appears only when
     * the contact lost numbers — the desktop deletes those rows, so
     * a removed number does not linger until the next full snapshot.
     */
    @Serializable
    data class ContactUpdated(
        @SerialName("contact") val contact: SyncedContact,
        @SerialName("removed_phones") val removedPhones: List<String>? = null,
    )

    /**
     * Server → client: one contact deleted (live delta). [phones]
     * are the contact's last-known numbers; the desktop deletes
     * those rows (and their avatar files).
     */
    @Serializable
    data class ContactDeleted(
        @SerialName("phones") val phones: List<String>,
    )

    /**
     * Server → client: the phone cannot sync contacts right now.
     * Separate from `sync_error` by design — message sync proceeds
     * regardless. The desktop shows the reason in Settings → Linked
     * devices with a Retry (which sends `contacts_request`); it does
     * not touch the SyncDialog state.
     */
    @Serializable
    data class ContactsError(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /** READ_CONTACTS missing on the phone. */
            const val HUB_NO_CONTACTS_ACCESS = "hub_no_contacts_access"

            /** Anything else; [ContactsError.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    // -- Create conversation (SYNC_PROTOCOL.md, Create conversation) ---

    /**
     * Client → server: create a conversation for [addresses] (one
     * number = a 1:1 conversation, several = a group) and name it
     * [customName] when one was typed. [addresses] are normalized
     * number strings; [customName] absent or blank means an unnamed
     * group (it shows member names, as on the phone). There is NO
     * `client_message_id`: the phone's `findOrCreate` is idempotent
     * for the same member set — a retry returns the existing
     * conversation, never a duplicate — so no dedup key is needed.
     */
    @Serializable
    data class CreateConversation(
        @SerialName("addresses") val addresses: List<String>,
        @SerialName("custom_name") val customName: String? = null,
    )

    /**
     * Server → client: the conversation was created (or already
     * existed for the same member set) — the ack to
     * `create_conversation`. [conversation] is the full
     * `SyncedConversation`, the exact same shape as one entry in
     * the `conversations` batches, so the desktop opens it the same
     * way it opens any synced conversation. A failure answers
     * `action_result` with `action: "create_conversation"` and
     * `ok: false` instead — no `conversation_id` (none exists yet).
     */
    @Serializable
    data class ConversationCreated(
        @SerialName("conversation") val conversation: SyncedConversation,
    )

    // -- Round 3: contact photo (SYNC_PROTOCOL.md, Contact photo) -----

    /**
     * Client → server: set one contact's photo. [address] is the
     * normalized number, exactly as it appears in the CONTACT
     * payload — the phone resolves it with the same `PhoneLookup`
     * the UI uses. [photoBase64Url] is the prepared 512 px JPEG as
     * base64url (the protocol's byte alphabet, like the chunk
     * frames), in ONE frame — a 512 px JPEG is small (tens of KB),
     * so the Slice 4 chunking is not needed. The phone answers
     * `action_result` (`action: "set_contact_photo"`, the address
     * echoed, `ok` + `code` / `message` on refusal — no contact for
     * the number, or the phone lacks the contacts-write
     * permission). The desktop applies the photo locally first, so
     * a refusal keeps the local choice and only shows the reason.
     */
    @Serializable
    data class SetContactPhoto(
        @SerialName("address") val address: String,
        @SerialName("photo_base64url") val photoBase64Url: String,
    )

    // -- Live calls: call signaling + dial (SYNC_PROTOCOL.md, Live calls) --

    /**
     * Server → client: the phone started ringing. Sent together
     * with a `call_state` `ringing` for the same transition — the
     * announcement carries the [timestamp] (epoch millis, when
     * the ring was observed), the state frame carries the state.
     * [number] is "" when the number is unknown: a withheld
     * number, or a phone without READ_CALL_LOG (since Android 9
     * the platform passes an empty number to listeners that lack
     * it).
     */
    @Serializable
    data class IncomingCall(
        @SerialName("number") val number: String,
        @SerialName("timestamp") val timestamp: Long,
    )

    /**
     * Server → client: the phone's call state changed — sent on
     * EVERY transition (ringing, active, ended), broadcast to
     * every authenticated session. [number] is the number of the
     * call in progress, "" when unknown (an outgoing call dialed
     * on the phone has no number to report — the platform only
     * reports a number while ringing).
     */
    @Serializable
    data class CallState(
        @SerialName("state") val state: String,
        @SerialName("number") val number: String,
    ) {
        companion object {
            /** The phone is ringing (an incoming call). */
            const val RINGING = "ringing"

            /** The phone dialed out (off-hook, not yet answered). */
            const val DIALING = "dialing"

            /** A call is off-hook (an incoming call answered). */
            const val ACTIVE = "active"

            /** The call ended (hung up, missed, or rejected). */
            const val ENDED = "ended"
        }
    }

    /**
     * Client → server: place a cellular call on the phone. The
     * phone dials [number] with ACTION_CALL + CALL_PHONE and
     * answers `dial_result`. The resulting off-hook transition
     * also arrives as `call_state` `dialing`, so the desktop sees
     * its own dial land (the answer itself comes over Bluetooth
     * HFP, or never — Android reports no answer moment for a
     * dialed call).
     */
    @Serializable
    data class DialRequest(
        @SerialName("number") val number: String,
    )

    /**
     * Server → client: the ack to `dial_request`. [ok] `false`
     * means the phone did not dial — [error] is a short
     * snake_case code, never a stack trace. A refused dial is
     * never a protocol violation — the socket stays up.
     */
    @Serializable
    data class DialResult(
        @SerialName("ok") val ok: Boolean,
        @SerialName("error") val error: String? = null,
    ) {
        companion object {
            /** CALL_PHONE is not granted on the phone. */
            const val PERMISSION_DENIED = "permission_denied"

            /** The request carried no usable number (blank). */
            const val NO_NUMBER = "no_number"

            /** Anything else: no dialer installed, or the OS refused. */
            const val FAILED = "failed"
        }
    }

    // -- Category mutes (SYNC_PROTOCOL.md, Category mutes) ------------

    /**
     * Server → client: the FULL set of muted categories, as
     * lowercase wire strings (`"personal"`, `"otp"`,
     * `"promotional"`, `"transactions"`, `"other"`). Sent after
     * every snapshot (right after `sync_complete`) and whenever
     * the set changes on the phone — the phone is the source of
     * truth, so the desktop replaces its own list with this one
     * wholesale (an empty list means nothing is muted). Sorted,
     * so the frame is stable.
     */
    @Serializable
    data class CategoryMutes(
        @SerialName("muted_categories") val mutedCategories: List<String>,
    )

    /**
     * Client → server: mute / unmute one category. [category] is
     * a lowercase wire string; [muted] is REQUIRED (like
     * `set_muted`'s `value` — a missing flag is malformed, never
     * a silent unmute). An unknown category is refused with
     * `action_result` `ok: false` `invalid_request` — the phone
     * applies nothing and the desktop reverts its optimistic
     * change. A successful apply goes through AppSettings, so
     * every session's collector sees the change and broadcasts
     * the updated set back via `category_mutes`.
     */
    @Serializable
    data class SetCategoryMuted(
        @SerialName("category") val category: String,
        @SerialName("muted") val muted: Boolean,
    )

    // -- Round 36 phase 2: notes sync (SYNC_PROTOCOL.md, Notes sync) --

    /** Server → client: one batch of the note snapshot (100 per batch). */
    @Serializable
    data class Notes(
        @SerialName("notes") val notes: List<SyncedNote>,
        @SerialName("has_more") val hasMore: Boolean,
    )

    /**
     * Server → client: one page of one note's entries, newest first.
     * [nextBeforeId] is passed back as `before_id` for the next page;
     * absent when [hasMore] is false.
     */
    @Serializable
    data class NoteMessagesBatch(
        @SerialName("note_id") val noteId: String,
        @SerialName("messages") val messages: List<SyncedNoteMessage>,
        @SerialName("has_more") val hasMore: Boolean,
        @SerialName("next_before_id") val nextBeforeId: String? = null,
    )

    /**
     * Server → client: the note a `create_note` just made — the full
     * row, sent to the requesting session only. The other sessions
     * learn about it via `note_new`.
     */
    @Serializable
    data class NoteCreated(
        @SerialName("note") val note: SyncedNote,
    )

    /** Server → client: a note was created (pushed to the OTHER sessions). */
    @Serializable
    data class NoteNew(
        @SerialName("note") val note: SyncedNote,
    )

    /** Server → client: a note row changed — the full row is sent. */
    @Serializable
    data class NoteUpdated(
        @SerialName("note") val note: SyncedNote,
    )

    /** Server → client: a note was deleted. */
    @Serializable
    data class NoteDeleted(
        @SerialName("note_id") val noteId: String,
    )

    /** Server → client: one live note entry (a burst arrives as several frames). */
    @Serializable
    data class NoteMessageNew(
        @SerialName("message") val message: SyncedNoteMessage,
    )

    /** Server → client: a note entry was deleted. */
    @Serializable
    data class NoteMessageDeleted(
        @SerialName("note_id") val noteId: String,
        @SerialName("message_id") val messageId: String,
    )

    /**
     * Server → client: a note entry's text was edited — the full row
     * is sent (the client upserts it, so the "Edited" marker and the
     * new body land together). This is also the success signal for
     * `edit_note_message`: the broadcast goes to ALL sessions,
     * including the requester.
     */
    @Serializable
    data class NoteMessageUpdated(
        @SerialName("message") val message: SyncedNoteMessage,
    )

    /**
     * Client → server: create a note. [clientNoteId] is the client's
     * UUID dedup key: the hub stores it on the row, so a retried
     * create lands on the same note instead of duplicating it.
     * Answered by `note_created`, or `action_result` `ok: false`
     * (`action: "create_note"`) on failure.
     */
    @Serializable
    data class CreateNote(
        @SerialName("client_note_id") val clientNoteId: String,
        @SerialName("title") val title: String,
    )

    /**
     * Client → server: save an entry to a note. [clientMessageId]
     * is the client's UUID dedup key (same rule as `create_note`).
     * [attachments] reuses [SendAttachment]: an upload reference
     * ([uploadIndex] + metadata) per file, uploaded beforehand with
     * `kind: "note"`. Answered by `note_message_saved` or
     * `note_message_failed`.
     */
    @Serializable
    data class SaveNoteMessage(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("note_id") val noteId: String,
        @SerialName("body") val body: String,
        @SerialName("attachments") val attachments: List<SendAttachment>? = null,
    )

    /** Server → client: the `save_note_message` ack — the entry's hub id. */
    @Serializable
    data class NoteMessageSaved(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("note_id") val noteId: String,
    )

    /** Server → client: the `save_note_message` refusal. */
    @Serializable
    data class NoteMessageFailed(
        @SerialName("client_message_id") val clientMessageId: String,
        @SerialName("note_id") val noteId: String,
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /** No note carries that id. */
            const val UNKNOWN_NOTE = "unknown_note"

            /**
             * No entry carries that id — used by the
             * `edit_note_message` / `delete_note_message`
             * `action_result` refusals, never by `note_message_failed`
             * itself (a save has no entry id yet).
             */
            const val UNKNOWN_MESSAGE = "unknown_message"

            /** The entry is over the [MAX_SEND_BODY_BYTES] wire cap. */
            const val MESSAGE_TOO_LONG = "message_too_long"

            /** An upload the save references expired (24 h TTL) or never landed. */
            const val UPLOAD_EXPIRED = "upload_expired"

            /** Anything else; [NoteMessageFailed.message] stays generic. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * Client → server: edit a note entry's text. No client key —
     * edits are idempotent (re-sending the same edit is a no-op),
     * and the success signal is the `note_message_updated`
     * broadcast, not an ack. A refusal answers `action_result`
     * `ok: false` (`action: "edit_note_message"`, `code` one of
     * `unknown_note` / `unknown_message` / `message_too_long` /
     * `internal`) and the client reverts its optimistic edit.
     */
    @Serializable
    data class EditNoteMessage(
        @SerialName("note_id") val noteId: String,
        @SerialName("message_id") val messageId: String,
        @SerialName("body") val body: String,
    )

    /** Client → server: rename a note. Answered by `action_result`. */
    @Serializable
    data class RenameNote(
        @SerialName("note_id") val noteId: String,
        @SerialName("title") val title: String,
    )

    /** Client → server: delete a note. Answered by `action_result`. */
    @Serializable
    data class DeleteNote(
        @SerialName("note_id") val noteId: String,
    )

    /** Client → server: delete one entry. Answered by `action_result`. */
    @Serializable
    data class DeleteNoteMessage(
        @SerialName("note_id") val noteId: String,
        @SerialName("message_id") val messageId: String,
    )

    /** Client → server: pin / unpin a note. Answered by `action_result`. */
    @Serializable
    data class SetNotePin(
        @SerialName("note_id") val noteId: String,
        @SerialName("value") val value: Boolean,
    )

    /** Client → server: archive / unarchive a note. Answered by `action_result`. */
    @Serializable
    data class SetNoteArchived(
        @SerialName("note_id") val noteId: String,
        @SerialName("value") val value: Boolean,
    )

    /**
     * Client → server: targeted fetch of one note's entries.
     * [beforeId] fetches entries with id strictly below it, newest
     * first (`"0"` / omitted = newest); [limit] is capped at 200 by
     * the server. Answered by `note_messages_batch`.
     */
    @Serializable
    data class NoteMessagesRequest(
        @SerialName("note_id") val noteId: String,
        @SerialName("before_id") val beforeId: String? = null,
        @SerialName("limit") val limit: Int? = null,
    )

    // -- Round 42: E2E safety numbers + notices (SYNC_PROTOCOL.md, E2E safety numbers + notices) --

    /**
     * Server → client: the FULL set of E2E peers — every address
     * with a peer row — as [SyncedE2ePeer] entries. Sent after
     * every snapshot (right after `sync_complete`, next to
     * `category_mutes`) and whenever any peer row changes on the
     * phone — the phone is the source of truth, so the desktop
     * replaces its own set with this one wholesale (an empty list
     * means no peer has ever exchanged keys). The safety number
     * rides as the FORMATTED string (12 groups of 5 digits), or
     * null when the peer has no keys yet — the desktop stores and
     * displays it verbatim, never re-deriving, so the number it
     * shows is byte-identical to the phone's. Address order, so
     * the frame is stable.
     */
    @Serializable
    data class E2eSafetyNumbers(
        @SerialName("peers") val peers: List<SyncedE2ePeer>,
    )

    /**
     * Server → client: the FULL set of in-thread E2E notices, as
     * [SyncedE2eNotice] entries. Sent after every snapshot (right
     * after `sync_complete`, next to `category_mutes`) and
     * whenever any notice row changes on the phone — the phone is
     * the source of truth, so the desktop replaces its own set
     * with this one wholesale. The desktop renders each notice as
     * a centered system row in its thread, at [SyncedE2eNotice.date].
     */
    @Serializable
    data class E2eNotices(
        @SerialName("notices") val notices: List<SyncedE2eNotice>,
    )

    /**
     * Client → server: mark (or unmark) one peer verified.
     * [address] is the normalized number; [verified] is REQUIRED
     * (like `set_muted`'s `value` — a missing flag is malformed,
     * never a silent unverify). The phone updates its own store —
     * creating the peer row when none exists yet, like its own
     * verification sheet does — and answers `action_result` (the
     * address echoed); the collector's re-send of the full set is
     * the confirmation. Verification is optional (TOFU): the flag
     * is just a marker, and messaging works encrypted whether or
     * not a peer is verified.
     */
    @Serializable
    data class SetE2eVerified(
        @SerialName("address") val address: String,
        @SerialName("verified") val verified: Boolean,
    )

    /**
     * Client → server: retry the key exchange with one peer — the
     * same manual probe as the phone's Details panel "Retry
     * encryption" (one probe now, budget + discovery-toggle gates
     * lifted, address / blocked / ENCRYPTED gates still apply).
     * The phone answers `action_result` (the address echoed):
     * `ok: false` `unknown_conversation` when no 1:1 conversation
     * exists for the address, `send_failed` when the probe could
     * not go out.
     */
    @Serializable
    data class RetryE2eProbe(
        @SerialName("address") val address: String,
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
        data class SetCategoryMuted(val value: SyncProtocol.SetCategoryMuted) : Parsed
        data class SetBlocked(val value: SyncProtocol.SetBlocked) : Parsed
        data class SetReaction(val value: SyncProtocol.SetReaction) : Parsed
        data class SetPinnedMessage(val value: SyncProtocol.SetPinnedMessage) : Parsed
        data class AttachmentRequest(val value: SyncProtocol.AttachmentRequest) : Parsed
        data class AttachmentUploadStart(val value: SyncProtocol.AttachmentUploadStart) : Parsed
        data class AttachmentUploadChunk(val value: SyncProtocol.AttachmentUploadChunk) : Parsed
        data class ScheduleMessage(val value: SyncProtocol.ScheduleMessage) : Parsed
        data class CancelScheduled(val value: SyncProtocol.CancelScheduled) : Parsed
        data class ContactsRequest(val value: SyncProtocol.ContactsRequest) : Parsed
        data class AvatarRequest(val value: SyncProtocol.AvatarRequest) : Parsed
        data class CreateConversation(val value: SyncProtocol.CreateConversation) : Parsed
        data class SetContactPhoto(val value: SyncProtocol.SetContactPhoto) : Parsed
        data class DialRequest(val value: SyncProtocol.DialRequest) : Parsed
        data class CreateNote(val value: SyncProtocol.CreateNote) : Parsed
        data class SaveNoteMessage(val value: SyncProtocol.SaveNoteMessage) : Parsed
        data class EditNoteMessage(val value: SyncProtocol.EditNoteMessage) : Parsed
        data class RenameNote(val value: SyncProtocol.RenameNote) : Parsed
        data class DeleteNote(val value: SyncProtocol.DeleteNote) : Parsed
        data class DeleteNoteMessage(val value: SyncProtocol.DeleteNoteMessage) : Parsed
        data class SetNotePin(val value: SyncProtocol.SetNotePin) : Parsed
        data class SetNoteArchived(val value: SyncProtocol.SetNoteArchived) : Parsed
        data class NoteMessagesRequest(val value: SyncProtocol.NoteMessagesRequest) : Parsed

        /**
         * E2E commands (round 42): grouped under one sealed
         * sub-interface so the session handler dispatches them
         * through a single arm (the main dispatch sits at the
         * Detekt complexity cap).
         */
        sealed interface E2eCommand : Parsed
        data class SetE2eVerified(val value: SyncProtocol.SetE2eVerified) : E2eCommand
        data class RetryE2eProbe(val value: SyncProtocol.RetryE2eProbe) : E2eCommand
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
            null -> Parsed.Malformed
            // The remaining post-auth frames live in [parseRemaining]:
            // one `when` for all of them pushed `parse` over the
            // Detekt complexity cap (40).
            else -> parseRemaining(type, obj)
        }
    }

    /**
     * The second half of [parse]: the Slice 3 write-back actions, the
     * Slice 4 attachment frames, the Phase 2 schedule frames, the
     * Slice 5 contact frames, the create-conversation frame, the
     * round-3 contact-photo frame, the live-calls dial frame, and
     * the category-mute frame. The note frames live in
     * [parseNoteFrames] — one more `when` arm here would push this
     * function over the Detekt complexity cap (40). A well-formed
     * message of an unknown type comes back as [Parsed.Unknown],
     * like in [parse].
     */
    private fun parseRemaining(type: String, obj: JsonObject): Parsed = when (type) {
        "set_pin" -> decode<SetPin>(obj)?.let(Parsed::SetPin) ?: Parsed.Malformed
        "set_archived" -> decode<SetArchived>(obj)?.let(Parsed::SetArchived) ?: Parsed.Malformed
        "set_muted" -> decode<SetMuted>(obj)?.let(Parsed::SetMuted) ?: Parsed.Malformed
        "set_category_muted" -> decode<SetCategoryMuted>(obj)?.let(Parsed::SetCategoryMuted) ?: Parsed.Malformed
        "set_blocked" -> decode<SetBlocked>(obj)?.let(Parsed::SetBlocked) ?: Parsed.Malformed
        "set_reaction" -> decode<SetReaction>(obj)?.let(Parsed::SetReaction) ?: Parsed.Malformed
        "set_pinned_message" -> decode<SetPinnedMessage>(obj)?.let(Parsed::SetPinnedMessage) ?: Parsed.Malformed
        "attachment_request" -> decode<AttachmentRequest>(obj)?.let(Parsed::AttachmentRequest) ?: Parsed.Malformed
        "attachment_upload_start" -> decode<AttachmentUploadStart>(obj)?.let(Parsed::AttachmentUploadStart) ?: Parsed.Malformed
        "attachment_upload_chunk" -> decode<AttachmentUploadChunk>(obj)?.let(Parsed::AttachmentUploadChunk) ?: Parsed.Malformed
        "schedule_message" -> decode<ScheduleMessage>(obj)?.let(Parsed::ScheduleMessage) ?: Parsed.Malformed
        "cancel_scheduled" -> decode<CancelScheduled>(obj)?.let(Parsed::CancelScheduled) ?: Parsed.Malformed
        "contacts_request" -> decode<ContactsRequest>(obj)?.let(Parsed::ContactsRequest) ?: Parsed.Malformed
        "avatar_request" -> decode<AvatarRequest>(obj)?.let(Parsed::AvatarRequest) ?: Parsed.Malformed
        "create_conversation" -> decode<CreateConversation>(obj)?.let(Parsed::CreateConversation) ?: Parsed.Malformed
        "set_contact_photo" -> decode<SetContactPhoto>(obj)?.let(Parsed::SetContactPhoto) ?: Parsed.Malformed
        "dial_request" -> decode<DialRequest>(obj)?.let(Parsed::DialRequest) ?: Parsed.Malformed
        else -> parseNoteFrames(type, obj)
    }

    /** The third part of [parse]: the round-36 phase-2 note frames. */
    private fun parseNoteFrames(type: String, obj: JsonObject): Parsed = when (type) {
        "create_note" -> decode<CreateNote>(obj)?.let(Parsed::CreateNote) ?: Parsed.Malformed
        "save_note_message" -> decode<SaveNoteMessage>(obj)?.let(Parsed::SaveNoteMessage) ?: Parsed.Malformed
        "edit_note_message" -> decode<EditNoteMessage>(obj)?.let(Parsed::EditNoteMessage) ?: Parsed.Malformed
        "rename_note" -> decode<RenameNote>(obj)?.let(Parsed::RenameNote) ?: Parsed.Malformed
        "delete_note" -> decode<DeleteNote>(obj)?.let(Parsed::DeleteNote) ?: Parsed.Malformed
        "delete_note_message" -> decode<DeleteNoteMessage>(obj)?.let(Parsed::DeleteNoteMessage) ?: Parsed.Malformed
        "set_note_pin" -> decode<SetNotePin>(obj)?.let(Parsed::SetNotePin) ?: Parsed.Malformed
        "set_note_archived" -> decode<SetNoteArchived>(obj)?.let(Parsed::SetNoteArchived) ?: Parsed.Malformed
        "note_messages_request" -> decode<NoteMessagesRequest>(obj)?.let(Parsed::NoteMessagesRequest) ?: Parsed.Malformed
        else -> parseE2eFrames(type, obj)
    }

    /** The fourth part of [parse]: the round-42 E2E frames. */
    private fun parseE2eFrames(type: String, obj: JsonObject): Parsed = when (type) {
        "set_e2e_verified" -> decode<SetE2eVerified>(obj)?.let(Parsed::SetE2eVerified) ?: Parsed.Malformed
        "retry_e2e_probe" -> decode<RetryE2eProbe>(obj)?.let(Parsed::RetryE2eProbe) ?: Parsed.Malformed
        else -> Parsed.Unknown(type)
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

    fun encodeCategoryMutes(value: CategoryMutes): String =
        withType(value, "category_mutes").toString()

    fun encodeMessageSent(value: MessageSent): String = withType(value, "message_sent").toString()

    fun encodeMessageFailed(value: MessageFailed): String = withType(value, "message_failed").toString()

    fun encodeActionResult(value: ActionResult): String = withType(value, "action_result").toString()

    fun encodeAttachmentChunk(value: AttachmentChunk): String =
        withType(value, "attachment_chunk").toString()

    fun encodeAttachmentError(value: AttachmentError): String =
        withType(value, "attachment_error").toString()

    fun encodeAttachmentUploadReady(value: AttachmentUploadReady): String =
        withType(value, "attachment_upload_ready").toString()

    fun encodeAttachmentUploadDone(value: AttachmentUploadDone): String =
        withType(value, "attachment_upload_done").toString()

    fun encodeAttachmentUploadError(value: AttachmentUploadError): String =
        withType(value, "attachment_upload_error").toString()

    fun encodeMessageScheduled(value: MessageScheduled): String =
        withType(value, "message_scheduled").toString()

    fun encodeScheduleCancelled(value: ScheduleCancelled): String =
        withType(value, "schedule_cancelled").toString()

    fun encodeContacts(value: Contacts): String = withType(value, "contacts").toString()

    fun encodeContactUpdated(value: ContactUpdated): String =
        withType(value, "contact_updated").toString()

    fun encodeContactDeleted(value: ContactDeleted): String =
        withType(value, "contact_deleted").toString()

    fun encodeContactsError(value: ContactsError): String =
        withType(value, "contacts_error").toString()

    fun encodeConversationCreated(value: ConversationCreated): String =
        withType(value, "conversation_created").toString()

    fun encodeIncomingCall(value: IncomingCall): String = withType(value, "incoming_call").toString()

    fun encodeCallState(value: CallState): String = withType(value, "call_state").toString()

    fun encodeDialResult(value: DialResult): String = withType(value, "dial_result").toString()

    // -- Round 36 phase 2: notes sync encoders -------------------------

    fun encodeNotes(value: Notes): String = withType(value, "notes").toString()

    fun encodeNoteMessagesBatch(value: NoteMessagesBatch): String =
        withType(value, "note_messages_batch").toString()

    fun encodeNoteCreated(value: NoteCreated): String = withType(value, "note_created").toString()

    fun encodeNoteNew(value: NoteNew): String = withType(value, "note_new").toString()

    fun encodeNoteUpdated(value: NoteUpdated): String = withType(value, "note_updated").toString()

    fun encodeNoteDeleted(value: NoteDeleted): String = withType(value, "note_deleted").toString()

    fun encodeNoteMessageNew(value: NoteMessageNew): String =
        withType(value, "note_message_new").toString()

    fun encodeNoteMessageDeleted(value: NoteMessageDeleted): String =
        withType(value, "note_message_deleted").toString()

    fun encodeNoteMessageUpdated(value: NoteMessageUpdated): String =
        withType(value, "note_message_updated").toString()

    fun encodeNoteMessageSaved(value: NoteMessageSaved): String =
        withType(value, "note_message_saved").toString()

    fun encodeNoteMessageFailed(value: NoteMessageFailed): String =
        withType(value, "note_message_failed").toString()

    // -- Round 42: E2E safety numbers + notices encoders -------------

    fun encodeE2eSafetyNumbers(value: E2eSafetyNumbers): String =
        withType(value, "e2e_safety_numbers").toString()

    fun encodeE2eNotices(value: E2eNotices): String =
        withType(value, "e2e_notices").toString()

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
