package com.filestech.sms.system.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.filestech.sms.core.ext.stripInvisibleChars
import com.filestech.sms.di.ApplicationScope
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.e2e.E2eHandshake
import com.filestech.sms.e2e.E2eHandshakeHandler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject

/**
 * Receives the handshake data SMS (design §2.3): probes and bundle
 * replies on port [E2eHandshake.E2E_DATA_SMS_PORT], declared in the
 * manifest with the `sms://localhost:<port>` data filter.
 *
 * Data SMS never reach the system inbox and never become messages:
 * this receiver only hands the raw bytes to [E2eHandshakeHandler],
 * which validates the magic + version and drops anything else
 * silently. There is no salvage path (unlike [SmsDeliverReceiver]): a
 * dropped handshake packet is not a lost user message — the next probe
 * restarts the exchange.
 *
 * Like the other receivers, the handler is resolved lazily on the IO
 * dispatcher: it reaches Room through the peer store, and field
 * injection happens on the main thread before `onReceive` runs.
 *
 * Client mode — a linked device drops the packet silently (see
 * [onReceive]): it cannot do E2E messaging (no default-SMS role, no
 * SMS_DELIVER), so answering a handshake addressed to its own number
 * would provision an identity it must never hold.
 */
@AndroidEntryPoint
class E2eDataSmsReceiver : BroadcastReceiver() {

    @Inject lateinit var handshakeHandlerLazy: dagger.Lazy<E2eHandshakeHandler>

    @Inject lateinit var settingsLazy: dagger.Lazy<AppSettingsSource>

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var ioDispatcher: CoroutineDispatcher

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return
        val subId = intent.extractIncomingSubId()
        val pending = goAsync()
        scope.launch {
            try {
                // Client mode — a linked device must never hold an E2E
                // identity: it cannot do E2E messaging (no
                // default-SMS role, no SMS_DELIVER), so a handshake
                // packet addressed to its own number is dropped
                // silently instead of provisioning an identity and
                // replying with bundles. Same gate shape as
                // `ScheduledSendAttempt` (`hydratedOrNull`, never
                // `state.value` — a cold process has not hydrated yet
                // and the default would read as hub mode).
                if ((settingsLazy.get().hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT) {
                    return@launch
                }
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                    ?: emptyArray()
                val first = messages.firstOrNull() ?: return@launch
                val address = first.displayOriginatingAddress
                    ?.stripInvisibleChars()
                    ?: return@launch
                // `userData` is the raw binary payload of a data SMS —
                // the probe or bundle bytes, never text.
                val data = first.userData ?: return@launch
                withContext(ioDispatcher) {
                    handshakeHandlerLazy.get().handleDataSms(address, data, subId)
                }
            } catch (ce: kotlinx.coroutines.CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Timber.w(t, "E2E: data SMS handling failed")
            } finally {
                pending.finish()
            }
        }
    }
}
