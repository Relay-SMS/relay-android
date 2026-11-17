package com.filestech.sms.sync.client

import android.content.Context
import android.telephony.TelephonyManager

/**
 * Client mode — whether this device has no SIM: a tablet or a
 * SIM-less phone, the devices client mode is for. Drives the
 * no-SIM nudge card ("This device has no SIM. Link it to your
 * phone…").
 *
 * No telephony hardware at all counts as SIM-less; an absent or
 * unknown SIM state counts as SIM-less; anything else (a SIM
 * present, locked, or erroring) does not. An exception while asking
 * the system counts as "has a SIM" — the nudge must never appear on
 * a phone that can message on its own.
 */
fun isSimLess(context: Context): Boolean {
    return try {
        val telephony =
            context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                ?: return true
        when (telephony.simState) {
            TelephonyManager.SIM_STATE_ABSENT,
            TelephonyManager.SIM_STATE_UNKNOWN,
            -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}
