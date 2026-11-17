package com.filestech.sms.sync.client

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Client mode — the narrow surface the sync engine needs from the
 * connection: the connection state, the inbound frame stream, and a
 * send. [ClientConnectionManager] implements it; engine tests use a
 * fake, so no WebSocket is ever needed to test the sync logic.
 */
interface ClientSyncChannel {
    /** The connection state (`Connected` carries the hub id + name). */
    val state: StateFlow<ClientConnectionState>

    /** Inbound frames from the live session (post-auth only). */
    val frames: SharedFlow<ServerFrame>

    /**
     * Sends one JSON message on the live session. False when no
     * session is live — the caller retries once reconnected.
     */
    suspend fun send(text: String): Boolean
}
