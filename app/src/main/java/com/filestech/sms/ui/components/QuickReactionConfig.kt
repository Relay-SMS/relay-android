package com.filestech.sms.ui.components

/**
 * Round 12 — the quick-reaction bar's input, bundled in one parameter.
 * One bundle parameter instead of two — [MessageBubble] sits at the
 * Detekt 20-parameter limit (see [BubbleMenuToggles]).
 */
data class QuickReactionConfig(
    /** Most recently used reaction emojis, most recent first. */
    val recentEmojis: List<String>,
    /**
     * Called with the tapped emoji (always non-null — the row only emits
     * real emojis); the caller sends the reaction. Round 23 — the caller
     * applies [toggleReaction] to decide: tapping the emoji already
     * applied stores `null` (removes the reaction), any other tap stores
     * the tapped emoji.
     */
    val onSelect: (String) -> Unit,
)

/**
 * Round 23 — what a tap on a quick-reaction emoji stores: tapping the
 * emoji already applied to the message removes the reaction (toggle), so
 * the caller passes `null`; any other tap stores the tapped emoji. A
 * multi-emoji reaction only matches when the whole string is tapped — a
 * single emoji from it swaps the reaction instead, as before. Shared by
 * the tap bar and the long-press menu's reaction row, which both feed
 * the same [QuickReactionConfig.onSelect].
 */
fun toggleReaction(currentReaction: String?, tappedEmoji: String): String? =
    if (tappedEmoji == currentReaction) null else tappedEmoji
