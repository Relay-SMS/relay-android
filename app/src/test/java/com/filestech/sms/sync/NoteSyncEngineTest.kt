package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 36 phase 2 — the hub note engine, driven without a socket:
 * a fake [NoteSyncSource] holds the notes and entries, and the
 * harness records every outbound frame. Pinned here: the stream
 * phase (snapshot → per-note pages, the `since_note_message_id`
 * delta, `new_only` list-only), the write-back answers, and the
 * live collectors (note diff + edits).
 */
class NoteSyncEngineTest {

    private class FakeNoteSyncSource : NoteSyncSource {
        val notesFlow = MutableStateFlow<List<SyncedNote>>(emptyList())
        val editedFlow = MutableStateFlow<List<EditedNoteMessage>>(emptyList())
        val messagesByNote = mutableMapOf<Long, MutableList<SyncedNoteMessage>>()
        var maxMessageId = 0L
        var createResult: (String, String) -> CreateNoteResult =
            { _, title -> CreateNoteResult.Created(sampleNote(99, title)) }
        var saveResult: (String, Long, String, List<SyncProtocol.SendAttachment>?) ->
        SaveNoteMessageResult = { _, _, _, _ -> SaveNoteMessageResult.Saved(42L) }
        var actionResult: ActionResult = ActionResult(ok = true)
        val calls = mutableListOf<String>()

        fun store(message: SyncedNoteMessage) {
            val noteId = message.noteId.toLong()
            val list = messagesByNote.getOrPut(noteId) { mutableListOf() }
            list.removeAll { it.id == message.id }
            list.add(message)
            maxMessageId = maxOf(maxMessageId, message.id.toLong())
        }

        override suspend fun notes(): List<SyncedNote> = notesFlow.value

        override fun observeNotes(): Flow<List<SyncedNote>> = notesFlow

        override suspend fun totalNotes(): Int = notesFlow.value.size

        override suspend fun maxNoteMessageId(): Long = maxMessageId

        override suspend fun noteMessagesBefore(
            noteId: Long,
            beforeId: Long,
            limit: Int,
        ): List<SyncedNoteMessage> {
            val all = (messagesByNote[noteId] ?: emptyList())
                .sortedByDescending { it.id.toLong() }
            val filtered = if (beforeId == 0L) all else all.filter { it.id.toLong() < beforeId }
            return filtered.take(limit)
        }

        override suspend fun noteMessagesAfter(
            noteId: Long,
            afterId: Long,
        ): List<SyncedNoteMessage> =
            (messagesByNote[noteId] ?: emptyList())
                .filter { it.id.toLong() > afterId }
                .sortedBy { it.id.toLong() }

        override suspend fun noteMessagesAfterAll(afterId: Long): List<SyncedNoteMessage> =
            messagesByNote.values.flatten()
                .filter { it.id.toLong() > afterId }
                .sortedBy { it.id.toLong() }

        override suspend fun noteMessageById(messageId: Long): SyncedNoteMessage? =
            messagesByNote.values.flatten().firstOrNull { it.id.toLong() == messageId }

        override fun observeEditedMessages(): Flow<List<EditedNoteMessage>> = editedFlow

        override suspend fun createNote(clientNoteId: String, title: String): CreateNoteResult {
            calls += "createNote:$title"
            return createResult(clientNoteId, title)
        }

        override suspend fun saveNoteMessage(
            clientMessageId: String,
            noteId: Long,
            body: String,
            attachments: List<SyncProtocol.SendAttachment>?,
        ): SaveNoteMessageResult {
            calls += "saveNoteMessage:$noteId"
            return saveResult(clientMessageId, noteId, body, attachments)
        }

        override suspend fun editNoteMessage(
            noteId: Long,
            messageId: Long,
            body: String,
        ): ActionResult {
            calls += "editNoteMessage:$noteId:$messageId"
            return actionResult
        }

        override suspend fun renameNote(noteId: Long, title: String): ActionResult {
            calls += "renameNote:$noteId"
            return actionResult
        }

        override suspend fun deleteNote(noteId: Long): ActionResult {
            calls += "deleteNote:$noteId"
            return actionResult
        }

        override suspend fun deleteNoteMessage(noteId: Long, messageId: Long): ActionResult {
            calls += "deleteNoteMessage:$noteId:$messageId"
            return actionResult
        }

        override suspend fun setNotePinned(noteId: Long, pinned: Boolean): ActionResult {
            calls += "setNotePinned:$noteId:$pinned"
            return actionResult
        }

        override suspend fun setNoteArchived(noteId: Long, archived: Boolean): ActionResult {
            calls += "setNoteArchived:$noteId:$archived"
            return actionResult
        }

        override suspend fun openNoteAttachment(
            attachmentId: Long,
            messageId: Long,
        ): AttachmentOpenResult = AttachmentOpenResult.Unknown
    }

    private suspend fun TestScope.runNoteEngine(
        source: FakeNoteSyncSource,
        block: suspend TestScope.(NoteSyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = mutableListOf<String>()
        val engine = NoteSyncEngine(source, this, send = { sent += it })
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    // -- Stream phase ---------------------------------------------------

    @Test fun `streamNotesPhase streams the snapshot then each note's entries`() = runTest {
        val source = FakeNoteSyncSource()
        val notes = listOf(sampleNote(7), sampleNote(8))
        source.store(sampleNoteMessage(42, 7))
        source.store(sampleNoteMessage(41, 7))
        source.store(sampleNoteMessage(50, 8))
        runNoteEngine(source) { engine, sent ->
            engine.streamNotesPhase(SyncProtocol.SyncRequest(mode = "full"), notes)

            assertThat(sent.map { it.frameType() }).containsExactly(
                "notes",
                "note_messages_batch",
                "note_messages_batch",
            ).inOrder()
            val snapshot = sent[0].asObject()
            assertThat(snapshot["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(snapshot["notes"]?.jsonArray?.size).isEqualTo(2)
            val firstPage = sent[1].asObject()
            assertThat(firstPage["note_id"]?.jsonPrimitive?.content).isEqualTo("7")
            assertThat(firstPage["messages"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
                .containsExactly("42", "41").inOrder()
            val secondPage = sent[2].asObject()
            assertThat(secondPage["note_id"]?.jsonPrimitive?.content).isEqualTo("8")
        }
    }

    @Test fun `streamNotesPhase honors since_note_message_id for entries only`() = runTest {
        val source = FakeNoteSyncSource()
        val notes = listOf(sampleNote(7), sampleNote(8))
        source.store(sampleNoteMessage(42, 7))
        source.store(sampleNoteMessage(41, 7))
        source.store(sampleNoteMessage(50, 8))
        runNoteEngine(source) { engine, sent ->
            engine.streamNotesPhase(
                SyncProtocol.SyncRequest(mode = "full", sinceNoteMessageId = "41"),
                notes,
            )

            // The LIST still streams in full (the cursor is an entry
            // id — there is no "note newer than the cursor" filter);
            // the ENTRIES are the delta above the cursor.
            assertThat(sent.map { it.frameType() }).containsExactly(
                "notes",
                "note_messages_batch",
                "note_messages_batch",
            ).inOrder()
            val firstPage = sent[1].asObject()
            assertThat(firstPage["messages"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
                .containsExactly("42")
            val secondPage = sent[2].asObject()
            assertThat(secondPage["messages"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
                .containsExactly("50")
        }
    }

    @Test fun `streamNotesPhase in new_only streams the list only`() = runTest {
        val source = FakeNoteSyncSource()
        val notes = listOf(sampleNote(7))
        source.store(sampleNoteMessage(42, 7))
        runNoteEngine(source) { engine, sent ->
            engine.streamNotesPhase(SyncProtocol.SyncRequest(mode = "new_only"), notes)

            assertThat(sent.map { it.frameType() }).containsExactly("notes")
        }
    }

    @Test fun `streamNotesPhase with no notes still sends one empty batch`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.streamNotesPhase(SyncProtocol.SyncRequest(mode = "full"), emptyList())

            assertThat(sent.map { it.frameType() }).containsExactly("notes")
            assertThat(sent[0].asObject()["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    // -- Write-back handlers --------------------------------------------

    @Test fun `create_note answers note_created`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onCreateNote(SyncProtocol.CreateNote("uuid-9", "Ideas"))
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("note_created")
            assertThat(frame["note"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
                .isEqualTo("Ideas")
        }
    }

    @Test fun `a failed create answers action_result ok false`() = runTest {
        val source = FakeNoteSyncSource()
        source.createResult = { _, _ ->
            CreateNoteResult.Failed("internal", "This note couldn't be created.")
        }
        runNoteEngine(source) { engine, sent ->
            engine.onCreateNote(SyncProtocol.CreateNote("uuid-9", "Ideas"))
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("action_result")
            assertThat(frame["action"]?.jsonPrimitive?.content).isEqualTo("create_note")
            assertThat(frame["ok"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    @Test fun `save_note_message answers note_message_saved`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onSaveNoteMessage(
                SyncProtocol.SaveNoteMessage("uuid-1", "7", "hello"),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("note_message_saved")
            assertThat(frame["client_message_id"]?.jsonPrimitive?.content).isEqualTo("uuid-1")
            assertThat(frame["message_id"]?.jsonPrimitive?.content).isEqualTo("42")
            assertThat(source.calls).containsExactly("saveNoteMessage:7")
        }
    }

    @Test fun `save_note_message to an unknown note answers note_message_failed`() = runTest {
        val source = FakeNoteSyncSource()
        source.saveResult = { _, _, _, _ ->
            SaveNoteMessageResult.Failed("unknown_note", "This note doesn't exist.")
        }
        runNoteEngine(source) { engine, sent ->
            engine.onSaveNoteMessage(
                SyncProtocol.SaveNoteMessage("uuid-1", "7", "hello"),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("note_message_failed")
            assertThat(frame["code"]?.jsonPrimitive?.content).isEqualTo("unknown_note")
        }
    }

    @Test fun `save_note_message with an unparseable note id answers unknown_note`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onSaveNoteMessage(
                SyncProtocol.SaveNoteMessage("uuid-1", "abc", "hello"),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            assertThat(sent.single().asObject()["code"]?.jsonPrimitive?.content)
                .isEqualTo("unknown_note")
            assertThat(source.calls).isEmpty()
        }
    }

    @Test fun `edit_note_message answers action_result with both ids`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onEditNoteMessage(
                SyncProtocol.EditNoteMessage("7", "42", "Fixed"),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("action_result")
            assertThat(frame["action"]?.jsonPrimitive?.content).isEqualTo("edit_note_message")
            assertThat(frame["conversation_id"]?.jsonPrimitive?.content).isEqualTo("7")
            assertThat(frame["message_id"]?.jsonPrimitive?.content).isEqualTo("42")
            assertThat(frame["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(source.calls).containsExactly("editNoteMessage:7:42")
        }
    }

    @Test fun `edit_note_message with an unparseable message id answers unknown_message`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onEditNoteMessage(
                SyncProtocol.EditNoteMessage("7", "abc", "Fixed"),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["ok"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(frame["code"]?.jsonPrimitive?.content).isEqualTo("unknown_message")
            assertThat(source.calls).isEmpty()
        }
    }

    @Test fun `rename delete pin and archive answer action_result`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onRenameNote(SyncProtocol.RenameNote("7", "New"))
            engine.onDeleteNote(SyncProtocol.DeleteNote("7"))
            engine.onDeleteNoteMessage(SyncProtocol.DeleteNoteMessage("7", "42"))
            engine.onSetNotePin(SyncProtocol.SetNotePin("7", true))
            engine.onSetNoteArchived(SyncProtocol.SetNoteArchived("7", false))
            advanceUntilIdle()

            assertThat(sent).hasSize(5)
            assertThat(sent.map { it.asObject()["action"]?.jsonPrimitive?.content })
                .containsExactly(
                    "rename_note",
                    "delete_note",
                    "delete_note_message",
                    "set_note_pin",
                    "set_note_archived",
                ).inOrder()
            sent.forEach {
                assertThat(it.asObject()["ok"]?.jsonPrimitive?.content).isEqualTo("true")
            }
            assertThat(source.calls).containsExactly(
                "renameNote:7",
                "deleteNote:7",
                "deleteNoteMessage:7:42",
                "setNotePinned:7:true",
                "setNoteArchived:7:false",
            ).inOrder()
        }
    }

    @Test fun `note_messages_request answers one page`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        source.store(sampleNoteMessage(42, 7))
        source.store(sampleNoteMessage(41, 7))
        runNoteEngine(source) { engine, sent ->
            engine.onNoteMessagesRequest(
                SyncProtocol.NoteMessagesRequest(noteId = "7", limit = 500),
            )
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            val frame = sent.single().asObject()
            assertThat(frame["type"]?.jsonPrimitive?.content).isEqualTo("note_messages_batch")
            assertThat(frame["messages"]?.jsonArray?.map { it.jsonObject["id"]?.jsonPrimitive?.content })
                .containsExactly("42", "41").inOrder()
            assertThat(frame["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    @Test fun `note_messages_request for an unknown note answers an error`() = runTest {
        val source = FakeNoteSyncSource()
        runNoteEngine(source) { engine, sent ->
            engine.onNoteMessagesRequest(SyncProtocol.NoteMessagesRequest(noteId = "7"))
            advanceUntilIdle()

            assertThat(sent).hasSize(1)
            assertThat(sent.single().frameType()).isEqualTo("error")
        }
    }

    // -- Live collectors --------------------------------------------------

    @Test fun `collectNotes pushes note_new note_updated and note_deleted`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        runNoteEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            // The first emission is the baseline — silent.
            assertThat(sent).isEmpty()

            source.notesFlow.value = listOf(sampleNote(7), sampleNote(8))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("note_new")
            assertThat(sent.single().asObject()["note"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                .isEqualTo("8")

            sent.clear()
            source.notesFlow.value = listOf(sampleNote(7, "Renamed"), sampleNote(8))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("note_updated")

            sent.clear()
            source.notesFlow.value = listOf(sampleNote(7, "Renamed"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("note_deleted")
            assertThat(sent.single().asObject()["note_id"]?.jsonPrimitive?.content).isEqualTo("8")
        }
    }

    @Test fun `a note this session created gets no duplicate note_new`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        runNoteEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            engine.onCreateNote(SyncProtocol.CreateNote("uuid-9", "Ideas"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("note_created")

            sent.clear()
            // The collector now sees the note the create just made —
            // the skip-set swallows the `note_new` for this session.
            source.notesFlow.value = listOf(sampleNote(7), sampleNote(99, "Ideas"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()
        }
    }

    @Test fun `a new entry in a known note pushes note_message_new`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        runNoteEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()

            // A new entry lands: the preview changes + the count grows.
            source.store(sampleNoteMessage(42, 7))
            source.notesFlow.value = listOf(
                sampleNote(7).copy(
                    lastMessageAt = 1_794_670_200_000L,
                    lastMessagePreview = "entry 42",
                    messageCount = 4,
                ),
            )
            advanceUntilIdle()

            assertThat(sent.map { it.frameType() }).containsExactly(
                "note_updated",
                "note_message_new",
            ).inOrder()
            val pushed = sent[1].asObject()
            assertThat(pushed["message"]?.jsonObject?.get("id")?.jsonPrimitive?.content)
                .isEqualTo("42")
        }
    }

    @Test fun `the edits collector pushes note_message_updated on a first edit`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        source.store(sampleNoteMessage(42, 7, body = "Fixed", edited = true))
        runNoteEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            // The row APPEARS in a later emission: a first edit, pushed.
            source.editedFlow.value = listOf(EditedNoteMessage(42L, 7L, "Fixed"))
            advanceUntilIdle()

            assertThat(sent.map { it.frameType() }).containsExactly("note_message_updated")
            val pushed = sent.single().asObject()
            assertThat(pushed["message"]?.jsonObject?.get("body")?.jsonPrimitive?.content)
                .isEqualTo("Fixed")
            assertThat(pushed["message"]?.jsonObject?.get("edited")?.jsonPrimitive?.content)
                .isEqualTo("true")
        }
    }

    @Test fun `the edits collector stays silent for an unchanged baseline row`() = runTest {
        val source = FakeNoteSyncSource()
        source.notesFlow.value = listOf(sampleNote(7))
        source.store(sampleNoteMessage(42, 7, body = "Fixed", edited = true))
        source.editedFlow.value = listOf(EditedNoteMessage(42L, 7L, "Fixed"))
        runNoteEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            // The row was already edited at baseline — no push.
            assertThat(sent).isEmpty()

            // Same body re-emitted: still no push.
            source.editedFlow.value = listOf(EditedNoteMessage(42L, 7L, "Fixed"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            // A changed body IS a re-edit: pushed.
            source.store(sampleNoteMessage(42, 7, body = "Fixed again", edited = true))
            source.editedFlow.value = listOf(EditedNoteMessage(42L, 7L, "Fixed again"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("note_message_updated")
            assertThat(sent.single().asObject()["message"]?.jsonObject?.get("body")?.jsonPrimitive?.content)
                .isEqualTo("Fixed again")
        }
    }
}
private fun sampleNote(id: Long, title: String = "Groceries") = SyncedNote(
    id = id.toString(),
    title = title,
    createdAt = 1_794_670_000_000L,
    updatedAt = 1_794_670_100_000L,
    lastMessageAt = 1_794_670_100_000L,
    lastMessagePreview = "Milk",
    pinned = false,
    archived = false,
    messageCount = 3,
)

private fun sampleNoteMessage(
    id: Long,
    noteId: Long,
    body: String = "entry $id",
    edited: Boolean = false,
) = SyncedNoteMessage(
    id = id.toString(),
    noteId = noteId.toString(),
    body = body,
    date = 1_794_670_100_000L,
    attachments = emptyList(),
    edited = edited,
)
