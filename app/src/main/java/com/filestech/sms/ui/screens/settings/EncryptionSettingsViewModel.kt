package com.filestech.sms.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2eDisplayFormat
import com.filestech.sms.e2e.E2eFailureRecorder
import com.filestech.sms.e2e.E2ePeerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Backs the [EncryptionScreen] (Settings → Security & Privacy →
 * Encryption, design §7.1): our own fingerprint (the Curve25519
 * identity key, grouped hex) and the verified contacts, live.
 *
 * Viewing the fingerprint creates the identity lazily (via
 * [E2eAccountManager.identityKeys]) — viewing it is an E2E use, so
 * that is expected. The screen is hub-only (the row is hidden on a
 * linked device), so a client never creates an identity here.
 * Nothing here logs keys or addresses (§8.3).
 *
 * When the identity fails to load, the failure is surfaced, not
 * swallowed: [UiState.fingerprintError] carries the reason (see
 * [E2eDisplayFormat.describeFailure]) and the failure is recorded
 * in the local error log ([E2eFailureRecorder]) — round 38 showed
 * a missing fingerprint with no trace of why, and the next device
 * test must be able to say which step failed.
 *
 * Round 40 — the screen also carries the discovery toggle
 * ([UiState.autoDiscoverRelayContacts]): automatic Relay discovery
 * (outbound probing), default ON. OFF stops this phone sending
 * probes; inbound handshakes stay active.
 */
@HiltViewModel
class EncryptionSettingsViewModel @Inject constructor(
    private val peerRepository: E2ePeerRepository,
    private val accountManager: E2eAccountManager,
    private val contactRepository: ContactRepository,
    private val recorder: E2eFailureRecorder,
    private val settings: AppSettingsSource,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    /** One verified contact: the peer address plus its display name. */
    @Stable
    data class VerifiedContact(
        /** The peer's normalized address. */
        val address: String,
        /** Contact name, or the formatted number when unknown. */
        val displayName: String,
    )

    @Stable
    data class UiState(
        /** Our fingerprint (grouped hex), null until loaded. */
        val fingerprint: String? = null,
        /** True once the identity load has finished, success or failure. */
        val fingerprintLoaded: Boolean = false,
        /**
         * Why the fingerprint failed to load, null on success or
         * while loading (see [E2eDisplayFormat.describeFailure]).
         * Never carries keys, addresses, or PII.
         */
        val fingerprintError: String? = null,
        /** The verified contacts, most recently updated first. */
        val verifiedContacts: List<VerifiedContact> = emptyList(),
        /**
         * Automatic Relay discovery (outbound probing), default ON.
         * OFF stops this phone sending probes; inbound handshakes
         * stay active, so encryption still starts when the other
         * side initiates.
         */
        val autoDiscoverRelayContacts: Boolean = true,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(io) {
            val result = runCatching { accountManager.identityKeys() }
            val keys = result.getOrNull()
            if (keys != null) {
                _state.update {
                    it.copy(
                        fingerprint = E2eDisplayFormat.formatFingerprint(keys.curve25519),
                        fingerprintLoaded = true,
                    )
                }
            } else {
                val error = result.exceptionOrNull()
                if (error != null) {
                    recorder.record("e2e-fingerprint", error)
                }
                _state.update {
                    it.copy(
                        fingerprintLoaded = true,
                        fingerprintError = error?.let { E2eDisplayFormat.describeFailure(it) },
                    )
                }
            }
        }
        viewModelScope.launch(io) {
            peerRepository.observeVerifiedPeers().collect { peers ->
                val contacts = peers.map { peer ->
                    val name = contactRepository.lookupByPhone(peer.address)?.displayName
                        ?.takeIf { it.isNotBlank() }
                        ?: peer.address.formatPhoneNumberForDisplay()
                    VerifiedContact(address = peer.address, displayName = name)
                }
                _state.update { it.copy(verifiedContacts = contacts) }
            }
        }
        viewModelScope.launch(io) {
            settings.flow.collect { appSettings ->
                val autoDiscover = appSettings.encryption.autoDiscoverRelayContacts
                _state.update { it.copy(autoDiscoverRelayContacts = autoDiscover) }
            }
        }
    }

    /** Flips automatic Relay discovery (outbound probing only). */
    fun setAutoDiscoverRelayContacts(enabled: Boolean) {
        viewModelScope.launch(io) {
            settings.update {
                it.copy(
                    encryption = it.encryption.copy(autoDiscoverRelayContacts = enabled),
                )
            }
        }
    }
}
