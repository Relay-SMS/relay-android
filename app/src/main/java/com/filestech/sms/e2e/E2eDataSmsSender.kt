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
 * The send is fire-and-forget: no sent/delivery intents (the handshake
 * is idempotent — a lost probe is re-probed within the budget, a lost
 * bundle is re-requested by the next probe). Returns `false` when the
 * radio refuses the send (no SIM, airplane mode) so the caller does not
 * consume probe budget or the bundle-reply rate limit on a send that
 * never left the phone.
 */
interface E2eDataSmsSender {

    /**
     * Sends [data] as one data-SMS segment to [address] on [port],
     * from the subscription [subId] (`null` = the system default).
     * Returns `true` when the radio accepted the send.
     */
    fun sendDataMessage(address: String, subId: Int?, port: Int, data: ByteArray): Boolean
}

/** The real sender over `SmsManager.sendDataMessage`. */
@Singleton
class E2eDataSmsSenderImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val wireFormatter: PhoneNumberWireFormatter,
) : E2eDataSmsSender {

    override fun sendDataMessage(address: String, subId: Int?, port: Int, data: ByteArray): Boolean {
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
                // No sent/delivery intents: a probe is fire-and-forget,
                // and a lost probe is retried on the next thread open.
                null,
                null,
            )
            true
        } catch (t: Exception) {
            // No address in the log line (design §8.3): the port + the
            // error class are enough to diagnose a dead radio.
            Timber.w(t, "E2E: data SMS send failed (port=%d)", port)
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
