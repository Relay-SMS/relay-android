package com.filestech.sms.domain.model

/**
 * A note: a local-only thread of entries, stored in Relay's own database and never in the
 * system SMS store. Notes look like conversations in the list (same row, sorted by recency)
 * but are not SMS: no addresses, no unread state, no mute/block/vault.
 *
 * The text of a note is encrypted at rest by the whole-database SQLCipher envelope; its
 * attachment files carry their own AES-256-GCM envelope (see
 * `NoteAttachmentResolver` for the decrypt-on-read seam).
 *
 * Uninstall deletes notes — the separate notes backup is the only recovery path.
 */
data class Note(
    val id: Long,
    /** UUID v4, assigned at creation; the backup dedup key and the phase-2 sync key. */
    val uuid: String,
    /** The note's name, required. */
    val title: String,
    val createdAt: Long,
    /**
     * Last metadata edit (rename, pin, archive, bubble color). Message inserts/deletes do
     * NOT move this — content recency is [lastMessageAt].
     */
    val updatedAt: Long,
    /** Date of the newest entry, null while the note is empty. */
    val lastMessageAt: Long?,
    /** Preview of the newest entry (body, or an emoji + file label), null when empty. */
    val lastMessagePreview: String?,
    /** Composer draft, parity with conversations. */
    val draft: String?,
    val pinned: Boolean,
    val archived: Boolean,
    /** Chat color (ARGB); null = default brand blue. */
    val bubbleColorArgb: Int?,
)
