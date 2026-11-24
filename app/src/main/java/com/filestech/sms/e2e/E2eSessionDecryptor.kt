package com.filestech.sms.e2e

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decrypts reassembled ciphertext payloads (design §3.5), shared by the
 * live receive path ([E2eReceiveManager]) and the kept-ciphertext retry
 * ([E2eHandshakeHandler]): both need the same session dance, and it
 * belongs in exactly one place.
 *
 * - PRE_KEY → `createInboundSession` (builds the session from the
 *   pre-key message and decrypts in one step). The new session becomes
 *   current (the old one, if any, drops to a fallback) and the
 *   one-time-key pool is topped back up — the inbound session consumed
 *   one of our keys. If the inbound creation fails, the pre-key may be
 *   a RE-SENT first message whose session already exists, so the
 *   existing sessions are tried next (a session decrypts pre-key
 *   messages addressed to it).
 * - NORMAL → decrypt with the sessions newest first (current, then
 *   fallbacks). The winning session is re-pickled: the ratchet advanced.
 *
 * Every failure mode collapses to [Outcome.Failed] — the caller decides
 * what a failure means (placeholder + re-probe on the live path, "stay
 * a placeholder" on retry). No exception escapes: a corrupt payload or
 * a missing session is an expected outcome here, not a crash.
 *
 * The crypto, session store, and account manager are taken lazily (see
 * [E2eProbeManager]): resolving them provisions [E2eCrypto] (the
 * first native call loads the crypto library), and this decryptor is
 * reachable from broadcast receivers.
 */
@Singleton
class E2eSessionDecryptor @Inject constructor(
    private val crypto: dagger.Lazy<E2eCrypto>,
    private val sessions: dagger.Lazy<E2eSessionStore>,
    private val accountManager: dagger.Lazy<E2eAccountManager>,
) {

    /** The outcome of one decrypt attempt. */
    sealed interface Outcome {
        /** Decrypted. [newSession] is true when a pre-key message established a fresh session. */
        data class Decrypted(val plaintext: ByteArray, val newSession: Boolean) : Outcome

        /** No session could decrypt this payload. */
        data object Failed : Outcome
    }

    /** Decrypts one reassembled payload for a peer. Never throws. */
    suspend fun decrypt(address: String, payload: E2eWireFormat.DecodedPayload): Outcome {
        // The Account must be loaded before any session work (and a
        // first E2E use creates it here — receiving ciphertext IS first
        // use). Cheap after the first call.
        accountManager.get().ensureAccount()
        return when (payload.message.type) {
            OlmMessageType.PRE_KEY -> decryptPreKey(address, payload.message)
            OlmMessageType.NORMAL -> decryptWithSessions(address, payload.message)
            // Round 49 — a GROUP envelope is a sender-key AEAD blob,
            // not an Olm message: the receive path routes it to the
            // group decryptor before any session work, so reaching
            // here means a crossed payload — undecryptable, never a
            // session attempt.
            OlmMessageType.GROUP -> Outcome.Failed
        }
    }

    private suspend fun decryptPreKey(address: String, message: EncryptedMessage): Outcome {
        val inbound = runCatching { crypto.get().createInboundSession(message.bytes) }.getOrNull()
        if (inbound != null) {
            sessions.get().saveNewSession(address, inbound.sessionId)
            // The inbound session consumed one of our one-time keys:
            // top the pool back up (and re-save the Account pickle).
            accountManager.get().generateOneTimeKeys()
            return Outcome.Decrypted(inbound.plaintext, newSession = true)
        }
        // Not a fresh pre-key — most likely a re-sent first message
        // whose session already exists. Try the stored sessions.
        return decryptWithSessions(address, message)
    }

    private suspend fun decryptWithSessions(address: String, message: EncryptedMessage): Outcome {
        val store = sessions.get()
        for (sessionId in store.sessionIdsNewestFirst(address)) {
            // A corrupt pickle loads as "not loaded" — never thrown:
            // a corrupt fallback must not break the thread.
            if (store.loadSession(address, sessionId)) {
                val plaintext =
                    runCatching { crypto.get().decrypt(sessionId, message) }.getOrNull()
                if (plaintext != null) {
                    // The ratchet advanced: re-pickle before anything else
                    // uses this session, so a crash cannot replay state.
                    store.refreshSession(address, sessionId)
                    return Outcome.Decrypted(plaintext, newSession = false)
                }
            }
        }
        Timber.i("E2E: decrypt failed (no session could decrypt the payload)")
        return Outcome.Failed
    }
}
