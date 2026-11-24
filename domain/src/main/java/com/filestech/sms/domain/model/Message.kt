package com.filestech.sms.domain.model

data class Message(
    val id: Long,
    val conversationId: Long,
    val address: String,
    val body: String,
    val type: Type,
    val direction: Direction,
    val date: Long,
    val dateSent: Long?,
    val read: Boolean,
    val starred: Boolean,
    val status: Status,
    val errorCode: Int?,
    val attachmentsCount: Int,
    val subId: Int?,
    val scheduledAt: Long?,
    val attachments: List<Attachment> = emptyList(),
    /**
     * Local id of the message this one is replying to (#8 contextual reply). NULL for regular
     * messages. The UI resolves the preview by looking up [id] in the same conversation's
     * loaded list — a dangling reference (quoted message deleted) renders a fallback placeholder.
     */
    val replyToMessageId: Long? = null,
    /**
     * v1.3.0 — réaction emoji locale posée par l'utilisateur. `null` = pas de réaction.
     * Une seule réaction par message (la mienne), pas standardisée en SMS donc pas envoyée
     * — purement local pour annoter son propre fil.
     */
    val reactionEmoji: String? = null,
    /**
     * Slice 3 — clé d'idempotence d'un envoi fait depuis un appareil lié (UUID généré par
     * l'appareil pour cet envoi). `null` pour tout le reste. Une seule ligne par envoi la
     * porte — celle que l'appareil affiche.
     */
    val clientMessageId: String? = null,
    /**
     * E2E (schema v22) — this message was encrypted on the wire
     * (Relay-to-Relay text, media, or group message). The UI shows
     * a lock on its timestamp line; plain messages (a plain MMS,
     * the first plain message to a new contact) carry no lock.
     * Round 49 — the lock covers encrypted media, voice, and
     * video too, not just text. Round 47 —
     * the outgoing bubble colour keys on this flag too (blue when
     * encrypted, green `#3BA803` when plain). Note display
     * projections set it as well: a note entry is encrypted at
     * rest, so it keeps the blue default (see [NoteMessage.toDisplayMessage]).
     */
    val encrypted: Boolean = false,
    /**
     * E2E (schema v22) — placeholder: the ciphertext arrived but
     * could not be decrypted (no session, reinstalled peer). The
     * UI renders the "couldn't be decrypted" notice-style row
     * instead of a bubble. The raw ciphertext stays on the entity
     * ([com.filestech.sms.data.local.db.entity.MessageEntity.ciphertext])
     * for a later retry — it never reaches this model, the UI, or
     * the sync layer.
     */
    val decryptFailed: Boolean = false,
) {
    enum class Type { SMS, MMS }
    enum class Direction { INCOMING, OUTGOING }
    enum class Status { PENDING, SENT, DELIVERED, FAILED, RECEIVED, SCHEDULED }

    val isIncoming: Boolean get() = direction == Direction.INCOMING
    val isOutgoing: Boolean get() = direction == Direction.OUTGOING

    /** First audio attachment, if the message carries one. Convenience for the UI dispatch. */
    val audioAttachment: Attachment? get() = attachments.firstOrNull { it.isAudio }
}
