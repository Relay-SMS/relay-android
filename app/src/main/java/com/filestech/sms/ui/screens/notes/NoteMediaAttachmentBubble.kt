package com.filestech.sms.ui.screens.notes

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.NoteMessage
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.ui.components.MediaAttachmentBubble
import com.filestech.sms.ui.components.MediaPreviewOverrides
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Notes — the media bubble for a note entry: [MediaAttachmentBubble]
 * with the encrypted-preview seam ([MediaPreviewOverrides]).
 *
 * Each attachment resolves to a decrypted Coil model on first render:
 * an image decrypts to memory (plaintext bytes, never written to
 * disk — Coil's `ByteArrayMapper` feeds them to the decoder); a video
 * decrypts to a playback file, its first frame is extracted, and the
 * file is released immediately (the frame bitmap is the model — the
 * playback file has no extension, so Coil's video-frame decoder would
 * not match it). A file attachment needs no model (the icon tile).
 *
 * A decrypt that fails leaves a null model: the bubble renders the
 * neutral placeholder (parity with a missing SMS file) and logs —
 * the ciphertext URI is never handed to Coil. The models live in a
 * state map keyed by attachment id, so a re-render never re-decrypts.
 */
@Composable
fun NoteMediaAttachmentBubble(
    displayMessage: Message,
    noteMessage: NoteMessage,
    resolver: NoteAttachmentResolver,
    onOpenAttachment: (Attachment) -> Unit,
    showTimestamp: Boolean,
    customBubbleColorArgb: Int? = null,
    onDelete: (() -> Unit)? = null,
    onCopy: (() -> Unit)? = null,
) {
    val attachments = noteMessage.attachments
    val models = remember { mutableStateMapOf<Long, Any?>() }
    LaunchedEffect(noteMessage.id, attachments) {
        for (attachment in attachments) {
            if (models.containsKey(attachment.id)) continue
            val model: Any? = when {
                attachment.isImage -> when (val res = resolver.decryptToMemory(attachment)) {
                    is Outcome.Success -> res.value
                    is Outcome.Failure -> null
                }

                attachment.mimeType.startsWith("video/", ignoreCase = true) ->
                    when (val res = resolver.decryptToPlaybackFile(attachment)) {
                        is Outcome.Success -> {
                            // The frame extraction is blocking I/O
                            // (MediaMetadataRetriever) — off the
                            // composition thread.
                            val frame = withContext(Dispatchers.IO) {
                                extractVideoFrame(res.value)
                            }
                            resolver.releasePlaybackFile(res.value)
                            frame
                        }

                        is Outcome.Failure -> null
                    }

                else -> null
            }
            models[attachment.id] = model
        }
    }
    val overrides = remember(onOpenAttachment) {
        MediaPreviewOverrides(
            imageModel = { attachment -> models[attachment.id] },
            onOpen = onOpenAttachment,
        )
    }
    val first = attachments.firstOrNull() ?: return
    MediaAttachmentBubble(
        message = displayMessage,
        attachment = first,
        autres = attachments.drop(1),
        showTimestamp = showTimestamp,
        showStatus = false,
        onDelete = onDelete,
        onReply = null,
        onReact = null,
        onCopy = onCopy,
        previewOverrides = overrides,
        customBubbleColorArgb = customBubbleColorArgb,
    )
}

/**
 * Notes — extracts the first frame of a video file as a bitmap
 * scaled to at most [maxPx] on the long edge (the thread's 480 px
 * thumbnail cap). Fully guarded: an unreadable file yields null,
 * never a crash. Shared by the thread bubble and the panel tile.
 */
internal fun extractVideoFrame(file: File, maxPx: Int = 480): Bitmap? = runCatching {
    val retriever = MediaMetadataRetriever()
    try {
        retriever.setDataSource(file.absolutePath)
        val frame = retriever.getFrameAtTime(0) ?: return@runCatching null
        val longest = maxOf(frame.width, frame.height)
        if (longest <= maxPx) {
            frame
        } else {
            val scale = maxPx.toFloat() / longest
            Bitmap.createScaledBitmap(
                frame,
                (frame.width * scale).toInt().coerceAtLeast(1),
                (frame.height * scale).toInt().coerceAtLeast(1),
                true,
            )
        }
    } finally {
        runCatching { retriever.release() }
    }
}.getOrNull()
