package com.filestech.sms.domain.clientmode

/**
 * Client mode C4 — the domain-facing avatar gateway: a linked device
 * fetches a contact photo lazily, over the wire (`avatar_request` →
 * `attachment_chunk` under `avatar:<number>`), when the UI renders a
 * contact whose photo is not cached yet.
 *
 * The real implementation is [com.filestech.sms.sync.client.ClientContactSync]
 * (app module — the domain layer cannot import app classes, and the gateway
 * is the seam). Bound in `ClientSyncModule`; Hilt always provides the real
 * one in production, so [NoOpClientAvatarGateway] only exists as a default
 * constructor value so existing tests keep compiling untouched.
 */
interface ClientAvatarGateway {

    /**
     * Asks the hub for one contact photo, by normalized number. A
     * no-op when the photo is already cached, the number has no photo,
     * or a download is already in flight / queued / refused (a refused
     * avatar is re-asked on reconnect or when the contact changes).
     * Never throws — a failed request must not break a contact lookup.
     */
    suspend fun requestAvatar(number: String)
}

/** The no-op [ClientAvatarGateway] used as a default constructor value. */
object NoOpClientAvatarGateway : ClientAvatarGateway {
    override suspend fun requestAvatar(number: String) {
        // No-op: hub mode has no hub to ask.
    }
}
