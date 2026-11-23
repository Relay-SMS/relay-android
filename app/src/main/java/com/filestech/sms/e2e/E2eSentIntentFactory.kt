package com.filestech.sms.e2e

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 43 — builds the sent [PendingIntent] for a handshake data
 * SMS, so the radio's accept/reject verdict lands in
 * [E2eDataSmsSentReceiver] instead of vanishing into a
 * fire-and-forget send.
 *
 * One intent per send: the request code is a per-instance counter,
 * so two sends in flight (a probe and a bundle reply) never share
 * an intent and never overwrite each other's kind extra.
 * [PendingIntent.FLAG_IMMUTABLE] (Android 12+): the kind extra is
 * fixed at creation and the radio must not mutate it.
 *
 * Never throws: a failure to build the intent returns null and the
 * sender falls back to fire-and-forget — a diagnostic that cannot
 * break the send it observes.
 */
@Singleton
class E2eSentIntentFactory @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val requestCodes = AtomicInteger(0)

    fun sentIntent(kind: DataSmsKind): PendingIntent? {
        return runCatching {
            PendingIntent.getBroadcast(
                context,
                requestCodes.incrementAndGet(),
                Intent(context, E2eDataSmsSentReceiver::class.java).apply {
                    action = E2eDataSmsSentReceiver.ACTION_DATA_SMS_SENT
                    putExtra(E2eDataSmsSentReceiver.EXTRA_KIND, kind.name)
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        }.getOrNull()
    }
}
