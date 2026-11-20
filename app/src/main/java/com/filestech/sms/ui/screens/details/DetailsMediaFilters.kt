package com.filestech.sms.ui.screens.details

import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.ui.components.isEmojiOnlyMessage

/**
 * Round 32 — the Photos tab is real photos only: no GIFs, no
 * stickers (the desktop detail pane's rule). GIFs are exact
 * (`image/gif`); stickers have no marker in the metadata, so they
 * are caught by the same heuristic as the desktop — the
 * `sticker-` filename the app gives its own sticker renders, or a
 * 512×512 (or 72×72 fallback) PNG sent with no caption (stickers
 * are rendered at 512px and sent bare). A real 512×512 square PNG
 * with no caption is also hidden — rare, and accepted.
 *
 * Round 33 — the dimensions arm could never fire for a RECEIVED
 * sticker: incoming MMS rows store `width`/`height` as `null`
 * (see `ConversationMirror.inscrireMmsRecu` and the system import)
 * and their filenames are generated (`in-<ts>-<uuid>.png`), so
 * neither arm matched and the sticker stayed in Images. The
 * dimensions now arrive two ways: probed at import for new MMS,
 * and probed from the bytes at read time for existing rows (see
 * `MediaGalleryViewModel`), so the metadata the filter sees is
 * complete by the time it runs. The filename arm also widens
 * from a `sticker-` prefix to any name CONTAINING "sticker"
 * (other apps name their sticker files differently).
 *
 * Round 33 follow-up — the dimensions arm gains a second size:
 * 72×72. That is the bundled Twemoji PNG `stickerSendUri` falls
 * back to when the SVG render fails, so a sticker sent from the
 * phone can be 72×72 instead of 512×512. Same body rule as the
 * 512 arm (blank or emoji-only caption); no real photo is 72×72.
 *
 * When the metadata has no dimensions (`null`) — a file that could
 * not be probed — only the filename arm applies.
 */
internal fun Attachment.isPhotosTabItem(): Boolean {
    return isImage &&
        !mimeType.equals("image/gif", ignoreCase = true) &&
        !isStickerAttachment()
}

internal fun Attachment.isStickerAttachment(): Boolean {
    val fileNameMatch = (fileName ?: "").contains("sticker", ignoreCase = true)
    // No message body to judge the caption arm — only hide on
    // evidence, never on its absence.
    val body = messageBody
    val dimensionsMatch = body != null &&
        mimeType.equals("image/png", ignoreCase = true) &&
        isStickerSize(width, height) &&
        (body.isBlank() || body.isEmojiOnlyMessage())
    return fileNameMatch || dimensionsMatch
}

/** 512 is the SVG render; 72 is the bundled-PNG fallback (see `stickerSendUri`). */
private fun isStickerSize(width: Int?, height: Int?): Boolean =
    (width == STICKER_SIZE_PX && height == STICKER_SIZE_PX) ||
        (width == STICKER_FALLBACK_SIZE_PX && height == STICKER_FALLBACK_SIZE_PX)

private const val STICKER_SIZE_PX = 512
private const val STICKER_FALLBACK_SIZE_PX = 72
