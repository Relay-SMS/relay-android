package com.filestech.sms.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.filestech.sms.data.local.db.entity.AttachmentEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attachment: AttachmentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT * FROM attachments WHERE message_id = :messageId")
    suspend fun findForMessage(messageId: Long): List<AttachmentEntity>

    /**
     * Slice 4 — lookup ponctuel d'une pièce jointe par id, pour le
     * téléchargement vers un appareil lié. Aucune garde coffre ici :
     * la colle vérifie l'appartenance (message + conversation
     * non-coffre) avant d'ouvrir les octets.
     */
    @Query("SELECT * FROM attachments WHERE id = :id")
    suspend fun findById(id: Long): AttachmentEntity?

    /**
     * Client mode (schema v18) — the attachment synced from the hub
     * attachment [hubId], or null. The mirror upserts synced
     * attachment metadata by this key so a re-sync never duplicates
     * a row — and never drops the downloaded `localUri` C5 fills in.
     */
    @Query("SELECT * FROM attachments WHERE hub_attachment_id = :hubId LIMIT 1")
    suspend fun findByHubAttachmentId(hubId: Long): AttachmentEntity?

    @Update
    suspend fun update(attachment: AttachmentEntity)

    /**
     * Feedback round 6 — bulk-fetch attachments for exactly the given
     * messages. Used by
     * [com.filestech.sms.data.repository.ConversationRepositoryImpl.observeMessagesWindow],
     * whose window is bounded: fetching the whole conversation there re-ran
     * a SQLCipher query over the entire history on every emission. The
     * caller chunks the ids — a window grown by repeated "load older" pages
     * can exceed SQLite's bound-variable cap for a single `IN` list.
     */
    @Query("SELECT * FROM attachments WHERE message_id IN (:messageIds)")
    suspend fun findForMessages(messageIds: List<Long>): List<AttachmentEntity>

    /**
     * Audit P-P0-1: bulk-fetch every attachment for a whole conversation in a single query.
     * Used by [com.filestech.sms.data.repository.ConversationRepositoryImpl.observeMessages]
     * which previously fired one [findForMessage] per audio row — an N+1 that exploded during
     * fresh-install imports (50 audio messages × N flow emissions = 50 N queries). The caller
     * groups the result by [AttachmentEntity.messageId] in memory; both reads share the same
     * `(message_id)` index so the join is index-only on the attachments side.
     */
    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.message_id
        WHERE m.conversation_id = :conversationId
        """,
    )
    suspend fun findForConversation(conversationId: Long): List<AttachmentEntity>

    /**
     * Issue #59 — les photos et vidéos d'une conversation, les plus récentes d'abord, pour l'écran du
     * même nom. Seules les pièces jointes sont lues, pas l'historique des messages. `hidden = 0` : les
     * mêmes messages que le fil (`MessageDao.observeForConversation`).
     */
    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.message_id
        WHERE m.conversation_id = :conversationId
          AND m.hidden = 0
          AND (a.mime_type LIKE 'image/%' OR a.mime_type LIKE 'video/%')
        ORDER BY m.date DESC, m.id DESC, a.id ASC
        """,
    )
    fun observeMediaForConversation(conversationId: Long): Flow<List<AttachmentEntity>>

    /**
     * Details panel — Files tab: every attachment that is neither a
     * photo nor a video (documents, audio, vCards…), newest first.
     * Same hidden-message rule as [observeMediaForConversation].
     */
    @Query(
        """
        SELECT a.* FROM attachments a
        JOIN messages m ON m.id = a.message_id
        WHERE m.conversation_id = :conversationId
          AND m.hidden = 0
          AND a.mime_type NOT LIKE 'image/%' AND a.mime_type NOT LIKE 'video/%'
        ORDER BY m.date DESC, m.id DESC, a.id ASC
        """,
    )
    fun observeFilesForConversation(conversationId: Long): Flow<List<AttachmentEntity>>

    @Query("DELETE FROM attachments WHERE message_id = :messageId")
    suspend fun deleteForMessage(messageId: Long)

    /**
     * v1.28.9 (septième note d'Andrew, constat 1) — toutes les lignes qui citent l'un de
     * [localUris], avec leur message et leur conversation.
     *
     * Un envoi à plusieurs destinataires écrit UNE copie durable du fichier et la fait citer par
     * chaque ligne ; l'écho de groupe et l'envoi programmé la citent aussi. Effacer le fichier au
     * premier message supprimé vidait les bulles de tous les autres. L'appelant compte ce qui
     * reste cité une fois écarté ce qu'il supprime — cf.
     * [com.filestech.sms.data.repository.FichiersDePiecesJointes].
     *
     * Comparaison exacte des chaînes : chaque `local_uri` est écrit par l'application à partir de
     * `File.absolutePath`, sous la même racine, et les chemins comparés viennent de ces mêmes
     * lignes. `IN` est borné par SQLite : l'appelant découpe en lots.
     */
    @Query(
        """
        SELECT a.local_uri, a.message_id, m.conversation_id
          FROM attachments a
          JOIN messages m ON m.id = a.message_id
         WHERE a.local_uri IN (:localUris)
        """,
    )
    suspend fun findCitations(localUris: List<String>): List<CitationDePieceJointe>

    /**
     * v1.14.7 — récupère toutes les attachments dont [AttachmentEntity.localUri] commence
     * par [oldPrefix]. Utilisé par la migration cache → filesDir de MainApplication.onCreate
     * pour ré-écrire les chemins pointant vers `cacheDir/mms_incoming/` (volatile, sujet à
     * cache-clear Android + clear cache utilisateur) vers `filesDir/mms_attachments/`
     * (persistant). Cold-start one-shot, idempotent (flag DataStore).
     */
    @Query("SELECT * FROM attachments WHERE local_uri LIKE :oldPrefix || '%'")
    suspend fun findByLocalUriPrefix(oldPrefix: String): List<AttachmentEntity>

    /**
     * v1.14.7 — réécrit [AttachmentEntity.localUri] d'une row spécifique. Appelé après
     * que le fichier ait été déplacé physiquement de cacheDir → filesDir par la migration.
     */
    @Query("UPDATE attachments SET local_uri = :newUri WHERE id = :id")
    suspend fun updateLocalUri(id: Long, newUri: String)

    /**
     * Client mode C5 — every downloaded file cited by the synced
     * cache: the local URIs of attachments on synced messages, or
     * on messages of synced conversations (an optimistic row has no
     * hub id yet, but its conversation does). The wiper collects
     * these BEFORE deleting the rows, so the files can be deleted
     * after — a file still cited by a hub-mode row survives, via the
     * citation check.
     */
    @Query(
        """
        SELECT DISTINCT a.local_uri FROM attachments a
        JOIN messages m ON m.id = a.message_id
        WHERE a.local_uri != ''
          AND (m.hub_message_id IS NOT NULL
            OR m.conversation_id IN (
                SELECT id FROM conversations WHERE hub_conversation_id IS NOT NULL
            ))
        """,
    )
    suspend fun findLocalUrisForSynced(): List<String>

    /**
     * Client mode C5 — deletes the optimistic attachment rows of a
     * message (the ones with no hub id, inserted by the send
     * manager), so the mirror's reconcile can link their durable
     * files onto the synced attachments without duplicating rows.
     */
    @Query("DELETE FROM attachments WHERE message_id = :messageId AND hub_attachment_id IS NULL")
    suspend fun deleteOptimisticForMessage(messageId: Long)
}
