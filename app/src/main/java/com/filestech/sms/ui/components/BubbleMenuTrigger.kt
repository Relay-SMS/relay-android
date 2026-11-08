package com.filestech.sms.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.starColor

/**
 * Overflow menu attached to a chat bubble — surfaces Copy / Select text / Forward /
 * Reply / Translate / React / Star / **Delete** (in that order, with Delete rendered
 * in red at the bottom of the list as the destructive action).
 *
 * Relay: the three-dot trigger beside every bubble is gone — the caller opens
 * this menu from a tap on the bubble itself (see [MessageBubble],
 * [AudioMessageBubble], [MediaAttachmentBubble]). The menu itself is unchanged apart
 * from the rounded corners (~18 dp, Relay). Reply and Translate are optional
 * (a row with neither still renders the menu just for the Delete action).
 *
 * The menu reuses the same delete pipeline as before (`onDelete()` →
 * `pendingDelete = msg` in `ThreadScreen`), so the destructive confirmation dialog
 * still runs.
 */
@Composable
fun BubbleMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onDelete: () -> Unit,
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F3) — copy the bubble's text payload to the system clipboard. Caller passes
     * `null` for bubbles where copy makes no sense (audio MMS, attachment without caption).
     */
    onCopy: (() -> Unit)? = null,
    /**
     * v1.28.6 — ouvre la sélection libre d'un extrait ([MessageTextSelectionDialog]). Même
     * disponibilité que [onCopy] : `null` sans corps de texte.
     */
    onSelectText: (() -> Unit)? = null,
    /**
     * v1.3.11 (F5) — forward the message to another conversation or new recipient. Caller
     * passes `null` for bubbles where forwarding is not yet supported.
     */
    onForward: (() -> Unit)? = null,
    /**
     * v1.26.1 (audit F2) — bascule « favori ». `starred` porte l'état courant pour choisir le
     * libellé. `null` désactive l'entrée (mode leurre).
     */
    starred: Boolean = false,
    /**
     * Feedback round 5 — bascules « favori » / « épingler »
     * ([BubbleMenuToggles]). `null` désactive les deux entrées. Un seul
     * message épinglé par conversation : épingler un autre message remplace
     * l'épingle courante.
     */
    toggles: BubbleMenuToggles? = null,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(18.dp),
        modifier = modifier,
    ) {
        // v1.3.11 (F3) — Copy ranked first because it's the most-requested action
        // (clipboard fed by long-press is the iMessage / Google Messages convention).
        if (onCopy != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_copy)) },
                onClick = {
                    onDismissRequest()
                    onCopy()
                },
            )
        }
        // v1.28.6 — « Sélectionner le texte » juste sous Copier : même intention, la
        // partie au lieu du tout. Aussi joignable par appui long sur la bulle, qui ne copie
        // plus le message entier (doublon de Copier).
        if (onSelectText != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.SelectAll,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_select_text)) },
                onClick = {
                    onDismissRequest()
                    onSelectText()
                },
            )
        }
        // v1.3.11 (F5) — Forward right under Copy: same "I want to do something with
        // this content" intent group, before per-message actions (Reply / React).
        if (onForward != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Send,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_forward)) },
                onClick = {
                    onDismissRequest()
                    onForward()
                },
            )
        }
        if (onReply != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Reply,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_reply)) },
                onClick = {
                    onDismissRequest()
                    onReply()
                },
            )
        }
        if (onTranslate != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.Translate,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_translate)) },
                onClick = {
                    onDismissRequest()
                    onTranslate()
                },
            )
        }
        // v1.3.0 — entrée "Réagir" : ouvre le picker emoji côté ThreadScreen. Placée
        // avant Delete pour respecter le pattern "destructive en dernier".
        if (onReact != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.EmojiEmotions,
                        contentDescription = null,
                    )
                },
                text = { Text(stringResource(R.string.action_react)) },
                onClick = {
                    onDismissRequest()
                    onReact()
                },
            )
        }
        // v1.26.1 (audit F2) — « Favori ». `MessageDao.setStarred` existait avec ZÉRO
        // appelant, alors que trois chaînes promettaient à l'utilisateur que « les messages
        // favoris ne sont jamais supprimés » et que la purge automatique s'appuie réellement
        // sur le drapeau (`DELETE … AND starred = 0`). La promesse ne protégeait donc rien :
        // aucun message ne POUVAIT être mis en favori. Placé juste avant la suppression,
        // parce que c'est précisément ce dont il protège.
        if (toggles != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = null,
                        // v1.27.0 — l'étoile PLEINE porte l'or ; la creuse garde la couleur
                        // d'icône du menu. Teinter les deux ferait de l'or une décoration au
                        // lieu d'un état, et l'utilisateur ne lirait plus la différence.
                        tint = if (starred) {
                            starColor(MaterialTheme.colorScheme)
                        } else {
                            LocalContentColor.current
                        },
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (starred) R.string.action_unstar else R.string.action_star,
                        ),
                    )
                },
                onClick = {
                    onDismissRequest()
                    toggles.onToggleStar()
                },
            )
        }
        // Feedback round 5 — « Épingler » / « Désépingler », juste après le
        // favori : même intention « marquer ce message », avant l'action
        // destructive. L'icône punaise pleine signale l'état épinglé.
        if (toggles != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Outlined.PushPin,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        stringResource(
                            if (toggles.isPinned) R.string.action_unpin else R.string.action_pin,
                        ),
                    )
                },
                onClick = {
                    onDismissRequest()
                    toggles.onTogglePin()
                },
            )
        }
        // Destructive action always at the bottom, painted in error red.
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    stringResource(R.string.action_delete),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = {
                onDismissRequest()
                onDelete()
            },
        )
    }
}
