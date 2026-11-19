package com.filestech.sms.ui.screens.clientcall

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.client.ClientActionSender
import com.filestech.sms.sync.client.ClientCallPhase
import com.filestech.sms.sync.client.ClientCallState
import com.filestech.sms.sync.client.ClientSyncEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Client mode — the incoming-call popup the tablet shows for a
 * ringing call: the caller's name (from the synced contacts) or the
 * number, plus their photo when the cache has one. The name stays
 * null for an unknown caller — the popup renders the number
 * instead, and "Unknown number" when the hub reported no number at
 * all (withheld, or a phone without the call-log permission).
 */
data class ClientCallPopup(
    val number: String,
    val displayName: String?,
    val avatarUri: String?,
)

/**
 * Client mode — tablet calls: the UI side of the show + text + dial
 * subset. One class, two owners: the AppRoot overlay observes the
 * incoming-call popup ([popup]) and opens the chat from it
 * ([openChat]); a thread observes the live call ([call]) for its
 * outgoing dial state and dials from its header button ([dial]).
 * Both instances read the same engine flow, so they can never
 * disagree about whether a call is live.
 *
 * The tablet never answers or ends a call: there is no public API
 * for either on a linked device, and the call audio stays on the
 * phone. The popup's only action is the chat button (text them
 * instead), and dialing just asks the hub to place the call.
 *
 * Hub mode renders nothing: the flows are gated on the device
 * mode, like `ConversationsViewModel.clientSyncUi`, and the engine
 * is only resolved in client mode (resolving it builds the mirror
 * graph, which a hub phone never touches).
 */
@HiltViewModel
class ClientCallViewModel @Inject constructor(
    private val syncEngine: dagger.Lazy<ClientSyncEngine>,
    private val actionSender: ClientActionSender,
    private val contacts: ContactRepository,
    private val conversations: ConversationRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    /**
     * The incoming-call popup, or null: an incoming call in
     * [ClientCallPhase.RINGING], resolved to its caller. The popup
     * clears itself when the call ends (or is answered elsewhere) —
     * the engine clears the call state, and this flow follows it.
     * An outgoing dial never produces a popup.
     */
    val popup: StateFlow<ClientCallPopup?> = settings.flow
        .map { it.mode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            if (mode != DeviceMode.CLIENT) flowOf(null) else callPopups()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The live call, or null — a thread renders its outgoing dial state from this. */
    val call: StateFlow<ClientCallState?> = settings.flow
        .map { it.mode }
        .distinctUntilChanged()
        .flatMapLatest { mode ->
            if (mode != DeviceMode.CLIENT) {
                flowOf(null)
            } else {
                syncEngine.get().ui.map { it.call }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<Event>(Channel.BUFFERED)
    val events: Flow<Event> = _events.receiveAsFlow()

    /** The popup stream: one resolved popup per ringing incoming call, null otherwise. */
    private fun callPopups(): Flow<ClientCallPopup?> = syncEngine.get().ui
        .map { it.call }
        .distinctUntilChanged()
        .flatMapLatest { current ->
            if (current == null || !current.incoming || current.phase != ClientCallPhase.RINGING) {
                flowOf(null)
            } else {
                flow { emit(resolvePopup(current.number)) }
            }
        }

    /**
     * The popup for a ringing number: the synced contact's name and
     * photo when the cache knows the number (the lookup is
     * mode-aware — the synced cache in client mode, with a lazy
     * avatar request when the photo is not cached yet), the bare
     * number otherwise. A failed lookup never hides the popup —
     * the number alone is still worth showing.
     */
    private suspend fun resolvePopup(number: String): ClientCallPopup {
        val contact = number.takeIf { it.isNotBlank() }?.let {
            runCatching { contacts.lookupByPhone(it) }.getOrNull()
        }
        return ClientCallPopup(
            number = number,
            displayName = contact?.displayName,
            avatarUri = contact?.photoUri,
        )
    }

    /**
     * Dials [number] on the hub phone: the outgoing state shows at
     * once, the frame goes out, and the phone's `call_state`
     * follows (`active` when the dial lands, `ended` when it is
     * over). A failed dial clears the state and reports why — the
     * audio stays on the phone either way.
     */
    fun dial(number: String) {
        viewModelScope.launch {
            syncEngine.get().noteDialRequested(number)
            when (val result = actionSender.dial(number)) {
                is Outcome.Success -> Unit
                is Outcome.Failure -> {
                    syncEngine.get().noteDialFailed(number)
                    _events.trySend(Event.DialFailed(dialFailureOf(result.error)))
                }
            }
        }
    }

    /**
     * Opens the chat with [number] from the popup: the existing
     * 1:1 thread when one is cached (archived included — a thread
     * is a thread), else a new conversation through the hub (the
     * C3 create — the engine upserts the row before the answer
     * lands, so the id is navigable at once).
     */
    fun openChat(number: String) {
        viewModelScope.launch {
            val existing = findConversationId(number)
            if (existing != null) {
                _events.trySend(Event.OpenThread(existing))
                return@launch
            }
            when (val result = actionSender.createConversation(listOf(number), null)) {
                is Outcome.Success -> _events.trySend(Event.OpenThread(result.value))
                is Outcome.Failure -> {
                    Timber.w("ClientCallViewModel: opening the chat failed: %s", result.error)
                    _events.trySend(Event.OpenChatFailed)
                }
            }
        }
    }

    /** The cached 1:1 thread for [number], matched on the normalized form, or null. */
    private suspend fun findConversationId(number: String): Long? {
        val normalized = PhoneAddress.of(number).normalized
        return runCatching {
            conversations.observeAll(includeArchived = true).first().firstOrNull { conversation ->
                !conversation.isGroup &&
                    conversation.addresses.any { it.normalized == normalized }
            }?.id
        }.getOrNull()
    }

    /** The dial failure the UI explains, from the sender's error. */
    private fun dialFailureOf(error: AppError): DialFailure = when (error) {
        is AppError.ClientOffline -> DialFailure.Offline
        is AppError.Validation -> when (error.message) {
            SyncProtocol.DialResult.PERMISSION_DENIED -> DialFailure.NoPermission
            else -> DialFailure.Failed
        }

        else -> DialFailure.Failed
    }

    /** Why a dial did not go through, as the UI explains it. */
    enum class DialFailure {
        /** The tablet is not connected to the phone. */
        Offline,

        /** The phone refused: CALL_PHONE is not granted on the hub. */
        NoPermission,

        /** Anything else: the phone refused, or no answer came back. */
        Failed,
    }

    sealed interface Event {
        /** Open this thread (an existing one, or one just created). */
        data class OpenThread(val conversationId: Long) : Event

        /** The chat could not be opened (the create failed). */
        data object OpenChatFailed : Event

        /** The dial did not go through. */
        data class DialFailed(val reason: DialFailure) : Event
    }
}
