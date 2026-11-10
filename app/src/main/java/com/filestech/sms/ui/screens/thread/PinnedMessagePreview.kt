package com.filestech.sms.ui.screens.thread

import com.filestech.sms.domain.model.Message

/**
 * Feedback round 5 — pinned-message banner helpers (pure, unit-tested in
 * [PinnedMessagePreviewTest]).
 *
 * The banner shows the sender ("You" for your own messages, the contact or
 * group-member name otherwise), the message text truncated to one line, and a
 * "Pinned · date at time" line with the PIN date. A pinned media/voice
 * message has no text to show, so the banner falls back to a short preview
 * label (Photo / Video / Voice message / file name).
 */

/**
 * One-line preview for the pinned banner. The body wins when it is
 * non-blank (a captioned photo shows its caption, not "Photo"); otherwise
 * the first attachment decides: image → [photoLabel], video → [videoLabel],
 * audio → [voiceLabel], anything else → its file name, or [fileLabel] when
 * the attachment has no name either.
 */
fun pinnedPreviewText(
    message: Message,
    photoLabel: String,
    videoLabel: String,
    voiceLabel: String,
    fileLabel: String,
): String {
    if (message.body.isNotBlank()) return message.body
    val attachment = message.attachments.firstOrNull() ?: return fileLabel
    return when {
        attachment.isAudio -> voiceLabel
        attachment.isImage -> photoLabel
        attachment.mimeType.startsWith("video/", ignoreCase = true) -> videoLabel
        else -> attachment.fileName?.takeIf { it.isNotBlank() } ?: fileLabel
    }
}

/**
 * Sender line for the pinned banner. Your own messages read [youLabel];
 * a 1-to-1 incoming message reads the conversation [title]; a group
 * incoming message reads the sender's resolved name when known
 * ([memberNames]), falling back to the raw address.
 */
fun pinnedSenderLabel(
    message: Message,
    isGroup: Boolean,
    title: String,
    memberNames: Map<String, String>,
    youLabel: String,
): String = when {
    message.isOutgoing -> youLabel
    isGroup -> memberNames[message.address] ?: message.address
    else -> title
}
