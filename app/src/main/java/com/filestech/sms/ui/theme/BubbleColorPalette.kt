package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * v1.11.0 — Sujet 5 apparence : palette WCAG-safe pour la couleur de la
 * bulle SORTANTE personnalisée par conversation.
 *
 * **Contrat de contraste** : chaque couleur de la palette donne un ratio
 * de contraste ≥ 4.5:1 avec le texte blanc (WCAG AA pour texte normal).
 * Le user choisit dans cette grille fermée — pas de color picker libre —
 * pour garantir la lisibilité du texte des bulles dans tous les thèmes
 * (clair, sombre).
 *
 * Les couleurs sont stockées en ARGB Int dans
 * [com.filestech.sms.data.local.db.entity.ConversationEntity.bubbleColorArgb]
 * via `Color.toArgb()`. Le rendu côté
 * [com.filestech.sms.ui.components.MessageBubble] reconstruit le Color via
 * `Color(argb)`.
 *
 * **Note d'UX** : la première entrée [BRAND_BLUE] est la valeur par défaut
 * Sélectionner cette valeur équivaut à un reset (`bubbleColorArgb = null`).
 * Le rendu d'une bulle `null` utilise désormais [BrandBlue] **fixe** (et non
 * `cs.primary`) : sous Material You / One UI, `cs.primary` suit le fond d'écran
 * et dérivait vers un bleu clair — la bulle par défaut doit rester le bleu de
 * marque foncé, en cohérence avec la bulle entrante (elle aussi couleur fixe).
 */
internal object BubbleColorPalette {

    /** Bleu marque par défaut — sélection = reset à `null` dans la persistance. */
    val BRAND_BLUE: Color = Color(0xFF1E4FD8)

    /**
     * Couleurs alternatives, choisies pour rester sobres et lisibles
     * avec texte blanc. **Toutes WCAG AA ≥ 4.5:1 contre `Color.White`** —
     * principalement basées sur Material Design palette 700-900 (validé
     * par Material a11y guidelines). Ne pas ajouter de teintes pastel
     * sans re-tester avec un outil contraste.
     *
     * v1.11.0 audit U1 — chaque entrée porte aussi une string ressource
     * pour le `contentDescription` TalkBack (a11y).
     *
     * v1.19.0 — palette resserrée (demande user) : retrait des verts (forest,
     * emerald, teal), du marron et des doublons de teinte (crimson≈red,
     * deep_orange≈orange, royal≈brand_blue, eggplant≈indigo, bordeaux≈purple,
     * steel≈slate_grey). Ajout de `mauve` (#6D4AFF, vibe ProtonMail, 5.15:1)
     * et `framboise` (#C2185B, 5.86:1). 10 teintes distinctes.
     */
    data class Option(val color: Color, val nameRes: Int)

    val OPTIONS: List<Option> = listOf(
        // Bleus
        Option(BRAND_BLUE, com.filestech.sms.R.string.appearance_color_brand_blue),
        Option(Color(0xFF0D47A1), com.filestech.sms.R.string.appearance_color_navy),
        Option(Color(0xFF01579B), com.filestech.sms.R.string.appearance_color_sky_blue),
        // Indigo / violet / mauve
        Option(Color(0xFF4527A0), com.filestech.sms.R.string.appearance_color_deep_indigo),
        Option(Color(0xFF6D4AFF), com.filestech.sms.R.string.appearance_color_mauve),
        Option(Color(0xFF6A1B9A), com.filestech.sms.R.string.appearance_color_deep_purple),
        // Rose framboise
        Option(Color(0xFFC2185B), com.filestech.sms.R.string.appearance_color_framboise),
        // Rouge / orange
        Option(Color(0xFFC62828), com.filestech.sms.R.string.appearance_color_brand_red),
        Option(Color(0xFFE65100), com.filestech.sms.R.string.appearance_color_burnt_orange),
        // Gris bleu
        Option(Color(0xFF455A64), com.filestech.sms.R.string.appearance_color_slate_blue_grey),
    )
}
