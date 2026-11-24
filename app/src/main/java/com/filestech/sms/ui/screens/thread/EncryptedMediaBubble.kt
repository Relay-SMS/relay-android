package com.filestech.sms.ui.screens.thread

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.mms.RelayMediaFilename
import com.filestech.sms.data.voice.VoicePlaybackController
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Message
import com.filestech.sms.e2e.withMediaKey
import com.filestech.sms.ui.components.AudioMessageBubble
import com.filestech.sms.ui.components.BubbleMenuToggles
import com.filestech.sms.ui.components.E2eMediaDecryptPendingRow
import com.filestech.sms.ui.components.MediaAttachmentBubble
import com.filestech.sms.ui.components.MediaPreviewOverrides
import com.filestech.sms.ui.components.QuickReactionConfig
import com.filestech.sms.ui.components.ReplyQuotePreview
import com.filestech.sms.ui.components.SearchHighlight
import com.filestech.sms.ui.components.rememberAttachmentOpener
import com.filestech.sms.ui.screens.notes.extractVideoFrame
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Round 48 — the media bubble for an INCOMING encrypted MMS
 * message: [MediaAttachmentBubble] / [AudioMessageBubble] with
 * the encrypted-preview seam ([MediaPreviewOverrides]), modelled
 * on [com.filestech.sms.ui.screens.notes.NoteMediaAttachmentBubble].
 *
 * Three states, from the key rows (pending is DERIVED — the
 * message row never changes on a key arrival):
 * - **Pending** — any relay-media attachment has no key yet: the
 *   pending row ("Waiting to decrypt…"), not a bubble. The
 *   wrapper collects the key Flows, so the row flips to the
 *   decrypted bubble the moment the key lands, without
 *   reopening the thread.
 * - **Ready** — every relay-media attachment has a key: each
 *   attachment resolves to its EFFECTIVE copy (original
 *   mime/name/size from the key metadata — the stored row
 *   describes the ciphertext part, and dispatch keys off the
 *   MIME) and decrypts on view. Images/GIFs decrypt to memory
 *   (Coil `ByteArray`); video decrypts to a playback file whose
 *   first frame is the model (the file is released immediately
 *   after extraction); audio decrypts to a playback file on
 *   tap. Thumbnails are derived after decrypt only — never
 *   from ciphertext, and Coil's disk cache is disabled for
 *   override models (see `MediaAttachmentBubble`).
 * - **Failed** — the resolver marks the message failed on a
 *   decrypt/verification failure, and the thread re-renders the
 *   "couldn't be decrypted" row (the wrapper unmounts — it has
 *   no failure UI of its own).
 *
 * The ciphertext URI is never handed to Coil, a player or a
 * viewer: image models come from decrypted bytes, playback goes
 * through a decrypted file, and tap-to-open decrypts to a
 * playback file first (the plaintext file lives in the
 * `cacheDir` playback dir until the 24-hour stale purge — the
 * transient-plaintext window documented on
 * [com.filestech.sms.e2e.E2eMediaResolver.decryptToPlaybackFile]).
 *
 * INCOMING only: the caller gates on it. Our own outgoing
 * encrypted media renders from the plaintext original the sender
 * kept — the receiver must never try to decrypt our own parts
 * (we hold no key for them; the sender keeps none).
 *
 * Round 49 — the lock rides the delegated bubbles: both
 * [MediaAttachmentBubble] and [AudioMessageBubble] render their
 * timestamp line through
 * [com.filestech.sms.ui.components.BubbleTimestamp]
 * ([com.filestech.sms.ui.components.shouldShowLock]), so an
 * incoming encrypted photo, video or voice clip shows it with no
 * lock code in this wrapper.
 */
@Composable
fun EncryptedMediaBubble(
    message: Message,
    showTimestamp: Boolean,
    showStatus: Boolean,
    senderLabel: String?,
    playbackProvider: () -> VoicePlaybackController.PlaybackState,
    repliedToPreview: ReplyQuotePreview?,
    searchHighlight: SearchHighlight,
    onDelete: (() -> Unit)?,
    onReply: (() -> Unit)?,
    onReact: (() -> Unit)?,
    quickReactions: QuickReactionConfig?,
    onForward: (() -> Unit)?,
    toggles: BubbleMenuToggles?,
    onCopy: (() -> Unit)?,
    onPhoneClick: (String) -> Unit,
    onSeekPlayback: (Int) -> Unit,
    /**
     * The two-arg playback toggle (`key`, `uri`): the key is the
     * attachment's `localUri` (the ciphertext path — the stable
     * identity, like the notes path), the URI is the decrypted
     * playback file. The one-arg toggle couples key + URI from a
     * single attachment and cannot express "play this plaintext
     * file under that ciphertext identity".
     */
    onTogglePlaybackAt: (String, Uri) -> Unit,
) {
    val context = LocalContext.current
    val resolver = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            E2eMediaResolverEntryPoint::class.java,
        ).mediaResolver()
    }
    val scope = rememberCoroutineScope()

    val attachments = message.attachments
    // The STORED rows, by id: decryption always reads these —
    // the effective copies carry the ORIGINAL filename, which no
    // longer parses to a file id.
    val storedById = remember(attachments) { attachments.associateBy { it.id } }
    val relayFileIds = remember(attachments) {
        attachments.mapNotNull { RelayMediaFilename.parse(it.fileName) }
    }
    // The keys flow is remembered: `observeKeys` builds a new
    // Flow per call, and collecting a fresh instance on every
    // recomposition would restart the collection each time.
    // Initial `emptyMap()` reads as "no keys yet" — the same as
    // the pending row — until the Room Flow emits its first map
    // (which carries every file id, null-valued when keyless).
    val keysFlow = remember(resolver, relayFileIds) { resolver.observeKeys(relayFileIds) }
    val keys by keysFlow.collectAsStateWithLifecycle(initialValue = emptyMap())
    val anyPending = relayFileIds.any { keys[it] == null }
    if (anyPending) {
        E2eMediaDecryptPendingRow()
        return
    }
    // Effective attachments: relay-media parts substitute the
    // original mime/name/size from their key; plain parts in the
    // same message pass through unchanged.
    val effective = remember(attachments, keys) {
        attachments.map { attachment ->
            val fileId = RelayMediaFilename.parse(attachment.fileName)
            val key = fileId?.let { keys[it] }
            if (key != null) attachment.withMediaKey(key) else attachment
        }
    }
    val effectiveById = remember(effective) { effective.associateBy { it.id } }

    // Decrypted Coil models, keyed by attachment id — a re-render
    // never re-decrypts. The effect re-runs when the effective
    // list changes (a key landing flips a mime), and skips ids
    // already resolved. A decrypt failure leaves a null model
    // (the neutral placeholder) — and the resolver has already
    // marked the message failed, so the thread re-renders the
    // failed row.
    val models = remember { mutableStateMapOf<Long, Any?>() }
    LaunchedEffect(message.id, effective) {
        for (stored in attachments) {
            if (models.containsKey(stored.id)) continue
            val eff = effectiveById[stored.id] ?: stored
            val model: Any? = when {
                eff.isImage -> when (val res = resolver.decryptToMemory(stored)) {
                    is Outcome.Success -> res.value
                    is Outcome.Failure -> null
                }

                eff.isVideo -> when (val res = resolver.decryptToPlaybackFile(stored)) {
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
            models[stored.id] = model
        }
    }

    // Tap-to-open: decrypt to a playback file, then hand a copy
    // of the EFFECTIVE attachment (original mime + name, so the
    // viewer picker shows the right type) pointing at the
    // plaintext file to the SHARED remembered-viewer opener — one
    // remembered viewer per media type, never a second
    // preference. The playback file is not released here (there
    // is no way to know when an external viewer finishes); the
    // 24-hour stale purge is the backstop.
    val defaultOpener = rememberAttachmentOpener()
    val onOpen = remember(resolver, defaultOpener, storedById, scope) {
        {
                attachment: Attachment ->
            scope.launch {
                val stored = storedById[attachment.id] ?: attachment
                when (val res = resolver.decryptToPlaybackFile(stored)) {
                    is Outcome.Success -> {
                        defaultOpener(attachment.copy(localUri = res.value.absolutePath))
                    }

                    is Outcome.Failure -> {
                        // The resolver marked the row failed; the
                        // thread re-renders as the failed row.
                    }
                }
            }
            Unit
        }
    }
    val overrides = remember(onOpen) {
        MediaPreviewOverrides(
            imageModel = { attachment -> models[attachment.id] },
            onOpen = onOpen,
        )
    }

    // Audio playback files, cached per attachment id: the first
    // tap decrypts, later taps reuse the same file — so the
    // playback key (the ciphertext `localUri`) stays bound to one
    // file and pause/resume works. Not released on dispose
    // (playback may outlive the bubble on screen); the 24-hour
    // stale purge is the backstop.
    val playbackFiles = remember { mutableStateMapOf<Long, File>() }
    val onTogglePlayEncrypted: (Attachment) -> Unit = { audio ->
        val key = audio.localUri
        if (playbackProvider().activeKey == key) {
            // Pause/resume: the controller ignores the URI in
            // this branch (the clip is already bound).
            onTogglePlaybackAt(key, Uri.EMPTY)
        } else {
            scope.launch {
                val cached = playbackFiles[audio.id]
                if (cached != null) {
                    onTogglePlaybackAt(key, Uri.fromFile(cached))
                } else {
                    val stored = storedById[audio.id] ?: audio
                    when (val res = resolver.decryptToPlaybackFile(stored)) {
                        is Outcome.Success -> {
                            playbackFiles[audio.id] = res.value
                            onTogglePlaybackAt(key, Uri.fromFile(res.value))
                        }

                        is Outcome.Failure -> {
                            // The resolver marked the row failed;
                            // the thread re-renders as the failed row.
                        }
                    }
                }
            }
        }
    }

    // Dispatch mirrors the plain thread item: audio wins over
    // media (a voice note + a photo in one message shows the
    // audio bubble, as on the plain path).
    val mediaAttachments = remember(effective) { effective.filter { !it.isAudio } }
    val mediaAttachment = mediaAttachments.firstOrNull()
    val audio = effective.firstOrNull { it.isAudio }
    when {
        audio != null -> {
            AudioMessageBubble(
                message = message,
                audio = audio,
                playbackProvider = playbackProvider,
                onTogglePlay = { onTogglePlayEncrypted(audio) },
                onSeekTo = onSeekPlayback,
                onDelete = onDelete,
                onReply = onReply,
                onReact = onReact,
                quickReactions = quickReactions,
                onForward = onForward,
                toggles = toggles,
                repliedToPreview = repliedToPreview,
                showTimestamp = showTimestamp,
                showStatus = showStatus,
                senderLabel = senderLabel,
                // Client mode never sees ciphertext parts (the
                // hub serves decrypted bytes), so no download.
                onRequestDownload = null,
            )
        }

        mediaAttachment != null -> {
            MediaAttachmentBubble(
                message = message,
                attachment = mediaAttachment,
                autres = mediaAttachments.drop(1),
                showTimestamp = showTimestamp,
                showStatus = showStatus,
                onDelete = onDelete,
                onReply = onReply,
                onReact = onReact,
                quickReactions = quickReactions,
                onCopy = onCopy,
                onForward = onForward,
                toggles = toggles,
                onPhoneClick = onPhoneClick,
                repliedToPreview = repliedToPreview,
                senderLabel = senderLabel,
                searchHighlight = searchHighlight,
                previewOverrides = overrides,
            )
        }
    }
}
