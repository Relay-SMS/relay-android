package com.filestech.sms.domain.pdf

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message

/**
 * Port domaine : rend une conversation en PDF partageable.
 *
 * [com.filestech.sms.domain.usecase.ExportConversationPdfUseCase] en dépend ; l'implémentation
 * [com.filestech.sms.data.pdf.ConversationPdfExporter] utilise le framework `PdfDocument` +
 * `FileProvider` (Android) et projette le résultat en [PdfExportResult] — `domain/` reste sans
 * import Android.
 */
interface PdfExporter {

    /** Rend [conversation] + [messages] en PDF, renvoie l'URI de partage + le nombre de pages. */
    suspend fun export(conversation: Conversation, messages: List<Message>): Outcome<PdfExportResult>

    /**
     * Notes — renders a note to PDF: [title] is the note's title
     * (the header + the filename stem), [messages] are the note's
     * entries mapped with `NoteMessage.toDisplayMessage()`. Same
     * layout as [export]; the header names no participants, and the
     * file is prefixed `smsrelay_note_` instead of
     * `smsrelay_conversation_`.
     */
    suspend fun exportNote(title: String, messages: List<Message>): Outcome<PdfExportResult>
}
