package com.filestech.sms.system.sync

import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.entity.E2eNoticeEntity
import com.filestech.sms.data.local.db.entity.E2ePeerEntity
import com.filestech.sms.data.sms.PhoneIdentity
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.e2e.E2eAccountManager
import com.filestech.sms.e2e.E2eNoticeStore
import com.filestech.sms.e2e.E2ePeerRepository
import com.filestech.sms.e2e.E2eProbeManager
import com.filestech.sms.e2e.E2eSafetyNumber
import com.filestech.sms.e2e.IdentityKeys
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.E2eSyncSource
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedE2eNotice
import com.filestech.sms.sync.SyncedE2ePeer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 42 — the [E2eSyncSource] backed by the real E2E store:
 * [E2ePeerRepository] for the peer rows (state + keys + verified
 * flag), [E2eAccountManager] for OUR identity keys (the other half
 * of the safety number), [E2eNoticeStore] for the in-thread
 * notices, and [E2eProbeManager] for the manual probe retry.
 *
 * One derivation, one string: the safety numbers are derived here
 * with [E2eSafetyNumber.deriveForPeer] — the same function the
 * phone's verification sheet uses — and cross the link as the
 * FORMATTED string. The desktop stores and displays it verbatim,
 * never re-deriving, so the number it shows is byte-identical to
 * the phone's.
 *
 * Sync never creates an identity: our keys are only loaded when at
 * least one peer holds keys AND an account row already exists
 * (see [loadOurKeysIfNeeded]). A user who never uses E2E pays
 * nothing, and a Keystore failure degrades to null numbers — the
 * peers still sync with their state + verified flag — instead of
 * failing the sync.
 */
@Singleton
class RepositoryE2eSyncSource @Inject constructor(
    private val peerRepository: E2ePeerRepository,
    private val accountManager: E2eAccountManager,
    private val noticeStore: E2eNoticeStore,
    private val probeManager: E2eProbeManager,
    private val conversationDao: ConversationDao,
    private val phoneIdentity: PhoneIdentity,
) : E2eSyncSource {

    override suspend fun safetyNumbers(): List<SyncedE2ePeer> =
        deriveAll(peerRepository.allPeers())

    override fun observeSafetyNumbers(): Flow<List<SyncedE2ePeer>> =
        peerRepository.observeAllPeers().map { peers -> deriveAll(peers) }

    override suspend fun notices(): List<SyncedE2eNotice> =
        withContext(Dispatchers.IO) {
            noticeStore.allNotices().map { it.toSynced() }
        }

    override fun observeNotices(): Flow<List<SyncedE2eNotice>> =
        noticeStore.observeAllNotices().map { list -> list.map { it.toSynced() } }

    override suspend fun setVerified(address: String, verified: Boolean): ActionResult {
        peerRepository.setVerified(address, verified)
        return ActionResult(ok = true)
    }

    override suspend fun retryProbe(address: String): ActionResult =
        withContext(Dispatchers.IO) {
            val conversationId = conversationIdForAddress(address)
            if (conversationId == null) {
                ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                    message = "Conversation not found.",
                )
            } else if (probeManager.probeConversation(conversationId, manual = true)) {
                ActionResult(ok = true)
            } else {
                ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.SEND_FAILED,
                    message = "Encryption couldn't be retried right now.",
                )
            }
        }

    /**
     * Derives the full safety-number set for [peers]. Our keys are
     * loaded at most once per pass (and only when needed — see
     * [loadOurKeysIfNeeded]); a peer without keys, or with keys
     * that do not decode, carries a null number.
     */
    private suspend fun deriveAll(peers: List<E2ePeerEntity>): List<SyncedE2ePeer> =
        withContext(Dispatchers.IO) {
            val ours = loadOurKeysIfNeeded(peers)
            peers.map { peer ->
                SyncedE2ePeer(
                    address = peer.address,
                    state = peer.state.name.lowercase(),
                    safetyNumber = ours?.let {
                        E2eSafetyNumber.deriveForPeer(it, peer.theirCurveKey, peer.theirEdKey)
                    },
                    verified = peer.verified,
                )
            }
        }

    /**
     * Our identity keys for a derivation pass — or null when no
     * derivation is possible. Null has three causes, all safe: no
     * peer holds keys yet, no account row exists, or the keys
     * could not be read (a Keystore failure degrades to null
     * numbers, never a failed sync).
     *
     * The two gates are the point: [E2eAccountManager.identityKeys]
     * CREATES an account when none exists, and sync must never do
     * that. The key-presence gate skips the account read entirely
     * for a user who never exchanged keys; the `hasAccount()` gate
     * makes the guarantee airtight even for an inconsistent store
     * (a peer row with keys but no account row).
     */
    private suspend fun loadOurKeysIfNeeded(peers: List<E2ePeerEntity>): IdentityKeys? {
        if (peers.none { it.theirCurveKey != null && it.theirEdKey != null }) return null
        return try {
            if (!accountManager.hasAccount()) null else accountManager.identityKeys()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.w(t, "E2E sync: our identity keys unreadable — syncing peers without numbers")
            null
        }
    }

    /**
     * The 1:1 conversation for an address, for the probe retry.
     * Exact CSV match first, then the identity fallback for
     * representation drift (`+33` vs `06`) — the same two-step
     * lookup the handshake handler uses for in-thread notices.
     * Null when no conversation exists (yet): the desktop only
     * offers a retry for peers it knows through a conversation.
     */
    private suspend fun conversationIdForAddress(address: String): Long? {
        conversationDao.findByAddressesCsv(address)?.let { return it.id }
        val ident = phoneIdentity.snapshot()
        return conversationDao.snapshotOneToOneConversations().firstOrNull { conv ->
            val raw = PhoneAddress.list(conv.addressesCsv).firstOrNull()?.raw
            raw != null && ident.matches(raw, address)
        }?.id
    }
}

/** One notice row → its wire DTO: ids as decimal strings, kind as its lowercase wire string. */
private fun E2eNoticeEntity.toSynced(): SyncedE2eNotice = SyncedE2eNotice(
    id = id.toString(),
    conversationId = conversationId.toString(),
    kind = kind.name.lowercase(),
    date = date,
)
