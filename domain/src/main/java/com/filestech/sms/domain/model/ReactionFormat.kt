package com.filestech.sms.domain.model

/**
 * Format du SMS de réaction envoyé au correspondant (choisi dans les réglages).
 *
 *  - [READABLE_FR] : "Réagi par ❤️ à votre message : «…»"
 *  - [TAPBACK_EN]  : "Reacted ❤️ to «…»" (Tapback iMessage / Google Messages récent)
 *  - [EMOJI_ONLY]  : "❤️" seul (compact, perd le contexte)
 *  - [EMOJI_WITH_QUOTE] (v1.9.0) : "❤️ «aperçu du message»" (compact + contexte visuel)
 *  - [IOS_TAPBACK] : `Liked "aperçu"` — la forme textuelle qu'Apple Messages envoie
 *    par SMS aux non-iMessage (et que Google Messages affiche comme badge via
 *    « Show iPhone reactions as emoji »). Guillemets droits ASCII : ils
 *    survivent à tous les transcodages opérateur, contrairement aux «» qui
 *    arrivaient déformés en `<<>>` côté correspondant.
 *
 * Type métier : vit dans `domain/`. Persisté **par nom** dans DataStore
 * (`SendingSettings.reactionFormat`), donc le package est indifférent au format sur disque.
 * Pour le décodage entrant, voir [com.filestech.sms.domain.reaction.IncomingReactionDecoder].
 */
enum class ReactionFormat { READABLE_FR, TAPBACK_EN, EMOJI_ONLY, EMOJI_WITH_QUOTE, IOS_TAPBACK }
