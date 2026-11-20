package com.filestech.sms.ui.screens.notes

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.notes.NoteAttachmentResolver
import com.filestech.sms.ui.components.rememberAttachmentOpener
import kotlinx.coroutines.launch

/**
 * Notes — the tap-to-open handler for note attachments, shared by the
 * thread bubbles and the details panel.
 *
 * The flow: decrypt the attachment to a playback file (plaintext, in
 * `cacheDir/notes_playback/`), register the file with the ViewModel
 * (released in `onCleared` — there is no way to know when an external
 * viewer finishes), then hand a copy of the attachment pointing at the
 * plaintext file to the SHARED remembered-viewer opener
 * ([rememberAttachmentOpener]). The viewer preference is the same one
 * the thread uses — one remembered viewer per media type, never a
 * second preference. A failed decrypt calls [onOpenFailed] (the
 * screen shows the snackbar).
 */
@Composable
fun rememberNoteAttachmentOpener(
    resolver: NoteAttachmentResolver,
    onRegisterPlaybackFile: (java.io.File) -> Unit,
    onOpenFailed: () -> Unit,
): (Attachment) -> Unit {
    val defaultOpener = rememberAttachmentOpener()
    val scope = rememberCoroutineScope()
    return remember(resolver, defaultOpener, scope, onRegisterPlaybackFile, onOpenFailed) {
        {
                attachment: Attachment ->
            scope.launch {
                when (val res = resolver.decryptToPlaybackFile(attachment)) {
                    is Outcome.Success -> {
                        onRegisterPlaybackFile(res.value)
                        defaultOpener(attachment.copy(localUri = res.value.absolutePath))
                    }

                    is Outcome.Failure -> onOpenFailed()
                }
            }
        }
    }
}
