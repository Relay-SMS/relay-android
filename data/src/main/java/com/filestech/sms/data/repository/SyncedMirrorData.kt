package com.filestech.sms.data.repository

import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType

/**
 * Client mode — the parsed payloads the client mirror accepts. These
 * live in the data module because `ConversationMirror` does, and the
 * data module cannot see the app-module wire DTOs (`SyncedMessage`
 * etc. in `sync/`). The app layer maps wire → these in
 * `sync.client.WireMappers` (string ids parsed to Long, wire strings
 * to domain enums), so the mirror only ever sees valid values.
 *
 * The wire DTOs are the source of truth for each field; the notes
 * here record only the mapping decisions that matter to the mirror.
 *
 * Deliberately NOT carried over from the wire:
 * - `SyncedConversation.threadId` — the hub's AOSP system thread id.
 *   It means nothing on this device and must never be stored: it
 *   could collide with a local `thread_id` under its UNIQUE index.
 *   Client rows keep `threadId = null`.
 * - `SyncedConversation.messageCount` — no column needs it.
 * - `SyncedConversation.blocked` IS carried ([SyncedConversationData.isBlocked])
 *   so the call sites can see it, but there is no `blocked` column on
 *   `ConversationEntity` (blocking is derived at read time from the
 *   local block list) — the mirror records the flag nowhere in C2.
 *   Block-list sync needs a design decision; see the C2 report.
 */

/** A synced attachment's metadata — the file itself downloads in C5. */
data class SyncedAttachmentData(
    /** Hub attachment id (wire `id`), the upsert key. */
    val hubAttachmentId: Long,
    /** MIME type, e.g. `image/jpeg` (empty when the hub omitted it). */
    val mimeType: String,
    val fileName: String?,
    /** Bytes on the hub, 0 when the hub omitted the size. */
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    /** Milliseconds, audio/video only. */
    val durationMs: Long?,
)

/** A synced message, parsed — mirrors `SyncedMessage` on the wire. */
data class SyncedMessageData(
    /** Hub message id (wire `id`), the upsert key and the cursor unit. */
    val hubMessageId: Long,
    /** Hub conversation id (wire `conversation_id`). */
    val hubConversationId: Long,
    /** Sender's address (for a group message, the sender, not the group). */
    val address: String,
    val body: String,
    val type: MessageType,
    val direction: MessageDirection,
    /** Epoch ms the message was received/sent. */
    val date: Long,
    /** Epoch ms the message was sent (outgoing), or null. */
    val dateSent: Long?,
    /** Whether the hub user had read it. */
    val read: Boolean,
    val starred: Boolean,
    val status: MessageStatus,
    /** Delivery-failure detail, or null. */
    val errorCode: Int?,
    /** Reply target (hub message id), or null. */
    val replyToHubMessageId: Long?,
    /** Emoji reaction on this message, or null. */
    val reaction: String?,
    /** The linked device's UUID send key, when this device sent it (Slice 3). */
    val clientMessageId: String?,
    val attachments: List<SyncedAttachmentData>,
)

/** A synced conversation, parsed — mirrors `SyncedConversation` on the wire. */
data class SyncedConversationData(
    /** Hub conversation id (wire `id`), the upsert key. */
    val hubConversationId: Long,
    /** The hub's computed title (custom name > display name > numbers). */
    val title: String,
    /** Contact display name the hub resolved, or null. */
    val displayName: String?,
    /** Group name the hub user chose, or null. */
    val customName: String?,
    /** Participant addresses (raw, as the hub stores them). */
    val addresses: List<String>,
    /** The hub's newest-message preview (exact text, incl. media labels). */
    val lastMessagePreview: String?,
    /** Epoch ms of the newest message, 0 when the thread is empty. */
    val lastMessageAt: Long,
    /** The hub's unread count — authoritative, applied as-is. */
    val unreadCount: Int,
    /** Whether the hub user pinned this conversation. */
    val pinned: Boolean,
    val muted: Boolean,
    val archived: Boolean,
    /** Wire `blocked`: true when the hub user blocked this thread. */
    val isBlocked: Boolean = false,
    /** The pinned message (hub id) with its pin time, when one is pinned. */
    val pinnedMessageHubId: Long? = null,
    val pinnedMessageAt: Long? = null,
)

/**
 * What `upsertSyncedConversation` did: [localId] is the local Room
 * id, [created] is true when the row was inserted (false: updated).
 * [unresolvedPinnedHubMessageId] carries a pinned message that has
 * not arrived yet — the engine keeps it in a pending map and pins it
 * when the message lands.
 */
data class SyncedConversationResult(
    val localId: Long,
    val created: Boolean,
    val unresolvedPinnedHubMessageId: Long? = null,
    val pinnedMessageAt: Long? = null,
)

/**
 * What `upsertSyncedMessage` did: [localId] is the local Room id,
 * [inserted] is true when the row was inserted (false: updated).
 * [unresolvedReplyToHubMessageId] carries a reply target that has
 * not arrived yet — the stream is newest-first, so a reply routinely
 * lands before the message it quotes. The engine keeps it pending
 * and patches the reply when the target lands (the desktop never
 * re-resolves these — its quotes stay "Message unavailable").
 */
data class SyncedMessageResult(
    val localId: Long,
    val inserted: Boolean,
    val unresolvedReplyToHubMessageId: Long? = null,
)
