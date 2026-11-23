package com.filestech.sms.e2e

import com.filestech.sms.crashlog.CrashLogSettings
import com.filestech.sms.crashlog.CrashReportFormatter
import com.filestech.sms.crashlog.CrashReportStore
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records E2E failures in the local error log (Settings →
 * Advanced), so a silent encryption failure leaves a readable
 * trace instead of just a missing fingerprint.
 *
 * Six writers use it: the [E2eCrypto] provider (a native-load
 * failure that degrades to [UnavailableE2eCrypto]), the
 * Encryption settings ViewModel (an identity load that fails
 * after the library loaded — Keystore, Room, or a native call),
 * the data-SMS sender (a handshake SMS the radio refuses —
 * probe or bundle reply, tagged by kind; recorded sanitized,
 * the exception's class only, so no address can leak in — see
 * [E2eDataSmsSenderImpl]), the sent-intent handler (a handshake
 * SMS the radio accepted and then rejected — the async verdict,
 * tagged by kind, with the result-code name — see
 * [E2eDataSmsSentHandler]), the handshake handler (success
 * events — probe received, bundle sent, bundle rate-limited —
 * via [recordEvent], so the inbound path is visible in release
 * builds, where Timber is a no-op), and the probe manager (a
 * manual retry that did not send — gated or failed, with the
 * reason, under `e2e-manual-retry`; automatic probes never
 * record, they gate constantly).
 * All write through the same [CrashReportStore] the crash
 * handler uses, so the entries appear in the same list, with
 * the exception's simple name as the title (failures) or the
 * tag itself (events) and the full chain in the detail text.
 *
 * The error-log switch gates every write, the same way it gates
 * the crash handler ([CrashLogSettings.enabled] is read live on
 * each call, so turning the log off mid-session takes effect at
 * once). Nothing here logs keys, addresses, or PII: the
 * recorded text is the exception chain (our fixed messages,
 * loader messages, Keystore/Room errors, and code locations
 * from the stack trace) — the same content the crash reports
 * already carry — or a fixed event message.
 *
 * Dedupe: one record per distinct root cause per process. The
 * provider's failure and the ViewModel's failure are often the
 * SAME underlying cause — the fallback wraps the native-load
 * error in an `E2eCryptoException` — and the settings screen
 * can be opened many times, so without dedupe one outage would
 * fill the 10-report rotation. The key is the root cause's
 * class + message (see [E2eDisplayFormat.rootCause]). Events
 * are NEVER deduped: every occurrence is a diagnostic signal —
 * a probe arriving twice means two probes went out, and the
 * tester tapping Retry repeatedly needs to see EACH event with
 * its timestamp (round 44: the old per-process dedupe hid every
 * probe and bundle after the first, so the log looked dead
 * while the handshake was flowing). The 10-report rotation caps
 * the total, and events are infrequent — probes at most 3 per
 * 30 days plus manual retries, bundles at most one per sender
 * per day — so spam is not a risk.
 *
 * Never throws: a diagnostics failure must never break the
 * path it is diagnosing.
 */
@Singleton
class E2eFailureRecorder @Inject constructor(
    private val store: CrashReportStore,
    private val settings: CrashLogSettings,
) {

    /** Root causes already recorded this process (class name + message). */
    private val recordedRootCauses: MutableSet<String> =
        Collections.synchronizedSet(mutableSetOf())

    /**
     * Records [throwable] under [tag] ("e2e-native-load",
     * "e2e-fingerprint", "e2e-probe-send", "e2e-bundle-send") in
     * the local error log. Skips the write when the error log is
     * off, and when this root cause was already recorded this
     * process.
     */
    fun record(tag: String, throwable: Throwable) {
        runCatching {
            if (!settings.enabled) return@runCatching
            val root = E2eDisplayFormat.rootCause(throwable)
            val key = root.javaClass.name + "|" + (root.message ?: "")
            if (!recordedRootCauses.add(key)) return@runCatching
            val now = System.currentTimeMillis()
            val text = CrashReportFormatter.formatDiagnostic(tag, now, throwable)
            val crashType = throwable.javaClass.simpleName.ifBlank { "Unknown" }
            store.writeReport(text, now, crashType)
        }
    }

    /**
     * Records a SUCCESS event under [tag]
     * ("e2e-probe-received", "e2e-bundle-sent",
     * "e2e-bundle-rate-limited") in the local error log.
     * Success events have no Throwable, so the entry is built
     * from a synthetic marker carrying only the fixed [message]
     * — no keys, no addresses, no PII — through the same
     * [CrashReportFormatter.formatDiagnostic] path, with the tag
     * as the list title (the detail text carries the tag line,
     * the marker message, and the call path). Skips the write
     * when the error log is off. NEVER deduped: every occurrence
     * writes an entry with its timestamp (see the class KDoc) —
     * the tester tapping Retry repeatedly must see each probe
     * and bundle, not just the first.
     */
    fun recordEvent(tag: String, message: String) {
        runCatching {
            if (!settings.enabled) return@runCatching
            val now = System.currentTimeMillis()
            val text = CrashReportFormatter.formatDiagnostic(tag, now, E2eDiagnosticEvent(message))
            store.writeReport(text, now, tag)
        }
    }
}

/**
 * Marker throwable for success-event diagnostics: carries only
 * a fixed message — never keys, addresses, or PII. The
 * formatter prints its class + message + the call path, which
 * is the diagnostic payload for an event entry.
 */
class E2eDiagnosticEvent(message: String) : Exception(message)
