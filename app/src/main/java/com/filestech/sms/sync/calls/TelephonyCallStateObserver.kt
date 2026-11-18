package com.filestech.sms.sync.calls

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import javax.inject.Inject

/**
 * The real [CallStateObserver]: a [PhoneStateListener] on the
 * phone's [TelephonyManager], translating callbacks through the
 * pure [CallStateMapper] into [CallEvent]s.
 *
 * ## Why PhoneStateListener, not TelephonyCallback
 *
 * The modern API, `TelephonyCallback` (API 31+), has NO number
 * callback — its `CallStateListener` reports only the state
 * (ringing / off-hook / idle). The incoming number is the whole
 * point of the `incoming_call` frame, and the only API that
 * delivers it is `PhoneStateListener.onCallStateChanged(state,
 * incomingNumber)`. A version-gated hybrid (TelephonyCallback
 * for the state on 31+, PhoneStateListener for the number) would
 * register two listeners and reconcile two event streams for
 * zero benefit: `PhoneStateListener` is deprecated in API 31
 * but still functional on every API level, including the
 * current one. So one listener throughout, with the deprecation
 * suppressed here and justified in this comment — the same
 * pattern as the existing `@Suppress("DEPRECATION")` uses in
 * `MainActivity` and `IncomingSubId`.
 *
 * Lint: the `Deprecated` check is warning-severity and the
 * build does not treat warnings as errors (`warningsAsErrors =
 * false` in `app/build.gradle.kts`), so the suppressed use
 * cannot fail CI's blocking `lintDebug`.
 *
 * ## Permissions
 *
 * Listening needs READ_PHONE_STATE (requested at cold start in
 * `MainActivity`, as a critical SMS permission — the observer
 * reuses that granted state; if it was denied, [start] is a
 * no-op). The NUMBER needs READ_CALL_LOG on top: since Android 9
 * the platform passes an empty number to listeners that lack it
 * (Android 9 behavior changes, "Restrict access to phone
 * numbers"). Without it the observer still reports every
 * transition — just with an empty number. The observer never
 * asks for either permission itself: it runs in the background
 * service, which cannot show a dialog; the Linked devices
 * screen owns the prompts.
 *
 * ## Lifecycle
 *
 * [start] is idempotent; [stop] unregisters and resets the
 * mapper, so a restart never replays a stale call. Both are
 * fail-soft (`runCatching` around the platform calls): a
 * listener failure must never break the server start, and a
 * phone without telephony (a Wi-Fi-only tablet in hub mode)
 * simply produces no events.
 */
@Suppress("DEPRECATION")
@SuppressLint("Deprecated")
class TelephonyCallStateObserver @Inject constructor(
    @ApplicationContext private val context: Context,
) : CallStateObserver {

    private val telephonyManager: TelephonyManager? = runCatching {
        context.getSystemService<TelephonyManager>()
    }.getOrNull()

    private val mapper = CallStateMapper()

    private val _events = MutableSharedFlow<CallEvent>(extraBufferCapacity = 16)

    override val events: SharedFlow<CallEvent> = _events.asSharedFlow()

    private var started = false

    /**
     * The platform callback: translates the raw state int to the
     * mapper's enum (an unknown int is ignored — a future
     * platform state must not invent an event), feeds the mapper,
     * and emits the transition, if any. `tryEmit`, never a
     * suspending send: this runs on the telephony callback
     * thread, which must never block.
     */
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(state: Int, incomingNumber: String?) {
            val telephonyState = when (state) {
                TelephonyManager.CALL_STATE_IDLE -> TelephonyState.IDLE
                TelephonyManager.CALL_STATE_RINGING -> TelephonyState.RINGING
                TelephonyManager.CALL_STATE_OFFHOOK -> TelephonyState.OFFHOOK
                else -> return
            }
            val event = mapper.onState(
                telephonyState,
                incomingNumber.orEmpty(),
                System.currentTimeMillis(),
            ) ?: return
            // Counts only: numbers never reach the log.
            Timber.d("TelephonyCallStateObserver: %s", event::class.simpleName)
            _events.tryEmit(event)
        }
    }

    override fun start() {
        if (started) return
        val manager = telephonyManager
        if (manager == null) {
            Timber.d("TelephonyCallStateObserver: no telephony on this device — no call events")
            return
        }
        // Checked at start time, not at construction: the user can
        // grant the permission in Settings while the app runs, and
        // the next server start must pick it up.
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Timber.i(
                "TelephonyCallStateObserver: READ_PHONE_STATE not granted — call events off",
            )
            return
        }
        runCatching {
            manager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        }.onSuccess {
            started = true
        }.onFailure { t ->
            Timber.w(t, "TelephonyCallStateObserver: listen failed — call events off")
        }
    }

    override fun stop() {
        if (!started) return
        started = false
        runCatching {
            telephonyManager?.listen(listener, PhoneStateListener.LISTEN_NONE)
        }.onFailure { t ->
            Timber.w(t, "TelephonyCallStateObserver: unlisten failed")
        }
        mapper.reset()
    }
}
