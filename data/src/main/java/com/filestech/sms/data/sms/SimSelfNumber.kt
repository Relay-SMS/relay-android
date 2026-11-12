package com.filestech.sms.data.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 14 — the device's own number, read from the SIM at receive time, so a group MMS
 * can be filed in its group without « Mon numéro » ever being typed in Settings.
 *
 * Why this is the missing piece: the group path that WORKS (`GroupMmsMembers.of`) needs
 * to know which participant is us, so it can remove us from the PDU's recipient set —
 * the thread key for a group is its members *without* self (that is also the set the
 * outgoing writeback uses, so sent and received messages land on the same thread).
 * Until now that number only came from the « Mon numéro » setting. This supplies it
 * from the SIM instead, at the moment the MMS is processed: the setting still wins
 * when set, the SIM is the fallback when it is blank.
 *
 * Fail-soft by design: no permission, no subscription, no number, or any exception all
 * return `null`, and the caller falls back to the raw participant list with `inclutSoi`.
 * Never throws.
 */
@Singleton
class SimSelfNumber @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The SIM's number for [subId] (or the default SMS subscription when `null`), or
     * `null` when no source yields one.
     */
    fun ownNumber(subId: Int?): String? = readSimMsisdn(context, subId)
}

/**
 * The shared SIM-number read: the Settings « Detect » button and the MMS receive path
 * used to each carry their own copy of this sequence, and two copies of one rule drift
 * — this is the single definition both call sites use.
 *
 * Three sources, first non-blank wins (the order the Settings button established):
 *  1. `SubscriptionManager.getPhoneNumber(subId)` (API 33+) — the modern official
 *     method; aggregates SIM / carrier / IMS.
 *  2. `SubscriptionInfo.number` (API 22+, deprecated API 30+) — historical.
 *  3. `TelephonyManager.createForSubscriptionId(subId).line1Number` (API 22+) —
 *     the fallback some ROMs/carriers answer when the other two return empty.
 *
 * Needs `READ_PHONE_NUMBERS` (already in the manifest, requested at startup). Returns
 * `null` without it — never throws.
 */
fun readSimMsisdn(context: Context, subId: Int?): String? {
    val granted = context.checkSelfPermission(Manifest.permission.READ_PHONE_NUMBERS) ==
        PackageManager.PERMISSION_GRANTED
    if (!granted) return null
    val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        as? SubscriptionManager ?: return null
    val id = subId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        ?: SubscriptionManager.getDefaultSmsSubscriptionId()
            .takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
        ?: return null
    val number = sequence {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching { sm.getPhoneNumber(id) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { yield(it) }
        }
        runCatching {
            val info = sm.activeSubscriptionInfoList?.firstOrNull { it.subscriptionId == id }
            @Suppress("DEPRECATION")
            info?.number
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { yield(it) }
        runCatching {
            val tm = context.getSystemService(TelephonyManager::class.java)
                ?.createForSubscriptionId(id)
            @Suppress("DEPRECATION")
            tm?.line1Number
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { yield(it) }
    }.firstOrNull()
    if (number.isNullOrBlank()) {
        Timber.w("readSimMsisdn: no source returned a number (subId=%d)", id)
    }
    return number?.takeIf { it.isNotBlank() }
}
