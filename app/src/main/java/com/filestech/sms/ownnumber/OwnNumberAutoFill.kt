package com.filestech.sms.ownnumber

import com.filestech.sms.data.sms.SimSelfNumber
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group-send own-number fix (b) — auto-fill "My phone number"
 * (`send.userMsisdn`) from the SIM when the setting is blank.
 *
 * Plainly: auto-detect DOES work on GrapheneOS — verified by the
 * user's own button press, which filled the number. The field was
 * blank because auto-detect had never been RUN (it only ran on tap),
 * not because the SIM read returns nothing there. That is exactly
 * what this auto-run closes: the number is now filled at startup
 * without anyone having to find the button.
 *
 * Runs once per cold start from [com.filestech.sms.MainApplication],
 * after the settings are hydrated. The send path never depends on
 * this number (the PDU carries no From header), so the fill is a
 * convenience for group filing and for the sender address other apps
 * display — never a gate on sending.
 *
 * Rules, all enforced by [autoFillOwnNumber] and pinned by tests:
 * - hub mode only: a linked device has no SIM and never sends via
 *   telephony, so in client mode this is a no-op;
 * - only when the stored value is blank: a number the user typed is
 *   never overwritten, and once the number is set the SIM is never
 *   read again (later launches return before any detection);
 * - never clobbers input: the blank check is repeated inside the
 *   atomic settings update, so a number typed (or filled) while the
 *   SIM read was in flight wins;
 * - fail-soft throughout: no READ_PHONE_NUMBERS permission, no SIM
 *   number, an unreadable settings store, or a throwing detector all
 *   end in a silent skip — startup never blocks on this, and the
 *   next launch simply tries again.
 *
 * The number itself is never logged.
 */
@Singleton
class OwnNumberAutoFill @Inject constructor(
    private val settings: AppSettingsSource,
    private val simSelfNumber: SimSelfNumber,
) {

    /** One auto-fill attempt; see the class KDoc for the rules. */
    suspend fun runOnce() {
        autoFillOwnNumber(settings) { simSelfNumber.ownNumber(null) }
    }
}

/**
 * The auto-fill itself, over injectable seams so plain unit tests can
 * drive it: [settings] is the settings port, [detect] is the SIM
 * read (in production `SimSelfNumber.ownNumber(null)`, which checks
 * the permission and never throws — the `runCatching` here is belt
 * and braces for a detector that does).
 */
internal suspend fun autoFillOwnNumber(
    settings: AppSettingsSource,
    detect: () -> String?,
) {
    val snapshot = runCatching { settings.hydratedOrNull() }.getOrNull()
    // Client mode — no SIM, no telephony send: nothing to fill.
    // Already set (typed by the user or filled on an earlier launch):
    // never read the SIM again, never overwrite. An unreadable
    // settings store also skips silently; the next launch retries.
    if (snapshot == null ||
        snapshot.mode != DeviceMode.HUB ||
        !snapshot.sending.userMsisdn.isNullOrBlank()
    ) {
        return
    }
    val detected = runCatching { detect() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return
    // Re-checked inside the atomic update: a number the user typed
    // while the SIM read was in flight must survive.
    var filled = false
    runCatching {
        settings.update { current ->
            if (current.sending.userMsisdn.isNullOrBlank()) {
                filled = true
                current.copy(sending = current.sending.copy(userMsisdn = detected))
            } else {
                current
            }
        }
    }.onFailure {
        // The transform may already have run: a failed write is not a fill.
        filled = false
        Timber.w(it, "OwnNumberAutoFill: persisting the detected number failed")
    }
    if (filled) Timber.i("OwnNumberAutoFill: filled the own number from the SIM")
}
