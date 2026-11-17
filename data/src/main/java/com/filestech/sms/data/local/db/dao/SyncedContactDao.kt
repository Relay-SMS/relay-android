package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.filestech.sms.data.local.db.entity.SyncedContactEntity

/**
 * Client mode — the synced-contacts cache. Schema only in C2: the
 * table is created by the v17→v18 migration and wiped with the rest
 * of the cache. C4 fills it from the `contacts` snapshot + live
 * deltas (`ClientContactSync`) and reads it through the mode-aware
 * `ContactRepository`.
 */
@Dao
interface SyncedContactDao {

    /** Inserts or replaces one synced contact row. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: SyncedContactEntity)

    /** Inserts or replaces a batch of synced contact rows. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(contacts: List<SyncedContactEntity>)

    /** One row by normalized number, or null. */
    @Query("SELECT * FROM synced_contacts WHERE number = :number")
    suspend fun get(number: String): SyncedContactEntity?

    /** Every row, display-name order (the compose picker's list). */
    @Query("SELECT * FROM synced_contacts ORDER BY display_name COLLATE NOCASE ASC")
    suspend fun listAll(): List<SyncedContactEntity>

    /** Every stored number (the snapshot prune's input). */
    @Query("SELECT number FROM synced_contacts")
    suspend fun numbers(): List<String>

    /** Deletes the rows for these numbers (never call with an empty list). */
    @Query("DELETE FROM synced_contacts WHERE number IN (:numbers)")
    suspend fun deleteNumbers(numbers: List<String>)

    /**
     * Points one row at its downloaded avatar file (the relative path
     * under `synced_avatars/`), or clears it (null). A plain update:
     * it never creates a row, so an avatar that lands after its
     * contact was wiped cannot resurrect it.
     */
    @Query("UPDATE synced_contacts SET avatar_local_path = :path WHERE number = :number")
    suspend fun setAvatarPath(number: String, path: String?)

    /** Wipes the synced address book (Re-sync / Unlink / mode switch). */
    @Query("DELETE FROM synced_contacts")
    suspend fun deleteAll()
}
