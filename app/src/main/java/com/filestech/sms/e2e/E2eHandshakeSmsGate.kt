package com.filestech.sms.e2e

import com.filestech.sms.data.sms.TelephonyReader
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import timber.log.Timber

/**
 * The decision logic behind the handshake-SMS branch of
 * [com.filestech.sms.system.receiver.SmsDeliverReceiver] (round 45),
 * kept out of the receiver so it is testable on plain JVM — the
 * receiver is a Hilt entry point, and Hilt weaves an `inject(context)`
 * call into its real `onReceive`, so calling it under MockK throws
 * `MockKException` (see [E2eDataSmsGate], the same extraction for the
 * data-SMS receiver). The receiver itself stays thin glue: it hands
 * the branch's inputs to [handle] and honors its Boolean.
 *
 * One call handles one received SMS:
 * 1. A body that is not a handshake SMS returns `false` before any
 *    write or settings read (the caller falls through to the
 *    reaction / plain-text paths).
 * 2. Otherwise the SMS is written to the system inbox first, like
 *    every SMS — with the CARRIER timestamp (`ts`): the inbox row
 *    is a telephony record, and its date is the SMSC date, as for
 *    any other message.
 * 3. Client mode swallows the SMS (returns `true` without calling
 *    the handler): a linked device must never hold an E2E identity,
 *    and the default-SMS role can linger after a switch to client
 *    mode, so SMS_DELIVER still fires and this branch still runs.
 * 4. Otherwise the handler processes the packet — with `now` left
 *    to its default (processing time). The carrier timestamp is
 *    NEVER passed as `now`: `ts` is the SMSC clock, and the handler
 *    stamps the ACTIVATED notice (and the peer state, the session
 *    and the bundle-reply rate limit) with `now` — while every
 *    message date the notice is ordered against uses the phone
 *    clock. An SMSC clock ahead of the phone clock dated the
 *    notice AFTER a message sent later, so the sent message
 *    rendered above the notice it followed (round 46). The
 *    data-SMS path ([E2eDataSmsGate]) and the fragment path's
 *    `now` already use processing time; this branch does too.
 *
 * A failure inside the handler is logged, not rethrown (except
 * `CancellationException`), so one bad handshake SMS cannot kill
 * the receiver — the sender's retry re-sends the probe.
 */
class E2eHandshakeSmsGate(
    private val telephonyReader: TelephonyReader,
    private val settings: AppSettingsSource,
    private val handler: E2eHandshakeHandler,
) {
    suspend fun handle(
        address: String,
        body: String,
        ts: Long,
        subId: Int?,
    ): Boolean {
        if (!E2eHandshakeSms.isHandshakeSms(body)) return false
        telephonyReader.insertInboxSms(address, body, ts, subId)
        if ((settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT) return true
        try {
            // Round 46 — no `ts` here: `now` defaults to processing
            // time (see the class KDoc). The carrier timestamp dates
            // the inbox row above, never the notice.
            handler.handleSms(address, body, subId)
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (t: Throwable) {
            Timber.w(t, "E2E: handshake SMS handling failed")
        }
        return true
    }
}
