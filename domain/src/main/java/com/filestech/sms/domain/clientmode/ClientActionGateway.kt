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
}
