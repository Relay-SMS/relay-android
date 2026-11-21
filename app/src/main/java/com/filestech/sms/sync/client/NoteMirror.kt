package com.filestech.sms.sync.client

import com.filestech.sms.domain.repository.NoteRepository
import com.filestech.sms.domain.repository.SyncedNoteMessageResult
import com.filestech.sms.domain.repository.SyncedNoteResult
import com.filestech.sms.sync.SyncedNote
import com.filestech.sms.sync.SyncedNoteMessage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Round 36 phase 2 — client mode: the narrow surface the sync
 * engine needs from the note store: upsert synced rows, delete a
 * synced note or entry, prune the snapshot. The engine talks wire
 * types in and gets local ids out; the mapping to the repository's
 * parsed DTOs happens here ([WireMappers]), so the engine never
 * sees a Long id it did not ask for.
 *
 * A separate interface from [ClientMirror] on purpose: the note
 * store is a different subsystem, and the conversation mirror file
 * is already 2,500+ lines — note logic does not belong in it. The
 * implementation delegates everything to [NoteRepository] (the
 * single write path — seam S1 for notes); there is no DAO access
 * here, since the repository owns the preview + file lifecycle.
 */
interface NoteMirror {

    /**
     * Upserts one synced note. Null when the wire id does not
     * parse — the row is dropped, never stored under a wrong key.
     */
    suspend fun upsertSyncedNote(wire: SyncedNote): SyncedNoteResult?

    /**
     * Upserts one synced entry. Null when the wire id does not
     * parse, or the parent note is unknown (the entry cannot be
     * stored without its note).
     */
    suspend fun upsertSyncedMessage(wire: SyncedNoteMessage): SyncedNoteMessageResult?

    /**
     * Deletes one synced note by hub id, with its entries,
     * attachment rows, and their encrypted files. No-op when
     * unknown.
     */
    suspend fun deleteSyncedNote(hubNoteId: Long)

    /**
     * Deletes one synced entry by hub id, with its attachments +
     * files. No-op when unknown.
     */
    suspend fun deleteSyncedMessage(hubMessageId: Long)

    /**
     * Snapshot prune: deletes every locally mirrored note whose
     * hub id is absent from [keepHubIds] (the snapshot just
     * received — deletions included, there is no tombstone feed).
     * Local (hub-mode) notes are never pruned. Returns the number
     * of notes pruned.
     */
    suspend fun pruneSyncedNotes(keepHubIds: Set<Long>): Int
}

/**
 * Round 36 phase 2 — the [NoteMirror] backed by the real store:
 * [NoteRepository] for every write.
 */
@Singleton
class NoteMirrorAdapter @Inject constructor(
    private val noteRepository: NoteRepository,
) : NoteMirror {

    override suspend fun upsertSyncedNote(wire: SyncedNote): SyncedNoteResult? {
        val data = wire.toData() ?: return null
        return noteRepository.upsertSyncedNote(data)
    }

    override suspend fun upsertSyncedMessage(wire: SyncedNoteMessage): SyncedNoteMessageResult? {
        val data = wire.toData() ?: return null
        return noteRepository.upsertSyncedMessage(data)
    }

    override suspend fun deleteSyncedNote(hubNoteId: Long) {
        noteRepository.deleteSyncedNote(hubNoteId)
    }

    override suspend fun deleteSyncedMessage(hubMessageId: Long) {
        noteRepository.deleteSyncedMessage(hubMessageId)
    }

    override suspend fun pruneSyncedNotes(keepHubIds: Set<Long>): Int =
        noteRepository.pruneSyncedNotes(keepHubIds)
}
