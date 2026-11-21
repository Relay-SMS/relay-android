package com.filestech.sms.ui.screens.settings

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2eDisplayFormat
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
 */
@HiltViewModel
class EncryptionSettingsViewModel @Inject constructor(
    private val peerRepository: E2ePeerRepository,
    private val accountManager: E2eAccountManager,
    private val contactRepository: ContactRepository,
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
        /** The verified contacts, most recently updated first. */
        val verifiedContacts: List<VerifiedContact> = emptyList(),
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch(io) {
            val keys = runCatching { accountManager.identityKeys() }.getOrNull()
            if (keys != null) {
                _state.update {
                    it.copy(fingerprint = E2eDisplayFormat.formatFingerprint(keys.curve25519))
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
    }
}
