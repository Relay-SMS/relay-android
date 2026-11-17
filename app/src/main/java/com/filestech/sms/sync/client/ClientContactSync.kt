package com.filestech.sms.sync.client

import android.content.Context
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import com.filestech.sms.data.local.db.entity.SYNCED_AVATARS_DIR_NAME
import com.filestech.sms.data.local.db.entity.SyncedContactEntity
import com.filestech.sms.domain.clientmode.ClientAvatarGateway
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedContact
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Client mode C4 — the `attachment_chunk` id prefix for avatars (`avatar:<number>`). */
internal const val AVATAR_ID_PREFIX = "avatar:"

/** Client mode C4 — true when an attachment id names an avatar download. */
internal fun isAvatarAttachmentId(attachmentId: String): Boolean =
    attachmentId.startsWith(AVATAR_ID_PREFIX)

/** Client mode C4 — the number behind an avatar attachment id, or null. */
internal fun avatarNumber(attachmentId: String): String? =
    attachmentId.substringAfter(AVATAR_ID_PREFIX, "").takeIf {
        attachmentId.startsWith(AVATAR_ID_PREFIX) && it.isNotEmpty()
    }

/**
 * Client mode C4 — contacts sync: the snapshot (`contacts` batches,
 * both sync modes, before conversations) + live deltas
 * (`contact_updated` / `contact_deleted`) + lazy avatars
 * (`avatar_request` → `attachment_chunk` under `avatar:<number>`,
 * thumbnail bytes) into the `synced_contacts` table.
 *
 * Rules, ported from the desktop:
 * - **Snapshot prune.** On the final batch (`has_more: false`) the
 *   local numbers absent from the snapshot are deleted (deletions
 *   included — there is no tombstone feed). The snapshot set resets
 *   at every run start and on Retry.
 * - **Stale photos.** A photo-version bump (or a photo going away)
 *   deletes the cached file and clears the path; the next render
 *   re-requests it. An unchanged version keeps its file.
 * - **Lazy avatars.** Photos download on demand ([requestAvatar],
 *   called when the UI renders a contact whose photo is not cached),
 *   at most [MAX_CONCURRENT_AVATAR_DOWNLOADS] at once, the rest
 *   queued. A refused avatar is re-asked on reconnect or when its
 *   contact changes — never per render. Chunks verify like any
 *   download: sequential offsets, SHA-256 + size on the final chunk.
 * - **Contacts never touch the local provider.** The cache is
 *   private to the app; `set_contact_photo` is deferred to V2.
 *
 * The frame handlers never throw: a failed batch or chunk logs and
 * keeps the previous state (the next snapshot repairs it) instead of
 * killing the engine's frame loop. One [Mutex] serializes the
 * snapshot set + download state — the engine calls the frame
 * handlers, the repository calls [requestAvatar], and the two never
 * interleave. Private helpers run with the mutex held; only the
 * public entry points lock (the mutex is not reentrant).
 */
@Singleton
class ClientContactSync @Inject constructor(
    private val channel: ClientSyncChannel,
    private val syncedContactDao: SyncedContactDao,
    @ApplicationContext private val context: Context,
) : ClientAvatarGateway {

    /** One avatar download in flight: the bytes so far + their count. */
    private class AvatarDownload(val bytes: ByteArrayOutputStream = ByteArrayOutputStream()) {
        var received: Long = 0
    }

    private val mutex = Mutex()

    /** Hub numbers seen in this snapshot (reset at every run start + Retry). */
    private val snapshotNumbers = mutableSetOf<String>()

    /** Avatar downloads with chunks in flight, by number. */
    private val activeDownloads = mutableMapOf<String, AvatarDownload>()

    /** Numbers waiting for a download slot. */
    private val downloadQueue = ArrayDeque<String>()

    /** Numbers whose avatar was refused (or failed to verify). */
    private val failedAvatars = mutableSetOf<String>()

    private val avatarsDir: File get() = File(context.filesDir, SYNCED_AVATARS_DIR_NAME)

    // -- Snapshot + deltas ------------------------------------------

    /**
     * One `contacts` snapshot batch. Upserts the rows; on the final
     * batch prunes local numbers absent from the snapshot and returns
     * true, so the engine can clear the contacts error. Returns false
     * while more batches are coming, and on failure.
     */
    suspend fun onContacts(value: SyncProtocol.Contacts): Boolean = runCatching {
        mutex.withLock {
            val rows = value.contacts.flatMap { it.toRows() }
            upsertRows(rows)
            for (row in rows) snapshotNumbers.add(row.number)
            if (!value.hasMore) {
                pruneSnapshot()
                true
            } else {
                false
            }
        }
    }.onFailure { Timber.w(it, "ClientContactSync: contacts batch failed") }.getOrDefault(false)

    /**
     * One contact changed or was added (live delta). Upserts by
     * phones; `removed_phones` lists numbers dropped from the
     * contact, deleted here so a removed number does not linger until
     * the next full snapshot. A photo the row didn't have is asked
     * for directly — the render that would have asked already ran.
     */
    suspend fun onContactUpdated(value: SyncProtocol.ContactUpdated) {
        runCatching {
            mutex.withLock {
                val rows = value.contact.toRows()
                upsertRows(rows)
                val current = value.contact.phones.toSet()
                val removed = (value.removedPhones ?: emptyList())
                    .filter { it.isNotEmpty() && it !in current }
                deleteRows(removed)
                for (row in rows) {
                    if (row.hasPhoto) requestAvatarLocked(row.number)
                }
            }
        }.onFailure { Timber.w(it, "ClientContactSync: contact_updated failed") }
    }

    /** One contact deleted (live delta): its rows and avatar files go. */
    suspend fun onContactDeleted(value: SyncProtocol.ContactDeleted) {
        runCatching {
            mutex.withLock { deleteRows(value.phones.filter { it.isNotEmpty() }) }
        }.onFailure { Timber.w(it, "ClientContactSync: contact_deleted failed") }
    }

    // -- Avatars ------------------------------------------------------

    /**
     * One `attachment_chunk` for an avatar (`avatar:<number>`).
     * Chunks are sequential per download — a gap, bad base64url, or
     * an oversized total fails the download. The final chunk verifies
     * (SHA-256 + size) before the file lands.
     */
    suspend fun onAvatarChunk(value: SyncProtocol.AttachmentChunk) {
        val number = avatarNumber(value.attachmentId) ?: return
        runCatching {
            mutex.withLock { handleAvatarChunk(number, value) }
        }.onFailure { Timber.w(it, "ClientContactSync: avatar chunk failed") }
    }

    /** An `attachment_error` for an avatar: the download is refused (re-asked on reconnect). */
    suspend fun onAvatarError(value: SyncProtocol.AttachmentError) {
        val number = avatarNumber(value.attachmentId) ?: return
        mutex.withLock {
            activeDownloads.remove(number)
            failedAvatars.add(number)
            pumpLocked()
        }
    }

    /**
     * Asks the hub for one contact photo, by normalized number.
     * No-op when not connected-able (the send fails and the number
     * stays queued for the reconnect), unknown, photo-less, already
     * cached, already in flight / queued / refused. A stale cached
     * path (file gone, or 0 bytes) is cleared and re-downloaded.
     */
    override suspend fun requestAvatar(number: String) {
        runCatching {
            mutex.withLock { requestAvatarLocked(number) }
        }.onFailure { Timber.w(it, "ClientContactSync: avatar request failed") }
    }

    // -- Session lifecycle --------------------------------------------

    /** A run starts (or Retry re-asks): the next snapshot is a fresh set. */
    suspend fun resetSnapshot() {
        mutex.withLock { snapshotNumbers.clear() }
    }

    /** Connected (again): refused avatars get a fresh start, and the queue resumes. */
    suspend fun onSessionResumed() {
        mutex.withLock {
            downloadQueue.addAll(failedAvatars)
            failedAvatars.clear()
            pumpLocked()
        }
    }

    /** The session ended: in-flight downloads go back to the queue (their bytes dropped). */
    suspend fun onSessionEnded() {
        mutex.withLock {
            downloadQueue.addAll(0, activeDownloads.keys)
            activeDownloads.clear()
        }
    }

    /** A different hub: its address book replaces this one — drop every in-memory download. */
    suspend fun onHubChanged() {
        mutex.withLock {
            snapshotNumbers.clear()
            activeDownloads.clear()
            downloadQueue.clear()
            failedAvatars.clear()
        }
    }

    /** The cache was wiped (Re-sync / Unlink / mode switch): downloads go, avatar files go. */
    suspend fun onCacheWiped() {
        mutex.withLock {
            snapshotNumbers.clear()
            activeDownloads.clear()
            downloadQueue.clear()
            failedAvatars.clear()
            runCatching { avatarsDir.deleteRecursively() }
        }
    }

    // -- Locked helpers (the mutex is held) ---------------------------

    /**
     * Upserts rows, keeping a still-current avatar path and clearing
     * a stale one (photo version moved, or the photo went away — the
     * file goes with it). Any upsert is a fresh start for a refused
     * avatar: the hub just re-asserted the contact.
     */
    private suspend fun upsertRows(rows: List<SyncedContactEntity>) {
        for (row in rows) {
            val existing = syncedContactDao.get(row.number)
            val stale = isPhotoStale(existing, row)
            if (stale) deleteAvatarFile(existing?.avatarLocalPath)
            syncedContactDao.upsert(
                row.copy(
                    avatarLocalPath = if (stale) null else existing?.avatarLocalPath,
                    localAvatar = if (stale) null else existing?.localAvatar,
                    localAvatarBaseline = if (stale) null else existing?.localAvatarBaseline,
                ),
            )
            failedAvatars.remove(row.number)
        }
    }

    /** A cached photo is stale when the version moved or the photo went away. */
    private fun isPhotoStale(existing: SyncedContactEntity?, fresh: SyncedContactEntity): Boolean {
        if (existing?.avatarLocalPath == null) return false
        if (!fresh.hasPhoto) return true
        return existing.photoUpdatedAt != fresh.photoUpdatedAt
    }

    /** Snapshot complete: delete the local numbers the hub no longer has. */
    private suspend fun pruneSnapshot() {
        val absent = syncedContactDao.numbers().filter { it !in snapshotNumbers }
        deleteRows(absent)
        snapshotNumbers.clear()
    }

    /** Deletes rows + their avatar files (a no-op for an empty list). */
    private suspend fun deleteRows(numbers: List<String>) {
        if (numbers.isEmpty()) return
        for (number in numbers) {
            deleteAvatarFile(syncedContactDao.get(number)?.avatarLocalPath)
        }
        syncedContactDao.deleteNumbers(numbers)
    }

    private fun deleteAvatarFile(relativePath: String?) {
        if (relativePath == null) return
        runCatching { File(avatarsDir, relativePath).delete() }
    }

    private suspend fun requestAvatarLocked(number: String) {
        if (number in activeDownloads || number in downloadQueue || number in failedAvatars) return
        val row = syncedContactDao.get(number) ?: return
        if (!row.hasPhoto) return
        val current = row.avatarLocalPath?.let { File(avatarsDir, it) }
        if (current != null && current.exists() && current.length() > 0L) return
        if (current != null) {
            runCatching { current.delete() }
            syncedContactDao.setAvatarPath(number, null)
        }
        downloadQueue.add(number)
        pumpLocked()
    }

    /** Starts queued downloads while a slot is free (at most the cap runs at once). */
    private suspend fun pumpLocked() {
        while (activeDownloads.size < MAX_CONCURRENT_AVATAR_DOWNLOADS && downloadQueue.isNotEmpty()) {
            val number = downloadQueue.removeFirst()
            val sent = runCatching {
                channel.send(ClientFrames.encodeAvatarRequest(SyncProtocol.AvatarRequest(number)))
            }.getOrDefault(false)
            if (!sent) {
                // Offline (or the session died): back to the front —
                // the reconnect resumes the queue.
                downloadQueue.addFirst(number)
                return
            }
            activeDownloads[number] = AvatarDownload()
        }
    }

    private suspend fun handleAvatarChunk(number: String, chunk: SyncProtocol.AttachmentChunk) {
        val download = activeDownloads[number] ?: return
        val bytes = Base64Url.decode(chunk.dataBase64Url)
        if (chunk.offset != download.received || bytes == null) {
            failDownload(number)
            return
        }
        download.bytes.write(bytes)
        download.received += bytes.size
        if (download.received > MAX_AVATAR_BYTES) {
            failDownload(number)
            return
        }
        if (!chunk.isFinal) return
        activeDownloads.remove(number)
        val data = download.bytes.toByteArray()
        // Client mode C5 — the shared transfer check (ChunkVerify):
        // same size-then-SHA-256 rule this file used to carry
        // privately; the avatar path is otherwise untouched.
        if (verifyTransferBytes(data, chunk.sha256, chunk.sizeBytes)) {
            storeAvatar(number, data)
        } else {
            failedAvatars.add(number)
        }
        pumpLocked()
    }

    private suspend fun failDownload(number: String) {
        activeDownloads.remove(number)
        failedAvatars.add(number)
        pumpLocked()
    }

    /**
     * Writes the verified bytes (one file per number) and points the
     * row at them. The path update never creates a row, and bytes for
     * a contact wiped mid-download are dropped, not orphaned on disk.
     * A number that could escape the avatar directory is refused.
     */
    private suspend fun storeAvatar(number: String, data: ByteArray) {
        if (number.contains('/') || number.contains('\\')) {
            failedAvatars.add(number)
            return
        }
        if (syncedContactDao.get(number) == null) return
        val written = runCatching {
            avatarsDir.mkdirs()
            File(avatarsDir, number).writeBytes(data)
            true
        }.getOrDefault(false)
        if (written) {
            syncedContactDao.setAvatarPath(number, number)
        } else {
            failedAvatars.add(number)
        }
    }

    companion object {
        /** At most this many avatar downloads run at once (the desktop's downloader cap). */
        private const val MAX_CONCURRENT_AVATAR_DOWNLOADS = 3

        /** An avatar thumbnail is small; anything past this is a protocol error, not a photo. */
        private const val MAX_AVATAR_BYTES: Long = 512 * 1024
    }
}

/** One wire contact → one cache row per phone (the number is the join key). */
private fun SyncedContact.toRows(): List<SyncedContactEntity> = phones.map { phone ->
    SyncedContactEntity(
        number = phone,
        displayName = displayName,
        hasPhoto = hasPhoto,
        photoUpdatedAt = photoUpdatedAt,
    )
}
