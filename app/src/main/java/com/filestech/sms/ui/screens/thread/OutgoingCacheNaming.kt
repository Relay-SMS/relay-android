package com.filestech.sms.ui.screens.thread

import com.filestech.sms.core.mms.MmsConstants
import java.util.UUID

/**
 * Round 33 follow-up — the cache filename for a picked attachment.
 *
 * A sticker picked from the sticker tab arrives as a FileProvider URI whose
 * display name is `sticker-<codepoint>-512.png` (see `stickerSendUri`), but
 * the copy used to rename every attachment to a generated
 * `<timestamp>-<uuid>.<ext>` — the sticker marker was stripped before
 * storage, so the Photos tab's filename arm could never see it and the
 * sticker stayed in Photos (the dimensions arm needs the cache file to
 * still exist for its read-time probe, and the cache is pruned).
 *
 * A source name starting with `sticker-` now keeps that prefix on the
 * staged copy: `sticker-<timestamp>-<uuid>.<ext>`. The stored row then
 * carries "sticker" in its filename — via `promoteToDurable`, which
 * preserves the prefix into the durable name — and the filter catches it
 * with no probing, even after the cache is gone. The unique suffix stays:
 * two sends of the same sticker must not share (and overwrite) one file.
 * Anything else keeps the plain generated name; GIFs (`klipy-<id>.gif`)
 * are untouched and still filter by MIME.
 *
 * Pure, so the naming decision is unit-testable without a `Context` —
 * `copyAttachmentToCache` only applies it.
 */
internal fun outgoingCacheFileName(sourceName: String?, extension: String): String {
    val ext = extension.ifBlank { "bin" }
    val unique = "${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}"
    val stickerPrefix = if (
        sourceName?.startsWith(MmsConstants.STICKER_FILENAME_PREFIX, ignoreCase = true) == true
    ) {
        MmsConstants.STICKER_FILENAME_PREFIX
    } else {
        ""
    }
    return "$stickerPrefix$unique.$ext"
}
