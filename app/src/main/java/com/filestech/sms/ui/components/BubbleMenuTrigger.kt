package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Overflow menu attached to a chat bubble — a quick-reaction row on top
 * (recents + defaults + "+", see [QuickReactionRow]), then Copy /
 * Forward / Reply / Translate / Pin / **Delete** (in that order, with
 * Delete rendered in red at the bottom of the list as the destructive
 * action). Same structure as the desktop bubble popup.
 *
 * Relay: the three-dot trigger beside every bubble is gone — the caller opens
 * this menu from a tap on the bubble itself (see [MessageBubble],
 * [AudioMessageBubble], [MediaAttachmentBubble]). The menu itself is unchanged apart
 * from the rounded corners (~18 dp, Relay). Reply and Translate are optional
 * (a row with neither still renders the menu just for the Delete action).
 *
 * Feedback round 6 — the "Select text" entry is gone with the free text
 * selection (long-press now opens this menu instead); the menu is 10dp wider
 * on each side than the Material default, like the overflow menus.
 *
 * Round 23 — the "React" entry is gone: the reaction row above the items
 * is the way in (tap an emoji to react, "+" for the full picker), so a
 * second entry is redundant. The row also widens the menu to its own
 * width. "Star" is gone too: starring only exempted a message from
 * auto-delete and there is no starred list, screen or marker, so the
 * entry promised more than it did.
 *
 * The menu reuses the same delete pipeline as before (`onDelete()` →
 * `pendingDelete = msg` in `ThreadScreen`), so the destructive confirmation dialog
 * still runs.
 */
@Composable
fun BubbleMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    /**
     * Client mode C2 — nullable, like the other actions: `null`
     * hides the Delete entry (a linked device cannot delete — there
     * is no delete frame in C2). Every hub-mode caller still passes
     * a handler.
     */
    onDelete: (() -> Unit)? = null,
    onReply: (() -> Unit)? = null,
    onTranslate: (() -> Unit)? = null,
    /**
     * Round 23 — opens the full emoji picker, from the "+" at the end of
     * the reaction row ([quickReactions]). No longer a menu item: the row
     * is the way in, like the desktop popup.
     */
    onReact: (() -> Unit)? = null,
    /**
     * v1.3.11 (F3) — copy the bubble's text payload to the system clipboard. Caller passes
     * `null` for bubbles where copy makes no sense (audio MMS, attachment without caption).
     */
    onCopy: (() -> Unit)? = null,
    /**
     * v1.3.11 (F5) — forward the message to another conversation or new recipient. Caller
     * passes `null` for bubbles where forwarding is not yet supported.
     */
    onForward: (() -> Unit)? = null,
    /**
     * Round 23 — the quick-reaction row at the TOP of the menu (recents +
     * defaults + "+", see [QuickReactionRow]), like the desktop bubble
     * popup. The row widens the menu to its own width. `null` = no row,
     * items only.
     */
    quickReactions: QuickReactionConfig? = null,
    /**
     * Round 23 — the message's current reaction, highlighted in the row.
     * `null` = no reaction applied, nothing highlighted.
     */
    currentReaction: String? = null,
    /**
     * Feedback round 5 — bascule « épingler » ([BubbleMenuToggles]).
     * `null` désactive l'entrée. Un seul message épinglé par
     * conversation : épingler un autre message remplace l'épingle
     * courante. Round 23 — pin only: the star toggle is gone with the
     * "Star" entry (see [BubbleMenuToggles]).
     */
    toggles: BubbleMenuToggles? = null,
    modifier: Modifier = Modifier,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        shape = RoundedCornerShape(18.dp),
        // Feedback round 6 — 10dp more breathing room on each side than the
        // Material default, like the overflow menus.
        modifier = modifier.padding(horizontal = 10.dp),
    ) {
        // Round 23 — the reaction row at the top of the menu, like the
        // desktop bubble popup: tap an emoji to react at once (the
        // applied emoji toggles the reaction off), "+" opens the full
        // picker. The divider below it marks the row as its own section
        // above the items.
        if (quickReactions != null) {
            QuickReactionRow(
                recentEmojis = quickReactions.recentEmojis,
                currentReaction = currentReaction,
                onEmojiSelected = { emoji ->
                    onDismissRequest()
                    quickReactions.onSelect(emoji)
                },
                onOpenPicker = {
                    onDismissRequest()
                    onReact?.invoke()
                },
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
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
        // v1.3.11 (F5) — Forward right under Copy: same "I want to do something with
        // this content" intent group, before per-message actions (Reply / Pin).
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
        // Feedback round 5 — « Épingler » / « Désépingler », juste avant
        // l'action destructive : même intention « marquer ce message ».
        // L'icône punaise pleine signale l'état épinglé. Round 23 — the
        // "Star" entry that used to sit here is gone (see the KDoc above).
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
        // Client mode C2 — hidden when `onDelete` is null.
        if (onDelete != null) {
            DropdownMenuItem(
                leadingIcon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_trash),
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
}
