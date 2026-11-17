package com.filestech.sms.ownnumber

import com.filestech.sms.domain.ownnumber.OwnNumberEnsurer
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Group-send own-number fix — the [OwnNumberEnsurer] implementation.
 *
 * Thin binding only: the logic lives in the pure seam
 * [ensureOwnNumberForGroupSend], driven directly by tests; this class
 * just plugs it into the real auto-fill
 * ([OwnNumberAutoFill.runOnce], the exact Detect-button path) and the
 * real settings store.
 */
@Singleton
class OwnNumberEnsurerImpl @Inject constructor(
    private val settings: AppSettingsSource,
    private val autoFill: OwnNumberAutoFill,
) : OwnNumberEnsurer {

    override suspend fun ensureForGroupSend(): Boolean =
        ensureOwnNumberForGroupSend(settings) { autoFill.runOnce() }
}

/**
 * The just-in-time check itself, over injectable seams so plain unit
 * tests can drive it: [settings] is the settings port, [runAutoFill]
 * is the auto-fill attempt (in production
 * `OwnNumberAutoFill.runOnce()`, which runs the exact Detect-button
 * path and persists the number when found).
 *
 * Returns `true` when a number is on file afterwards, `false` only
 * when the number is blank AND the detection found nothing — the
 * caller then fails the group send fast instead of dispatching into
 * a stuck "sending" row. The rules:
 * - already set: no detection run, the SIM is never read again;
 * - client mode: nothing to ensure — no SIM, no telephony send; the
 *   hub owns the number (client sends never reach this path once
 *   the sync-client send lands, and there is no client send today);
 * - unreadable settings: fail open — the send proceeds as it did
 *   before this guard existed. Blocking on a guess would strand a
 *   message whose number may well be set;
 * - a throwing detector is a failed detection: the re-read still
 *   finds the number blank, so the send fails fast. Cancellation is
 *   never swallowed — it rethrows like anywhere else.
 *
 * The post-fill check re-reads from [AppSettingsSource.flow], NOT
 * from `hydratedOrNull()`: the latter serves a StateFlow fed by an
 * async collector that may not have seen the just-persisted fill
 * yet, while a fresh collection of the DataStore flow reflects the
 * committed write.
 *
 * The number itself is never logged.
 */
internal suspend fun ensureOwnNumberForGroupSend(
    settings: AppSettingsSource,
    runAutoFill: suspend () -> Unit,
): Boolean {
    val snapshot = attempt { settings.hydratedOrNull() }
    if (snapshot == null || snapshot.mode != DeviceMode.HUB) return true
    if (!snapshot.sending.userMsisdn.isNullOrBlank()) return true
    // Blank: run the detection, then re-read — the number is on file
    // iff the second read finds it (detected just now, or typed by
    // the user while the detection was in flight).
    attempt { runAutoFill() }
    val after = attempt { settings.flow.first() }
    return !after?.sending?.userMsisdn.isNullOrBlank()
}

/**
 * Runs [block], returning `null` on any failure — except a
 * [CancellationException], which rethrows: an annulment is not a
 * failed detection. Same rule as
 * [com.filestech.sms.core.result.runCatchingCancellable], whose
 * block is not suspendable, so this suspendable twin lives here.
 */
private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Throwable) {
    null
}
