package com.filestech.sms.e2e

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Hilt entry point for the E2E pieces the thread ViewModel needs: the
 * peer state (composer placeholder, send icon, details-panel row), the
 * in-thread notices, and the probe manager (probe on thread open).
 *
 * Resolved from the injected application context, the same way
 * `ClientSyncEngineEntryPoint` / `ClientSendManagerEntryPoint` are
 * reached: `ThreadViewModel`'s constructor is at the Detekt
 * 20-parameter cap (baselined — adding a parameter would break the
 * baseline), so E2E dependencies cannot ride the constructor. Callers
 * resolve this lazily and treat a failure as "no E2E" (JVM unit tests
 * construct the ViewModel with a mocked context, where entry-point
 * resolution throws — the thread must work without E2E there).
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface E2eThreadEntryPoint {

    /** The peer state machine's storage (live peer rows, verified flags). */
    fun peerRepository(): E2ePeerRepository

    /** The in-thread encryption notices (live per-conversation list). */
    fun noticeStore(): E2eNoticeStore

    /** The probe trigger (thread open → key exchange, B3-owned). */
    fun probeManager(): E2eProbeManager
}
