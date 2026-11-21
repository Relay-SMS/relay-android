package com.filestech.sms.ui.screens.media

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.media.probePngDimensions
import com.filestech.sms.data.media.probeWebpDimensions
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.sync.client.ClientAttachmentDownloader
import com.filestech.sms.sync.client.ClientDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Issue #59 — les photos et vidéos d'une conversation, envoyées et reçues, en lecture seule.
 *
 * On y entre par le menu du fil, jamais d'ailleurs : une galerie de toutes les conversations mêlerait
 * celles du Coffre aux autres.
 */
@HiltViewModel
class MediaGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    repo: ConversationRepository,
    /** Client mode C5 — the download states + request hook for not-yet-downloaded tiles. */
    private val downloader: ClientAttachmentDownloader,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val conversationId: Long = checkNotNull(savedStateHandle["conversationId"])

    // Round 33 — probed image dimensions, by attachment id: a probe
    // reads a header per file, but the media flow re-emits on every
    // DB change, so a probe runs once per attachment per process. A
    // failed probe is remembered too (in [probeAttempted] with no
    // cache entry), so a dead URI is not re-opened on every emission.
    // Round 36 — the probe covers PNG and WebP, and falls back to
    // the system MMS store when the cache file is gone.
    private val dimensionCache = ConcurrentHashMap<Long, Pair<Int, Int>>()
    private val probeAttempted = ConcurrentHashMap.newKeySet<Long>()

    /** `null` tant que la première lecture n'a pas répondu : l'écran ne dit pas « aucune photo » avant de savoir. */
    val media: StateFlow<List<Attachment>?> = repo.observeMedia(conversationId)
        // Round 33 — dimension-less PNGs are probed from their bytes
        // before the panel's sticker filter sees them (see
        // [Attachment.withProbedDimensions]). Round 36 — WebP too,
        // plus the system-store fallback for pruned cache files.
        .map { list -> list?.map { it.withProbedDimensions() } }
        .flowOn(Dispatchers.IO)
        .stateIn<List<Attachment>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Details panel — Files tab: the non-photo, non-video attachments (`null` while loading). */
    val files: StateFlow<List<Attachment>?> = repo.observeFiles(conversationId)
        .stateIn<List<Attachment>?>(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    /** Le Coffre s'est refermé : l'écran sort, comme le fil, au lieu de prétendre qu'il n'y a rien. */
    val vaultHidden: StateFlow<Boolean> = repo.observeVaultHidden(conversationId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    /** Client mode C5 — the download states, keyed by local attachment id (empty in hub mode). */
    val downloadStates: StateFlow<Map<Long, ClientDownloadState>> = downloader.states
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyMap())

    /** Client mode C5 — asks the downloader for an attachment's bytes (tap-to-download). */
    fun requestDownload(localAttachmentId: Long) {
        viewModelScope.launch {
            runCatching { downloader.request(localAttachmentId) }
        }
    }

    /** Client mode C5 — retries a failed download (tap-to-retry on a failed tile). */
    fun retryDownload(localAttachmentId: Long) {
        viewModelScope.launch {
            runCatching { downloader.retry(localAttachmentId) }
        }
    }

    /**
     * Round 33 — fills in image dimensions the import never stored:
     * incoming MMS rows carry `width`/`height` as `null`, so the
     * sticker filter's 512×512 arm could never fire for a received
     * sticker. A dimension-less image is probed from its bytes —
     * the file, or the provider stream for a `content://mms/part/…`
     * row — before the filter sees it. Anything else passes through
     * untouched, and a probe runs once per attachment (see
     * [dimensionCache]).
     *
     * Round 36 — the probe covers WebP as well as PNG (imported
     * stickers from a switcher's old app), and falls back to the
     * system MMS store when the cache file is gone (see
     * [Attachment.probeSystemStoreBytes]): an old sticker whose
     * file was pruned still has its bytes in the system store.
     */
    private fun Attachment.withProbedDimensions(): Attachment {
        if (!isProbedImageType() || width != null || height != null) {
            return this
        }
        val cached = dimensionCache[id]
        if (cached != null) return copy(width = cached.first, height = cached.second)
        if (!probeAttempted.add(id)) return this
        val probed = probeImageBytes() ?: probeSystemStoreBytes()
        if (probed != null) dimensionCache[id] = probed
        return if (probed != null) {
            copy(width = probed.first, height = probed.second)
        } else {
            this
        }
    }

    /** Probes this attachment's image bytes (file or provider stream), or `null`. */
    private fun Attachment.probeImageBytes(): Pair<Int, Int>? {
        if (localUri.isBlank()) return null
        return runCatching {
            if (localUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(localUri))
                    ?.use { probeImageStream(it) }
            } else {
                File(localUri).inputStream().use { probeImageStream(it) }
            }
        }.getOrNull()
    }

    /**
     * Round 36 — the fallback for a pruned cache file: the bytes
     * are still in the system MMS store when the message was
     * written there (`mmsSystemId`, set on send). The part is
     * matched only when the MMS carries exactly one part of this
     * attachment's MIME type — a sticker message carries one
     * image, and with several this attachment cannot be told
     * apart from its siblings, so the probe stays silent (the
     * filter only hides on evidence). Returns `null` when there
     * is no system id, no matching part, or unreadable bytes.
     */
    private fun Attachment.probeSystemStoreBytes(): Pair<Int, Int>? {
        val systemId = mmsSystemId ?: return null
        val partId = runCatching {
            context.contentResolver.query(
                Uri.parse("content://mms/part"),
                arrayOf("_id", "ct"),
                "mid = ?",
                arrayOf(systemId.toString()),
                null,
            )?.use { cursor ->
                val matchingParts = ArrayList<Long>()
                while (cursor.moveToNext()) {
                    val contentType = cursor.getString(1)?.substringBefore(';')?.trim()
                    if (contentType.equals(mimeType.substringBefore(';').trim(), ignoreCase = true)) {
                        matchingParts += cursor.getLong(0)
                    }
                }
                matchingParts.singleOrNull()
            }
        }.getOrNull() ?: return null
        return runCatching {
            context.contentResolver.openInputStream(Uri.parse("content://mms/part/$partId"))
                ?.use { probeImageStream(it) }
        }.getOrNull()
    }

    /** Probes one image stream (PNG or WebP), or `null`. */
    private fun Attachment.probeImageStream(input: InputStream): Pair<Int, Int>? = when {
        mimeType.equals("image/png", ignoreCase = true) -> probePngDimensions(input)
        mimeType.equals("image/webp", ignoreCase = true) -> probeWebpDimensions(input)
        else -> null
    }

    /** Whether this attachment's bytes can be probed for dimensions (PNG or WebP). */
    private fun Attachment.isProbedImageType(): Boolean =
        mimeType.equals("image/png", ignoreCase = true) ||
            mimeType.equals("image/webp", ignoreCase = true)
}
