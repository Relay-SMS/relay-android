package com.filestech.sms.domain.repository

/**
 * Phase 2 (notes sync) — the parsed wire data the client mirror
 * hands to [NoteRepository]: ids as Longs, never decimal strings,
 * so the repository never has to guess (the `SyncedConversationData`
 * pattern — see `WireMappers`). A row whose wire id does not parse
 * is dropped by the mapper before it gets here.
 *
 * The wire's `message_count` is NOT carried: the mobile list shows
 * the preview, never a count — the desktop uses it, the mirror does
 * not need it.
 */

/** One synced note, parsed from the wire. */
data class SyncedNoteData(
    val hubNoteId: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastMessageAt: Long?,
    val lastMessagePreview: String?,
    val pinned: Boolean,
    val archived: Boolean,
)

/** One synced note entry, parsed from the wire. */
data class SyncedNoteMessageData(
    val hubMessageId: Long,
    val hubNoteId: Long,
    val body: String,
    val date: Long,
    val edited: Boolean,
    val clientMessageId: String?,
    val attachments: List<SyncedNoteAttachmentData>,
)

/** One synced note attachment's metadata, parsed from the wire — no bytes. */
data class SyncedNoteAttachmentData(
    val hubAttachmentId: Long,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
)

/** What a synced-note upsert did: the local row + whether it was created. */
data class SyncedNoteResult(
    val localId: Long,
    val created: Boolean,
)

/** What a synced-message upsert did: the local row + whether it was inserted. */
data class SyncedNoteMessageResult(
    val localId: Long,
    val inserted: Boolean,
)
