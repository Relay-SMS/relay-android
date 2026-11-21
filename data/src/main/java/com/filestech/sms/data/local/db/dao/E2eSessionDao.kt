package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.E2eSessionEntity

/**
 * DAO for [E2eSessionEntity] — the Olm session pickles, up to 3 per peer (current +
 * fallbacks).
 *
 * All access goes through [com.filestech.sms.e2e.E2eSessionStore], which owns the
 * pickle-after-every-use rule and the 3-session cap. The store upserts via
 * [findByAddressAndSessionId] + [insert]/[update] rather than an `ON CONFLICT`
 * upsert: this codebase has no UPSERT SQL anywhere, and the explicit path keeps
 * `created_at` (a REPLACE would regenerate the row id and lose it).
 */
@Dao
interface E2eSessionDao {

    @Query("SELECT * FROM e2e_sessions WHERE address = :address AND session_id = :sessionId LIMIT 1")
    suspend fun findByAddressAndSessionId(address: String, sessionId: String): E2eSessionEntity?

    /** The peer's current session, if any — the one new messages encrypt with. */
    @Query("SELECT * FROM e2e_sessions WHERE address = :address AND is_current = 1 LIMIT 1")
    suspend fun currentSession(address: String): E2eSessionEntity?

    /**
     * Every session for a peer, current first, then newest-used first — the order
     * decryption tries them in (current, then fallbacks newest first).
     */
    @Query(
        """
        SELECT * FROM e2e_sessions WHERE address = :address
        ORDER BY is_current DESC, last_used_at DESC
        """,
    )
    suspend fun sessionsForPeer(address: String): List<E2eSessionEntity>

    @Query("SELECT COUNT(*) FROM e2e_sessions WHERE address = :address")
    suspend fun countForPeer(address: String): Int

    @Insert
    suspend fun insert(session: E2eSessionEntity): Long

    @Update
    suspend fun update(session: E2eSessionEntity)

    /** Clears the current flag on all of a peer's sessions (before marking a new one). */
    @Query("UPDATE e2e_sessions SET is_current = 0 WHERE address = :address")
    suspend fun clearCurrent(address: String)

    @Query(
        "UPDATE e2e_sessions SET is_current = 1 WHERE address = :address AND session_id = :sessionId",
    )
    suspend fun markCurrent(address: String, sessionId: String)

    /**
     * Re-writes a session's pickle after an encrypt/decrypt advanced its ratchet.
     * The row keeps its id and `created_at`; only the pickle and the use time move.
     */
    @Query(
        """
        UPDATE e2e_sessions SET pickle = :pickle, last_used_at = :lastUsedAt
         WHERE address = :address AND session_id = :sessionId
        """,
    )
    suspend fun updatePickle(address: String, sessionId: String, pickle: String, lastUsedAt: Long)

    /**
     * Drops a peer's oldest fallback sessions, keeping the [keepFallbacks] most
     * recently used ones. The current session is never pruned by this query.
     */
    @Query(
        """
        DELETE FROM e2e_sessions
         WHERE address = :address AND is_current = 0
           AND id NOT IN (
               SELECT id FROM e2e_sessions
                WHERE address = :address AND is_current = 0
                ORDER BY last_used_at DESC LIMIT :keepFallbacks
           )
        """,
    )
    suspend fun pruneFallbacks(address: String, keepFallbacks: Int): Int

    /** Deletes all of a peer's sessions (panic wipe / reset). */
    @Query("DELETE FROM e2e_sessions WHERE address = :address")
    suspend fun deleteByAddress(address: String): Int
}
