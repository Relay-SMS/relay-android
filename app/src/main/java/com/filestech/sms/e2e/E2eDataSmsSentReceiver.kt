package com.filestech.sms.e2e

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Round 43 — receives the sent-intent verdict for a handshake data
 * SMS (probe / bundle reply): the radio's accept/reject for a send
 * [E2eDataSmsSenderImpl] issued with a sent intent from
 * [E2eSentIntentFactory].
 *
 * Internal only (`exported=false`); the PendingIntent targets this
 * class explicitly and the manifest declares the action. Thin glue
 * only: the decision (RESULT_OK → nothing, anything else → record
 * under the kind's failure tag) lives in [E2eDataSmsSentHandler],
 * which is testable on plain JVM — a Hilt entry point's real
 * `onReceive` cannot be called under MockK on plain JVM (the Hilt
 * transform weaves an `inject(context)` call into it), so the
 * receiver is never tested directly.
 *
 * The kind extra names the [DataSmsKind]; a missing or unknown
 * value falls back to PROBE (the sender's default kind) rather
 * than dropping the verdict.
 */
@AndroidEntryPoint
class E2eDataSmsSentReceiver : BroadcastReceiver() {

    @Inject
    lateinit var handler: E2eDataSmsSentHandler

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DATA_SMS_SENT) return
        val kind = runCatching {
            DataSmsKind.valueOf(intent.getStringExtra(EXTRA_KIND) ?: "")
        }.getOrDefault(DataSmsKind.PROBE)
        handler.handle(resultCode, kind)
    }

    companion object {
        const val ACTION_DATA_SMS_SENT = "com.filestech.sms.e2e.DATA_SMS_SENT"
        const val EXTRA_KIND = "e2e_data_sms_kind"
    }
}
