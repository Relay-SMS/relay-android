package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.E2eAccountEntity

/**
 * DAO for [E2eAccountEntity] — the single-row table holding our Olm Account pickle.
 *
 * All access goes through [com.filestech.sms.e2e.E2eAccountManager]: the pickle is
 * meaningless without the Keystore-wrapped pickle key, and the manager owns the
 * load/save lifecycle (lazy creation on first E2E use, re-save after every
 * one-time-key pool change).
 */
@Dao
interface E2eAccountDao {

    /** The Account pickle, or null before the first E2E use created the Account. */
    @Query("SELECT * FROM e2e_account WHERE id = 1")
    suspend fun get(): E2eAccountEntity?

    /** Inserts the Account row, or replaces it — the id is always 1. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: E2eAccountEntity)

    /** Deletes the Account row (panic wipe / reset — the identity is gone for good). */
    @Query("DELETE FROM e2e_account WHERE id = 1")
    suspend fun delete()
}
