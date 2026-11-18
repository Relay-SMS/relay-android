package com.filestech.sms.sync.client

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.sync.QrPairing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Client mode — the ViewModel for the client branch of the Linked
 * devices screen (and the mode row on the hub branch). It owns the
 * link flow: the device identity, the pairing QR, the auto-link
 * loop, Unlink, and the mode switch. The hub branch keeps its own
 * ViewModel ([com.filestech.sms.ui.screens.linkeddevices
 * .LinkedDevicesViewModel]) untouched.
 *
 * The mode itself comes from [AppSettingsSource] and is `null`
 * until the settings hydrate, so the screen never flashes the wrong
 * branch on a cold start.
 */
@HiltViewModel
class ClientLinkViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettingsSource,
    private val linkStore: ClientLinkStore,
    private val identityStore: ClientIdentityStore,
    private val connectionManager: ClientConnectionManager,
    private val autoLinker: AutoLinker,
    private val cacheWiper: ClientCacheWiper,
    private val defaultSmsAppChecker: DefaultSmsAppChecker,
    // Client mode C2 — the sync engine: the prompt, the mode choice
    // and Re-sync all delegate to it.
    private val syncEngine: ClientSyncEngine,
) : ViewModel() {

    /** Hub or linked device — null until the settings hydrate. */
    val mode: StateFlow<DeviceMode?> = settings.flow
        .map { it.mode }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The link record, or null while unlinked. */
    val link: StateFlow<ClientLink?> = linkStore.link
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** The live connection state (delegated to the manager). */
    val connectionState: StateFlow<ClientConnectionState> = connectionManager.state

    /** The auto-link display state (delegated to the loop). */
    val autoLinkState: StateFlow<AutoLinkViewState> = autoLinker.state

    /** Client mode C2 — the sync state: the prompt phase, progress, errors (delegated). */
    val syncUi: StateFlow<ClientSyncUiState> = syncEngine.ui

    private val _qrOpen = MutableStateFlow(false)
    val qrOpen: StateFlow<Boolean> = _qrOpen

    /** The pairing URI rendered as the QR code, once loaded. */
    private val _pairUri = MutableStateFlow<String?>(null)
    val pairUri: StateFlow<String?> = _pairUri

    /** Why the identity (and so the QR) could not be built, if it failed. */
    private val _identityError = MutableStateFlow<String?>(null)
    val identityError: StateFlow<String?> = _identityError

    /**
     * The no-SIM nudge: this device has no SIM and does not hold the
     * default-SMS role, so it cannot message on its own. Read once
     * when the screen opens — the role can only change while the
     * screen is away, and reopening it re-reads.
     */
    val showNudge: Boolean =
        isSimLess(context) && !defaultSmsAppChecker.isDefault()

    /** This device's kind (phone / tablet), for the pairing QR and the auth hello. */
    private val kind = deviceKindFor(context.resources.configuration.smallestScreenWidthDp)

    init {
        // TEMP-DIAG "Not linked": proves whether the ViewModel is
        // fresh per entry or survives pops.
        Timber.d("ClientLinkViewModel: created vm=%s", hashCode())
        viewModelScope.launch {
            connectionManager.state.collect { state ->
                if (state is ClientConnectionState.Connected) {
                    // Linked: the QR has done its job, and the
                    // foreground service keeps the link alive from
                    // here on (idempotent — a reconnect re-enters
                    // here too).
                    if (_qrOpen.value) {
                        autoLinker.stop()
                        _qrOpen.value = false
                    }
                    ClientSyncService.start(context)
                }
            }
        }
    }

    // TEMP-DIAG "Not linked": proves whether the ViewModel is
    // destroyed when the screen is popped.
    override fun onCleared() {
        Timber.d("ClientLinkViewModel: cleared vm=%s", hashCode())
        super.onCleared()
    }

    /**
     * Opens the pairing QR and starts the auto-link loop. The license
     * gate runs first (LICENSE-GATE-STUB — it always passes, so the
     * flow is never blocked); a device whose identity cannot be
     * loaded shows the error instead of a code.
     */
    fun openQr() {
        viewModelScope.launch { openQrInternal() }
    }

    private suspend fun openQrInternal() {
        // LICENSE-GATE-STUB: the link flow checks the license before
        // linking proceeds. The stub always returns Valid, so this
        // passes through and never blocks linking.
        if (!LicenseGate.checkLicense().isValid()) {
            Timber.w("ClientLinkViewModel: license gate refused the link (stub should not)")
            _identityError.value = "Linking is unavailable."
            return
        }
        val identity = try {
            identityStore.identity()
        } catch (t: Throwable) {
            Timber.w(t, "ClientLinkViewModel: identity could not be loaded")
            _identityError.value = t.message ?: "This device's identity could not be loaded."
            return
        }
        _identityError.value = null
        _pairUri.value = QrPairing.buildPairUri(
            identity.deviceId,
            kind,
            identity.displayName,
            identity.publicKeyBase64Url,
        )
        _qrOpen.value = true
        autoLinker.start(kind, viewModelScope)
    }

    /** Closes the pairing QR and stops the auto-link loop. */
    fun closeQr() {
        autoLinker.stop()
        _qrOpen.value = false
    }

    /**
     * The user picked a sync mode in the prompt ("full" /
     * "new_only"). Delegates to the engine, which stores the choice
     * per-hub and starts the sync.
     */
    fun chooseSyncMode(mode: String) {
        viewModelScope.launch { syncEngine.chooseMode(mode) }
    }

    /**
     * Re-sync: wipes the message cache and re-prompts for the mode.
     * The two-tap confirm lives in the UI. The link survives.
     */
    fun resync() {
        viewModelScope.launch { syncEngine.resync() }
    }

    /**
     * Client mode C4 — the contacts Retry: re-asks the contact
     * snapshot. The error note stays until a snapshot succeeds.
     */
    fun retryContacts() {
        viewModelScope.launch { syncEngine.retryContacts() }
    }

    /**
     * Unlinks from the hub: stops the service and the transport,
     * drops the hub record, the TOFU pin and the cache. The mode
     * stays client and the device identity survives, so re-linking
     * keeps the same device id. The two-tap confirm lives in the UI.
     */
    fun unlink() {
        viewModelScope.launch {
            ClientSyncService.stop(context)
            connectionManager.disconnect()
            autoLinker.stop()
            cacheWiper.wipeHubState()
            _qrOpen.value = false
        }
    }

    /**
     * Switches the device mode (hub ↔ linked device). The transport
     * and service stop first, then the hub state is wiped (link,
     * TOFU pin, cache — the device identity survives), then the mode
     * flips; the app's service observers react to the flip and start
     * or stop the hub server accordingly. The confirm dialog lives in
     * the UI.
     */
    fun switchMode(target: DeviceMode) {
        viewModelScope.launch { switchModeInternal(target) }
    }

    /**
     * The no-SIM nudge tap: switches to linked-device mode, then
     * opens the pairing QR — one tap starts the whole link flow.
     */
    fun switchToClientAndOpenQr() {
        viewModelScope.launch {
            switchModeInternal(DeviceMode.CLIENT)
            openQrInternal()
        }
    }

    private suspend fun switchModeInternal(target: DeviceMode) {
        ClientSyncService.stop(context)
        connectionManager.disconnect()
        autoLinker.stop()
        _qrOpen.value = false
        cacheWiper.wipeHubState()
        settings.update { it.copy(mode = target) }
    }
}
