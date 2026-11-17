package com.filestech.sms.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 24 — the "+" reaction picker's testable surface
 * ([ReactionEmojiPickerSheet]): the emoji set it shows, the search
 * filtering ([emojiMatchesQuery]) and the tap toggle ([toggleReaction]).
 * The sheet itself is a composable (no Compose test harness in this
 * repo), so these pure functions are what the tests cover. The toggle is
 * shared with the quick-reaction row ([QuickReactionRowTest] covers it
 * from the row's side); the cases here pin the picker's contract,
 * including the skin-tone variants its long-press picker hands it.
 */
class ReactionEmojiPickerTest {

    // The set: 487 emojis in titled sections, no more, no duplicates.

    @Test fun `the picker shows all 487 emojis`() {
        assertThat(EMOJI_GRID_EMOJIS).hasSize(487)
    }

    @Test fun `no emoji appears twice in the picker`() {
        assertThat(EMOJI_GRID_EMOJIS).containsNoDuplicates()
    }

    @Test fun `the picker keeps its fourteen titled sections`() {
        assertThat(EMOJI_SECTIONS).hasSize(14)
        assertThat(EMOJI_SECTIONS.map { it.titleRes }).containsNoDuplicates()
        EMOJI_SECTIONS.forEach { section ->
            assertThat(section.emojis).isNotEmpty()
        }
    }

    @Test fun `the excluded same-sex couples stay out of the picker`() {
        // Rounds 15-16, per Kristin: these stay out of the grid.
        assertThat(EMOJI_GRID_EMOJIS).containsNoneOf(
            "👨‍❤️‍👨",
            "👩‍❤️‍👩",
            "👨‍❤️‍💋‍👨",
            "👩‍❤️‍💋‍👩",
            "👭",
            "👬",
        )
    }

    @Test fun `the excluded same-sex-parent families stay out of the picker`() {
        // Rounds 15-16, per Kristin: these stay out of the grid.
        assertThat(EMOJI_GRID_EMOJIS).containsNoneOf(
            "👨‍👨‍👦",
            "👨‍👨‍👧",
            "👨‍👨‍👧‍👦",
            "👩‍👩‍👦",
            "👩‍👩‍👧",
            "👩‍👩‍👧‍👦",
        )
    }

    @Test fun `the kept couples and families are still in the picker`() {
        // Guards the two tests above against a vacuous pass (e.g. a
        // broken exclusion list hiding a missing section).
        assertThat(EMOJI_GRID_EMOJIS).containsAtLeast(
            "👩‍❤️‍👨",
            "💑",
            "👩‍❤️‍💋‍👨",
            "💏",
            "👨‍👩‍👦",
            "👨‍👩‍👧",
            "👨‍👩‍👧‍👦",
            "👪",
        )
    }

    // Search filtering — while the query is non-blank the picker
    // collapses the sections into the flat match list (see [EmojiGrid]).

    @Test fun `searching cry finds the crying faces`() {
        val results = EMOJI_GRID_EMOJIS.filter { emojiMatchesQuery(it, "cry") }
        assertThat(results).containsAtLeast("😭", "😢")
    }

    @Test fun `search is case-insensitive`() {
        val results = EMOJI_GRID_EMOJIS.filter { emojiMatchesQuery(it, "CRY") }
        assertThat(results).containsAtLeast("😭", "😢")
    }

    @Test fun `every word of the query must match`() {
        assertThat(emojiMatchesQuery("👍", "thumbs up")).isTrue()
        assertThat(emojiMatchesQuery("👍", "thumbs down")).isFalse()
    }

    @Test fun `surrounding whitespace in the query is ignored`() {
        assertThat(emojiMatchesQuery("😭", "  cry  ")).isTrue()
    }

    @Test fun `an unknown word matches nothing`() {
        val results = EMOJI_GRID_EMOJIS.filter { emojiMatchesQuery(it, "zzqzx") }
        assertThat(results).isEmpty()
    }

    // Tap behaviour — the picker applies [toggleReaction] and stores
    // the result (`null` = removal), then closes.

    @Test fun `tapping an emoji with no reaction applies it`() {
        assertThat(toggleReaction(null, "🎉")).isEqualTo("🎉")
    }

    @Test fun `tapping the applied emoji again removes the reaction`() {
        assertThat(toggleReaction("🎉", "🎉")).isNull()
    }

    @Test fun `tapping a different emoji swaps the reaction`() {
        assertThat(toggleReaction("🎉", "🔥")).isEqualTo("🔥")
    }

    @Test fun `a skin-tone variant from the long-press picker swaps the reaction`() {
        // The grid's tone picker hands the picker a toned variant; it is
        // a different emoji from the base, so it swaps rather than removes.
        assertThat(toggleReaction("👍", "👍🏽")).isEqualTo("👍🏽")
    }

    @Test fun `tapping the applied skin-tone variant again removes the reaction`() {
        assertThat(toggleReaction("👍🏽", "👍🏽")).isNull()
    }
}
