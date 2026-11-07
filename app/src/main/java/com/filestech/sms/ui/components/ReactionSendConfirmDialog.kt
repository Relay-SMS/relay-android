package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayText

/**
 * v1.3.1 — confirmation du PREMIER envoi de réaction par SMS au correspondant. Affiché
 * une fois (au tout premier tap réaction quand la préférence
 * `sendReactionsToRecipient = true` est ON et que `reactionConfirmDismissed = false`).
 *
 * Le dialog présente :
 *
 *  - l'emoji prêt à être envoyé (gros, sans texte parasite),
 *  - le destinataire (nom de contact ou numéro brut),
 *  - une explication courte que cela enverra UN SMS au tarif normal,
 *  - une case à cocher "Ne plus demander" (pré-cochée par défaut pour fluidifier),
 *  - bouton Annuler (l'envoi n'a pas lieu, le badge local reste posé),
 *  - bouton Envoyer (déclenche le SMS via [onConfirm]).
 *
 * Si l'utilisateur dismisse (back / clic hors dialog), aucun envoi n'a lieu mais le
 * badge local reste posé — cohérent avec la sémantique "le badge est local, le SMS est
 * une couche optionnelle de communication".
 */
@Composable
fun ReactionSendConfirmDialog(
    emoji: String,
    recipientLabel: String,
    onConfirm: (neverAskAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var neverAskAgain by remember { mutableStateOf(true) }

    // X4 audit v1.3.1 — autofocus sur "Annuler" (action non destructive) cohérent avec le
    // pattern projet pour tous les dialogs à conséquence financière/destructive
    // (DestructiveConfirmDialog, PurgeNowConfirmDialog, nuke data). Un user qui appuie
    // sur Entrée / clique vite ne déclenche pas un SMS facturé par accident.
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { cancelFocusRequester.requestFocus() }
    }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.reaction_send_confirm_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Preview gros + nom destinataire
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = emoji, fontSize = 40.sp)
                    Spacer(Modifier.size(12.dp))
                    Text(
                        text = recipientLabel,
                        style = RelayText.MessageBody.copy(fontWeight = FontWeight.Medium),
                    )
                }
                Text(
                    text = stringResource(R.string.reaction_send_confirm_body),
                    style = RelayText.Timestamp,
                    color = cs.onSurfaceVariant,
                )
                // Case "Ne plus demander". X6 audit v1.3.1 : `Modifier.toggleable` avec
                // `role = Role.Checkbox` regroupe Row + Checkbox dans un seul nœud a11y
                // (TalkBack/Switch Access annoncent une seule cible cliquable). La Checkbox
                // reçoit `onCheckedChange = null` pour ne PAS doubler le toggle quand le
                // tap atterrit pile sur elle ; toute la rangée est cliquable.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = neverAskAgain,
                            onValueChange = { neverAskAgain = it },
                            role = Role.Checkbox,
                        )
                        .padding(vertical = 4.dp),
                ) {
                    Checkbox(checked = neverAskAgain, onCheckedChange = null)
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = stringResource(R.string.reaction_send_confirm_never_ask),
                        style = RelayText.MessageBody,
                    )
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_send),
                onClick = { onConfirm(neverAskAgain) },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
                modifier = Modifier.focusRequester(cancelFocusRequester),
            )
        },
    )
}
