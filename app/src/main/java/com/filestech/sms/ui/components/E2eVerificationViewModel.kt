package com.filestech.sms.ui.components

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.e2e.E2E_KEY_LENGTH
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.e2e.E2eSafetyNumber
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import javax.inject.Inject

/**
 * Backs the [E2eVerificationSheet]: derives the 60-digit safety
 * number for one peer (our identity keys + the peer's stored bundle
 * keys, via [E2eSafetyNumber]) and owns the local verified flag.
 *
 * The number is re-derived on every [open]: a key change between
 * two openings must show the new number, never a cached one. The
 * verified flag is local only — it is never sent or synced (design
 * §5.2). Nothing here logs keys, numbers or addresses (§8.3).
 */
@HiltViewModel
class E2eVerificationViewModel @Inject constructor(
    private val peerRepository: E2ePeerRepository,
    private val accountManager: E2eAccountManager,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    @Stable
    data class UiState(
        /** The 60-digit safety number (12 groups of 5), null until loaded. */
        val safetyNumber: String? = null,
        /** The peer is marked verified (local flag). */
        val verified: Boolean = false,
        /** The number is still loading. */
        val loading: Boolean = true,
        /** The number could not be derived (no peer keys, bad keys). */
        val loadFailed: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The peer this sheet verifies (normalized address). */
    private var currentAddress: String? = null

    /**
     * Loads the safety number for [address]. Called from the sheet's
     * `LaunchedEffect(address)` — opening the sheet for a new peer,
     * or re-opening it after a key change, always re-derives.
     */
    fun open(address: String) {
        currentAddress = address
        _state.value = UiState()
        viewModelScope.launch(io) {
            val loaded = runCatching { loadSafetyNumber(address) }.getOrNull()
            _state.value = if (loaded == null) {
                UiState(loading = false, loadFailed = true)
            } else {
                UiState(
                    safetyNumber = loaded.first,
                    verified = loaded.second,
                    loading = false,
                )
            }
        }
    }

    /** Marks the current peer verified (local flag only, no confirm). */
    fun markVerified() {
        val address = currentAddress ?: return
        viewModelScope.launch(io) {
            runCatching { peerRepository.setVerified(address, true) }.onSuccess {
                _state.update { it.copy(verified = true) }
            }
        }
    }

    /** Clears the current peer's verified flag (local only, no confirm). */
    fun removeVerification() {
        val address = currentAddress ?: return
        viewModelScope.launch(io) {
            runCatching { peerRepository.setVerified(address, false) }.onSuccess {
                _state.update { it.copy(verified = false) }
            }
        }
    }

    /**
     * Derives the safety number, or null when it cannot be derived:
     * no peer row, no stored keys, keys that do not decode, or keys
     * of the wrong length. The caller turns null into the
     * load-failed state.
     */
    private suspend fun loadSafetyNumber(address: String): Pair<String, Boolean>? {
        val peer = peerRepository.getPeer(address)
        val theirCurve = peer?.theirCurveKey?.let { decodeKey(it) }
        val theirEd = peer?.theirEdKey?.let { decodeKey(it) }
        if (peer == null || theirCurve == null || theirEd == null) return null
        val ours = accountManager.identityKeys()
        val number = runCatching {
            E2eSafetyNumber.derive(ours.curve25519, theirCurve, ours.ed25519, theirEd)
        }.getOrNull() ?: return null
        return number to peer.verified
    }

    /** Decodes one stored base64 identity key, or null when malformed. */
    private fun decodeKey(base64: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(base64) }.getOrNull()
            ?.takeIf { it.size == E2E_KEY_LENGTH }
}
