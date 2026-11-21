package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Round 36 phase 2 — the notes-sync wire types: every new frame
 * encodes with its `type` field and parses back to the same value.
 * The frame names and payloads are the fixed §4 contract — the
 * desktop builder implements the other end from the same doc, so a
 * rename here breaks the link (and the desktop's
 * `SYNC_MESSAGE_TYPES` allowlist must list every server→client
 * type, or the frame is silently dropped).
 */
class SyncProtocolNotesTest {

    private fun sampleNote(id: String = "7") = SyncedNote(
        id = id,
        title = "Groceries",
        createdAt = 1_794_670_000_000L,
        updatedAt = 1_794_670_100_000L,
        lastMessageAt = 1_794_670_100_000L,
        lastMessagePreview = "Milk",
        pinned = true,
        archived = false,
        messageCount = 3,
    )

    private fun sampleMessage(id: String = "42", edited: Boolean = false) = SyncedNoteMessage(
        id = id,
        noteId = "7",
        body = "Milk, eggs",
        date = 1_794_670_100_000L,
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
        edited = edited,
    )

    // -- Server → client: snapshot + live frames ----------------------

    @Test fun `notes encodes a batch with has_more`() {
        val encoded = SyncProtocol.encodeNotes(
            SyncProtocol.Notes(notes = listOf(sampleNote()), hasMore = true),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("notes")
        assertThat(obj["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
        val note = obj["notes"]?.jsonArray?.get(0)?.jsonObject
        assertThat(note?.get("id")?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(note?.get("title")?.jsonPrimitive?.content).isEqualTo("Groceries")
        assertThat(note?.get("pinned")?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(note?.get("message_count")?.jsonPrimitive?.content).isEqualTo("3")
        // No bubble color crosses the wire — it does not sync (§4.1).
        assertThat(encoded).doesNotContain("bubble")
    }

    @Test fun `note_messages_batch encodes the page with its cursor`() {
        val encoded = SyncProtocol.encodeNoteMessagesBatch(
            SyncProtocol.NoteMessagesBatch(
                noteId = "7",
                messages = listOf(sampleMessage()),
                hasMore = true,
                nextBeforeId = "40",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("note_messages_batch")
        assertThat(obj["note_id"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(obj["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
        assertThat(obj["next_before_id"]?.jsonPrimitive?.content).isEqualTo("40")
        val message = obj["messages"]?.jsonArray?.get(0)?.jsonObject
        assertThat(message?.get("body")?.jsonPrimitive?.content).isEqualTo("Milk, eggs")
        assertThat(message?.get("attachments")?.jsonArray?.size).isEqualTo(1)
    }

    @Test fun `note_messages_batch omits next_before_id on the last page`() {
        val encoded = SyncProtocol.encodeNoteMessagesBatch(
            SyncProtocol.NoteMessagesBatch(
                noteId = "7",
                messages = listOf(sampleMessage()),
                hasMore = false,
            ),
        )
        assertThat(encoded).doesNotContain("next_before_id")
    }

    @Test fun `note_created note_new and note_updated encode the full row`() {
        for (type in listOf("note_created", "note_new", "note_updated")) {
            val encoded = when (type) {
                "note_created" -> SyncProtocol.encodeNoteCreated(SyncProtocol.NoteCreated(sampleNote()))
                "note_new" -> SyncProtocol.encodeNoteNew(SyncProtocol.NoteNew(sampleNote()))
                else -> SyncProtocol.encodeNoteUpdated(SyncProtocol.NoteUpdated(sampleNote()))
            }
            val obj = Json.parseToJsonElement(encoded).jsonObject
            assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo(type)
            assertThat(obj["note"]?.jsonObject?.get("title")?.jsonPrimitive?.content)
                .isEqualTo("Groceries")
        }
    }

    @Test fun `note_deleted encodes the id only`() {
        val encoded = SyncProtocol.encodeNoteDeleted(SyncProtocol.NoteDeleted(noteId = "7"))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("note_deleted")
        assertThat(obj["note_id"]?.jsonPrimitive?.content).isEqualTo("7")
    }

    @Test fun `note_message_new and note_message_updated encode the full row`() {
        val newEncoded = SyncProtocol.encodeNoteMessageNew(
            SyncProtocol.NoteMessageNew(message = sampleMessage()),
        )
        assertThat(Json.parseToJsonElement(newEncoded).jsonObject["type"]?.jsonPrimitive?.content)
            .isEqualTo("note_message_new")
        val updatedEncoded = SyncProtocol.encodeNoteMessageUpdated(
            SyncProtocol.NoteMessageUpdated(message = sampleMessage(edited = true)),
        )
        val obj = Json.parseToJsonElement(updatedEncoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("note_message_updated")
        assertThat(obj["message"]?.jsonObject?.get("edited")?.jsonPrimitive?.content)
            .isEqualTo("true")
    }

    @Test fun `an unedited entry omits the edited key`() {
        val encoded = SyncProtocol.encodeNoteMessageNew(
            SyncProtocol.NoteMessageNew(message = sampleMessage(edited = false)),
        )
        // encodeDefaults = false: `edited: false` is the default, so
        // older clients (and older hubs) never see the key at all.
        assertThat(encoded).doesNotContain("edited")
    }

    @Test fun `note_message_deleted encodes both ids`() {
        val encoded = SyncProtocol.encodeNoteMessageDeleted(
            SyncProtocol.NoteMessageDeleted(noteId = "7", messageId = "42"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("note_message_deleted")
        assertThat(obj["note_id"]?.jsonPrimitive?.content).isEqualTo("7")
        assertThat(obj["message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    @Test fun `note_message_saved and note_message_failed encode the ack pair`() {
        val saved = SyncProtocol.encodeNoteMessageSaved(
            SyncProtocol.NoteMessageSaved(
                clientMessageId = "uuid-1",
                messageId = "42",
                noteId = "7",
            ),
        )
        val savedObj = Json.parseToJsonElement(saved).jsonObject
        assertThat(savedObj["type"]?.jsonPrimitive?.content).isEqualTo("note_message_saved")
        assertThat(savedObj["message_id"]?.jsonPrimitive?.content).isEqualTo("42")

        val failed = SyncProtocol.encodeNoteMessageFailed(
            SyncProtocol.NoteMessageFailed(
                clientMessageId = "uuid-1",
                noteId = "7",
                code = SyncProtocol.NoteMessageFailed.UNKNOWN_NOTE,
                message = "No note carries that id.",
            ),
        )
        val failedObj = Json.parseToJsonElement(failed).jsonObject
        assertThat(failedObj["type"]?.jsonPrimitive?.content).isEqualTo("note_message_failed")
        assertThat(failedObj["code"]?.jsonPrimitive?.content).isEqualTo("unknown_note")
    }

    // -- Cursors --------------------------------------------------------

    @Test fun `sync_started encodes the note cursor and total`() {
        val encoded = SyncProtocol.encodeSyncStarted(
            SyncProtocol.SyncStarted(
                mode = SyncProtocol.SyncRequest.FULL,
                totalConversations = 5,
                totalMessages = 100L,
                cursorMessageId = "900",
                hubIsDefaultSms = true,
                cursorNoteMessageId = "42",
                totalNotes = 2,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["cursor_note_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
        assertThat(obj["total_notes"]?.jsonPrimitive?.content).isEqualTo("2")
    }

    @Test fun `sync_complete encodes the note cursor`() {
        val encoded = SyncProtocol.encodeSyncComplete(
            SyncProtocol.SyncComplete(cursorMessageId = "900", cursorNoteMessageId = "42"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["cursor_note_message_id"]?.jsonPrimitive?.content).isEqualTo("42")
    }

    // -- Client → server: parse ----------------------------------------

    @Test fun `sync_request parses the note cursor`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"sync_request","mode":"full","since_message_id":"900","since_note_message_id":"42"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SyncRequest(
                SyncProtocol.SyncRequest(
                    mode = "full",
                    sinceMessageId = "900",
                    sinceNoteMessageId = "42",
                ),
            ),
        )
    }

    @Test fun `sync_request without a note cursor parses to null`() {
        assertThat(
            SyncProtocol.parse("""{"type":"sync_request","mode":"full","since_message_id":"900"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.SyncRequest(
                SyncProtocol.SyncRequest(mode = "full", sinceMessageId = "900"),
            ),
        )
    }

    @Test fun `create_note parses the client key and title`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"create_note","client_note_id":"uuid-9","title":"Ideas"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.CreateNote(
                SyncProtocol.CreateNote(clientNoteId = "uuid-9", title = "Ideas"),
            ),
        )
    }

    @Test fun `save_note_message parses with and without attachments`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"save_note_message","client_message_id":"uuid-1","note_id":"7","body":"Hi"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SaveNoteMessage(
                SyncProtocol.SaveNoteMessage(
                    clientMessageId = "uuid-1",
                    noteId = "7",
                    body = "Hi",
                ),
            ),
        )
        assertThat(
            SyncProtocol.parse(
                """{"type":"save_note_message","client_message_id":"uuid-1","note_id":"7","body":"Hi",""" +
                    """"attachments":[{"upload_index":0,"mime_type":"image/jpeg","file_name":"a.jpg"}]}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.SaveNoteMessage(
                SyncProtocol.SaveNoteMessage(
                    clientMessageId = "uuid-1",
                    noteId = "7",
                    body = "Hi",
                    attachments = listOf(
                        SyncProtocol.SendAttachment(
                            uploadIndex = 0,
                            mimeType = "image/jpeg",
                            fileName = "a.jpg",
                        ),
                    ),
                ),
            ),
        )
    }

    @Test fun `edit_note_message parses the three fields and nothing else`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"edit_note_message","note_id":"7","message_id":"42","body":"Fixed"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.EditNoteMessage(
                SyncProtocol.EditNoteMessage(noteId = "7", messageId = "42", body = "Fixed"),
            ),
        )
    }

    @Test fun `rename_note delete_note and delete_note_message parse`() {
        assertThat(
            SyncProtocol.parse("""{"type":"rename_note","note_id":"7","title":"New"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.RenameNote(
                SyncProtocol.RenameNote(noteId = "7", title = "New"),
            ),
        )
        assertThat(SyncProtocol.parse("""{"type":"delete_note","note_id":"7"}"""))
            .isEqualTo(
                SyncProtocol.Parsed.DeleteNote(SyncProtocol.DeleteNote(noteId = "7")),
            )
        assertThat(
            SyncProtocol.parse(
                """{"type":"delete_note_message","note_id":"7","message_id":"42"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.DeleteNoteMessage(
                SyncProtocol.DeleteNoteMessage(noteId = "7", messageId = "42"),
            ),
        )
    }

    @Test fun `set_note_pin and set_note_archived parse the flag`() {
        assertThat(
            SyncProtocol.parse("""{"type":"set_note_pin","note_id":"7","value":true}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.SetNotePin(
                SyncProtocol.SetNotePin(noteId = "7", value = true),
            ),
        )
        assertThat(
            SyncProtocol.parse("""{"type":"set_note_archived","note_id":"7","value":false}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.SetNoteArchived(
                SyncProtocol.SetNoteArchived(noteId = "7", value = false),
            ),
        )
    }

    @Test fun `note_messages_request parses with defaults and with paging`() {
        assertThat(
            SyncProtocol.parse("""{"type":"note_messages_request","note_id":"7"}"""),
        ).isEqualTo(
            SyncProtocol.Parsed.NoteMessagesRequest(
                SyncProtocol.NoteMessagesRequest(noteId = "7"),
            ),
        )
        assertThat(
            SyncProtocol.parse(
                """{"type":"note_messages_request","note_id":"7","before_id":"40","limit":50}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.NoteMessagesRequest(
                SyncProtocol.NoteMessagesRequest(noteId = "7", beforeId = "40", limit = 50),
            ),
        )
    }

    @Test fun `attachment_upload_start parses the note kind`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"attachment_upload_start","client_message_id":"uuid-1","index":0,""" +
                    """"mime_type":"image/jpeg","size_bytes":100,"sha256":"abc","kind":"note"}""",
            ),
        ).isEqualTo(
            SyncProtocol.Parsed.AttachmentUploadStart(
                SyncProtocol.AttachmentUploadStart(
                    clientMessageId = "uuid-1",
                    index = 0,
                    mimeType = "image/jpeg",
                    sizeBytes = 100L,
                    sha256 = "abc",
                    kind = "note",
                ),
            ),
        )
    }

    @Test fun `attachment_upload_start without a kind parses to null (MMS behavior)`() {
        val parsed = SyncProtocol.parse(
            """{"type":"attachment_upload_start","client_message_id":"uuid-1","index":0,""" +
                """"mime_type":"image/jpeg","size_bytes":100,"sha256":"abc"}""",
        ) as SyncProtocol.Parsed.AttachmentUploadStart
        assertThat(parsed.value.kind).isNull()
    }

    @Test fun `a note frame missing a required field is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"create_note","title":"No key"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
        assertThat(
            SyncProtocol.parse(
                """{"type":"save_note_message","client_message_id":"uuid-1","note_id":"7"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }
}
