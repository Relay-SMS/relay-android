package com.filestech.sms.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.LocalMessageTextFactor
import com.filestech.sms.ui.theme.MessageTextSize
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.scaledBy
import com.filestech.sms.ui.util.relativeRowLabel
import com.filestech.sms.ui.util.rememberChatFormatters

/**
 * v1.28.8 (issue #17) — un message trouvé par la recherche : sa conversation, sa date, et l'extrait
 * où les passages trouvés sont mis en évidence. Un appui ouvre la conversation.
 *
 * Même géométrie que [ConversationRow] : ligne 72 dp minimum, avatar 48 dp, nom 17sp/500,
 * date 13sp, extrait 14sp/400 (deux lignes ici, pour laisser voir le contexte du passage trouvé).
 */
@Composable
fun MessageSearchRow(
    hit: MessageSearchHit,
    /**
     * `null` = ligne non cliquable (mode sélection) : ni action, ni effet d'appui. Sa sémantique reste
     * FUSIONNÉE (audit 3 axes, U1) : sans `clickable`, TalkBack annoncerait sinon l'avatar, le titre,
     * la date et l'extrait comme quatre éléments séparés.
     */
    onClick: (() -> Unit)?,
    showAvatars: Boolean = true,
    /**
     * Contact photo from the system contacts, resolved by the list ViewModel. The
     * per-conversation custom avatar ([com.filestech.sms.domain.model.Conversation.avatarUri])
     * still wins; when both are null the avatar falls back to initials.
     */
    contactPhotoUri: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val conversation = hit.conversation
    val formatters = rememberChatFormatters()
    val dateLabel = remember(hit.date) { formatters.relativeRowLabel(hit.date) }
    val surligne = SpanStyle(fontWeight = FontWeight.SemiBold, color = cs.primary)
    val extrait = remember(hit.excerpt, surligne) { extraitSurligne(hit.excerpt, surligne) }
    // Facteur taille du texte, lu au-dessus de [MessageTextSize] comme dans [ConversationRow].
    val textFactor = LocalMessageTextFactor.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            )
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showAvatars) {
            RelayAvatar(
                label = conversation.title,
                customUri = conversation.avatarUri ?: contactPhotoUri,
                isGroup = conversation.isGroup,
                // Feedback round 10 — grey person avatar for a non-contact.
                isUnknownContact = !conversation.isGroup &&
                    conversation.displayName == null,
            )
            Spacer(Modifier.width(12.dp))
        }
        // Taille du texte des messages : la colonne de texte seulement, comme ConversationRow.
        MessageTextSize {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = conversation.title,
                        style = RelayText.ConvoName.scaledBy(textFactor),
                        color = cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    // Phase 8 — l'heure est du méta-texte de mise en page, fixe (voir
                    // [FixedTypeSize]), comme dans [ConversationRow].
                    FixedTypeSize {
                        Text(
                            text = dateLabel,
                            style = RelayText.Timestamp,
                            color = cs.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.size(3.dp))
                Text(
                    text = extrait,
                    // Same 14sp/18sp preview as [ConversationRow] — the two rows share a list.
                    style = RelayText.Preview.copy(fontSize = 14.sp, lineHeight = 18.sp)
                        .scaledBy(textFactor),
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Transforme l'extrait marqué par l'index en texte mis en évidence. Les marqueurs ne sont JAMAIS
 * rendus. Un marqueur orphelin — l'extrait peut couper un passage — ouvre ou ferme simplement la
 * mise en évidence, sans rien casser : une fin sans début est ignorée, un début sans fin court
 * jusqu'au bout de l'extrait.
 */
internal fun extraitSurligne(extrait: String, surligne: SpanStyle): AnnotatedString = buildAnnotatedString {
    val debut = MessageSearchHit.MARK_START.single()
    val fin = MessageSearchHit.MARK_END.single()
    var ouvert = false
    for (c in extrait) {
        when {
            c == debut -> if (!ouvert) {
                pushStyle(surligne)
                ouvert = true
            }
            c == fin -> if (ouvert) {
                pop()
                ouvert = false
            }
            else -> append(c)
        }
    }
    if (ouvert) pop()
}
