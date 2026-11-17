package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Client mode — the history-walk frontier of one synced
 * conversation: how far back its messages have been pulled from the
 * hub.
 *
 * Kept in a side table so [ConversationEntity] gains only the hub-id
 * column. A conversation is created here the first time a
 * `messages_batch` for it lands (or when the engine starts walking
 * it): [minSyncedId] is the oldest hub message id stored for it
 * (`"0"` = newest page not yet walked), [historyComplete] is true
 * once a page arrived with `has_more: false` — except in a delta run,
 * whose batches carry `has_more: false` without saying anything about
 * the unfinished walk (the engine never moves the frontier for a
 * delta stream batch).
 *
 * Gap-fill walks every incomplete conversation after `sync_complete`;
 * opening a thread with incomplete history walks it on demand.
 */
@Entity(tableName = "client_conversation_state")
data class ClientConversationStateEntity(
    /** The hub conversation id (wire `conversation_id`). */
    @PrimaryKey
    @ColumnInfo(name = "hub_conversation_id")
    val hubConversationId: Long,
    /** Oldest hub message id stored for this conversation; `"0"` when un-walked. */
    @ColumnInfo(name = "min_synced_id") val minSyncedId: String = "0",
    /** True once the walk reached the oldest message (a page with `has_more: false`). */
    @ColumnInfo(name = "history_complete") val historyComplete: Boolean = false,
)
