package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["conversation_id", "date"]),
        Index(value = ["telephony_uri"], unique = true),
        Index(value = ["status"]),
        Index(value = ["read"]),
        Index(value = ["starred"]),
        // Schema v2 (#8 contextual reply). Index the FK so loading a thread and resolving
        // "this message replied to which one?" stays an O(log n) lookup even on long threads.
        Index(value = ["reply_to_message_id"]),
        // Schema v3 (v1.2.6 audit F2 idempotence retry). Indexed because the retry path looks
        // up the previous system-provider row id by Room message id, and (future) the watchdog
        // may reconcile Room ↔ content://mms via mmsSystemId.
        Index(value = ["mms_system_id"]),
        // Schema v4 (v1.3.0 audit P1). Auto-purge filtre par `date < cutoff` sur l'ensemble
        // de la table ; sans index dédié, SQLCipher fait un full scan + déchiffrement page par
        // page (~1 s pour 50 k rows). L'index composite (conversation_id, date) est inopérant
        // ici puisque la purge est inter-conversations. Coût ~8 B / row, négligeable.
        Index(value = ["date"]),
        // Schema v14 (v1.28.9, F17). UNIQUE : receveur et reprise ne peuvent pas écrire deux fois le
        // même MMS entrant. SQLite admet plusieurs NULL sous un index unique — seuls les MMS entrants
        // portent une clé.
        Index(value = ["mms_transaction_key"], unique = true),
        // Schema v16 (Slice 3). UNIQUE : la clé de dédup d'un envoi fait depuis un appareil lié.
        // SQLite admet plusieurs NULL sous un index unique — seuls les messages envoyés depuis
        // un appareil lié portent une clé, et une seule ligne par envoi (la ligne montrée).
        Index(value = ["client_message_id"], unique = true),
        // Client mode (schema v18) — the hub message id is the upsert
        // key for `messages_batch` / `message_new` / `message_updated`.
        // UNIQUE: one local row per hub message, so a re-sync can
        // never duplicate one (SQLite admits several NULLs, so
        // hub-mode rows are unaffected). The mirror upserts by lookup,
        // so the index is a safety net, not the dedup mechanism.
        Index(value = ["hub_message_id"], unique = true),
    ],
)
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "telephony_uri") val telephonyUri: String?,
    @ColumnInfo(name = "address") val address: String,
    @ColumnInfo(name = "body") val body: String,
    /**
     * v1.16.0 — type devient `MessageType` enum (était Int). Stockage SQL inchangé via
     * [com.filestech.sms.data.local.db.MessageEnumConverters] : 0=SMS, 1=MMS en base.
     */
    @ColumnInfo(name = "type") val type: MessageType,
    /**
     * v1.16.0 — direction devient `MessageDirection` enum (était Int). 0=INCOMING, 1=OUTGOING.
     */
    @ColumnInfo(name = "direction") val direction: MessageDirection,
    @ColumnInfo(name = "date") val date: Long,
    @ColumnInfo(name = "date_sent") val dateSent: Long?,
    @ColumnInfo(name = "read") val read: Boolean = false,
    @ColumnInfo(name = "starred") val starred: Boolean = false,
    /**
     * v1.16.0 — status devient `MessageStatus` enum (était Int). Stockage SQL inchangé via
     * [com.filestech.sms.data.local.db.MessageEnumConverters] : 0=PENDING ... 5=SCHEDULED.
     */
    @ColumnInfo(name = "status") val status: MessageStatus = MessageStatus.PENDING,
    @ColumnInfo(name = "error_code") val errorCode: Int? = null,
    @ColumnInfo(name = "sub_id") val subId: Int? = null,
    @ColumnInfo(name = "scheduled_at") val scheduledAt: Long? = null,
    @ColumnInfo(name = "attachments_count") val attachmentsCount: Int = 0,
    /**
     * Schema v2 — contextual reply target (#8). Stores the local Room id of the message the
     * sender chose to quote when composing. NULL for regular messages and for legacy rows
     * imported before v1.2.
     *
     * Deliberately **not** a `ForeignKey`: a quoted message can be deleted (locally or via the
     * system provider) while the reply lives on; we tolerate the dangling reference and the UI
     * falls back to a "Message supprimé" placeholder rather than cascading the delete.
     */
    @ColumnInfo(name = "reply_to_message_id") val replyToMessageId: Long? = null,
    /**
     * Schema v3 — `_id` of the row we inserted into `content://mms` for this outgoing MMS
     * (v1.2.6 audit F2 idempotence retry). NULL for SMS rows, for incoming MMS, and for
     * outgoing MMS where the system writeback never succeeded (e.g. we were not default SMS
     * app at the time of dispatch).
     *
     * Used by [com.filestech.sms.data.mms.MmsSender] to delete the previous OUTBOX/FAILED
     * row before re-inserting a fresh one on retry, so we never leave two system-provider
     * rows for the same Room message even briefly.
     */
    @ColumnInfo(name = "mms_system_id") val mmsSystemId: Long? = null,
    /**
     * Schema v4 — réaction emoji locale posée par l'utilisateur sur ce message (v1.3.0).
     * `null` = pas de réaction. Une seule réaction par message (la mienne), pas envoyée
     * par SMS — c'est purement local côté Room, pas standardisé en SMS/MMS.
     */
    @ColumnInfo(name = "reaction_emoji") val reactionEmoji: String? = null,
    /**
     * Schema v11 (v1.28.3, F23) — numéro de la **tentative d'envoi** en cours pour ce message.
     *
     * `0` à la création, incrémenté par chaque relance explicite
     * ([com.filestech.sms.domain.repository.OutgoingMessageMirror.resetOutgoingForRetry]).
     *
     * # Ce que ce compteur répare
     *
     * Les `PendingIntent` de suivi ne portaient que l'id Room. Une relance réutilisant le même
     * id, l'accusé **tardif** de la tentative précédente était indiscernable de celui de la
     * tentative courante — et la relance venant de rétrograder la ligne en `PENDING`, la règle
     * monotone ne le filtrait plus. Un `FAILED` en retard d'une minute écrivait donc `3`, sommet
     * de l'échelle, que le succès réel de la nouvelle tentative ne pouvait **plus jamais**
     * promouvoir : bulle rouge définitive sur un message bel et bien reçu.
     *
     * Un accusé porte désormais le numéro de sa tentative, et n'est appliqué que si la ligne en
     * est toujours là. Les `PendingIntent` sont distincts d'une tentative à l'autre (le numéro
     * entre dans leur `requestCode`), donc `FLAG_UPDATE_CURRENT` ne les fait plus se confondre.
     */
    @ColumnInfo(name = "send_attempt", defaultValue = "0") val sendAttempt: Int = 0,
    /**
     * v1.28.4 — **ligne de service, jamais affichée** : la trace locale d'une réaction (Tapback)
     * envoyée ou reçue, dont seul le badge est exposé.
     *
     * Avant, six requêtes la reconnaissaient à sa FORME — `body = ''`, aucune pièce jointe,
     * aucune réaction. Or un MMS sans légende restauré d'une sauvegarde, qui ne transporte pas
     * les pièces jointes, prenait exactement cette forme : importé, compté comme importé, et
     * montré nulle part (trouvé en écrivant les tests de F25). L'intention est désormais écrite
     * par le seul producteur de sentinelles, pas devinée par les lecteurs ; et le garde
     * d'identité (F14) sait qu'un corps n'est pas comparable parce que la ligne le DIT.
     */
    @ColumnInfo(name = "hidden", defaultValue = "0") val hidden: Boolean = false,
    /**
     * Schema v14 (v1.28.9, F17 — septième note d'Andrew sur la MR !38458) — **clé de transaction d'un
     * MMS entrant** : empreinte de ce que la notification WAP-Push a donné pour ce message
     * (`transactionId`, adresse de téléchargement, SIM), cf. [com.filestech.sms.data.mms.PdusEnAttente].
     * `null` pour tout le reste.
     *
     * Elle permet à la reprise d'un PDU gardé de reconnaître un message déjà écrit : le compléter plutôt
     * que le dupliquer, et ne pas ressusciter un message supprimé. Index UNIQUE.
     *
     * `@Transient` pour la sauvegarde : la clé ne vaut que pour les PDU de CE téléphone, et les
     * sauvegardes sérialisent cette entité avec un parseur strict qui refuse les clés inconnues — une
     * version antérieure ne relirait plus un fichier qui la porterait. Le format reste identique.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "mms_transaction_key") val mmsTransactionKey: String? = null,
    /**
     * Schema v16 (Slice 3) — **clé d'idempotence d'un envoi fait depuis un appareil lié** :
     * l'UUID généré par l'appareil pour cet envoi. `null` pour tout le reste.
     *
     * Elle est écrite sur la ligne miroir au moment de l'insertion, AVANT la remise au radio,
     * et l'index UNIQUE est le filet de sécurité : deux insertions concurrentes de la même clé
     * ne peuvent pas aboutir toutes les deux. Une relance (accusé perdu, reconnexion) retrouve
     * la ligne et ne renvoie jamais le texte une seconde fois.
     *
     * `@Transient` pour la sauvegarde, comme `mms_transaction_key` : la clé ne vaut que pour
     * l'envoi de CET appareil, et les sauvegardes sérialisent cette entité avec un parseur
     * strict qui refuse les clés inconnues — une version antérieure ne relirait plus un
     * fichier qui la porterait. Le format reste identique.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "client_message_id") val clientMessageId: String? = null,
    /**
     * Client mode (schema v18) — **the hub's Room id for this message**
     * (wire `id`): the upsert key for `messages_batch` / `message_new` /
     * `message_updated`, and the cursor unit (the global cursor is the
     * max hub id durably stored). `null` on hub-mode rows and on
     * not-yet-reconciled optimistic rows.
     *
     * `@Transient` for the backup, like `client_message_id`: the hub
     * id only means something on THIS link, and the backup parser is
     * strict about unknown keys.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "hub_message_id") val hubMessageId: Long? = null,
    /**
     * E2E (schema v22) — **this message was encrypted on the wire**
     * (Relay-to-Relay 1:1 text). The UI shows a lock on its timestamp
     * line; plain messages (MMS, attachments, the first plain message
     * to a new contact) carry no lock. Threaded through to the domain
     * [com.filestech.sms.domain.model.Message] like `reactionEmoji` —
     * the thread renders from the domain model, so the flag must
     * survive the mapping.
     *
     * `@Transient` for the backup, like `hub_message_id`: the flag
     * describes this install's wire history, and a restored message
     * simply shows no lock — the sessions are gone on a new install
     * anyway, and the strict backup parser refuses unknown keys.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "encrypted", defaultValue = "0") val encrypted: Boolean = false,
    /**
     * E2E (schema v22) — **placeholder: the ciphertext arrived but
     * could not be decrypted** (no session, reinstalled peer). The
     * body is empty and the UI renders the "couldn't be decrypted"
     * notice-style row instead of a bubble. Threaded through to the
     * domain model with [encrypted].
     *
     * `@Transient` for the backup, like [encrypted]: a restored
     * placeholder could never be retried (the ciphertext is not
     * backed up either), so restoring it as a plain empty row is
     * the honest outcome.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "decrypt_failed", defaultValue = "0") val decryptFailed: Boolean = false,
    /**
     * E2E (schema v22) — **the raw base64 ciphertext**, kept in two
     * cases: on a `decrypt_failed` row (kept so a later session can
     * retry after a re-probe re-establishes the exchange), and on an
     * OUTGOING encrypted row (kept so a retry re-sends the EXACT bytes
     * of the first attempt — same fragment set, same message id).
     * NULL otherwise. Never synced (the desktop gets `decrypt_failed`,
     * not the bytes), never in previews, search, or notifications, and
     * never on the domain model — the retry paths read it via
     * [com.filestech.sms.data.local.db.dao.MessageDao.findDecryptFailed]
     * (incoming) and `findById` (outgoing).
     *
     * `@Transient` for the backup, like the flags: ciphertext from a
     * dead install is undecryptable forever.
     */
    @kotlinx.serialization.Transient
    @ColumnInfo(name = "ciphertext") val ciphertext: String? = null,
)
