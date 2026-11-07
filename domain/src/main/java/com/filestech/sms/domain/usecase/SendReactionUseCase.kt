package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ReactionFormat
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.sender.SenderNameProvider
import javax.inject.Inject

/**
 * v1.3.1 — envoie une réaction emoji à **l'expéditeur du message réagi** comme un SMS
 * texte standard contenant uniquement l'emoji (ex. "❤️"). Délègue à [SendSmsUseCase]
 * pour la dispatch réelle (mêmes guards : default SMS app, blocklist, side-effects
 * provider + Room mirror).
 *
 * Sécurité (audit v1.3.1) :
 *
 *   - **F1** : refuse les réactions sur messages **sortants**. Si l'utilisateur réagit
 *     à son propre message, on n'envoie RIEN au destinataire d'origine (ce serait un
 *     SMS sorti de nulle part pour lui : "❤️" sans contexte).
 *   - **F2** : cible **uniquement le numéro qui a envoyé le message réagi**
 *     ([com.filestech.sms.domain.model.Message.address]), pas toute la conversation.
 *     Sans cette garde, réagir à un message dans un groupe (Alice/Bob/Charlie) enverrait
 *     "❤️" aux 3 → spam + leak de comportement intra-groupe + facturation ×N.
 *   - **F3** : passe `appendSignature = false` à [SendSmsUseCase] pour qu'un emoji
 *     reste un emoji seul (sinon "❤️\n--\nPat" bascule en SMS multi-part et pollue
 *     le fil du correspondant).
 *
 * Le caller (typiquement [com.filestech.sms.ui.screens.thread.ThreadViewModel]) reste
 * responsable :
 *
 *   - de vérifier que [com.filestech.sms.domain.settings.SendingSettings
 *     .sendReactionsToRecipient] est activée,
 *   - d'avoir affiché le dialog de confirmation au premier envoi si nécessaire,
 *   - de passer `isRemoval = true` pour un retrait ([SetReactionResult.Removed]) :
 *     le retrait part comme `Removed a like from "…"` (forme Apple, décodée
 *     côté SMS Relay) — il n'est plus silencieux local uniquement, car Apple
 *     et Google ont tous deux une forme de retrait sur le fil.
 *
 * **v1.4.1 — Changed désormais dispatché.** Depuis v1.4.1 le ViewModel propage aussi les
 * transitions [com.filestech.sms.domain.repository.SetReactionResult.Changed] (changement
 * d'emoji par le réacteur) via ce use case, afin que le destinataire d'origine voit le
 * nouvel emoji et pas l'ancien. La dédup côté ViewModel (clef = `(messageId, emoji)`,
 * fenêtre 60 s) empêche le double-envoi en cas de tap répété sur le même emoji.
 *
 * Erreurs propagées sans transformation depuis [SendSmsUseCase].
 */
class SendReactionUseCase @Inject constructor(
    private val sendSms: SendSmsUseCase,
    private val conversationRepo: ConversationRepository,
    // v1.8.1 — résout le nom de l'expéditeur (override Settings > Profile auto >
    // null). Inclus dans le format READABLE_FR uniquement (TAPBACK_EN / EMOJI_ONLY
    // restent identiques pour préserver la compat iMessage et la concision).
    private val senderNameProvider: SenderNameProvider,
) {
    /**
     * Envoie [emoji] à l'expéditeur du message [messageId]. Retourne :
     *
     *   - [Outcome.Failure] avec [AppError.Validation] si le message n'existe plus
     *     (purgé/supprimé entre-temps), s'il est sortant (cas F1), si son adresse est
     *     vide/invalide, ou si [emoji] est vide.
     *   - Le [Outcome] de [SendSmsUseCase] (Success avec l'id Room du SMS envoyé, ou
     *     Failure propagée).
     *
     * @param format v1.8.0 (bug 5 fix) — format du SMS sortant :
     *   - [ReactionFormat.READABLE_FR] :
     *     "J'ai réagi par ❤️ à : «aperçu»" — naturel et compréhensible pour tout
     *     destinataire francophone Android. Décodé côté SMS Tech via la regex FR.
     *   - [ReactionFormat.TAPBACK_EN] :
     *     "Reacted ❤️ to «aperçu»" — compat iMessage iPhone + Google Messages.
     *   - [ReactionFormat.EMOJI_ONLY] :
     *     "❤️" — minimal, perd le contexte du message d'origine côté destinataire.
     *   - [ReactionFormat.IOS_TAPBACK] :
     *     `Liked "aperçu"` — forme textuelle Apple (voir [buildIosTapbackBody]).
     *
     * @param isRemoval `true` pour un retrait : le corps est TOUJOURS la forme
     *   de retrait iPhone ([buildIosTapbackBody] avec `isRemoval = true`), quel
     *   que soit [format] — c'est la seule forme de retrait interopérable
     *   (ni `EMOJI_ONLY` ni `EMOJI_WITH_QUOTE` ne peuvent exprimer un retrait).
     *
     * ⚠️ v1.28.12 — [format] n'a PLUS de valeur par défaut. Elle valait `READABLE_FR`,
     * c'est-à-dire un TROISIÈME endroit où le défaut du format était écrit, donc un
     * troisième endroit d'où il pouvait diverger des deux autres — ce qui est précisément
     * arrivé. Le seul appelant le passe explicitement : ce défaut n'était pas un
     * comportement, c'était un piège posé pour le prochain appelant. Le défaut qui fait
     * foi est celui de `SendingSettings`, et il vaut `EMOJI_WITH_QUOTE` depuis la v1.14.4.
     */
    suspend operator fun invoke(
        messageId: Long,
        emoji: String,
        format: ReactionFormat,
        isRemoval: Boolean = false,
    ): Outcome<SendReport> {
        if (emoji.isBlank()) return Outcome.Failure(AppError.Validation("empty reaction emoji"))

        val message = conversationRepo.findMessageById(messageId)
            ?: return Outcome.Failure(AppError.Validation("message not found: $messageId"))

        // F1 — pas d'écho sur un message qu'on a soi-même envoyé : le destinataire ne
        // verrait qu'un emoji isolé sans aucun lien causal.
        if (message.isOutgoing) {
            return Outcome.Failure(AppError.Validation("cannot send reaction for outgoing message"))
        }

        // F2 — l'expéditeur du message est `message.address` (en SMS, c'est le numéro qui
        // a écrit le message qu'on lit). Pour un message reçu dans une conv 1-1 ou de
        // groupe, c'est cohérent : on ne notifie QUE cet expéditeur, jamais les autres
        // participants du groupe.
        val senderRaw = message.address.trim()
        if (senderRaw.isEmpty()) {
            return Outcome.Failure(AppError.Validation("message has no sender address"))
        }

        // X1 (CRITICAL audit v1.3.1) — refuser les expéditeurs alphanumériques (banques,
        // Free, INFO, AMAZON…) ET les short codes <4 chiffres. Sans ce garde, l'envoi
        // partirait soit vers un destinataire indéfini (rejet stack SmsManager), soit
        // vers le short code de notification facturé PREMIUM (1,50 €+). En France les
        // SMS bancaires / livraison / 2FA sont presque tous alpha → réagir à un SMS de
        // sa banque avec ❤️ ne doit JAMAIS déclencher d'envoi.
        if (!senderRaw.isDialablePhoneNumber()) {
            return Outcome.Failure(
                AppError.Validation("sender is not a dialable phone number: $senderRaw"),
            )
        }

        val target = PhoneAddress.of(senderRaw)
        if (target.normalized.isEmpty()) {
            return Outcome.Failure(AppError.Validation("invalid sender address: $senderRaw"))
        }

        return sendSms(
            recipients = listOf(target),
            // v1.8.0 (bug 5 fix) — switch sur les formats de réaction. Le retrait
            // ignore [format] : seule la forme iPhone sait exprimer un retrait
            // sur le fil (voir KDoc de [invoke]).
            body = if (isRemoval) {
                buildIosTapbackBody(emoji, message.body, isRemoval = true)
            } else {
                when (format) {
                    ReactionFormat.EMOJI_ONLY -> emoji
                    ReactionFormat.TAPBACK_EN -> buildTapbackBody(emoji, message.body)
                    ReactionFormat.READABLE_FR -> buildReadableFrBody(
                        emoji,
                        message.body,
                        // v1.8.1 — résolu à l'envoi (Settings override > Profile
                        // Android > null). Lecture rapide (~1-5 ms), faite à
                        // chaque envoi pour refléter immédiatement un changement
                        // de nom dans Settings sans avoir à redémarrer l'app.
                        senderName = senderNameProvider.resolveDisplayName(),
                    )
                    // v1.9.0 — format compact + contexte : "❤️ «aperçu»".
                    ReactionFormat.EMOJI_WITH_QUOTE -> buildEmojiWithQuoteBody(emoji, message.body)
                    // Forme textuelle Apple : `Liked "aperçu"`, défaut actuel.
                    ReactionFormat.IOS_TAPBACK -> buildIosTapbackBody(emoji, message.body)
                }
            },
            // F3 — pas de signature pour une réaction.
            appendSignature = false,
            // Pas de réponse contextuelle (la réaction est ponctuelle, ajouter un
            // replyTo polluerait le fil côté correspondant avec une quote inutile).
            replyToMessageId = null,
            // v1.4.1 — empty `body` in the Room mirror so the reactor doesn't see
            // their own outgoing Tapback as a redundant text bubble in their own
            // thread (the local badge on the incoming message they reacted to is
            // already the right feedback). The wire body + `content://sms`
            // writeback still carry the full Tapback so the correspondent's app
            // (SMS Tech, iMessage, Google Messages) can fold or render it.
            localMirrorBody = "",
        )
    }


    /**
     * X1 — vrai numéro téléphonique dialable. Refuse :
     *
     *   - alphanumériques (banque, Free, INFO, AMAZON, livraisons),
     *   - short codes de moins de [REACTION_MIN_DIGITS] chiffres, ce qui couvre les 3 à 6
     *     chiffres des codes courts mondiaux et les 5 chiffres des numéros premium français
     *     (1,50 € le SMS) — v1.27.2 : le seuil était de 4, il ne rejetait donc AUCUN des codes
     *     que cette ligne prétendait déjà écarter,
     *   - chaînes contenant des lettres ou symboles non téléphoniques.
     *
     * Acceptés : `+33612345678`, `0612345678`, `+1 (415) 555-2671`, `06.12.34.56.78`,
     * formats internationaux avec espaces/tirets/parenthèses standards.
     *
     * **Pourquoi pas `Telephony.PhoneNumberUtils.isWellFormedSmsAddress` ?** Cette API
     * accepte aussi les alphanumériques (`return isValidGsmAlphabet || isValidPhoneNumber`)
     * et ne distingue pas un sender alpha d'un dest. Notre besoin est plus strict côté
     * envoi : on veut un destinataire **dialable**, pas juste "syntaxiquement valide".
     */
    private fun String.isDialablePhoneNumber(): Boolean {
        // Refuse toute lettre ASCII (alphanumeric senders) — un vrai numéro n'en a pas.
        if (any { it in 'A'..'Z' || it in 'a'..'z' }) return false
        // Tous les caractères restants doivent être : digits, +, espace, tiret, point,
        // parenthèses. Refuse tout autre symbole exotique.
        if (!matches(Regex("^[+0-9 .()\\-]+$"))) return false
        // v1.27.2 (audit externe Gemini 2026-08-04) — seuil porté de 4 à
        // [REACTION_MIN_DIGITS] chiffres.
        //
        // ⚠️ Le commentaire précédent affirmait que le seuil de 4 « exclut les short codes
        // premium courts (32665, etc.) ». C'était FAUX, et son propre exemple le démontrait :
        // `32665` fait CINQ chiffres, il franchissait donc la garde. Aucun code court n'était
        // exclu.
        //
        // Ce que ça coûtait : une réaction est un VRAI SMS, envoyé automatiquement quand
        // l'utilisateur touche un emoji. Réagir dans un fil de service (banque, opérateur,
        // code 2FA) partait donc vers un code court, potentiellement SURTAXÉ, sans que rien
        // ne l'annonce — et sans le moindre sens fonctionnel : personne ne lit un Tapback à
        // l'autre bout.
        //
        // Restreint AU CHEMIN DES RÉACTIONS. Les trois chemins d'envoi ordinaires gardent
        // leur seuil : répondre « STOP » à un service est légitime et explicite, l'utilisateur
        // tape le message. C'est l'automatisme qui pose problème ici, pas la destination.
        return count { it.isDigit() } >= REACTION_MIN_DIGITS
    }

}

/**
 * v1.3.2 — cap UCS-2 d'un segment SMS unique. Les guillemets typographiques «» forcent
 * l'encodage UCS-2 (cap 70 chars/segment vs 160 GSM-7). Au-delà, le SMS bascule en
 * multi-part = facturation ×2 chez certains opérateurs et perte du parsing Apple/Google
 * qui attend une seule trame.
 */
private const val SMS_UCS2_SEGMENT_CAP = 70

/**
 * v1.27.2 (audit externe Gemini 2026-08-04) — nombre minimal de chiffres pour qu'une RÉACTION
 * parte vers un destinataire.
 *
 * **Sept.** Les codes courts font mondialement 3 à 6 chiffres (5 en France : 32665, 36000…) ;
 * sept les écarte tous. Un numéro réel en compte au moins autant dès qu'il porte son indicatif
 * pays, et les rares numérotations nationales à six chiffres restent joignables par tous les
 * autres chemins — seule la réaction automatique est refusée.
 *
 * Ne PAS aligner les trois use-cases d'envoi ordinaires là-dessus : y écrire volontairement à un
 * code court est légitime (répondre « STOP »).
 */
private const val REACTION_MIN_DIGITS = 7

/** v1.3.2 — wrap fixe `"Reacted "` (8) + `" to «"` (5) + `"»"` (1) + `"…"` éventuel (1). */
private const val TAPBACK_WRAP_LENGTH = 8 + 5 + 1 + 1

/**
 * v1.8.1 (bug 5 wording) — wrap fixe pour le format français lisible neutre :
 * `"Réagi par "` (10) + `" à votre message : «"` (20) + `"»"` (1) + `"…"` éventuel (1).
 * Format impersonnel (sans "J'ai") pour éviter l'ambiguïté côté destinataire qui
 * voit déjà l'expéditeur dans son carnet de contacts.
 */
private const val READABLE_FR_WRAP_LENGTH = 10 + 20 + 1 + 1

/**
 * v1.3.2 — borne SUPÉRIEURE stricte de l'aperçu, indépendante de l'emoji. Limite
 * conservatrice : un emoji multi-codepoint (drapeau composé = 4 UTF-16 units) ne doit
 * jamais faire déborder le segment. On calcule le cap réel via [previewBudget].
 */
private const val PREVIEW_HARD_MAX = 50

/**
 * v1.3.2 — caractères Unicode strippés du body avant injection dans le tapback :
 *
 *   - **C0/C1 controls** (`\u0000-`, `-`) : CR/LF/NUL/BEL etc.
 *     pourraient splitter la trame SMS ou injecter un préfixe trompeur.
 *   - **Line/Paragraph separators** (` `, ` `) : sauts de ligne Unicode
 *     que `\\s+` ne capturait pas seul.
 *   - **Bidi controls** (`‎`/`‏`, `‪-‮`, `⁦-⁩`) : un
 *     `‮` RLO injecté inverse visuellement le rendu — `Reacted ❤️ to «‮Hello»`
 *     s'affiche `«olleH»` côté correspondant, défaisant le parsing iMessage.
 *   - **BOM** (`﻿`) : zero-width invisible.
 */
private val FORBIDDEN_BODY_CHARS = Regex(
    "[\\u0000-\\u001F\\u007F-\\u009F\\u2028\\u2029\\u200E\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]"
)

/**
 * v1.3.2 — construit le corps SMS au format Apple/Google Tapback. Fonction top-level
 * pour être testable sans instancier [SendReactionUseCase] (dont les dépendances
 * nécessiteraient des mocks). Publique (top-level) : testée directement, y compris depuis les
 * tests de régression cross-module (`AuditV180Test`/`AuditV190Test`, qui importent des classes
 * `:app`/`:data` et ne peuvent donc pas vivre dans `:domain`).
 *
 * Garanties :
 *
 *   - Si [originalBody] est vide (cas MMS image pure), fallback compact
 *     `Reacted <emoji>` — la chaîne reste détectable par les parseurs Apple/Google.
 *   - Les caractères dangereux (cf. [FORBIDDEN_BODY_CHARS] : C0/C1, line separators
 *     Unicode, bidi controls RLO/LRO, BOM) sont remplacés par un espace simple ;
 *     les espaces consécutifs sont ensuite compressés (rendu propre).
 *   - L'aperçu est tronqué à un budget calculé dynamiquement
 *     (`SMS_UCS2_SEGMENT_CAP - TAPBACK_WRAP_LENGTH - emoji.length`, plafonné à
 *     [PREVIEW_HARD_MAX]) — assure 1 segment SMS UCS-2 même avec un emoji multi-
 *     codepoint (drapeau, famille ZWJ).
 *   - La troncature est **grapheme-safe** : si l'index de coupe tombe sur un high
 *     surrogate ou en plein cluster ZWJ, on recule jusqu'à un boundary propre pour
 *     ne jamais émettre un caractère corrompu côté correspondant.
 *   - Le format texte est ASCII pur côté wrap (`Reacted` / `to`) pour matcher
 *     EXACTEMENT la signature parsée par Apple/Google ; traduire en français
 *     casserait la détection automatique côté iPhone.
 */
fun buildTapbackBody(emoji: String, originalBody: String): String {
    val sanitized = originalBody
        .replace(FORBIDDEN_BODY_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (sanitized.isEmpty()) {
        return "Reacted $emoji"
    }
    val budget = previewBudget(emoji)
    val preview = if (sanitized.length <= budget) {
        sanitized
    } else {
        sanitized.safeTake(budget).trimEnd() + "…"
    }
    return "Reacted $emoji to «$preview»"
}

/**
 * v1.8.1 (feedback user — nom de l'expéditeur) — construit le corps SMS au format
 * français lisible. Si [senderName] est non-null/non-vide, il est préfixé pour
 * identifier explicitement l'auteur de la réaction côté destinataire :
 *   `"Florence a réagi par ❤️ à votre message : «…»"`.
 * Sinon, format anonyme aligné iMessage :
 *   `"Réagi par ❤️ à votre message : «…»"`.
 *
 * Pourquoi ce format précis :
 *  - **Nom explicite quand disponible** (Settings override ou
 *    `ContactsContract.Profile` Android) — le destinataire voit déjà le contact
 *    via son carnet, mais le nom dans le SMS lève toute ambiguïté sur l'auteur
 *    de la réaction (utile si l'utilisateur a plusieurs MSISDN dual-SIM, par ex).
 *  - **"a réagi par"** plutôt que "J'ai réagi" — la 3e personne sonne naturelle
 *    quand le destinataire la lit ("Florence a réagi par ❤️ à mon message").
 *  - **"à votre message"** — explicite que c'est une réaction au message du
 *    destinataire (aligné iMessage "Reacted ❤️ to your message").
 *  - Guillemets typographiques `«»` — uniformes avec [buildTapbackBody]
 *    (forcent UCS-2 single-segment, et matchent le décodage côté SMS Tech).
 *  - Troncature à `…` identique aux autres formats.
 *
 * Garanties (cf. [buildTapbackBody]) :
 *  - Body vide (MMS image pure) → fallback sans `« »`
 *    (avec ou sans nom selon `senderName`).
 *  - Caractères dangereux (C0/C1, bidi controls, BOM) strippés via
 *    [FORBIDDEN_BODY_CHARS].
 *  - Aperçu tronqué grapheme-safe via [safeTake] pour rester dans 1 segment
 *    UCS-2. Le budget est ré-ajusté en fonction de la longueur du nom.
 */
fun buildReadableFrBody(
    emoji: String,
    originalBody: String,
    senderName: String? = null,
): String {
    val sanitized = originalBody
        .replace(FORBIDDEN_BODY_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    val name = senderName?.takeIf { it.isNotBlank() }
    if (sanitized.isEmpty()) {
        return if (name != null) "$name a réagi par $emoji à votre message"
        else "Réagi par $emoji à votre message"
    }
    val budget = previewBudgetForFr(emoji, namePrefixLength = name?.length ?: 0)
    val preview = if (sanitized.length <= budget) {
        sanitized
    } else {
        sanitized.safeTake(budget).trimEnd() + "…"
    }
    return if (name != null) {
        "$name a réagi par $emoji à votre message : «$preview»"
    } else {
        "Réagi par $emoji à votre message : «$preview»"
    }
}

/**
 * v1.9.0 — wrap pour le format compact "emoji + citation" :
 * `<emoji> «<preview>»` = espace (1) + `«` (1) + `»` (1) + `…` éventuel (1).
 */
private const val EMOJI_WITH_QUOTE_WRAP_LENGTH = 1 + 1 + 1 + 1

/**
 * v1.9.0 — construit le corps SMS au format compact "emoji + citation" :
 * `❤️ «aperçu du message original»`. Pas de mot "Réagi" en préfixe — la
 * combinaison emoji-en-tête + guillemets typographiques suffit à signaler
 * une réaction côté destinataire (plus court que `READABLE_FR`, plus
 * explicite que `EMOJI_ONLY` seul).
 *
 * Body vide (MMS image pure) → fallback `<emoji>` seul (équivalent
 * EMOJI_ONLY pour ce cas, on évite d'envoyer `«»` vides).
 */
fun buildEmojiWithQuoteBody(emoji: String, originalBody: String): String {
    val sanitized = originalBody
        .replace(FORBIDDEN_BODY_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (sanitized.isEmpty()) return emoji
    val budget = (SMS_UCS2_SEGMENT_CAP - EMOJI_WITH_QUOTE_WRAP_LENGTH - emoji.length)
        .coerceAtMost(PREVIEW_HARD_MAX)
        .coerceAtLeast(8)
    val preview = if (sanitized.length <= budget) {
        sanitized
    } else {
        sanitized.safeTake(budget).trimEnd() + "…"
    }
    return "$emoji «$preview»"
}

/**
 * Forme textuelle Apple des six tapbacks classiques, et son verbe de retrait.
 * Les libellés sont ceux qu'Apple Messages envoie par SMS aux non-iMessage
 * (`Liked "…"`, `Removed a like from "…"`…) — repris tels quels pour que
 * l'interopérabilité ne dépende pas d'une reformulation. La table est le
 * miroir émetteur de [com.filestech.sms.domain.reaction.IncomingReactionDecoder]
 * (sa table `IOS_TAPBACKS`) : tout ce qui est émis ici doit être décodé là.
 */
private data class IosTapbackVerb(
    val emoji: String,
    val addVerb: String,
    val removeVerb: String,
)

private val IOS_TAPBACK_VERBS = listOf(
    IosTapbackVerb("❤️", "Loved", "Removed a heart from"),
    IosTapbackVerb("👍", "Liked", "Removed a like from"),
    IosTapbackVerb("👎", "Disliked", "Removed a dislike from"),
    IosTapbackVerb("😂", "Laughed at", "Removed a laugh from"),
    IosTapbackVerb("‼️", "Emphasized", "Removed an exclamation from"),
    IosTapbackVerb("❓", "Questioned", "Removed a question from"),
)

/**
 * Résout le verbe Apple d'un emoji classique, ou `null` pour tout autre emoji
 * (réaction custom, multi-emoji). La comparaison ignore le sélecteur de
 * variation U+FE0F : le picker émet "❤️" mais un clavier tiers peut émettre
 * "❤" — c'est la même réaction.
 */
private fun iosTapbackVerbFor(emoji: String): IosTapbackVerb? {
    val bare = emoji.replace("\uFE0F", "")
    return IOS_TAPBACK_VERBS.firstOrNull { it.emoji.replace("\uFE0F", "") == bare }
}

/**
 * Construit le corps SMS au format textuel Apple (`Liked "aperçu"`) — la
 * forme qu'Apple Messages envoie par SMS quand le correspondant n'a pas
 * iMessage, et que Google Messages affiche comme badge natif (« Show iPhone
 * reactions as emoji »). C'est le format par défaut : il est lisible comme
 * une phrase par n'importe quelle app SMS, y compris celles qui ne
 * connaissent pas les réactions.
 *
 * Pourquoi des guillemets DROITS (`"`) et pas les guillemets typographiques
 * (`«»` / `“”`) des autres formats :
 *
 *   - `"` est GSM-7 : il traverse tous les opérateurs et transcodages sans
 *     être touché. Les `«»` du format `EMOJI_WITH_QUOTE` arrivaient déformés
 *     en `<<>>` côté correspondant (`👍<<Hi>>`), et `“”` subissent le même
 *     sort sur les passerelles qui normalisent la ponctuation.
 *   - Le décodeur entrant accepte les deux formes, donc l'aller-retour
 *     Relay → Relay reste un badge.
 *
 * Garanties :
 *
 *   - Body vide (MMS image pure) → fallback `Reacted <emoji>` (ajout) ou
 *     `Removed <emoji>` (retrait), comme [buildTapbackBody] — la forme
 *     verbale exige une citation, il n'y en a pas.
 *   - Emoji hors des six classiques (custom, multi-emoji "❤️👍") → forme
 *     générique `Reacted <emoji> to "…"` / `Removed <emoji> from "…"`,
 *     décodée côté Relay par les regex `TAPBACK_*` / générique.
 *   - Guillemets DANS la citation : conservés tels quels. Le décodeur
 *     matche jusqu'au DERNIER guillemet (ancre `$`), et le matching par
 *     préfixe côté correspondant reste exact puisque l'aperçu est un
 *     préfixe du body d'origine.
 *   - Sanitisation et troncature grapheme-safe identiques à [buildTapbackBody]
 *     (via [truncatedPreview]) pour rester dans 1 segment SMS.
 */
fun buildIosTapbackBody(
    emoji: String,
    originalBody: String,
    isRemoval: Boolean = false,
): String {
    val sanitized = originalBody
        .replace(FORBIDDEN_BODY_CHARS, " ")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (sanitized.isEmpty()) {
        return if (isRemoval) "Removed $emoji" else "Reacted $emoji"
    }
    val verb = iosTapbackVerbFor(emoji)
    return if (verb != null) {
        buildVerbTapback(verb, sanitized, isRemoval)
    } else {
        buildGenericTapback(emoji, sanitized, isRemoval)
    }
}

/** Forme verbale : `Liked "aperçu"` ou `Removed a like from "aperçu"`. */
private fun buildVerbTapback(
    verb: IosTapbackVerb,
    sanitized: String,
    isRemoval: Boolean,
): String {
    val lead = if (isRemoval) verb.removeVerb else verb.addVerb
    val preview = truncatedPreview(sanitized, iosTapbackBudget(lead))
    return "$lead \"$preview\""
}

/** Forme générique : `Reacted <emoji> to "…"` ou `Removed <emoji> from "…"`. */
private fun buildGenericTapback(
    emoji: String,
    sanitized: String,
    isRemoval: Boolean,
): String {
    val preview = truncatedPreview(sanitized, previewBudget(emoji))
    return if (isRemoval) {
        "Removed $emoji from \"$preview\""
    } else {
        "Reacted $emoji to \"$preview\""
    }
}

/**
 * Budget chars UTF-16 pour l'aperçu de la forme verbale : le total tient
 * dans 1 segment (70) = verbe + espace + 2 guillemets + `…` éventuel.
 * Plafonné à [PREVIEW_HARD_MAX], plancher à 8, comme les autres formats.
 */
private fun iosTapbackBudget(leadVerb: String): Int =
    (SMS_UCS2_SEGMENT_CAP - leadVerb.length - IOS_TAPBACK_WRAP_LENGTH)
        .coerceAtMost(PREVIEW_HARD_MAX)
        .coerceAtLeast(8)

/** Wrap fixe de la forme verbale : espace (1) + guillemets (2) + `…` éventuel (1). */
private const val IOS_TAPBACK_WRAP_LENGTH = 1 + 2 + 1

/** Aperçu tronqué grapheme-safe via [safeTake], avec marqueur `…` si coupé. */
private fun truncatedPreview(sanitized: String, budget: Int): String {
    return if (sanitized.length <= budget) {
        sanitized
    } else {
        sanitized.safeTake(budget).trimEnd() + "…"
    }
}

/**
 * v1.8.1 — budget chars UTF-16 pour l'aperçu du format FR. Prend en compte la
 * longueur du nom de l'expéditeur (prefix " a") quand il est présent, pour
 * garder le SMS dans 1 segment UCS-2 même avec emoji multi-codepoint + nom.
 *
 * Le "+2" est pour l'espace + "a" du connecteur "<Nom> a réagi par <emoji>".
 */
private fun previewBudgetForFr(emoji: String, namePrefixLength: Int = 0): Int {
    val nameOverhead = if (namePrefixLength > 0) namePrefixLength + 2 else 0
    return (SMS_UCS2_SEGMENT_CAP - READABLE_FR_WRAP_LENGTH - emoji.length - nameOverhead)
        .coerceAtMost(PREVIEW_HARD_MAX)
        .coerceAtLeast(8)
}

/**
 * v1.3.2 — budget chars UTF-16 pour l'aperçu, calculé pour que le SMS final tienne
 * dans 1 segment UCS-2. Plafonné à [PREVIEW_HARD_MAX] pour préserver une marge
 * sécurité même si un futur emoji descendrait `emoji.length` à 0.
 */
private fun previewBudget(emoji: String): Int =
    (SMS_UCS2_SEGMENT_CAP - TAPBACK_WRAP_LENGTH - emoji.length)
        .coerceAtMost(PREVIEW_HARD_MAX)
        .coerceAtLeast(8) // garantie absolue de garder un peu de contexte

/**
 * v1.3.2 — `String.take` grapheme-safe : recule l'index si on coupe en plein milieu
 * d'un surrogate pair (emoji 4-byte) ou immédiatement après un ZWJ (cluster non
 * terminé). Évite de produire un `�` ou un emoji visuellement cassé côté
 * destinataire — ce qui ferait basculer le SMS en 2 segments ET ferait échouer le
 * parsing iMessage qui attend une trame intacte.
 */
private fun String.safeTake(maxChars: Int): String {
    if (length <= maxChars) return this
    var cut = maxChars
    // 1) si on coupe pile entre les deux moitiés d'un surrogate pair, recule d'1.
    if (cut > 0 && Character.isHighSurrogate(this[cut - 1])) cut--
    // 2) si le dernier caractère est un ZWJ ou un Variation Selector, le cluster
    //    n'est pas terminé — recule jusqu'à un boundary propre (au pire, 4 cran).
    var safetyHops = 0
    while (cut > 0 && safetyHops < 4) {
        val ch = this[cut - 1]
        val code = ch.code
        val isJoinerOrSelector = code == 0x200D || code in 0xFE00..0xFE0F
        if (!isJoinerOrSelector) break
        cut--
        safetyHops++
    }
    return substring(0, cut)
}
