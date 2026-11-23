package com.filestech.sms.ui.theme

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 47 — the outgoing-bubble colour rule: blue means the
 * message went out encrypted, green (`#3BA803`) means it went out
 * plain, per message, with a per-conversation custom colour still
 * winning over both. Incoming bubbles are untouched. Text, media,
 * and voice bubbles all resolve through the same function
 * ([resolveBubbleColors] — [MessageBubble],
 * [MediaAttachmentBubble], [AudioMessageBubble] each call it
 * with `message.encrypted`), so the media tests below assert the
 * shared rule under a media name.
 *
 * The contrast lock follows the BrandWarningContrastTest precedent:
 * a ratio that lives only in a comment ends up justifying a choice
 * it does not support — so the white-on-green ratio (3.09:1,
 * accepted by design) is asserted here, not just documented.
 */
class BubbleEncryptionColorsTest {

    private companion object {
        /** WCAG 2.1 AA bar for normal text — the bubble body is 16sp. */
        const val AA_NORMAL_TEXT = 4.5

        const val TOLERANCE = 0.01

        /** One palette colour, as the stored ARGB Int. */
        val CUSTOM_ARGB: Int = 0xFFC2185B.toInt()
        val CUSTOM_COLOR: Color = Color(0xFFC2185B)
    }

    private fun resolve(
        isOutgoing: Boolean = true,
        encrypted: Boolean = false,
        customBubbleColorArgb: Int? = null,
        defaultBlue: Color = BrandBlue,
        onDefaultBlue: Color = Color.White,
        incomingBackground: Color = BubbleIncomingLight,
        incomingText: Color = Color(0xFF1B1B1B),
    ): BubbleColors = resolveBubbleColors(
        isOutgoing = isOutgoing,
        encrypted = encrypted,
        customBubbleColorArgb = customBubbleColorArgb,
        defaultBlue = defaultBlue,
        onDefaultBlue = onDefaultBlue,
        incomingBackground = incomingBackground,
        incomingText = incomingText,
    )

    /** sRGB channel → linear, WCAG formula (same threshold as Color.kt's relativeLuminance). */
    private fun linearize(channel: Float): Double {
        val c = channel.toDouble()
        return if (c <= 0.039_28) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
    }

    /** Relative luminance, WCAG formula. */
    private fun luminance(color: Color): Double =
        0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)

    private fun contrast(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    @Test
    fun `plain outgoing message renders the green default with white text`() {
        val colors = resolve(isOutgoing = true, encrypted = false)

        assertThat(colors.background).isEqualTo(Color(0xFF3BA803))
        assertThat(colors.background).isEqualTo(PlainOutgoingGreen)
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `encrypted outgoing message renders the blue default with the blue text`() {
        val colors = resolve(isOutgoing = true, encrypted = true)

        assertThat(colors.background).isEqualTo(BrandBlue)
        assertThat(colors.background).isEqualTo(Color(0xFF1E4FD8))
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `encrypted outgoing uses the dynamic blue when one is passed`() {
        // The resolver must not hardcode the brand blue: under dynamic
        // colours the caller passes the wallpaper primary instead.
        val dynamicPrimary = Color(0xFF6750A4)
        val colors = resolve(
            isOutgoing = true,
            encrypted = true,
            defaultBlue = dynamicPrimary,
            onDefaultBlue = Color.White,
        )

        assertThat(colors.background).isEqualTo(dynamicPrimary)
    }

    @Test
    fun `custom color wins for a plain outgoing message`() {
        val colors = resolve(
            isOutgoing = true,
            encrypted = false,
            customBubbleColorArgb = CUSTOM_ARGB,
        )

        assertThat(colors.background).isEqualTo(CUSTOM_COLOR)
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `custom color wins for an encrypted outgoing message`() {
        val colors = resolve(
            isOutgoing = true,
            encrypted = true,
            customBubbleColorArgb = CUSTOM_ARGB,
        )

        assertThat(colors.background).isEqualTo(CUSTOM_COLOR)
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `incoming message is unaffected by encryption state`() {
        val plain = resolve(isOutgoing = false, encrypted = false)
        val encrypted = resolve(isOutgoing = false, encrypted = true)

        assertThat(plain.background).isEqualTo(BubbleIncomingLight)
        assertThat(plain.text).isEqualTo(Color(0xFF1B1B1B))
        assertThat(encrypted).isEqualTo(plain)
    }

    @Test
    fun `incoming message ignores a custom color`() {
        val colors = resolve(
            isOutgoing = false,
            encrypted = false,
            customBubbleColorArgb = CUSTOM_ARGB,
        )

        assertThat(colors.background).isEqualTo(BubbleIncomingLight)
        assertThat(colors.text).isEqualTo(Color(0xFF1B1B1B))
    }

    @Test
    fun `white on the green is 3-09 to 1 accepted by design`() {
        // 3.09:1 — under the WCAG AA 4.5:1 bar, ACCEPTED (Kristin's
        // explicit call, 2026-11-23): iMessage uses white on its
        // LIGHTER green (#34C759), and #3BA803 is darker, so white
        // reads better here than on iMessage. Locked so a future
        // retouch re-measures instead of assuming.
        val ratio = contrast(Color.White, PlainOutgoingGreen)

        assertThat(ratio).isLessThan(AA_NORMAL_TEXT)
        assertThat(ratio).isWithin(TOLERANCE).of(3.09)
    }

    @Test
    fun `the text on the green is white`() {
        assertThat(OnPlainOutgoingGreen).isEqualTo(Color.White)
    }

    @Test
    fun `white on the blue default passes the AA bar`() {
        // The encrypted bubble keeps its white text: 6.63:1.
        val ratio = contrast(Color.White, BrandBlue)

        assertThat(ratio).isGreaterThan(AA_NORMAL_TEXT)
        assertThat(ratio).isWithin(TOLERANCE).of(6.63)
    }

    @Test
    fun `text on a colored bubble covers white (blue, custom, green)`() {
        assertThat(isTextOnColoredBubble(Color.White)).isTrue()
        assertThat(isTextOnColoredBubble(OnPlainOutgoingGreen)).isTrue()
    }

    @Test
    fun `text on a neutral surface is not a colored bubble`() {
        assertThat(isTextOnColoredBubble(Color(0xFF1B1B1B))).isFalse()
        assertThat(isTextOnColoredBubble(Color(0xFFE6E1E5))).isFalse()
    }

    @Test
    fun `plain sent media resolves green (same rule as text)`() {
        // Media and voice bubbles call resolveBubbleColors with
        // message.encrypted, like the text bubble — a sent photo
        // is plain MMS today, so it is green. After media
        // encryption ships, an encrypted photo resolves blue
        // through the same call, with no media special-casing.
        val colors = resolve(isOutgoing = true, encrypted = false)

        assertThat(colors.background).isEqualTo(PlainOutgoingGreen)
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `encrypted sent media resolves blue`() {
        val colors = resolve(isOutgoing = true, encrypted = true)

        assertThat(colors.background).isEqualTo(BrandBlue)
        assertThat(colors.text).isEqualTo(Color.White)
    }

    @Test
    fun `custom color wins for sent media`() {
        val colors = resolve(
            isOutgoing = true,
            encrypted = false,
            customBubbleColorArgb = CUSTOM_ARGB,
        )

        assertThat(colors.background).isEqualTo(CUSTOM_COLOR)
        assertThat(colors.text).isEqualTo(Color.White)
    }
}
