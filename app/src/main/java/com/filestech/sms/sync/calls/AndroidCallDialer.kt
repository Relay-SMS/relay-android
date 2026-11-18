package com.filestech.sms.sync.calls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject

/**
 * The real [CallDialer]: places a cellular call via `ACTION_CALL`
 * + CALL_PHONE — the same shape as
 * `EmergencyCallHelper.executeCall` (permission check, intent
 * with `FLAG_ACTIVITY_NEW_TASK`, catch-all around `startActivity`),
 * without its emergency-number whitelist: the number comes from
 * an authenticated linked desktop over the TLS sync link, not
 * from an intent extra, and the desktop only ever dials numbers
 * the user picked there.
 *
 * Two deliberate differences from the emergency helper:
 *
 * - The number goes through `Uri.fromParts("tel", …)` instead
 *   of `Uri.parse("tel:$number")`: the emergency numbers are
 *   digits-only, but a desktop-sent number can carry spaces or
 *   a `+`, which `parse` would leave unencoded.
 * - No number ever reaches the log (the sync layer logs counts
 *   only — names and numbers stay out of logcat).
 *
 * The call itself is the phone's own cellular call: it rings
 * through the earpiece/speaker exactly as a call dialed on the
 * phone would, and the desktop's audio path (Bluetooth HFP) is
 * separate work on the desktop side. The observer reports the
 * resulting off-hook transition back as `call_state` `active`,
 * so the desktop sees its own dial land.
 */
class AndroidCallDialer @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallDialer {

    override suspend fun dial(number: String): DialOutcome {
        val cleaned = number.trim()
        if (cleaned.isBlank()) return DialOutcome.NoNumber
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.w("AndroidCallDialer: CALL_PHONE not granted — dial refused")
            return DialOutcome.PermissionDenied
        }
        val intent = Intent(Intent.ACTION_CALL).apply {
            data = Uri.fromParts("tel", cleaned, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        return try {
            context.startActivity(intent)
            DialOutcome.Success
        } catch (se: SecurityException) {
            // The permission was revoked between the check and the
            // start, or the OS refused for another reason.
            Timber.w(se, "AndroidCallDialer: dial refused by the OS")
            DialOutcome.Failed
        } catch (t: Throwable) {
            Timber.w(t, "AndroidCallDialer: dial failed (no dialer?)")
            DialOutcome.Failed
        }
    }
}
