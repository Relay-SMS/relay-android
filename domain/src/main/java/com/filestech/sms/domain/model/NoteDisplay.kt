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
 *
 * Round 47 — the mapped message carries `encrypted = true`: a note
 * entry IS encrypted (whole-DB SQLCipher + the AES-256-GCM
 * attachment envelope), just at rest rather than on the wire. The
 * bubble colour rule keys on this flag, so a note entry keeps the
 * blue default instead of turning green like a plain SMS — green
 * means "sent in the clear over the carrier", and a note never
 * leaves the device. Side effect, and intended: a text-only entry
 * shows the lock on its timestamp line, like any encrypted message.
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
    encrypted = true,
)

/**
 * Notes — the longest a note title may be. The list row shows the
 * title on one line, so a cap keeps it readable; 40 matches the
 * group-name cap ([GroupName.MAX]).
 */
const val NOTE_TITLE_MAX_LENGTH: Int = 40
