package com.filestech.sms.domain.clientmode

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome

/**
 * Client mode C3 — one write-back action a linked device sends to the hub
 * phone. Every action carries LOCAL ids: the sender resolves them to hub
 * ids at send time (the domain layer must never see hub ids).
 */
sealed interface ClientAction {
    /** Marks one conversation read (`mark_read`). */
    data class MarkRead(val localConversationId: Long) : ClientAction

    /** Marks every conversation read (`mark_all_read`). */
    data object MarkAllRead : ClientAction

    /** Pins or unpins a conversation (`set_pin`). */
    data class SetPin(val localConversationId: Long, val value: Boolean) : ClientAction

    /** Archives or unarchives a conversation (`set_archived`). */
    data class SetArchived(val localConversationId: Long, val value: Boolean) : ClientAction

    /** Mutes or unmutes a conversation (`set_muted`). */
    data class SetMuted(val localConversationId: Long, val value: Boolean) : ClientAction

    /** Blocks or unblocks every address of a conversation (`set_blocked`). */
    data class SetBlocked(val localConversationId: Long, val value: Boolean) : ClientAction

    /** Sets (or clears, with `emoji == null`) a message's reaction (`set_reaction`). */
    data class SetReaction(
        val localConversationId: Long,
        val localMessageId: Long,
        val emoji: String?,
    ) : ClientAction

    /** Pins a message in a conversation, or unpins (`localMessageId == null`) (`set_pinned_message`). */
    data class SetPinnedMessage(
        val localConversationId: Long,
        val localMessageId: Long?,
    ) : ClientAction

    /** Phase 2 — pins or unpins a note (`set_note_pin`). */
    data class SetNotePin(val localNoteId: Long, val value: Boolean) : ClientAction

    /** Phase 2 — archives or unarchives a note (`set_note_archived`). */
    data class SetNoteArchived(val localNoteId: Long, val value: Boolean) : ClientAction

    /** Phase 2 — renames a note (`rename_note`). */
    data class RenameNote(val localNoteId: Long, val title: String) : ClientAction

    /**
     * Phase 2 — edits a note entry's text (`edit_note_message`).
     * The hub answers `action_result` on success AND on failure
     * (like every other write-back action), and ALSO broadcasts
     * `note_message_updated` to all sessions; either success
     * signal drops the pending revert, whichever arrives first.
     * A refusal (`action_result` `ok:false`) runs the revert.
     */
    data class EditNoteMessage(
        val localNoteId: Long,
        val localMessageId: Long,
        val body: String,
    ) : ClientAction
}

/**
 * Client mode C3 — the domain-facing write-back gateway: the repository
 * and use cases apply their optimistic change locally, then hand the
 * action to the sender, which owns the frame and the revert. The hub is
 * authoritative: when it answers `ok:false`, the sender runs [revert].
 *
 * The real implementation is [com.filestech.sms.sync.client.ClientActionSender]
 * (app module — the domain layer cannot import app classes, and the gateway
 * is the seam). Bound in `ClientSyncModule`; Hilt always provides the real
 * one in production, so [NoOpClientActionGateway] only exists as a default
 * constructor value so existing tests keep compiling untouched.
 */
interface ClientActionGateway {

    /** True when the device is a linked device (not the hub phone). */
    suspend fun isClientMode(): Boolean

    /**
     * Sends one write-back frame. The caller already applied the
     * optimistic change; [revert] undoes it. Returns false when the
     * frame could not be sent (offline, or an id that could not be
     * resolved to a hub id) — the sender runs [revert] before
     * returning, so the caller never reverts itself. On success the
     * sender holds [revert] until the hub's `action_result` answers:
     * `ok:false` runs it, `ok:true` drops it. Pass no revert only
     * when there is nothing to undo (`mark_all_read` — the hub never
     * refuses it, and the next sync re-aligns the counts anyway).
     */
    suspend fun sendAction(action: ClientAction, revert: (suspend () -> Unit)? = null): Boolean

    /**
     * Sends `create_conversation` and awaits the hub's answer (up to
     * 20 s). Returns the LOCAL conversation id — the engine upserts
     * the conversation before the answer is delivered, so the row
     * exists by the time this returns. Offline → [AppError.ClientOffline].
     */
    suspend fun createConversation(addresses: List<String>, customName: String?): Outcome<Long>

    /**
     * Phase 2 — sends `create_note` and awaits the hub's answer (up
     * to 20 s). Returns the LOCAL note id — the engine upserts the
     * note before the answer is delivered, so the row exists by the
     * time this returns. Offline → [AppError.ClientOffline].
     */
    suspend fun createNote(title: String): Outcome<Long>

    /**
     * Phase 2 — sends `delete_note` and awaits the hub's answer (up
     * to 20 s). The caller deletes its local row only on success —
     * the delete is applied on the hub first. Offline → [AppError.ClientOffline].
     */
    suspend fun deleteNote(localNoteId: Long): Outcome<Unit>

    /**
     * Phase 2 — sends `delete_note_message` and awaits the hub's
     * answer (up to 20 s). The caller deletes its local row only on
     * success. Offline → [AppError.ClientOffline].
     */
    suspend fun deleteNoteMessage(localNoteId: Long, localMessageId: Long): Outcome<Unit>
}

/**
 * The no-op [ClientActionGateway] used as a default constructor value.
 * Reports hub mode, so every client branch that consults it keeps the
 * hub path — and fails loudly if a client action is ever attempted.
 */
object NoOpClientActionGateway : ClientActionGateway {
    override suspend fun isClientMode(): Boolean = false

    override suspend fun sendAction(action: ClientAction, revert: (suspend () -> Unit)?): Boolean = false

    override suspend fun createConversation(addresses: List<String>, customName: String?): Outcome<Long> =
        Outcome.Failure(AppError.Validation("client action gateway not provided"))

    override suspend fun createNote(title: String): Outcome<Long> =
        Outcome.Failure(AppError.Validation("client action gateway not provided"))

    override suspend fun deleteNote(localNoteId: Long): Outcome<Unit> =
        Outcome.Failure(AppError.Validation("client action gateway not provided"))

    override suspend fun deleteNoteMessage(localNoteId: Long, localMessageId: Long): Outcome<Unit> =
        Outcome.Failure(AppError.Validation("client action gateway not provided"))
}
