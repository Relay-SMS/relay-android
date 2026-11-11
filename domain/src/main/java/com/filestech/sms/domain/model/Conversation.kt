package com.filestech.sms.domain.model

import com.filestech.sms.core.ext.formatPhoneNumberForDisplay

data class Conversation(
    val id: Long,
    /**
     * `thread_id` du fournisseur système AOSP, ou `null` tant qu'aucun n'est connu — une
     * conversation créée depuis le composeur ou à la réception n'en a pas avant la resynchro.
     *
     * v1.28.3 — était `Long` avec la sentinelle `0L`. Cf. `ConversationEntity.threadId` : sous
     * l'index UNIQUE de la colonne, cette sentinelle partagée faisait s'entre-détruire les
     * conversations sans fil système.
     */
    val threadId: Long?,
    val addresses: List<PhoneAddress>,
    val displayName: String?,
    val lastMessageAt: Long,
    val lastMessagePreview: String?,
    val unreadCount: Int,
    val pinned: Boolean,
    val archived: Boolean,
    val muted: Boolean,
    val inVault: Boolean,
    val draft: String?,
    /** v1.11.0 — couleur ARGB de la bulle sortante. `null` = bleu marque par défaut. */
    val bubbleColorArgb: Int? = null,
    /** v1.11.0 — URI `content://` d'un avatar custom choisi par l'user. `null` = fallback contact natif. */
    val avatarUri: String? = null,
    /**
     * v1.25.3 — vrai quand **toutes** les adresses du fil sont dans la liste noire.
     *
     * Calculé à la volée par `ConversationRepositoryImpl.observeAll`, jamais persisté : la liste
     * noire est la seule source de vérité, et une conversation redevient normale au déblocage.
     */
    val blocked: Boolean = false,
    /** v1.28.3 — nom choisi par l'utilisateur pour un groupe, local au téléphone. Cf. [GroupName]. */
    val customName: String? = null,
    /**
     * Feedback round 5 — message épinglé du fil (un seul par conversation)
     * et date de l'épinglage (epoch ms, affichée par la bannière).
     * `null` = rien d'épinglé.
     */
    val pinnedMessageId: Long? = null,
    val pinnedMessageAt: Long? = null,
) {
    val isGroup: Boolean get() = addresses.size > 1

    /**
     * v1.28.3 — **la** règle de titre : nom choisi, sinon nom résolu, sinon les numéros. Six
     * sites la recalculaient chacun de leur côté ; un nom choisi aurait dû être ajouté six fois.
     *
     * Round 11 — les numéros sont affichés formatés, comme dans l'en-tête de fil
     * (`formatPhoneNumberForDisplay`) : `(xxx) xxx-xxxx`, `1 (xxx) xxx-xxxx`,
     * `+1 (xxx) xxx-xxxx`. Le nom choisi par l'utilisateur n'est pas touché ; un nom de
     * contact passe tel quel (le formateur laisse les noms inchangés). Le nom affiché
     * d'un groupe est une liste séparée par ", " : chaque partie est formatée à part.
     */
    val title: String
        get() = customName?.takeIf { it.isNotBlank() }
            ?: displayName?.takeIf { it.isNotBlank() }?.let { display ->
                display.split(", ").joinToString(", ") { it.formatPhoneNumberForDisplay() }
            }
            ?: addresses.joinToString { it.raw.formatPhoneNumberForDisplay() }
    val firstAddress: PhoneAddress? get() = addresses.firstOrNull()
}
