package com.filestech.sms.domain.model

/**
 * Domain projection of a stored attachment. Carries the MIME type + local file URI so the UI
 * layer can decide how to render the attachment (audio player, image, generic chip…) without
 * pulling in the Room dependency.
 */
data class Attachment(
    val id: Long,
    val messageId: Long,
    val mimeType: String,
    val fileName: String?,
    val sizeBytes: Long,
    val localUri: String,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    /**
     * Client mode C5 — the hub attachment id, for synced rows only
     * (null for hub-mode rows). The UI gates its download tile on
     * this: only a synced row's bytes can be requested from the
     * phone — a hub-mode row with a missing file has nothing to
     * download, and must never show a download tile.
     */
    val hubAttachmentId: Long? = null,
    /**
     * Round 32 — the date + body of the message carrying this
     * attachment. Only the media queries populate them (the DAO
     * joins the message row): the details panel groups media by
     * date and needs the body for its sticker check. Everywhere
     * else they stay `null`.
     */
    val messageDate: Long? = null,
    val messageBody: String? = null,
) {
    val isAudio: Boolean get() = mimeType.startsWith("audio/", ignoreCase = true)
    val isImage: Boolean get() = mimeType.startsWith("image/", ignoreCase = true)
    val isVideo: Boolean get() = mimeType.startsWith("video/", ignoreCase = true)
}
