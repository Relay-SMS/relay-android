package com.filestech.sms.core.result

/**
 * Typed application errors. Never throw raw exceptions to UI layers; map to one of these.
 */
sealed class AppError(open val cause: Throwable? = null) {

    data class Network(override val cause: Throwable? = null) : AppError(cause)
    data class Storage(override val cause: Throwable? = null) : AppError(cause)
    data class Permission(val permission: String) : AppError()
    data object NotDefaultSmsApp : AppError()
    data class Telephony(val reason: String, override val cause: Throwable? = null) : AppError(cause)
    data class MmsHttp(val statusCode: Int, override val cause: Throwable? = null) : AppError(cause)
    data class Crypto(val reason: String, override val cause: Throwable? = null) : AppError(cause)
    data class Database(override val cause: Throwable? = null) : AppError(cause)
    data class Validation(val message: String) : AppError()

    /**
     * v1.28.3 (F21) — l'envoi a été refusé parce que le destinataire figure dans la liste de
     * blocage de l'utilisateur.
     *
     * Typé, et non un [Validation] porteur d'un message en anglais, parce que l'interface doit
     * pouvoir le DIRE : ce n'est pas une panne, et l'utilisateur qui touche une bulle rouge doit
     * apprendre qu'il a lui-même bloqué ce numéro plutôt que de voir « Échec de l'envoi » et
     * réessayer indéfiniment. Le chemin de relance rendait jusqu'ici cette issue sans un mot.
     */
    data object RecipientBlocked : AppError()

    /**
     * v1.28.3 (audit global B-1) — le renvoi demandé porte sur un MMS, et le chemin de relance
     * ne connaît que la pile SMS. Il renvoyait la LÉGENDE en texte, sans la pièce jointe, et la
     * ligne pouvait passer « envoyé » sous une vignette qui n'était jamais partie — documenté
     * dans `SECURITY.md` depuis la v1.3.9, jamais fermé. Typé pour que l'écran puisse le dire,
     * au lieu d'un « Échec de l'envoi » qui invite à recommencer.
     */
    data object MmsRetryUnsupported : AppError()

    /**
     * v1.28.3 (groupes) — la ligne touchée est la COPIE gardée dans le fil d'un groupe : elle
     * n'a pas de ligne système et son adresse est la liste des membres. Le renvoi se fait depuis
     * la conversation de chaque membre, où vivent les vraies lignes et leurs accusés.
     */
    data object GroupEchoRetryUnsupported : AppError()

    /**
     * Group-send own-number fix — a group MMS send was refused
     * because "My phone number" (`send.userMsisdn`) is blank AND the
     * just-in-time SIM detection found nothing. The send never
     * started: no PENDING mirror row exists, so nothing sticks on
     * "sending".
     *
     * Typed, and not a [Validation] carrying an English message, for
     * the same reason as [RecipientBlocked]: the UI must be able to
     * SAY it — the user has to set their number in Settings →
     * Sending, and a generic "Failed to send" would send them
     * retrying a send that cannot succeed until they do.
     */
    data object OwnNumberMissing : AppError()

    /**
     * Client mode C3 — the linked phone could not be reached: the device
     * is offline, or the sync session is down. The send (or schedule, or
     * cancel) never left this device — nothing was queued, and retrying
     * is safe. Typed so the UI can SAY it instead of a generic "Failed
     * to send".
     */
    data object ClientOffline : AppError()

    /**
     * Client mode C3 — a media send (photo, video, file, voice message)
     * from a linked device. Media sends arrive in C5; until then the
     * router refuses them here instead of attempting telephony the
     * device does not have. Typed so the UI can SAY it.
     */
    data object ClientMediaUnsupported : AppError()

    /**
     * Client mode C5 — a media send from a linked device exceeded the
     * phone's MMS budget (280 KB, text + attachments): the photos
     * could not be recompressed small enough, or a video/file did
     * not fit as-is. Typed so the UI can say it with the phone's own
     * words (`snack_thread_attach_cap_reached`).
     */
    data object ClientMmsCapReached : AppError()

    /**
     * Client mode C5 — a photo from a linked device could not be
     * decoded for recompression (corrupt, or a format this device
     * cannot decode). Typed so the UI can SAY it.
     */
    data object ClientPhotoPrepareFailed : AppError()

    /**
     * v1.28.4 (F13) — une purge du coffre est en cours : on n'y entre pas pendant qu'on le vide.
     * Passager par nature ; l'utilisateur réessaie une fois la purge terminée.
     */
    data object VaultPurging : AppError()
    data class Locked(val unlockRequired: Boolean = true) : AppError()
    data class NotFound(val what: String) : AppError()
    data class Cancelled(val reason: String? = null) : AppError()
    data class Unknown(override val cause: Throwable? = null) : AppError(cause)
}
