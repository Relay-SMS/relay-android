package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 48 — the thread-composer button colour rule: the send,
 * mic, and voice-review send buttons are the theme blue when the
 * conversation encrypts and the fixed plain green (`#3BA803`)
 * when it does not — the same signal as the outgoing bubbles
 * ([BubbleEncryptionColorsTest]), keyed off the composer's own
 * `isEncrypted` (the flag behind the "Relay message"
 * placeholder), so the buttons follow the same recomposition as
 * the placeholder when the thread's encryption state changes.
 *
 * Groups read green (never encrypted); notes read blue (always
 * encrypted — their own composer keeps the brand blue directly
 * and never passes through this function). A per-conversation
 * custom chat colour does not touch the composer buttons today
 * (it colours bubbles only), so there is no custom override to
 * test here — green/blue is the whole rule.
 */
class ComposerButtonColorsTest {

    private fun resolve(
        encrypted: Boolean = false,
        defaultBlue: Color = BrandBlue,
        defaultBluePressed: Color = Color(0xFF1740A8),
        onDefaultBlue: Color = Color.White,
    ): ComposerButtonColors = resolveComposerButtonColors(
        encrypted = encrypted,
        defaultBlue = defaultBlue,
        defaultBluePressed = defaultBluePressed,
        onDefaultBlue = onDefaultBlue,
    )

    @Test
    fun `encrypted thread renders the blue buttons with the blue content`() {
        val colors = resolve(encrypted = true)

        assertThat(colors.background).isEqualTo(BrandBlue)
        assertThat(colors.background).isEqualTo(Color(0xFF1E4FD8))
        assertThat(colors.pressedBackground).isEqualTo(Color(0xFF1740A8))
        assertThat(colors.content).isEqualTo(Color.White)
    }

    @Test
    fun `plain thread renders the green buttons with white glyphs`() {
        val colors = resolve(encrypted = false)

        assertThat(colors.background).isEqualTo(Color(0xFF3BA803))
        assertThat(colors.background).isEqualTo(PlainOutgoingGreen)
        assertThat(colors.content).isEqualTo(Color.White)
        assertThat(colors.content).isEqualTo(OnPlainOutgoingGreen)
    }

    @Test
    fun `group thread renders green (groups never encrypt)`() {
        // The caller passes the composer's `isEncrypted`, which is
        // false for a group (no peer, no probe, `e2ePeerState`
        // never ENCRYPTED) — so the group buttons are green.
        val colors = resolve(encrypted = false)

        assertThat(colors.background).isEqualTo(PlainOutgoingGreen)
        assertThat(colors.content).isEqualTo(OnPlainOutgoingGreen)
    }

    @Test
    fun `note resolves blue (notes are encrypted)`() {
        // Notes are encrypted, so the resolver's encrypted branch
        // is the note branch. The note composer itself
        // (NoteComposerBar) keeps the brand blue directly and
        // never calls this function — this test locks the mapping,
        // not the call site.
        val colors = resolve(encrypted = true)

        assertThat(colors.background).isEqualTo(BrandBlue)
        assertThat(colors.content).isEqualTo(Color.White)
    }

    @Test
    fun `encrypted thread uses the dynamic blue when one is passed`() {
        // The resolver must not hardcode the brand blue: under dynamic
        // colours the caller passes the wallpaper primary instead.
        val dynamicPrimary = Color(0xFF6750A4)
        val dynamicPressed = Color(0xFF52407F)
        val colors = resolve(
            encrypted = true,
            defaultBlue = dynamicPrimary,
            defaultBluePressed = dynamicPressed,
            onDefaultBlue = Color.White,
        )

        assertThat(colors.background).isEqualTo(dynamicPrimary)
        assertThat(colors.pressedBackground).isEqualTo(dynamicPressed)
    }

    @Test
    fun `plain thread ignores the dynamic blue (the green is fixed)`() {
        // Like the plain bubble, the plain button green does not
        // follow the wallpaper palette: the colour itself is the
        // signal, so it reads the same on every device.
        val colors = resolve(
            encrypted = false,
            defaultBlue = Color(0xFF6750A4),
            defaultBluePressed = Color(0xFF52407F),
            onDefaultBlue = Color(0xFF1B1B1B),
        )

        assertThat(colors.background).isEqualTo(PlainOutgoingGreen)
        assertThat(colors.content).isEqualTo(OnPlainOutgoingGreen)
    }

    @Test
    fun `the pressed green is the green darkened toward black`() {
        // The same 20% step the pressed blue takes, derived from
        // the green itself — never a second hex.
        assertThat(PlainOutgoingGreenPressed).isEqualTo(
            lerp(PlainOutgoingGreen, Color.Black, 0.2f),
        )
        assertThat(PlainOutgoingGreenPressed.red).isLessThan(PlainOutgoingGreen.red)
        assertThat(PlainOutgoingGreenPressed.green).isLessThan(PlainOutgoingGreen.green)
        assertThat(PlainOutgoingGreenPressed.blue).isLessThan(PlainOutgoingGreen.blue)
        assertThat(PlainOutgoingGreenPressed.alpha).isEqualTo(PlainOutgoingGreen.alpha)
    }

    @Test
    fun `plain pressed background is the pressed green`() {
        val colors = resolve(encrypted = false)

        assertThat(colors.pressedBackground).isEqualTo(PlainOutgoingGreenPressed)
    }
}
