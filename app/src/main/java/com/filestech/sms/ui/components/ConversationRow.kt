package com.filestech.sms.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.VolumeOff
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.ui.theme.BrandBlocked
import com.filestech.sms.ui.theme.BrandDanger
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.LocalMessageTextFactor
import com.filestech.sms.ui.theme.MessageTextSize
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.blockedTextColor
import com.filestech.sms.ui.theme.scaledBy
import com.filestech.sms.ui.util.relativeRowLabel
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * One row of the main conversations list, in the Relay design-system geometry:
 *
 *  - 72dp row (minimum — it grows when the content needs it, e.g. a second preview line
 *    under a disambiguating number), 16dp horizontal inset, 48dp [RelayAvatar] leading,
 *    12dp gap to the text.
 *  - Name 17sp/500, preview 14sp/400 in the secondary colour, two lines; time 13sp in the
 *    secondary colour. The name and preview still follow the message-text-size setting
 *    (see below); the time is layout-fixed.
 *  - Circular unread pill in the brand primary, min 20dp, white count, "99+" cap.
 *  - **Pinned / muted** show as tiny outline icons inline next to the title so they don't fight
 *    the avatar for attention.
 *
 * The name and preview read [RelayText] styles, which an explicit style would otherwise
 * shield from [MessageTextSize]: the factor is read above the wrapper and applied with
 * [scaledBy] so the setting keeps working on this row. The time and the pill count stay
 * fixed — they are layout, not reading text.
 *
 * The component is stateless: tap opens the thread, long-press triggers [onLongClick] (used by
 * [SwipeableConversationRow] to surface the delete confirmation without forcing the user to do
 * a precise drag-to-dismiss gesture).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConversationRow(
    conversation: Conversation,
    onClick: () -> Unit,
    showAvatars: Boolean = true,
    previewLines: Int = 1,
    /**
     * Contact photo from the system contacts, resolved by the list ViewModel. The
     * per-conversation custom avatar ([Conversation.avatarUri]) still wins; when both are
     * null the avatar falls back to initials.
     */
    contactPhotoUri: String? = null,
    /**
     * v1.23.x — numéro affiché sous le nom UNIQUEMENT pour lever l'ambiguïté quand plusieurs
     * conversations partagent le même nom de contact (ex. un contact avec un numéro FR et un
     * numéro étranger). `null` = rien affiché (cas normal). Calculé par l'écran liste.
     */
    subtitleNumber: String? = null,
    onLongClick: (() -> Unit)? = null,
    /**
     * v1.25.3 — corbeille de fin de ligne, affichée **uniquement** sur une conversation bloquée.
     * Les conversations bloquées restent désormais dans la liste au lieu d'être supprimées : c'est
     * là, et seulement là, qu'un accès direct à la suppression a du sens. Ailleurs, l'appui long
     * et le balayage l'offrent déjà, et un troisième chemin alourdirait chaque ligne pour rien.
     */
    onDelete: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    // v1.28.3 — la règle de titre vit sur le modèle (nom choisi > nom résolu > numéros).
    val title = conversation.title
    val unread = conversation.unreadCount > 0

    // v1.25.3 — mise en évidence des conversations bloquées : fond rouge très pâle + bordure.
    // Les modificateurs ne sont composés que dans ce cas, pour ne rien coûter aux lignes
    // normales, qui sont l'écrasante majorité d'une liste.
    val blockedDecoration = if (conversation.blocked) {
        Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrandBlocked.copy(alpha = 0.08f))
            .border(1.dp, BrandBlocked.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
    } else {
        Modifier
    }

    val formatters = rememberChatFormatters()
    // Message-text-size factor, read above [MessageTextSize] (which masks it to 1f inside).
    val textFactor = LocalMessageTextFactor.current
    // Preview text: 14sp on an 18sp line (was [RelayText.Preview], 15sp/20sp) — one point
    // smaller, so two lines fit the row without crowding. The message-text-size setting
    // still scales it, like before.
    val previewStyle =
        RelayText.Preview.copy(fontSize = 14.sp, lineHeight = 18.sp).scaledBy(textFactor)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(blockedDecoration)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showAvatars) {
                ConversationAvatar(conversation, contactPhotoUri)
                // Feedback round 6 — 17dp between the avatar and the text
                // (was 12dp): a touch more air on the home screen.
                Spacer(Modifier.width(17.dp))
            }
            // Taille du texte des messages : la colonne de texte seulement — l'avatar écrit ses
            // initiales dans un cercle de taille fixe, qu'un texte agrandi déborderait.
            MessageTextSize {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = title,
                            style = RelayText.ConvoName.scaledBy(textFactor),
                            color = cs.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            // 8dp of air before the date: the name cuts off
                            // earlier and never runs right up against it.
                            modifier = Modifier.weight(1f).padding(end = 8.dp),
                        )
                        if (conversation.muted) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Outlined.VolumeOff,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = cs.onSurfaceVariant,
                            )
                            Spacer(Modifier.width(6.dp))
                        }
                        // v1.6.1 (audit PERF-03) — `relativeRowLabel` alloue 2 Calendar
                        // par appel ; sur liste de 50 conversations qui recompose lors d'une
                        // arrivée SMS, c'est 100 allocations Calendar inutiles. La clef
                        // `lastMessageAt` garantit le recalcul si la date change réellement.
                        val dateLabel = remember(conversation.lastMessageAt) {
                            formatters.relativeRowLabel(conversation.lastMessageAt)
                        }
                        // Phase 8 — l'heure est du méta-texte de mise en page : style
                        // explicite (elle échappe déjà au coefficient « Taille du texte des
                        // messages ») dans [FixedTypeSize] (elle échappe aussi à l'échelle de
                        // police d'Android).
                        FixedTypeSize {
                            Text(
                                text = dateLabel,
                                // Feedback round 10 — the date reads one
                                // weight step bolder than the meta text
                                // (Medium, was Normal) so it stands apart
                                // from the preview like the reference list.
                                style = RelayText.Timestamp.copy(
                                    fontWeight = FontWeight.Medium,
                                ),
                                color = cs.onSurfaceVariant,
                            )
                        }
                    }
                    if (!subtitleNumber.isNullOrBlank()) {
                        // v1.23.x — numéro sous le nom pour distinguer deux conversations du même
                        // contact (noms identiques, numéros différents).
                        Text(
                            text = subtitleNumber,
                            style = RelayText.Timestamp.scaledBy(textFactor),
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.size(3.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // v1.25.3 — « Bloqué » sur la ligne d'aperçu et non sur celle du titre : cette
                        // dernière se termine par l'horodatage relatif (« jeu. », « 14:32 »), et le
                        // titre prenant toute la largeur restante, le libellé s'y retrouvait collé à
                        // la date, les deux se lisant comme un seul bloc.
                        //
                        // Il double le fond et la bordure : la couleur seule ne doit jamais porter une
                        // information (daltonisme, contraste réduit), et TalkBack n'annonce pas un
                        // fond coloré.
                        if (conversation.blocked) {
                            Text(
                                text = androidx.compose.ui.res.stringResource(
                                    id = com.filestech.sms.R.string.conversation_blocked_label,
                                ),
                                style = previewStyle.copy(
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                // Phase 8 — l'orange [BrandBlocked] ne donnait que 3.79:1 sur
                                // fond clair pour du texte ; voir [blockedTextColor]. Le fond
                                // et la bordure gardent [BrandBlocked].
                                color = blockedTextColor(cs),
                                maxLines = 1,
                            )
                            Text(
                                text = " · ",
                                style = previewStyle,
                                color = cs.onSurfaceVariant,
                            )
                        }
                        val draftText = conversation.draft
                        Text(
                            text = if (!draftText.isNullOrBlank()) {
                                androidx.compose.ui.res.stringResource(
                                    id = com.filestech.sms.R.string.conversations_draft_prefix,
                                    draftText,
                                )
                            } else {
                                // v1.27.2 (relecture Codex 2026-08-04) — repli quand l'aperçu est
                                // vide alors qu'un dernier message existe.
                                //
                                // `conversations.last_message_preview` se reconstruit depuis
                                // `messages.body`, or le corps d'un MMS SANS LÉGENDE est vide : son
                                // libellé utile (« 🖼️ … », sujet) n'est posé qu'à l'insertion et
                                // aucune colonne ne le porte par message. Tout recalcul d'aperçu le
                                // perd donc — à la suppression d'un message, à la purge d'historique
                                // comme à la réconciliation de synchro. La ligne devenait alors
                                // MUETTE, sans rien dire de ce qu'elle contenait.
                                //
                                // Corrigé ici plutôt qu'en base : une colonne supplémentaire
                                // imposerait une migration Room sur une base chiffrée contenant tous
                                // les messages, pour un défaut d'affichage. Le repli couvre les
                                // trois chemins d'un coup.
                                //
                                // `lastMessageAt > 0` distingue « message sans texte » de
                                // « conversation sans aucun message » — cette dernière reste muette,
                                // ce qui est exact.
                                val preview = conversation.lastMessagePreview.orEmpty()
                                if (preview.isBlank() && conversation.lastMessageAt > 0L) {
                                    androidx.compose.ui.res.stringResource(
                                        id = com.filestech.sms.R.string.conversations_preview_attachment,
                                    )
                                } else {
                                    preview
                                }
                            },
                            style = previewStyle,
                            color = cs.onSurfaceVariant,
                            maxLines = previewLines,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (unread) {
                            Spacer(Modifier.width(8.dp))
                            UnreadChip(count = conversation.unreadCount)
                        }
                    }
                }
            }
            if (conversation.blocked && onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Outlined.Delete,
                        contentDescription = androidx.compose.ui.res.stringResource(
                            id = com.filestech.sms.R.string.action_delete,
                        ),
                        tint = BrandDanger,
                    )
                }
            }
        }
    }
}

/**
 * The leading avatar of a list row: two overlapping member avatars for a
 * group with no custom photo, the single avatar otherwise — a custom group
 * photo still wins, and 1-to-1 rows are untouched.
 */
@Composable
private fun ConversationAvatar(
    conversation: Conversation,
    contactPhotoUri: String?,
) {
    if (conversation.isGroup && conversation.avatarUri == null) {
        RelayGroupAvatar(members = groupMemberAvatars(conversation))
    } else {
        RelayAvatar(
            label = conversation.title,
            customUri = conversation.avatarUri ?: contactPhotoUri,
            isGroup = conversation.isGroup,
            // Feedback round 10 — a 1-to-1 thread with someone who
            // is not a contact (no display name) gets the grey
            // person-glyph avatar instead of digit initials on blue.
            isUnknownContact = !conversation.isGroup &&
                conversation.displayName == null,
        )
    }
}

/**
 * The first two members of a group, as the row's two overlapping avatars
 * see them. Labels come from the group's resolved display name — the
 * contact names joined in address order — and fall back to the raw numbers
 * when the name is missing or its parts don't line up with the addresses
 * (a name containing ", " would split wrong, and a stale name must never
 * put the wrong initials on a member). A member whose label is just their
 * number has no contact name, so they get the unknown-contact grey person
 * glyph instead of digit initials, like a 1-to-1 row.
 */
private fun groupMemberAvatars(conversation: Conversation): List<GroupMemberAvatar> {
    val addresses = conversation.addresses
    val labels = conversation.displayName
        ?.split(", ")
        ?.takeIf { it.size == addresses.size }
        ?: addresses.map { it.raw }
    return addresses.take(2).mapIndexed { index, address ->
        val label = labels.getOrNull(index) ?: address.raw
        GroupMemberAvatar(
            label = label,
            isUnknownContact = label == address.raw,
        )
    }
}

/**
 * Circular pill showing the unread count. Larger than Material's stock `Badge` so the digits
 * are legible without zooming, and shaped so a single digit produces a perfect circle while
 * two-digit values expand into a pill. "99+" is the cap to avoid ridiculous widths.
 * Min 20dp tall, 10dp corners, 12sp semibold count — the reference geometry.
 */
@Composable
private fun UnreadChip(count: Int) {
    val cs = MaterialTheme.colorScheme
    val label = if (count > 99) "99+" else count.toString()
    // Hauteur MINIMALE et non plus fixe : la pastille suit la police d'Android, qui coupait
    // déjà le chiffre au-delà de 22 dp. Coins à 10 dp : à police normale, rendu identique
    // au pixel près (mesuré ; un arrondi à 50 % différait de quelques pixels
    // d'anticrénelage).
    Box(
        modifier = Modifier
            .heightIn(min = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.primary)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Phase 8 — le compteur est du méta-texte de mise en page : il reste à 12sp
        // quelle que soit l'échelle de police (voir [FixedTypeSize]).
        FixedTypeSize {
            Text(
                text = label,
                style = RelayText.BubbleMeta.copy(fontWeight = FontWeight.SemiBold),
                color = cs.onPrimary,
                maxLines = 1,
            )
        }
    }
}
