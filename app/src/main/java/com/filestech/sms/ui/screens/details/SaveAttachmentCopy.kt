package com.filestech.sms.ui.screens.details

import android.content.Context
import android.net.Uri
import com.filestech.sms.domain.model.Attachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

/**
 * Round 33 — saves a copy of an attachment to a destination the
 * user picked (Storage Access Framework "Save as"): the hub-mode
 * meaning of the download icon on media tiles and file rows. The
 * file is already local, so "download" exports a copy out of Relay
 * instead of fetching anything — no permission needed on any API
 * level, and the user chooses where it lands (Downloads by
 * default in the picker), like the desktop's save-as.
 *
 * `true` when the bytes landed; `false` on any failure (missing
 * file, dead provider URI, no output stream, I/O error) — never
 * a throw. Runs on [Dispatchers.IO]; call from any scope.
 */
suspend fun saveAttachmentCopy(
    context: Context,
    attachment: Attachment,
    target: Uri,
): Boolean = withContext(Dispatchers.IO) {
    val input = runCatching { openAttachmentStream(context, attachment) }.getOrNull()
        ?: return@withContext false
    runCatching {
        input.use { inputStream ->
            val out = context.contentResolver.openOutputStream(target)
                ?: return@runCatching false
            out.use { inputStream.copyTo(it) }
            true
        }
    }.getOrDefault(false)
}

/** The filename the save picker suggests: the attachment's own name, else a generic one. */
internal fun saveFileNameFor(attachment: Attachment): String =
    attachment.fileName?.takeIf { it.isNotBlank() } ?: FALLBACK_SAVE_NAME

/** Opens the attachment's bytes: the provider stream for a `content://` row, the file otherwise. */
private fun openAttachmentStream(context: Context, attachment: Attachment): InputStream? {
    val uri = attachment.localUri
    if (uri.isBlank()) return null
    return if (uri.startsWith("content://")) {
        context.contentResolver.openInputStream(Uri.parse(uri))
    } else {
        File(uri).takeIf { it.exists() }?.inputStream()
    }
}

private const val FALLBACK_SAVE_NAME = "attachment"
