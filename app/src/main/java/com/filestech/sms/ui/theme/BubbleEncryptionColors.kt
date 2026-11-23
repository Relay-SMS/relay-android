package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Round 47 — the default outgoing bubble for a message sent in the
 * clear: fixed `#3BA803`, in both themes (Kristin's call: the colour
 * itself is the visual signal — blue means the message went out
 * encrypted, green means it went out plain). The rule is per-message
 * ([resolveBubbleColors]): a thread that turns encrypted keeps its
 * old plain messages green and shows the new encrypted ones blue.
 *
 * The green is fixed, not dynamic: unlike [brandBlue] it does not
 * follow the wallpaper palette, so the signal reads the same on
 * every device. A per-conversation custom colour still wins over
 * it (see [resolveBubbleColors]).
 *
 * **Text on this green is white, not black** (Kristin's call,
 * 2026-11-23): white on `#3BA803` is 3.09:1 — under the WCAG AA
 * 4.5:1 bar for normal text (the bubble body is 16sp) — but
 * iMessage uses white on its LIGHTER green (`#34C759`), and
 * `#3BA803` is darker, so white reads better here than on
 * iMessage. The ratio is accepted by design, not by oversight:
 * BubbleEncryptionColorsTest locks it (3.09) so a future retouch
 * re-measures instead of assuming. [OnPlainOutgoingGreen] is the
 * named foreground for this green.
 */
internal val PlainOutgoingGreen: Color = Color(0xFF3BA803)

/**
 * Round 47 — the text colour on [PlainOutgoingGreen]: white
 * (3.09:1, below WCAG AA — accepted by design, see
 * [PlainOutgoingGreen]: iMessage uses white on a lighter green,
 * and `#3BA803` is darker; Kristin's explicit call).
 */
internal val OnPlainOutgoingGreen: Color = Color.White

/** Round 48 — how far the pressed green darkens toward black: the
 * same 20% step [brandBluePressed] applies to the blue (Color.kt's
 * `BRAND_PRESSED_DARKEN`), so the send circle's press feedback
 * reads the same in both colours. */
private const val PLAIN_GREEN_PRESSED_DARKEN = 0.2f

/**
 * Round 48 — the pressed state of [PlainOutgoingGreen]: the green
 * darkened toward black by [PLAIN_GREEN_PRESSED_DARKEN]. Derived
 * from [PlainOutgoingGreen], never a second hex.
 */
internal val PlainOutgoingGreenPressed: Color =
    lerp(PlainOutgoingGreen, Color.Black, PLAIN_GREEN_PRESSED_DARKEN)

/**
 * Round 47 — a bubble's resolved colours: its fill plus the text
 * that sits on it. The two are resolved together because the text
 * depends on the fill (white on blue, white on green, white on a
 * custom colour) — resolving them separately would let a caller
 * pair the wrong text with a fill and silently drop below the
 * contrast bar.
 */
internal data class BubbleColors(
    val background: Color,
    val text: Color,
)

/**
 * Round 47 — the outgoing-bubble colour rule, as a pure function
 * (the composable only resolves the theme-dependent inputs).
 *
 *   - incoming: the neutral grey, whatever the encryption state —
 *     the signal is outgoing-only, and the lock on the timestamp
 *     line already marks an encrypted incoming message;
 *   - outgoing with a custom colour: the custom colour, plain or
 *     encrypted — the user's override wins, with white text (every
 *     palette colour is calibrated WCAG AA against white);
 *   - outgoing, encrypted, no custom colour: the current blue
 *     ([defaultBlue] — [brandBlue], dynamic-aware, unchanged);
 *   - outgoing, plain, no custom colour: [PlainOutgoingGreen]
 *     with [OnPlainOutgoingGreen] (white) text.
 *
 * The theme-dependent colours ([defaultBlue], [onDefaultBlue],
 * [incomingBackground], [incomingText]) arrive resolved: the
 * composable reads them from the scheme ([brandBlue],
 * [onBrandBlue], [bubbleIncomingColor], `onSurface`), so this
 * function stays pure and unit-testable on plain JVM.
 */
internal fun resolveBubbleColors(
    isOutgoing: Boolean,
    encrypted: Boolean,
    customBubbleColorArgb: Int?,
    defaultBlue: Color,
    onDefaultBlue: Color,
    incomingBackground: Color,
    incomingText: Color,
): BubbleColors = when {
    !isOutgoing -> BubbleColors(incomingBackground, incomingText)
    customBubbleColorArgb != null ->
        BubbleColors(Color(customBubbleColorArgb), Color.White)
    encrypted -> BubbleColors(defaultBlue, onDefaultBlue)
    else -> BubbleColors(PlainOutgoingGreen, OnPlainOutgoingGreen)
}

/**
 * Round 48 — a composer button's resolved colours: its fill, its
 * pressed fill, and the glyph on top. The three are resolved
 * together because the glyph depends on the fill (the scheme
 * `onPrimary` on the dynamic blue, white on the fixed green) —
 * resolving them separately would let a caller pair the wrong
 * glyph with a fill.
 */
internal data class ComposerButtonColors(
    val background: Color,
    val pressedBackground: Color,
    val content: Color,
)

/**
 * Round 48 — the thread-composer button colour rule, as a pure
 * function (the composable only resolves the theme-dependent
 * inputs). The buttons (send, mic, voice-review send) are the
 * blue of the current theme when the conversation encrypts —
 * [defaultBlue], dynamic-aware, unchanged — and the fixed
 * [PlainOutgoingGreen] when it does not: the same signal as the
 * outgoing bubbles ([resolveBubbleColors]) — blue means the next
 * message goes out encrypted, green means it goes out plain.
 *
 * The state is the composer's own `isEncrypted` (the flag behind
 * the "Relay message" placeholder): groups are never encrypted,
 * so they read green. Notes have their own composer and stay
 * blue — they never pass through this function. A
 * per-conversation custom chat colour does not touch the
 * composer buttons today (it colours bubbles only), so there is
 * no custom override here — green/blue is the whole rule.
 *
 * The theme-dependent colours ([defaultBlue],
 * [defaultBluePressed], [onDefaultBlue]) arrive resolved: the
 * composable reads them from [brandBlue], [brandBluePressed]
 * and [onBrandBlue], so this function stays pure and
 * unit-testable on plain JVM.
 */
internal fun resolveComposerButtonColors(
    encrypted: Boolean,
    defaultBlue: Color,
    defaultBluePressed: Color,
    onDefaultBlue: Color,
): ComposerButtonColors = if (encrypted) {
    ComposerButtonColors(defaultBlue, defaultBluePressed, onDefaultBlue)
} else {
    ComposerButtonColors(PlainOutgoingGreen, PlainOutgoingGreenPressed, OnPlainOutgoingGreen)
}

/**
 * Round 47 — true when a bubble's text colour sits on a saturated
 * fill (blue, custom, or the plain green) rather than on a
 * neutral surface. [com.filestech.sms.ui.components.BubbleTimestamp]
 * uses it to decide whether the status label keeps the body colour: the theme greys
 * ([bubbleStatusColor]) are matched to surfaces, and on the green
 * they would be unreadable (`#404040` is 3.35:1, `#C9C9C9` is
 * 1.87:1) — so the status keeps the white body colour there, as
 * on blue. Incoming text (`onSurface`, never exactly white)
 * stays false.
 */
internal fun isTextOnColoredBubble(textColor: Color): Boolean =
    textColor == Color.White
