package com.filestech.sms.ui.components

import com.filestech.sms.domain.model.Attachment

/**
 * Notes — the encrypted-preview seam for [MediaAttachmentBubble].
 *
 * A note attachment's `localUri` points at the ENCRYPTED file, so
 * the bubble's default behaviour — Coil on the URI, open via the
 * remembered-viewer opener — would show ciphertext and hand an
 * unreadable file to the viewer. The note thread instead passes
 * overrides: [imageModel] resolves a decrypted Coil model per
 * attachment (in-memory bytes for an image, a playback file for a
 * video; `null` while the bytes are not available yet, or when the
 * decrypt failed), and [onOpen] decrypts to a playback file and
 * hands the plaintext copy to the shared viewer picker.
 */
data class MediaPreviewOverrides(
    /** The decrypted Coil model for an attachment, or null while unavailable. */
    val imageModel: (Attachment) -> Any?,
    /** Opens an attachment (decrypts first, then the shared viewer picker). */
    val onOpen: (Attachment) -> Unit,
)
