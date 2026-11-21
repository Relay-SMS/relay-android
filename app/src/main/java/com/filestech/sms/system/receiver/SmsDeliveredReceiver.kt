package com.filestech.sms.system.receiver

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.sms.data.sms.SmsSenderImpl
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.repository.OutgoingMessageMirror
import com.filestech.sms.e2e.E2eFragmentDeliveryTracker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SmsDeliveredReceiver : BroadcastReceiver() {

    // v1.24.0 SEC-CRIT — `Lazy` : ce collaborateur atteint un DAO, donc `AppDatabase`, donc la
    // réparation zéro-clé. L'injection de champ Hilt précède le corps de `onReceive`, sur le main
    // thread : en eager, la reconstruction de la base y tournait sous un timeout ANR de 10 s.
    @Inject lateinit var mirrorLazy: dagger.Lazy<OutgoingMessageMirror>
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    // E2E — in-memory fragment counting (no DAO: cheap to resolve, but
    // kept lazy for symmetry with the mirror).
    @Inject lateinit var fragmentTrackerLazy: dagger.Lazy<E2eFragmentDeliveryTracker>

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SmsSenderImpl.ACTION_SMS_DELIVERED) return
        val localId = intent.getLongExtra(SmsSenderImpl.EXTRA_LOCAL_ID, -1L)
        if (localId < 0) return
        // v1.28.3 (F23) — cf. `SmsSentReceiver` : un accuse tardif appartient a SA tentative.
        val attempt = intent.getIntExtra(SmsSenderImpl.EXTRA_ATTEMPT, 0)
        if (resultCode != Activity.RESULT_OK) return
        // E2E — a fragment's delivery report promotes the message to
        // DELIVERED only once ALL fragments of the set reported (design
        // §3.4). A malformed fragment report is dropped, not promoted.
        if (intent.getBooleanExtra(SmsSenderImpl.EXTRA_E2E_FRAGMENT, false)) {
            val index = intent.getIntExtra(SmsSenderImpl.EXTRA_PART_INDEX, -1)
            val total = intent.getIntExtra(SmsSenderImpl.EXTRA_PART_COUNT, -1)
            if (!fragmentTrackerLazy.get().recordDelivered(localId, attempt, index, total)) return
        }
        val pending = goAsync()
        scope.launch {
            try {
                val mirror = mirrorLazy.get()
                mirror.updateOutgoingStatus(localId, MessageStatus.DELIVERED, attempt = attempt)
            } finally {
                pending.finish()
            }
        }
    }
}
