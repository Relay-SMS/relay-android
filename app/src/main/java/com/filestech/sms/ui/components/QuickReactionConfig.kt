package com.filestech.sms.ui.components

/**
 * Round 12 — the quick-reaction bar's input, bundled in one parameter.
 * One bundle parameter instead of two — [MessageBubble] sits at the
 * Detekt 20-parameter limit (see [BubbleMenuToggles]).
 */
data class QuickReactionConfig(
    /** Most recently used reaction emojis, most recent first. */
    val recentEmojis: List<String>,
    /** Called with the tapped emoji; the caller sends the reaction. */
    val onSelect: (String) -> Unit,
)
