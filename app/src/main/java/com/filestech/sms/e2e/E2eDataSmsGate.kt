package com.filestech.sms.e2e

import android.provider.Telephony
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The decision logic behind
 * [com.filestech.sms.system.receiver.E2eDataSmsReceiver] (design §2.3),
 * kept out of the receiver so it is testable on plain JVM: the receiver
 * is a Hilt entry point, and Hilt weaves an `inject(context)` call into
 * its real `onReceive` — a mocked `Context` has no application
 * component, so calling the real `onReceive` under MockK throws
 * `MockKException` (CI run 44935640392: all three receiver tests failed
 * at the `callOriginal()` stub, while the same tests passed locally
 * where no Hilt transform runs). The receiver itself stays thin glue:
 * action guard, `goAsync`, message parsing, error logging.
 *
 * One call handles one received intent:
 * 1. A non-data-SMS action returns `false` before any settings read
 *    (and before parsing).
 * 2. Client mode drops the packet silently — a linked device must
 *    never hold an E2E identity (no default-SMS role, no SMS_DELIVER),
 *    so no parse and no handler call happen either.
 * 3. Otherwise the first parsed message is handed to the handler on
 *    the IO dispatcher (it reaches Room through the peer store).
 *
 * [first] is a supplier, not a value, so the parse runs only after
 * the mode check — the hub never parses a packet it is about to drop.
 * A null message, a null address or null user data drops silently too
 * (a malformed packet is not a lost user message — the next probe
 * restarts the exchange).
 */
class E2eDataSmsGate(
    private val settingsLazy: dagger.Lazy<AppSettingsSource>,
    private val handlerLazy: dagger.Lazy<E2eHandshakeHandler>,
    private val ioDispatcher: CoroutineDispatcher,
) {
    suspend fun handle(
        action: String?,
        subId: Int?,
        first: () -> ParsedDataSms?,
    ): Boolean {
        if (action != Telephony.Sms.Intents.DATA_SMS_RECEIVED_ACTION) return false
        // Same gate shape as `ScheduledSendAttempt` (`hydratedOrNull`,
        // never `state.value` — a cold process has not hydrated yet
        // and the default would read as hub mode).
        if ((settingsLazy.get().hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT) {
            return false
        }
        val message = first()
        val address = message?.address
        val data = message?.data
        if (address == null || data == null) return false
        withContext(ioDispatcher) {
            handlerLazy.get().handleDataSms(address, data, subId)
        }
        return true
    }
}

/**
 * One parsed data SMS: the sender's address (invisible characters
 * already stripped) and the raw binary payload — the probe or bundle
 * bytes, never text. Either field may be null on a malformed packet.
 */
data class ParsedDataSms(
    val address: String?,
    val data: ByteArray?,
)
