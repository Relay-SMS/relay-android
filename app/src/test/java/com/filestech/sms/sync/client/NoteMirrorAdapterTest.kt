package com.filestech.sms.sync.client

import com.filestech.sms.domain.model.Note
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedNote
import com.filestech.sms.sync.SyncedNoteMessage
import com.filestech.sms.ui.screens.notes.FakeNoteRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Round 36 phase 2 — the [NoteMirror] adapter over the fake note
 * store: the wire → data mapping, the delegation to
 * [com.filestech.sms.domain.repository.NoteRepository], the
 * adopt-by-client-key rule, and the snapshot prune that never
 * touches a local (hub-mode) note. The adapter itself is a thin
 * delegate; the fake's upsert behavior is what these tests pin,
 * mirroring [ConversationMirrorAdapterTest] for SMS.
 */
class NoteMirrorAdapterTest {

    private fun fixture() = FakeNoteRepository().let { repo ->
        NoteMirrorAdapter(repo) to repo
    }

    private fun sampleNote(id: String = "7", title: String = "Groceries") = SyncedNote(
        id = id,
        title = title,
        createdAt = 1_794_670_000_000L,
        updatedAt = 1_794_670_100_000L,
        lastMessageAt = 1_794_670_100_000L,
        lastMessagePreview = "Milk",
        pinned = true,
        archived = false,
        messageCount = 3,
    )

    private fun sampleMessage(
        id: String = "42",
        noteId: String = "7",
        body: String = "Milk, eggs",
        edited: Boolean = false,
        clientMessageId: String? = null,
        attachments: List<SyncedAttachment> = emptyList(),
    ) = SyncedNoteMessage(
        id = id,
        noteId = noteId,
        body = body,
        date = 1_794_670_100_000L,
        attachments = attachments,
        edited = edited,
        clientMessageId = clientMessageId,
    )

    @Test fun `upsertSyncedNote creates then updates the same local row`() = runTest {
        val (adapter, repo) = fixture()
        val created = adapter.upsertSyncedNote(sampleNote())
        assertThat(created).isNotNull()
        assertThat(created!!.created).isTrue()
        val localId = created.localId
        assertThat(repo.findNoteByHubId(7L)?.title).isEqualTo("Groceries")
        assertThat(repo.findNoteByHubId(7L)?.pinned).isTrue()

        val updated = adapter.upsertSyncedNote(sampleNote(title = "Shopping"))
        assertThat(updated).isNotNull()
        assertThat(updated!!.created).isFalse()
        assertThat(updated.localId).isEqualTo(localId)
        assertThat(repo.findNoteByHubId(7L)?.title).isEqualTo("Shopping")
    }

    @Test fun `upsertSyncedNote drops a row whose id does not parse`() = runTest {
        val (adapter, _) = fixture()
        assertThat(adapter.upsertSyncedNote(sampleNote(id = "abc"))).isNull()
    }

    @Test fun `upsertSyncedMessage inserts under a known note with blank local uris`() = runTest {
        val (adapter, repo) = fixture()
        adapter.upsertSyncedNote(sampleNote())
        val result = adapter.upsertSyncedMessage(
            sampleMessage(
                attachments = listOf(
                    SyncedAttachment(
                        id = "9",
                        mimeType = "image/jpeg",
                        fileName = "list.jpg",
                        sizeBytes = 12_345L,
                        width = 800,
                        height = 600,
                        durationMs = null,
                    ),
                ),
            ),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.inserted).isTrue()
        val stored = repo.findMessageByHubId(42L)
        assertThat(stored).isNotNull()
        assertThat(stored!!.body).isEqualTo("Milk, eggs")
        assertThat(stored.attachments).hasSize(1)
        // Mirrored attachments have no bytes yet — the blank localUri
        // is what makes the download tile appear.
        assertThat(stored.attachments[0].localUri).isEmpty()
        assertThat(stored.attachments[0].hubAttachmentId).isEqualTo(9L)
    }

    @Test fun `upsertSyncedMessage drops an entry whose note is unknown`() = runTest {
        val (adapter, _) = fixture()
        assertThat(adapter.upsertSyncedMessage(sampleMessage())).isNull()
    }

    @Test fun `upsertSyncedMessage adopts an optimistic row by client key`() = runTest {
        val (adapter, repo) = fixture()
        adapter.upsertSyncedNote(sampleNote())
        val localNoteId = repo.findNoteByHubId(7L)!!.id
        val optimistic = repo.insertMessage(
            noteId = localNoteId,
            body = "Milk, eggs",
            uuid = "local-uuid",
            date = 1_794_670_100_000L,
            hubNoteMessageId = null,
            clientMessageId = "uuid-1",
        )
        val result = adapter.upsertSyncedMessage(
            sampleMessage(clientMessageId = "uuid-1"),
        )
        assertThat(result).isNotNull()
        assertThat(result!!.inserted).isFalse()
        assertThat(result.localId).isEqualTo(optimistic.id)
        assertThat(repo.messageHubId(optimistic.id)).isEqualTo(42L)
        // Still one row — the upsert adopted, it did not duplicate.
        assertThat(repo.listAllMessages()).hasSize(1)
    }

    @Test fun `upsertSyncedMessage re-applies an edit over the same row`() = runTest {
        val (adapter, repo) = fixture()
        adapter.upsertSyncedNote(sampleNote())
        adapter.upsertSyncedMessage(sampleMessage())
        val reapplied = adapter.upsertSyncedMessage(
            sampleMessage(body = "Milk, eggs, butter", edited = true),
        )
        assertThat(reapplied).isNotNull()
        assertThat(reapplied!!.inserted).isFalse()
        val stored = repo.findMessageByHubId(42L)
        assertThat(stored!!.body).isEqualTo("Milk, eggs, butter")
        assertThat(stored.edited).isTrue()
        assertThat(repo.listAllMessages()).hasSize(1)
    }

    @Test fun `deleteSyncedNote removes the note and its entries`() = runTest {
        val (adapter, repo) = fixture()
        adapter.upsertSyncedNote(sampleNote())
        adapter.upsertSyncedMessage(sampleMessage())
        adapter.deleteSyncedNote(7L)
        assertThat(repo.findNoteByHubId(7L)).isNull()
        assertThat(repo.findMessageByHubId(42L)).isNull()
    }

    @Test fun `deleteSyncedMessage removes the entry only`() = runTest {
        val (adapter, repo) = fixture()
        adapter.upsertSyncedNote(sampleNote())
        adapter.upsertSyncedMessage(sampleMessage())
        adapter.deleteSyncedMessage(42L)
        assertThat(repo.findMessageByHubId(42L)).isNull()
        assertThat(repo.findNoteByHubId(7L)).isNotNull()
    }

    @Test fun `pruneSyncedNotes keeps local notes and drops absent mirrored ones`() = runTest {
        val (adapter, repo) = fixture()
        // A local (hub-mode) note — no hub id, never pruned.
        val local: Note = repo.createNote(
            title = "Local only",
            uuid = "local-note",
            createdAt = 1L,
            updatedAt = 1L,
            hubNoteId = null,
            clientNoteId = null,
        )
        adapter.upsertSyncedNote(sampleNote(id = "7"))
        adapter.upsertSyncedNote(sampleNote(id = "8", title = "Ideas"))
        val pruned = adapter.pruneSyncedNotes(setOf(7L))
        assertThat(pruned).isEqualTo(1)
        assertThat(repo.findNoteByHubId(7L)).isNotNull()
        assertThat(repo.findNoteByHubId(8L)).isNull()
        assertThat(repo.findNoteById(local.id)).isNotNull()
    }
}
