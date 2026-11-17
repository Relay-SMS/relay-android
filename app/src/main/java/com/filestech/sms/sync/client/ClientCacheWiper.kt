package com.filestech.sms.sync.client

import com.filestech.sms.data.local.db.dao.AttachmentDao
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.FichiersDePiecesJointes
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode — wipes everything the client mode cached from the
 * hub, so a mode switch or an unlink leaves nothing of the hub's
 * messages behind on this device. The device identity (its own key
 * and id) survives — only the hub side of the state is dropped.
 *
 * C1 wiped the link record and the TOFU pin. C2 adds the
 * message-cache tables: [wipeMessageCache] clears the `hub_*`
 * mirror rows + the sync state, and [wipeHubState] funnels through
 * it — the mode-switch and Unlink call sites stay the same, and
 * Re-sync reuses [wipeMessageCache] on its own (the link survives
 * a Re-sync). C4 adds the avatar files (the contacts cache's bytes).
 * C5 adds the downloaded attachment files (the message cache's
 * bytes) + the downloader reset.
 */
@Singleton
class ClientCacheWiper @Inject constructor(
    private val linkStore: ClientLinkStore,
    private val pinStore: TofuPinStore,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val stateStore: SyncStateStore,
    /** Client mode C4 — the avatar files go with the contacts cache. */
    private val contactSync: ClientContactSync,
    /** Client mode C5 — the downloaded files are collected before the rows go. */
    private val attachmentDao: AttachmentDao,
    /** Client mode C5 — citation-counted file deletion (shared with the hub mode). */
    private val fichiers: FichiersDePiecesJointes,
    /** Client mode C5 — in-flight + queued downloads die with the cache. */
    private val downloader: ClientAttachmentDownloader,
) {

    /**
     * Drops the hub link and the pinned hub certificate, plus the
     * whole message cache. The device id and display name survive,
     * so re-linking keeps the same identity.
     */
    suspend fun wipeHubState() {
        wipeMessageCache()
        linkStore.clearHub()
        pinStore.clear()
    }

    /**
     * Drops the synced message cache: every conversation mirrored
     * from a hub (messages + attachments follow by FK cascade),
     * every synced message row, and all sync state (per-hub rows,
     * walk frontiers, the synced-contacts cache) — plus the avatar
     * files (C4) and any in-flight avatar download, and the
     * downloaded attachment files (C5) and any in-flight download.
     *
     * The attachment URIs are collected BEFORE the rows go (the
     * rows are the only record of which files belong to the cache),
     * and the files are deleted AFTER — the citation check then
     * sees the wiped rows gone, so a file cited only by a synced
     * row is deleted, and a file still cited by a hub-mode row
     * survives.
     *
     * Deliberately NOT cleared:
     * - hub-mode rows (`hub_*_id` NULL) — a device that switches
     *   back to hub mode keeps its own messages;
     * - `scheduled_messages` — the schedules this device created
     *   survive a Re-sync (the desktop rule).
     */
    suspend fun wipeMessageCache() {
        val uris = attachmentDao.findLocalUrisForSynced()
        conversationDao.deleteSynced()
        messageDao.deleteSynced()
        stateStore.clearAll()
        contactSync.onCacheWiped()
        downloader.onCacheWiped()
        if (uris.isNotEmpty()) {
            val failures = fichiers.effacerSiPlusCites(uris)
            if (failures > 0) Timber.w("ClientCacheWiper: %d downloaded files could not be deleted", failures)
        }
    }
}
