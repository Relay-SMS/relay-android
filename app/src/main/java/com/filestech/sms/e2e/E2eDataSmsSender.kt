package com.filestech.sms.e2e

import android.content.Context
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.filestech.sms.data.sms.PhoneNumberWireFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Sends the handshake data SMS (probe / bundle) on port
 * [E2eHandshake.E2E_DATA_SMS_PORT] (design §2.1).
 *
 * A data SMS is invisible and never stored in the system inbox, so a
 * probe to a phone without Relay is silently dropped by the OS — the
 * recipient sees nothing and pays nothing. No new permission:
 * SEND_SMS is already held.
 *
 * The send returns as soon as the radio accepts the call (no
 * delivery intent — the handshake is idempotent: a lost probe is
 * re-probed within the budget, a lost bundle is re-requested by
 * the next probe). Round 43 — a sent intent observes the radio's
 * verdict ([E2eDataSmsSentReceiver]): a send the radio silently
 * rejects after accepting the call now leaves a trace, instead of
 * looking exactly like a peer without Relay. Returns `false` when
 * the radio refuses the send (no SIM, airplane mode) so the caller
 * does not consume probe budget or the bundle-reply rate limit on
 * a send that never left the phone.
 */
interface E2eDataSmsSender {

    /**
     * Sends [data] as one data-SMS segment to [address] on [port],
     * from the subscription [subId] (`null` = the system default).
     * [kind] tags a failed send in the error log (probe vs bundle
     * reply — the two share this one send path, and a failed
     * bundle used to read as a probe failure). Returns `true`
     * when the radio accepted the send.
     */
    fun sendDataMessage(
        address: String,
        subId: Int?,
        port: Int,
        data: ByteArray,
        kind: DataSmsKind = DataSmsKind.PROBE,
    ): Boolean
}

/**
 * Which handshake message a data SMS carries. The failure tag
 * in the error log differs by kind: a failed bundle reply used
 * to be recorded under `e2e-probe-send` (one shared send path,
 * one unconditional tag), so it read as a probe failure — now
 * each kind carries its own tag and the two failures are
 * separable.
 */
enum class DataSmsKind(val failureTag: String) {
    /** A probe (the outbound key-exchange opener). */
    PROBE("e2e-probe-send"),

    /** A bundle reply (the answer to a probe). */
    BUNDLE("e2e-bundle-send"),
}

/** The real sender over `SmsManager.sendDataMessage`. */
@Singleton
class E2eDataSmsSenderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wireFormatter: PhoneNumberWireFormatter,
    private val recorder: E2eFailureRecorder,
    private val sentIntentFactory: E2eSentIntentFactory,
) : E2eDataSmsSender {

    override fun sendDataMessage(
        address: String,
        subId: Int?,
        port: Int,
        data: ByteArray,
        kind: DataSmsKind,
    ): Boolean {
        return try {
            val manager = subscriptionAwareManager(subId)
            // Same E.164 normalization as the text path: a foreign SIM
            // texting a national number must still route.
            val wireDestination = wireFormatter.toWireFormat(address, subId)
            manager.sendDataMessage(
                wireDestination,
                // scAddress: null — let the radio pick the service centre.
                null,
                // The port is an unsigned 16-bit value carried in a
                // `short`: 47832 wraps to a negative short here and the
                // telephony stack reads it back unsigned (`& 0xFFFF`)
                // when building the PDU — the standard pattern for data
                // SMS ports above 32767.
                port.toShort(),
                data,
                // Round 43 — a sent intent, so a radio that silently
                // rejects the send leaves a trace (the verdict lands
                // in E2eDataSmsSentReceiver; RESULT_OK records
                // nothing). The Boolean return is unchanged: true =
                // no synchronous throw. A null factory result falls
                // back to fire-and-forget.
                sentIntentFactory.sentIntent(kind),
                null,
            )
            true
        } catch (t: Exception) {
            // No address in the log line (design §8.3): the port + the
            // error class are enough to diagnose a dead radio.
            Timber.w(t, "E2E: data SMS send failed (port=%d)", port)
            // Round 40 — the same failure in the local error log, so a
            // phone that cannot send handshake SMS leaves a trace: a
            // failed probe otherwise looks exactly like a peer without
            // Relay. Covers probe and bundle-reply sends alike — both
            // go through here — tagged by [kind], so a failed bundle
            // reads as a bundle failure, not a probe failure.
            // Sanitized: the raw exception's message may carry the
            // destination address on some builds, so only the
            // exception's class travels — no message, no cause chain
            // (the formatter would print both).
            recorder.record(
                kind.failureTag,
                E2eCryptoException(
                    "Data SMS send failed (port=$port): ${t.javaClass.simpleName}",
                ),
            )
            false
        }
    }

    /**
     * Mirrors [com.filestech.sms.data.sms.SmsSenderImpl]'s subscription
     * resolution (API 31+ system-service factory, legacy helpers below).
     * The probe must leave on the same subscription the conversation
     * uses (design §11.1): a probe from the wrong SIM would present the
     * wrong sender number to the peer.
     */
    private fun subscriptionAwareManager(subId: Int?): SmsManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val sm = context.getSystemService(SmsManager::class.java)
            return if (subId == null) sm else sm.createForSubscriptionId(subId)
        }
        return if (subId == null) defaultManager() else managerFor(subId)
    }

    @Suppress("DEPRECATION")
    private fun defaultManager(): SmsManager = SmsManager.getDefault()

    @Suppress("DEPRECATION")
    private fun managerFor(subId: Int): SmsManager {
        val effectiveSubId = if (subId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            SubscriptionManager.getDefaultSmsSubscriptionId()
        } else {
            subId
        }
        return SmsManager.getSmsManagerForSubscriptionId(effectiveSubId)
    }
}
