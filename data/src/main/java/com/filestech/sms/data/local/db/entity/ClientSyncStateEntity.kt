package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Client mode — one row per linked hub: which sync mode this device
 * chose for that hub, and where its message stream stands.
 *
 * [globalLastMessageId] is the cursor: the max hub message id
 * durably stored (never the server's `sync_complete` cursor — a live
 * message could still be in flight past it). `"0"` means "no cursor
 * yet". In `new_only` mode the `sync_started` cursor is saved
 * immediately as the "now" baseline, since there are no batches to
 * advance it. An older cursor can only re-deliver by id (the mirror
 * upserts idempotently), never skip.
 *
 * V1 links one hub; the per-hub shape supports multi-hub later
 * without a data-model redo.
 */
@Entity(tableName = "client_sync_state")
data class ClientSyncStateEntity(
    /** The hub's id (from `auth_ok`), the row's identity. */
    @PrimaryKey
    @ColumnInfo(name = "hub_id")
    val hubId: String,
    /** `full` or `new_only` — the mode picked in the sync-mode prompt. */
    @ColumnInfo(name = "mode") val mode: String,
    /** Max hub message id stored, as a decimal string; `"0"` when none yet. */
    @ColumnInfo(name = "global_last_message_id") val globalLastMessageId: String = "0",
    /** True once a first sync run completed for this hub. */
    @ColumnInfo(name = "initial_sync_done") val initialSyncDone: Boolean = false,
    /** Epoch ms of the last completed sync run, or null before the first. */
    @ColumnInfo(name = "last_sync_at") val lastSyncAt: Long? = null,
)
