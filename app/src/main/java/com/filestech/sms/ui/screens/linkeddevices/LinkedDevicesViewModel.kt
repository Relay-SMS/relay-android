package com.filestech.sms.ui.screens.linkeddevices

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.sync.LinkedDevice
import com.filestech.sms.sync.LinkedDeviceStore
import com.filestech.sms.sync.QrPairing
import com.filestech.sms.sync.SyncServer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** One linked device as the screen shows it, with its live state. */
data class LinkedDeviceUi(
    val device: LinkedDevice,
    val connected: Boolean,
)

/** Everything the Linked devices screen renders. */
data class LinkedDevicesState(
    val serverEnabled: Boolean = false,
    val serverRunning: Boolean = false,
    val lastStartError: String? = null,
    val devices: List<LinkedDeviceUi> = emptyList(),
    val pendingInvite: QrPairing.Invite? = null,
    val scanError: QrPairing.Failure? = null,
)

/**
 * GLUE — the Linked devices screen's ViewModel. It wires the screen to
 * the standalone sync layer ([LinkedDeviceStore], [SyncServer],
 * [QrPairing]); it holds no protocol logic of its own.
 */
@HiltViewModel
class LinkedDevicesViewModel @Inject constructor(
    private val store: LinkedDeviceStore,
    private val server: SyncServer,
) : ViewModel() {

    sealed interface Event {
        data class Linked(val name: String) : Event
        data class Removed(val name: String) : Event
    }

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val pendingInvite = MutableStateFlow<QrPairing.Invite?>(null)
    private val scanError = MutableStateFlow<QrPairing.Failure?>(null)

    /**
     * When the user last dismissed a confirm dialog: scans are ignored
     * for a short cooldown after it, or the same code — still in frame
     * — would pop the dialog straight back up.
     */
    private var lastDismissedAtMs = 0L

    /**
     * The server's state as one flow: the typed `combine` overloads
     * stop at five inputs (verified in the coroutines 1.11.0 jar —
     * beyond five only the untyped `Array<Any?>` overload exists), so
     * the server's three flows combine here and the main combine
     * stays at five.
     */
    private data class ServerUiState(
        val enabled: Boolean,
        val running: Boolean,
        val lastStartError: String?,
    )

    private val serverUiState: Flow<ServerUiState> = combine(
        store.serverEnabled,
        server.running,
        store.lastStartError,
    ) { enabled, running, startError ->
        ServerUiState(enabled, running, startError)
    }

    val state: StateFlow<LinkedDevicesState> = combine(
        serverUiState,
        store.devices,
        server.connectedDeviceIds,
        pendingInvite,
        scanError,
    ) { server, devices, connected, invite, error ->
        LinkedDevicesState(
            serverEnabled = server.enabled,
            serverRunning = server.running,
            lastStartError = server.lastStartError,
            devices = devices.sortedByDescending { it.linkedAt }.map { device ->
                LinkedDeviceUi(device = device, connected = connected.contains(device.deviceId))
            },
            pendingInvite = invite,
            scanError = error,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LinkedDevicesState())

    fun setServerEnabled(enabled: Boolean) {
        viewModelScope.launch { store.setServerEnabled(enabled) }
    }

    /**
     * A QR code was decoded. A valid invite waits for the user's
     * confirm; an invalid one reports its reason and keeps the scanner
     * open — the user can point at another code. A scan that arrives
     * while an invite is already pending is ignored (the scanner
     * decodes every frame, and the same code would otherwise re-fire).
     */
    fun onQrScanned(text: String) {
        if (pendingInvite.value != null) return
        if (SystemClock.elapsedRealtime() - lastDismissedAtMs < DISMISS_COOLDOWN_MS) return
        when (val result = QrPairing.parse(text)) {
            is QrPairing.Result.Valid -> {
                scanError.value = null
                pendingInvite.value = result.invite
            }
            is QrPairing.Result.Invalid -> scanError.value = result.failure
        }
    }

    fun clearScanError() {
        scanError.value = null
    }

    fun dismissInvite() {
        pendingInvite.value = null
        lastDismissedAtMs = SystemClock.elapsedRealtime()
    }

    /** The user confirmed the scanned device: store it. */
    fun confirmLink() {
        val invite = pendingInvite.value ?: return
        pendingInvite.value = null
        viewModelScope.launch {
            store.addDevice(invite, System.currentTimeMillis())
            _events.emit(Event.Linked(invite.displayName))
        }
    }

    /**
     * Removes a device: revoke first (its live socket gets `revoked`,
     * then closes), then delete the stored key. A later auth attempt
     * from it gets `unknown_device`.
     */
    fun removeDevice(deviceId: String, displayName: String) {
        viewModelScope.launch {
            server.revoke(deviceId)
            store.removeDevice(deviceId)
            _events.emit(Event.Removed(displayName))
        }
    }

    companion object {
        /** Scans ignored for this long after the user dismisses a confirm dialog. */
        private const val DISMISS_COOLDOWN_MS = 2_000L
    }
}
