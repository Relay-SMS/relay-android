package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R

/**
 * v1.3.0 — Petit cercle 28 dp affichant l'emoji de réaction posé sur un message.
 *
 * Relay UI refresh : le badge devient une petite pastille qui chevauche le coin
 * de la bulle et reprend LA COULEUR DE LA BULLE qu'elle décore — gris entrant,
 * bleu sortant, dans les deux thèmes. (Avant : fond blanc fixe, qui rendait une
 * pastille claire sur les bulles sombres du thème sombre.)
 *
 * Le badge n'est pas cliquable : une réaction envoyée ne se retire plus en la
 * touchant. TalkBack l'annonce comme un simple libellé (« Réaction ❤️ »), pas
 * comme un bouton.
 */
@Composable
fun EmojiReactionBadge(
    emoji: String,
    bubbleColor: Color,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // v1.5.0 — pill shape that AUTO-GROWS when the reaction string carries multiple
    // emojis (multi-react feature). A single-emoji badge keeps a compact pill
    // appearance (min 24 dp, rounded-rect with corner radius half the height). Adding
    // a 2nd, 3rd or 4th emoji lets the Box widen naturally, so "❤️👍🎉" renders as a
    // horizontal pill instead of being cropped inside a circle. The 1 dp border in
    // `cs.outline` outlines the pill against the thread canvas where it overhangs
    // the bubble, since the pill now shares the bubble's own background colour.
    val shape = RoundedCornerShape(12.dp)
    // v1.6.0 (audit U2) — a11y : TalkBack annonce "Réaction <emoji>" au lieu d'un Box
    // muet. Pas de `Role.Button` : le badge n'est pas une action (tap = rien).
    val badgeContentDescription = stringResource(R.string.reaction_badge_content_description, emoji)
    Box(
        modifier = modifier
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .clip(shape)
            .background(bubbleColor)
            .border(width = 1.dp, color = cs.outline, shape = shape)
            .semantics {
                contentDescription = badgeContentDescription
            }
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = emoji, fontSize = 14.sp)
    }
}

/**
 * v1.3.0 — Wrap arbitraire d'une bulle pour ajouter un [EmojiReactionBadge] en surplomb
 * du coin bas, **côté opposé au speaker**. Réutilisé identiquement par [MessageBubble] et
 * [AudioMessageBubble] — pas de duplication de la logique d'alignement.
 *
 * Le early-return quand `reactionEmoji == null` garantit zéro overhead de composition pour
 * les bulles sans réaction (= 99 % des cas).
 *
 * Relay UI refresh : le badge reprend la couleur de fond de la bulle qu'il décore
 * (grise entrante, bleue sortante, dans les deux thèmes) et descend de quelques px
 * pour ne plus couvrir l'horodatage en bas de bulle.
 */
@Composable
fun BubbleReactionOverlay(
    reactionEmoji: String?,
    isOutgoing: Boolean,
    bubbleColor: Color,
    content: @Composable () -> Unit,
) {
    if (reactionEmoji == null) {
        content()
        return
    }
    Box {
        content()
        EmojiReactionBadge(
            emoji = reactionEmoji,
            bubbleColor = bubbleColor,
            modifier = Modifier
                .align(if (isOutgoing) Alignment.BottomStart else Alignment.BottomEnd)
                .offset(
                    x = if (isOutgoing) (-8).dp else 8.dp,
                    y = 12.dp,
                ),
        )
    }
}
