package com.filestech.sms.ui.screens.media

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.sync.client.ClientAttachmentDownloader
import com.filestech.sms.sync.client.ClientDownloadState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
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
) : ViewModel() {

    private val conversationId: Long = checkNotNull(savedStateHandle["conversationId"])

    /** `null` tant que la première lecture n'a pas répondu : l'écran ne dit pas « aucune photo » avant de savoir. */
    val media: StateFlow<List<Attachment>?> = repo.observeMedia(conversationId)
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
}
