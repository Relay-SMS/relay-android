package com.filestech.sms.ui.screens.details

import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.ui.components.isEmojiOnlyMessage

/**
 * Round 32 — the Photos tab is real photos only: no GIFs, no
 * stickers (the desktop detail pane's rule). GIFs are exact
 * (`image/gif`); stickers have no marker in the metadata, so they
 * are caught by the same heuristic as the desktop — the
 * `sticker-` filename the app gives its own sticker renders, or a
 * 512×512 PNG sent with no caption (stickers are rendered at
 * 512px and sent bare). A real 512×512 square PNG with no caption
 * is also hidden — rare, and accepted.
 *
 * The dimensions arm reads the part metadata the phone stored at
 * import; the desktop additionally classifies by the downloaded
 * file's real dimensions, which the phone never needs — its own
 * metadata already carries width/height. When the metadata has no
 * dimensions (`null`), only the filename arm applies.
 */
internal fun Attachment.isPhotosTabItem(): Boolean {
    return isImage &&
        !mimeType.equals("image/gif", ignoreCase = true) &&
        !isStickerAttachment()
}

internal fun Attachment.isStickerAttachment(): Boolean {
    val fileNameMatch = (fileName ?: "").startsWith("sticker-")
    // No message body to judge the caption arm — only hide on
    // evidence, never on its absence.
    val body = messageBody
    val dimensionsMatch = body != null &&
        mimeType.equals("image/png", ignoreCase = true) &&
        width == STICKER_SIZE_PX && height == STICKER_SIZE_PX &&
        (body.isBlank() || body.isEmojiOnlyMessage())
    return fileNameMatch || dimensionsMatch
}

private const val STICKER_SIZE_PX = 512
