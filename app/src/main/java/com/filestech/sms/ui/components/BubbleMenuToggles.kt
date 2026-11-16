package com.filestech.sms.ui.components

/**
 * Feedback round 5 — the bubble menu's per-message toggle action: pin
 * ([isPinned] + [onTogglePin]). One bundle parameter instead of two —
 * [MessageBubble] sits at the Detekt 20-parameter limit, and the pin
 * toggle alone would trip it. The pin state is read from the conversation
 * (one pinned message per thread).
 *
 * Round 23 — the star toggle is gone with the menu's "Star" entry: there
 * is no starred list, screen or marker, so starring only exempted a
 * message from auto-delete. The `starred` column still exists and still
 * protects a message from the purge; only the UI toggle is removed.
 *
 * Round 21 — [onRemoveReaction] rides the same bundle for the same
 * reason: the badge tap removes the reaction, and the text bubble has no
 * parameter slot left for a fourth callback.
 */
data class BubbleMenuToggles(
    val isPinned: Boolean,
    val onTogglePin: () -> Unit,
    /** Round 21 — badge tap removes the reaction (`setReaction(id, null)`). */
    val onRemoveReaction: () -> Unit,
)
