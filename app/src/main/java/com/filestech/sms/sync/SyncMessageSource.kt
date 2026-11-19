package com.filestech.sms.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import java.io.InputStream

/**
 * Slice 2 — the seam between the sync engine and the phone's message
 * store (SYNC_PROTOCOL.md, glue point 4). The sync package defines
 * this contract and never sees Room, the DAOs, or the domain models:
 * the implementation lives outside the sync package and maps domain
 * models to the wire DTOs below. A future port re-implements this
 * one interface and lifts the sync package as-is.
 *
 * All ids on the wire are decimal strings of the phone's Room ids
 * (the desktop's ids are strings everywhere, and this dodges any JS
 * number-precision concern).
 *
 * Slice 3 adds the send + write-back surface below. Reads and writes
 * hit the same store, the same DAOs, and the same glue file, so one
 * seam stays enough — a second interface would be more wiring for no
 * isolation gain.
 *
 * Slice 4 adds the attachment-byte surface below (download + upload
 * staging + the extended send), same argument.
 *
 * Phase 2 adds the scheduled-send surface below ([scheduleMessage]),
 * same argument: the phone's own scheduler does the work, the glue
 * only carries the desktop's key through it.
 *
 * Slice 5 adds the contact-sync surface below ([contacts],
 * [openAvatar], [contactsReadiness]), same argument: the phone's
 * own contacts provider does the work, the glue only maps the
 * provider's rows to the wire DTOs. Contacts failing must never
 * fail message sync — the contact readiness check is separate from
 * [readiness] (which stays SMS-only), and a contacts failure
 * surfaces as `contacts_error`, not `sync_error`.
 *
 * Create conversation adds [createConversation] below, same
 * argument: the phone's own composer path (`findOrCreate` + an
 * optional `setCustomName`) does the work, the glue only maps the
 * desktop's address strings to the phone's `PhoneAddress` values
 * and the created row to the wire DTO.
 *
 * The function count is the one-seam decision, not bloat: splitting
 * this interface would mean a second implementation, a second Hilt
 * binding, and a second seam to re-implement on a port, for no
 * isolation gain — the slices share one store, one glue file, and
 * one mutex.
 */
@Suppress("TooManyFunctions")
interface SyncMessageSource {
    /** One-shot snapshot of every non-vault conversation (archived included), most recent first. */
    suspend fun conversations(): List<SyncedConversation>

    /** Live conversation list (same content as [conversations], as a Flow). */
    fun observeConversations(): Flow<List<SyncedConversation>>

    /**
     * One page of one conversation, newest first: ids strictly below
     * [beforeId] (0 = newest), at most [limit] rows, `hidden = 0`.
     */
    suspend fun messagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<SyncedMessage>

    /** Messages with id strictly above [afterId], oldest first — the live path. */
    suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage>

    /** Total message count (all non-vault conversations) — the progress total. */
    suspend fun totalMessageCount(): Long

    /** Per-conversation message counts — the `message_count` field. */
    suspend fun messageCountsByConversation(): Map<Long, Int>

    /** Current max message id — the "now" cursor for `new_only` mode. */
    suspend fun maxMessageId(): Long

    /** Live states (status / reaction / read / starred) for a bounded id set. */
    fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>>

    /** Readiness: is there anything to sync, and if not, why? */
    suspend fun readiness(): Readiness

    /**
     * Whether this app is the phone's default SMS app. When it is
     * not, new messages only reach the mirror on app open or the
     * periodic import — so the desktop warns that live sync will lag
     * until the app is made the default.
     */
    suspend fun isDefaultSmsApp(): Boolean

    /**
     * The phone's tunnel IPv4 address, when a VPN tunnel is the
     * phone's active (default) network — the address a linked
     * device dials to reach the phone through the tunnel. Null
     * when no tunnel is up (or the tunnel is not the default
     * route): the desktop then dials the LAN address only. Never
     * an empty string. Rides on every `sync_started`, next to
     * [isDefaultSmsApp].
     */
    suspend fun tunnelIp(): String?

    // -- Slice 3: send + write-back -----------------------------------

    /**
     * Sends a text message in [conversationId]. [clientMessageId] is
     * the desktop's UUID key for this send attempt: a retry reuses
     * the same key, and the implementation MUST dedup on it — a row
     * already carrying the key is never sent twice (pending / sent /
     * delivered → return the stored result; failed → re-dispatch the
     * existing row). The key is stamped on the message row at insert
     * time, before the radio dispatch, so the dedup survives a phone
     * restart. The implementation resolves the recipients from the
     * conversation itself and refuses vault conversations.
     *
     * Slice 4 — [attachments] carries the media of the send (upload
     * references and/or stickers); null for a text send. The dedup
     * check runs BEFORE any upload validation: a seen key returns
     * the stored result without consulting the uploads, and only an
     * unseen key whose staged file is gone fails with
     * `upload_expired`.
     */
    suspend fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        replyToMessageId: Long?,
        attachments: List<SendAttachmentInput>? = null,
    ): SendResult

    // -- Slice 4: attachment bytes --------------------------------------

    /**
     * Opens one attachment's bytes for download. [Unknown] when the
     * attachment doesn't exist, doesn't belong to [messageId], or
     * its message is in a vault conversation (all three look the
     * same to the caller — ids can't be probed); [Unreadable] when
     * the row exists but the bytes don't (file gone, part URI won't
     * open). The [AttachmentData.stream] must be closed by the
     * caller; [AttachmentData.sizeBytes] is the stored size (the
     * stream's actual length is verified by the engine against the
     * final chunk's count).
     */
    suspend fun openAttachment(
        attachmentId: Long,
        messageId: Long,
    ): AttachmentOpenResult

    /**
     * Begins an upload session: validates the size (one file over
     * the 280 KB MMS cap is refused — it could never be sent
     * anyway), sweeps staging dirs older than 24 h, and creates the
     * staging file for this [index] of the send keyed by
     * [clientMessageId]. A re-begin restarts the upload from
     * scratch.
     */
    suspend fun beginUpload(
        clientMessageId: String,
        index: Int,
        mimeType: String,
        fileName: String?,
        sizeBytes: Long,
        sha256: String,
    ): UploadResult

    /**
     * Appends one chunk to the upload begun by [beginUpload];
     * [offset] must equal the bytes already staged (a gap fails the
     * upload — it can only be restarted, never repaired). On
     * [final], the staged bytes are verified against the size and
     * SHA-256 promised at `beginUpload` before the part becomes the
     * sendable file.
     */
    suspend fun appendUploadChunk(
        clientMessageId: String,
        index: Int,
        offset: Long,
        bytes: ByteArray,
        final: Boolean,
    ): UploadResult

    /** Applies one write-back action; returns whether the phone applied it. */
    suspend fun markRead(conversationId: Long): ActionResult
    suspend fun markAllRead(): ActionResult
    suspend fun setPinned(conversationId: Long, pinned: Boolean): ActionResult
    suspend fun setArchived(conversationId: Long, archived: Boolean): ActionResult
    suspend fun setMuted(conversationId: Long, muted: Boolean): ActionResult
    suspend fun setBlocked(conversationId: Long, blocked: Boolean): ActionResult

    // -- Phase 2: scheduled sends --------------------------------------

    /**
     * Schedules a text message in [conversationId] for [sendAt] (epoch
     * millis), through the phone's EXISTING scheduler — this is the
     * desktop asking the phone to schedule, not a second scheduler.
     * [clientMessageId] is the desktop's UUID key for this schedule
     * attempt: a retry reuses the same key, and the implementation
     * MUST dedup on it — a scheduled row already carrying the key is
     * never scheduled twice (return its id), and a key whose message
     * already fired is covered by the Slice 3 row dedup (same key on
     * the sent row). The key is stored on the scheduled row and
     * stamped on the message row at fire time, so the dedup survives
     * a phone restart and the desktop reconciles when `message_new`
     * arrives carrying it. The implementation resolves the recipients
     * from the conversation itself and refuses vault conversations.
     *
     * The phone's own scheduler refuses nothing about the default-SMS
     * role at schedule time — a scheduled send is accepted while
     * another app holds the role, and only fails at fire time — so
     * this function does not refuse it either.
     */
    suspend fun scheduleMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        sendAt: Long,
    ): ScheduleResult

    /**
     * Cancels the scheduled message keyed by [clientMessageId],
     * through the phone's EXISTING delete path (cancel the scheduled
     * work + delete the row) — the same path the phone's own
     * Scheduled Messages screen deletes with. IDEMPOTENT: an unknown
     * key (already cancelled, deleted, or never scheduled) and a key
     * whose message already fired both ack as cancelled — the
     * schedule is gone either way, and a fired message is already
     * reconciled on the desktop via `message_new`. Only a genuine
     * internal failure (the lookup or the delete itself throwing)
     * answers [CancelResult.Failed]. After a cancel the key is free:
     * the row is gone, so a later `scheduleMessage` with the same key
     * creates a fresh schedule — no tombstone blocks it.
     */
    suspend fun cancelScheduledMessage(clientMessageId: String): CancelResult

    // -- Create conversation from the desktop --------------------------

    /**
     * Creates a conversation for [addresses] (one number = a 1:1
     * conversation, several = a group) and names it [customName]
     * when one was typed — the phone's own composer path
     * (`findOrCreate`, then `setCustomName` when the name is
     * non-blank), so no message is needed to create the thread and
     * a blank name leaves the group unnamed (it shows member
     * names). IDEMPOTENT: `findOrCreate` returns the existing
     * conversation for the same member set, so a retry after a lost
     * ack never creates a duplicate — which is why the wire request
     * carries no client key. An empty list (or one whose entries
     * are all blank) fails with `invalid_request`, never with a
     * created thread.
     */
    suspend fun createConversation(
        addresses: List<String>,
        customName: String?,
    ): CreateConversationResult

    /**
     * Reacts to a message ([emoji] null = remove the reaction). The
     * local reaction is set first, then the reaction SMS is sent —
     * the phone's own order; if the SMS fails, the local reaction
     * stays set and the result carries `send_failed`.
     */
    suspend fun setReaction(conversationId: Long, messageId: Long, emoji: String?): ActionResult

    /** Pins a message in its thread ([messageId] null = unpin). */
    suspend fun setPinnedMessage(conversationId: Long, messageId: Long?): ActionResult

    // -- Slice 5: contact sync ----------------------------------------

    /**
     * One-shot snapshot of every contact with at least one phone
     * number, display-name order (the provider's order — the desktop
     * relies on it for deterministic last-write-wins when two
     * contacts share a number). The engine streams it in batches of
     * 100 after `sync_started` and before the conversations, and the
     * server re-reads it to diff live deltas. The caller checks
     * [contactsReadiness] first; a `SecurityException` here (the
     * permission revoked between the check and the read) is the
     * engine's signal for `hub_no_contacts_access`.
     */
    suspend fun contacts(): List<SyncedContact>

    /**
     * Opens one contact photo's bytes (the thumbnail — what the
     * phone itself displays). [Unknown] when there is no contact
     * for [address], no photo, or an unreadable stream — one answer
     * for all three, so the engine maps them all to
     * `unknown_attachment`. The [AvatarOpenResult.Opened.stream]
     * must be closed by the caller. The size is unknown up front
     * (the provider gives no length) — the engine caps the stream
     * while reading, and the final chunk carries the actual count.
     * No membership guard applies (there is no message and no vault
     * concept for contacts).
     */
    suspend fun openAvatar(address: String): AvatarOpenResult

    /**
     * Contact readiness: can contacts sync at all, and if not, why?
     * Separate from [readiness] by design — contacts failing must
     * never fail message sync. The implementation checks
     * READ_CONTACTS and treats a `SecurityException` from the
     * provider read as not-ready, never a crash.
     */
    suspend fun contactsReadiness(): ContactReadiness

    /**
     * Round 3 — writes a contact photo picked on the desktop: the
     * prepared 512 px JPEG for [address], resolved with the same
     * `PhoneLookup` the UI uses. The photo is written to the
     * contact's PHOTO row (an existing photo row is replaced, never
     * duplicated). `ok: false` with `unknown_contact` when no
     * contact exists for the number — the phone never creates a
     * contact for a photo set — and `hub_no_contacts_write` when
     * WRITE_CONTACTS is not granted. The write bumps the contact's
     * `CONTACT_LAST_UPDATED_TIMESTAMP`, so the next contact snapshot
     * carries the new photo version and the desktop's local choice
     * converges to the synced photo.
     */
    suspend fun setContactPhoto(address: String, photoBytes: ByteArray): ActionResult
}

/**
 * Slice 3 — what a send did: the `message_sent` / `message_failed`
 * payload. Codes are [SyncProtocol.MessageFailed]'s.
 */
sealed interface SendResult {
    /**
     * The send was dispatched (or was already dispatched — the dedup
     * returning the stored row). [messageId] is the phone's Room id
     * of the row the desktop shows; [status] is the wire status
     * string at ack time (`"pending"` normally); [blockedCount] is
     * how many recipients were blocked but others were sent (group
     * broadcast only).
     */
    data class Sent(
        val messageId: Long,
        val status: String,
        val blockedCount: Int = 0,
    ) : SendResult

    /** The send was refused or failed. [message] is user-facing and generic. */
    data class Failed(val code: String, val message: String) : SendResult
}

/** Slice 3 — what an action did: the `action_result` payload. */
data class ActionResult(val ok: Boolean, val code: String? = null, val message: String? = null)

/**
 * Phase 2 — what a schedule did: the `message_scheduled` /
 * `message_failed` payload. Codes are [SyncProtocol.MessageFailed]'s.
 */
sealed interface ScheduleResult {
    /**
     * The message is scheduled (or was already scheduled — the dedup
     * returning the stored row). [messageId] is the phone's
     * scheduled-row id for a fresh or pending schedule, or the sent
     * message row's id when the key already fired; [sendAt] is the
     * scheduled time in epoch millis (the stored row's time for a
     * pending dedup, the requested time for a fired one).
     */
    data class Scheduled(
        val messageId: Long,
        val sendAt: Long,
    ) : ScheduleResult

    /** The schedule was refused or failed. [message] is user-facing and generic. */
    data class Failed(val code: String, val message: String) : ScheduleResult
}

/**
 * Phase 2 — what a cancel did: the `schedule_cancelled` /
 * `message_failed` payload. Codes are [SyncProtocol.MessageFailed]'s.
 * A cancel almost always succeeds: an unknown key and an already
 * fired key both ack as [Cancelled] — only a genuine internal
 * failure is [Failed].
 */
sealed interface CancelResult {
    /** The scheduled message is cancelled (or was already gone). */
    data object Cancelled : CancelResult

    /** The cancel itself failed. [message] is user-facing and generic. */
    data class Failed(val code: String, val message: String) : CancelResult
}

/**
 * Create conversation — what a create did: the `conversation_created`
 * / `action_result` payload. Codes are [SyncProtocol.MessageFailed]'s,
 * plus `invalid_request` for an empty address list.
 */
sealed interface CreateConversationResult {
    /**
     * The conversation was created — or already existed for the
     * same member set (the idempotent `findOrCreate` returning the
     * stored row). [conversation] is the full `SyncedConversation`,
     * the exact same shape as one entry in the `conversations`
     * batches, with the real message count — so the desktop opens
     * an existing group with its history, not an empty thread.
     */
    data class Created(
        val conversation: SyncedConversation,
    ) : CreateConversationResult

    /** The create was refused or failed. [message] is user-facing and generic. */
    data class Failed(val code: String, val message: String) : CreateConversationResult
}

/**
 * Slice 4 — what [SyncMessageSource.openAttachment] found. The two
 * failure flavors exist so the engine picks the right
 * `attachment_error` code: [Unknown] for a missing row / wrong
 * message / vault conversation (all one answer, so ids can't be
 * probed), [Unreadable] for a row whose bytes are gone.
 */
sealed interface AttachmentOpenResult {
    /** The bytes are open; [AttachmentData.stream] must be closed by the caller. */
    data class Opened(val data: AttachmentData) : AttachmentOpenResult

    /** No such row, wrong message, or a vault conversation. */
    data object Unknown : AttachmentOpenResult

    /** The row exists, but the bytes don't. */
    data object Unreadable : AttachmentOpenResult
}

/**
 * Slice 4 — an opened attachment: its stored size and a stream over
 * its bytes. [sizeBytes] is -1 when the size is unknown up front
 * (Slice 5 avatar photo streams — the provider gives no length);
 * the engine caps the stream while reading either way, and the
 * final chunk carries the actual byte count.
 */
data class AttachmentData(val sizeBytes: Long, val stream: InputStream)

/**
 * Slice 4 — one entry of a media send: an upload reference
 * ([uploadIndex] + [mimeType] + optional [fileName], [width],
 * [height], [durationMs]), or a sticker ([stickerEmoji] — the phone
 * renders it, no bytes cross). Exactly one of [uploadIndex] /
 * [stickerEmoji] must be set; anything else is a client bug.
 */
data class SendAttachmentInput(
    val uploadIndex: Int? = null,
    val stickerEmoji: String? = null,
    val mimeType: String? = null,
    val fileName: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
)

/** Slice 4 — what an upload step did: the ready/done/error payload. Codes are [SyncProtocol.AttachmentUploadError]'s. */
sealed interface UploadResult {
    /** The step succeeded (start validated, or chunk appended / verified). */
    data object Ok : UploadResult

    /** The upload failed. [message] is user-facing and generic. */
    data class Failed(val code: String, val message: String) : UploadResult
}

/**
 * One conversation on the wire (§3.3 CONVERSATION). Plain data — not
 * the domain model. [title] is the phone's computed title (custom
 * name > display name > numbers) and is authoritative for display.
 * Never carries a vault conversation, a draft, or appearance data.
 *
 * The nullable fields have no defaults, so they are always encoded
 * (as explicit JSON nulls) — the wire shape of §3.3, not the
 * omit-when-null shape `encodeDefaults = false` would give them.
 */
@Serializable
data class SyncedConversation(
    @SerialName("id") val id: String,
    @SerialName("thread_id") val threadId: Long?,
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("display_name") val displayName: String?,
    @SerialName("custom_name") val customName: String?,
    @SerialName("title") val title: String,
    @SerialName("last_message_at") val lastMessageAt: Long,
    @SerialName("last_message_preview") val lastMessagePreview: String?,
    @SerialName("unread") val unread: Int,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("archived") val archived: Boolean,
    @SerialName("muted") val muted: Boolean,
    @SerialName("blocked") val blocked: Boolean,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("pinned_message_id") val pinnedMessageId: String?,
    @SerialName("pinned_message_at") val pinnedMessageAt: Long?,
)

/**
 * One message on the wire (§3.3 MESSAGE). [type] is `"sms"` | `"mms"`,
 * [direction] is `"incoming"` | `"outgoing"`, [status] is `"pending"` |
 * `"sent"` | `"delivered"` | `"failed"` | `"received"` | `"scheduled"`.
 * [address] is the sender's address (for group messages, the sender,
 * not the group). Attachments are metadata only in Slice 2 — no bytes.
 *
 * Slice 3 — [clientMessageId] is the desktop's UUID key for a send,
 * present only on messages the desktop itself sent (the phone stamps
 * the key on the row at send time). It is the one field with a
 * default, so it is OMITTED when null — unlike the other nullable
 * fields, which encode as explicit nulls.
 */
@Serializable
data class SyncedMessage(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("address") val address: String,
    @SerialName("body") val body: String,
    @SerialName("type") val type: String,
    @SerialName("direction") val direction: String,
    @SerialName("date") val date: Long,
    @SerialName("date_sent") val dateSent: Long?,
    @SerialName("read") val read: Boolean,
    @SerialName("starred") val starred: Boolean,
    @SerialName("status") val status: String,
    @SerialName("error_code") val errorCode: Int?,
    @SerialName("reply_to_message_id") val replyToMessageId: String?,
    @SerialName("reaction") val reaction: String?,
    @SerialName("attachments") val attachments: List<SyncedAttachment>,
    @SerialName("client_message_id") val clientMessageId: String? = null,
)

/** One attachment's metadata on the wire (§3.3 ATTACHMENT) — no bytes in Slice 2. */
@Serializable
data class SyncedAttachment(
    @SerialName("id") val id: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_name") val fileName: String?,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("width") val width: Int?,
    @SerialName("height") val height: Int?,
    @SerialName("duration_ms") val durationMs: Long?,
)

/**
 * The live-mutable slice of one synced message: what the states
 * watch compares to decide whether a `message_updated` push is due.
 * No body, no attachments — just the fields that can change after a
 * message was first synced. [status] is the wire string (see
 * [SyncedMessage]).
 */
data class MessageState(
    val id: Long,
    val conversationId: Long,
    val status: String,
    val reaction: String?,
    val read: Boolean,
    val starred: Boolean,
)

/** What [SyncMessageSource.readiness] reports: syncable, or why not. */
sealed interface Readiness {
    /** SMS permission granted and the mirror holds at least one message. */
    data object Ready : Readiness

    /** READ_SMS is missing — the mirror cannot be populated. */
    data object NoSmsPermission : Readiness

    /** Permission granted, but the phone's message import hasn't run yet. */
    data object MirrorEmpty : Readiness
}

/**
 * Slice 5 — one contact on the wire (§3.3 CONTACT). [phones] are
 * normalized (the phone's own `normalizePhone` — the normalized form
 * is the join key on both sides), in the order the provider returned
 * them (first = the contact's primary number as stored).
 * [photoUpdatedAt] is the photo-staleness version
 * (`CONTACT_LAST_UPDATED_TIMESTAMP`), present only when [hasPhoto]
 * is true, else null. No contact id, no emails, no groups — the
 * wire identity of a contact is its set of normalized numbers.
 *
 * [contactId] is the Android CONTACT_ID, for the server's internal
 * snapshot diff only: it is `@Transient`, so it never crosses the
 * wire (contact ids are reassigned when contacts merge, split, or
 * are restored from a backup — they are not stable identity). It
 * still participates in equality, so two snapshots of the same
 * contact compare equal.
 */
@Serializable
data class SyncedContact(
    @SerialName("display_name") val displayName: String,
    @SerialName("phones") val phones: List<String>,
    @SerialName("has_photo") val hasPhoto: Boolean,
    @SerialName("photo_updated_at") val photoUpdatedAt: Long? = null,
    @Transient val contactId: Long = 0L,
)

/**
 * Slice 5 — what [SyncMessageSource.openAvatar] found. The single
 * failure flavor is the design: no contact, no photo, and an
 * unreadable stream all answer `unknown_attachment`, so there is
 * nothing to distinguish.
 */
sealed interface AvatarOpenResult {
    /** The photo bytes are open; [stream] must be closed by the caller. */
    data class Opened(val stream: InputStream) : AvatarOpenResult

    /** No contact for the address, no photo, or an unreadable stream. */
    data object Unknown : AvatarOpenResult
}

/** Slice 5 — what [SyncMessageSource.contactsReadiness] reports: contacts syncable, or why not. */
sealed interface ContactReadiness {
    /** READ_CONTACTS granted and the provider reads. */
    data object Ready : ContactReadiness

    /** READ_CONTACTS is missing (or the provider read threw `SecurityException`). */
    data object NoContactsPermission : ContactReadiness
}
