package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 17 — [String.isEmojiOnlyMessage] decides which messages get the
 * big-emoji treatment (no bubble background, enlarged glyph). The tricky
 * cases are the multi-code-point clusters: a ZWJ chain, a skin tone, a
 * flag pair and a keycap must each count as ONE emoji.
 */
class EmojiOnlyMessageTest {

    @Test fun `single emoji counts`() {
        assertThat("🥰".isEmojiOnlyMessage()).isTrue()
        assertThat("❤️".isEmojiOnlyMessage()).isTrue()
        assertThat("©️".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `toned emoji counts as one`() {
        assertThat("👋🏽".isEmojiOnlyMessage()).isTrue()
        assertThat("👍🏿".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `ZWJ sequences count as one emoji`() {
        assertThat("👨‍👩‍👧‍👦".isEmojiOnlyMessage()).isTrue()
        assertThat("👩🏽‍❤️‍👨🏻".isEmojiOnlyMessage()).isTrue()
        assertThat("🧑‍💻".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `flag pair counts as one emoji`() {
        assertThat("🇫🇷".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `keycap counts as one emoji`() {
        assertThat("1️⃣".isEmojiOnlyMessage()).isTrue()
        assertThat("#️⃣".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `two and three emojis count`() {
        assertThat("🥰😂".isEmojiOnlyMessage()).isTrue()
        assertThat("🥰😂🎉".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `four emojis do not count`() {
        assertThat("🥰😂🎉🔥".isEmojiOnlyMessage()).isFalse()
    }

    @Test fun `emoji plus text does not count`() {
        assertThat("🥰 hi".isEmojiOnlyMessage()).isFalse()
        assertThat("hi 🥰".isEmojiOnlyMessage()).isFalse()
        assertThat("hello".isEmojiOnlyMessage()).isFalse()
    }

    @Test fun `empty and blank do not count`() {
        assertThat("".isEmojiOnlyMessage()).isFalse()
        assertThat("   ".isEmojiOnlyMessage()).isFalse()
    }

    @Test fun `surrounding whitespace is ignored`() {
        assertThat("🥰 ".isEmojiOnlyMessage()).isTrue()
        assertThat("  🥰\n".isEmojiOnlyMessage()).isTrue()
    }

    @Test fun `bare digit and lone regional indicator do not count`() {
        assertThat("1".isEmojiOnlyMessage()).isFalse()
        assertThat("🇫".isEmojiOnlyMessage()).isFalse()
    }
}
