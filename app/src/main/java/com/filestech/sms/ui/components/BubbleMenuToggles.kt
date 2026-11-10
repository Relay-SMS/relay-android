package com.filestech.sms.ui.components

/**
 * Feedback round 5 — the bubble menu's per-message toggle actions: star
 * ([onToggleStar]) and pin ([isPinned] + [onTogglePin]). One bundle
 * parameter instead of three — [MessageBubble] sits at the Detekt
 * 20-parameter limit, and the pin toggle alone would trip it. The pin
 * state is read from the conversation (one pinned message per thread),
 * the star state from the message itself (see `starred` on [BubbleMenu]).
 */
data class BubbleMenuToggles(
    val onToggleStar: () -> Unit,
    val isPinned: Boolean,
    val onTogglePin: () -> Unit,
)
