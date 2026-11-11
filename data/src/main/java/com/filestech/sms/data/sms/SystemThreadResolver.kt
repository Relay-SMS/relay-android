package com.filestech.sms.data.sms

import android.content.Context
import android.provider.Telephony
import com.filestech.sms.core.ext.stripInvisibleChars
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 13 — resolves the AOSP system thread ID for a set of recipients, for the live
 * MMS reception path.
 *
 * Why this exists: the two earlier group-MMS fixes (round 10 participant-set matching,
 * round 12 lenient `blockKey` matching) could only find a group that ALREADY existed in
 * Relay's database, and they needed the full participant list from the PDU. A group
 * Relay had never seen — or one whose `To` field the carrier had stripped — was filed
 * as a 1:1. The system thread ID is the grouping Android itself uses: `Telephony.Threads`
 * keys a thread by its recipient set, so the same group always resolves to the same
 * thread, whether or not Relay has seen it before and whether or not our own number is
 * known (no `userMsisdn` needed).
 *
 * The caller is [com.filestech.sms.data.mms.IntegrationMmsRecu] — it resolves the ID
 * BEFORE [com.filestech.sms.data.repository.ConversationMirror.inscrireMmsRecu] opens
 * its Room transaction, so the thread lookup (a ContentResolver query to another
 * provider) never runs inside a database transaction. Only group MMS are resolved
 * (see `integrer`): 1:1 routing is untouched.
 *
 * Fail-soft by design: the provider can refuse (not the default SMS app), the lookup
 * can throw, or the recipient list can be empty — every failure returns `0L`, and the
 * caller falls through to the existing group matching. Never throws.
 *
 * The recipient canonicalisation mirrors [com.filestech.sms.data.mms.MmsSystemWriteback]
 * (which resolves the same thread ID for outgoing MMS): strip invisible/bidi chars,
 * then whitespace, dashes and parens, so `"+33 6 12 34 56 78"` and `"+33612345678"`
 * hit the same `canonical_addresses` row. Kept as a separate copy on purpose — the
 * outgoing writeback path is not touched by this change.
 *
 * The class and [resolve] are `open` so tests can substitute a fixed thread ID.
 */
@Singleton
open class SystemThreadResolver @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * Returns the AOSP thread ID for [recipients], creating the thread in the provider
     * if it does not exist yet, or `0L` when it cannot be resolved.
     */
    open fun resolve(recipients: Collection<String>): Long {
        val canonical = canonicalThreadRecipients(recipients)
        if (canonical.isEmpty()) return 0L
        return runCatching {
            Telephony.Threads.getOrCreateThreadId(context, canonical)
        }.onFailure {
            Timber.w(it, "SystemThreadResolver: getOrCreateThreadId failed for %d recipients", canonical.size)
        }.getOrDefault(0L).takeIf { it > 0L } ?: 0L
    }
}

/**
 * Normalises raw addresses for the provider's `canonical_addresses` table, so two
 * spellings of the same number resolve to the same thread. Kept `internal` and
 * top-level so it is unit-testable without a Context or a provider.
 */
internal fun canonicalThreadRecipients(raw: Collection<String>): Set<String> =
    raw.asSequence()
        .map { it.stripInvisibleChars() }
        .map { it.replace(Regex("[\\s\\-()]"), "") }
        .filter { it.isNotBlank() }
        .toHashSet()
