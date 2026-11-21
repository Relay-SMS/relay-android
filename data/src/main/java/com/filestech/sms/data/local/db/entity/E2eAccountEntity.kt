package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * E2E encryption (schema v22) — our Olm Account as a vodozemac encrypted pickle.
 *
 * Exactly one row ever exists, with the fixed id 1: the Account holds this install's
 * Curve25519 + Ed25519 identity keys and the one-time-key pool, and there is one
 * identity per install. The single-row rule is enforced in code
 * ([com.filestech.sms.data.local.db.dao.E2eAccountDao] always reads/writes id 1) —
 * Room cannot express the design doc's `CHECK (id = 1)` on an entity, so the migration
 * SQL repeats the plain Room-generated form instead.
 *
 * The pickle is encrypted with the Keystore-wrapped pickle key
 * ([com.filestech.sms.e2e.E2eKeyManager]); the row itself sits inside the SQLCipher
 * database, so the Account is protected by both envelopes. It is never synced, never
 * backed up, never logged.
 */
@Entity(tableName = "e2e_account")
data class E2eAccountEntity(
    /** Always [SINGLE_ROW_ID] — the table holds one row, this install's Account. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = SINGLE_ROW_ID,
    /** The vodozemac encrypted Account pickle (opaque string). */
    @ColumnInfo(name = "pickle") val pickle: String,
) {
    companion object {
        const val SINGLE_ROW_ID = 1
    }
}
