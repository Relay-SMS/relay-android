package com.filestech.sms.ui.screens.media

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.data.media.probePngDimensions
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

    // Round 33 — probed PNG dimensions, by attachment id: a probe
    // reads 24 bytes per file, but the media flow re-emits on every
    // DB change, so a probe runs once per attachment per process. A
    // failed probe is remembered too (in [pngProbeAttempted] with no
    // cache entry), so a dead URI is not re-opened on every emission.
    private val pngDimensionCache = ConcurrentHashMap<Long, Pair<Int, Int>>()
    private val pngProbeAttempted = ConcurrentHashMap.newKeySet<Long>()

    /** `null` tant que la première lecture n'a pas répondu : l'écran ne dit pas « aucune photo » avant de savoir. */
    val media: StateFlow<List<Attachment>?> = repo.observeMedia(conversationId)
        // Round 33 — dimension-less PNGs are probed from their bytes
        // before the panel's sticker filter sees them (see
        // [Attachment.withProbedPngDimensions]).
        .map { list -> list?.map { it.withProbedPngDimensions() } }
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
     * Round 33 — fills in PNG dimensions the import never stored:
     * incoming MMS rows carry `width`/`height` as `null`, so the
     * sticker filter's 512×512 arm could never fire for a received
     * sticker. A dimension-less PNG is probed from its bytes — the
     * file, or the provider stream for a `content://mms/part/…` row
     * — before the filter sees it. Anything else passes through
     * untouched, and a probe runs once per attachment (see
     * [pngDimensionCache]).
     */
    private fun Attachment.withProbedPngDimensions(): Attachment {
        if (!mimeType.equals("image/png", ignoreCase = true) ||
            width != null || height != null
        ) {
            return this
        }
        val cached = pngDimensionCache[id]
        if (cached != null) return copy(width = cached.first, height = cached.second)
        if (!pngProbeAttempted.add(id)) return this
        val probed = probePngBytes()
        if (probed != null) pngDimensionCache[id] = probed
        return if (probed != null) {
            copy(width = probed.first, height = probed.second)
        } else {
            this
        }
    }

    /** Probes this attachment's PNG bytes (file or provider stream), or `null`. */
    private fun Attachment.probePngBytes(): Pair<Int, Int>? {
        if (localUri.isBlank()) return null
        return runCatching {
            if (localUri.startsWith("content://")) {
                context.contentResolver.openInputStream(Uri.parse(localUri))
                    ?.use { probePngDimensions(it) }
            } else {
                probePngDimensions(File(localUri))
            }
        }.getOrNull()
    }
}
