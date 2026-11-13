package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
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
 * Round 21 — tap = [onClick] (typically: removes the reaction, via
 * `setReaction(id, null)`). The badge lost its tap handler in the UI
 * refresh and a sent reaction could no longer be removed at all; this
 * restores the original single-tap removal. TalkBack announces it as a
 * button (« Réaction ❤️, double-tap pour retirer »).
 */
@Composable
fun EmojiReactionBadge(
    emoji: String,
    bubbleColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    // v1.5.0 — pill shape that AUTO-GROWS when the reaction string carries multiple
    // emojis (multi-react feature). A single-emoji badge keeps a compact pill
    // appearance (min 24 dp, rounded-rect with corner radius half the height). Adding
    // a 2nd, 3rd or 4th emoji lets the Box widen naturally, so "❤️👍🎉" renders as a
    // horizontal pill instead of being cropped inside a circle. The 1 dp border in
    // `cs.outlineVariant` outlines the pill against the thread canvas where it overhangs
    // the bubble, since the pill now shares the bubble's own background colour.
    // Feedback round 7 — `outlineVariant`, not `outline`: the round-6 palette
    // restore made `outline` a mid-contrast grey (dark in light mode, light in
    // dark mode), so the badge border read as swapped. `outlineVariant` is the
    // subtle same-side grey in both themes, as it was before round 6.
    val shape = RoundedCornerShape(12.dp)
    // v1.6.0 (audit U2) — a11y : TalkBack annonce "Réaction <emoji>, double-tap
    // pour retirer" au lieu d'un Box muet. `Role.Button` cible explicitement
    // les services d'a11y, `onClickLabel` qualifie l'action.
    val removeLabel = stringResource(R.string.reaction_badge_remove_action)
    val badgeContentDescription = stringResource(R.string.reaction_badge_content_description, emoji)
    Box(
        modifier = modifier
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .clip(shape)
            .background(bubbleColor)
            .border(width = 1.dp, color = cs.outlineVariant, shape = shape)
            .semantics {
                contentDescription = badgeContentDescription
                role = Role.Button
            }
            .clickable(onClickLabel = removeLabel, onClick = onClick)
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
 *
 * Round 21 — [onRemoveReaction] restores the badge tap: tapping the badge
 * removes the reaction (single tap, no confirmation), as it did before
 * the UI refresh dropped the handler.
 */
@Composable
fun BubbleReactionOverlay(
    reactionEmoji: String?,
    isOutgoing: Boolean,
    bubbleColor: Color,
    onRemoveReaction: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (reactionEmoji == null) {
        content()
        return
    }
    // Feedback round 6 — the Box reserves 20dp below the bubble: the badge
    // is offset 20dp down, so it hangs exactly that far past the bubble's
    // bottom edge. Without the reserve it overflowed the item's bounds and
    // the NEXT message (laid out right below, drawn on top) covered it —
    // the badge looked cut off whenever another message followed.
    // Alignment is inside the padding, so the badge still straddles the
    // bubble's bottom edge exactly as before; the item just owns the space
    // the badge hangs into now.
    Box(modifier = Modifier.padding(bottom = 20.dp)) {
        content()
        EmojiReactionBadge(
            emoji = reactionEmoji,
            bubbleColor = bubbleColor,
            onClick = onRemoveReaction,
            modifier = Modifier
                .align(if (isOutgoing) Alignment.BottomStart else Alignment.BottomEnd)
                // Feedback round 5 — the badge hung 12dp below the edge, so it
                // overlapped the bubble's bottom by its height minus 12dp
                // (~15dp: the 10dp bottom padding plus 5dp of the text or the
                // timestamp above it, measured on the dark-mode screenshot).
                // At 20dp it overlaps only ~7dp, inside the bottom padding,
                // and clears both the text and the time while still
                // straddling the edge.
                .offset(
                    x = if (isOutgoing) (-8).dp else 8.dp,
                    y = 20.dp,
                ),
        )
    }
}
