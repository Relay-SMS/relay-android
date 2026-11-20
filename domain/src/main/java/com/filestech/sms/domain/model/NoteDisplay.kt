package com.filestech.sms.domain.model

/**
 * Notes — the display mapping from a note entry to the [Message] the
 * bubble components render.
 *
 * A note entry is the user's own text and/or attachments, with no
 * delivery lifecycle: it renders as an OUTGOING message (the
 * self-chat pattern, like Saved Messages) with status RECEIVED and
 * no status line. The mapped message is a RENDER projection only —
 * it must never be passed into an SMS path (send / retry / schedule
 * / reaction dispatch): those UI paths are disabled in the note
 * thread, and the note repository is the only writer of note rows.
 *
 * Shared by the note thread UI and the note PDF export, so the two
 * can never disagree about what an entry looks like.
 */
fun NoteMessage.toDisplayMessage(): Message = Message(
    id = id,
    conversationId = noteId,
    address = "",
    body = body,
    type = if (attachments.isEmpty()) Message.Type.SMS else Message.Type.MMS,
    direction = Message.Direction.OUTGOING,
    date = date,
    dateSent = date,
    read = true,
    starred = false,
    status = Message.Status.RECEIVED,
    errorCode = null,
    attachmentsCount = attachments.size,
    subId = null,
    scheduledAt = null,
    attachments = attachments,
)

/**
 * Notes — the longest a note title may be. The list row shows the
 * title on one line, so a cap keeps it readable; 40 matches the
 * group-name cap ([GroupName.MAX]).
 */
const val NOTE_TITLE_MAX_LENGTH: Int = 40
