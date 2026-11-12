// EmojiSection lives in this file with its data (EMOJI_SECTIONS) rather than
// in a file of its own — the file-name rule is suppressed for the whole file.
@file:Suppress("MatchingDeclarationName")

package com.filestech.sms.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.decode.GifDecoder
import coil.decode.SvgDecoder
import com.caverock.androidsvg.SVG
import com.filestech.sms.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Twemoji content for the new emoji / sticker picker (round 13).
 *
 * Emojis and stickers come from the jdecked/twemoji fork (Unicode 17), NOT
 * the original twitter/twemoji (unmaintained since 2023, Unicode 14 only).
 * Code MIT, graphics CC-BY 4.0 — see THIRD_PARTY_NOTICES.md and the
 * `twemoji_attribution` string shown on the License screen.
 *
 * The emoji sent in a text is just Unicode — the picker only changes how it
 * looks inside Relay. Stickers are the same Twemoji graphics rendered
 * larger; a sticker is sent as an MMS image attachment, so the send path
 * gets a PNG ([stickerSendUri]), never an SVG (carriers expect image/png).
 *
 * Assets, bundled from the fork's `assets/svg/` and `assets/72x72/`:
 * - `assets/twemoji/svg/<codepoint>.svg` — one SVG per picker emoji, rendered
 *   by Coil (`coil-svg`) so it stays crisp at any grid or sticker size.
 * - `assets/twemoji/png/<codepoint>.png` — 72x72 PNG, sticker subset only,
 *   for sending as an MMS attachment.
 *
 * Filename rule (verified against the fork, round 14): the emoji's code
 * points in lowercase hex joined with `-`. U+FE0F is stripped ONLY for a
 * single-codepoint emoji — the red heart is `2764.svg`, not
 * `2764-fe0f.svg` — but KEPT in multi-codepoint sequences: the man health
 * worker is `1f468-200d-2695-fe0f.svg`, the rainbow flag is
 * `1f3f3-fe0f-200d-1f308.svg` (see [twemojiFileName]). The set started as
 * single-codepoint emojis only; round 14 added ZWJ sequences (families,
 * couples, professions) and flags. Skin-tone variants are base + modifier
 * for singles, e.g. `1f44d-1f3fb.svg`; for ZWJ sequences the modifier
 * follows each person component, e.g. `1f469-1f3fb-200d-1f4bb.svg`
 * (see [skinToneVariants]).
 */

/** One titled section of the emoji grid. */
data class EmojiSection(
    @androidx.annotation.StringRes val titleRes: Int,
    val emojis: List<String>,
)

/**
 * The hand emojis — every one of them ships skin-tone variants (see
 * [SKIN_TONE_MODIFIERS]), so the list lives here once and the Hands section
 * below reuses it.
 */
val HAND_EMOJIS: List<String> = listOf(
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤙", "👏",
    "🙌", "🙏", "💪", "👋", "🤝", "👊", "✊", "👈",
    "👉", "👆", "👇", "☝️",
    "🫰", "🫵", "🫶", "🤲", "👐", "🫳", "🫴",
)

/**
 * Round 14 — the people emojis (Kristin, 2026-11-11: the grid had no
 * man/woman/person). Single-codepoint only, like the rest of the set — no
 * ZWJ sequences (families, gendered professions), which need a different
 * tone-modifier placement. Every one of these supports skin tones, so they
 * all ship the five variants and long-press opens the tone picker, like
 * hands. Deliberately NOT here: 👼 🧞 🧟 — Unicode gives them no skin
 * tones at all, so they would be the only cells where long-press does
 * nothing.
 */
val PEOPLE_EMOJIS: List<String> = listOf(
    "👶", "👦", "👧", "🧑", "👨", "👩", "🧓", "👴", "👵",
    "👱", "👲", "👳", "👮", "👷", "💂", "🕵️", "👰", "🤵",
    "🎅", "🤶", "🦸", "🦹", "🧙", "🧚", "🧛", "🧜", "🧝",
    "💆", "💇", "🚶", "🏃", "💃", "🕺", "🧖", "🧗", "🤸",
    "🏄", "🏊", "🚴",
    "🏋️", "🤾", "🤽", "🧘", "🧎", "🧍", "🏌️", "⛹️",
    "🚣",
)

/**
 * Round 14 — the family emojis (Kristin, 2026-11-11). ZWJ sequences
 * (man/woman + children); families take no skin tones in Unicode, so
 * none of these is in [SKIN_TONE_BASES]. Round 15 — the same-sex-parent
 * families (👨‍👨‍👦 👨‍👨‍👧 👨‍👨‍👧‍👦 👩‍👩‍👦 👩‍👩‍👧
 * 👩‍👩‍👧‍👦) are deliberately not included, per Kristin.
 */
val FAMILY_EMOJIS: List<String> = listOf(
    "👨‍👩‍👦", "👨‍👩‍👧", "👨‍👩‍👧‍👦", "👨‍👦", "👨‍👧", "👨‍👧‍👦",
    "👩‍👦", "👩‍👧", "👩‍👧‍👦", "👪",
)

/**
 * Round 14 — the couples. Hearts and kisses take skin tones (Unicode 14,
 * both people — see [COUPLE_TONE_EMOJIS]); the plain holding-hands
 * pair (👫) and 💑💏 do not. The gendered holding-hands (👩‍🤝‍👨
 * and friends) are NOT here: Twemoji ships no picture for them at all —
 * base or toned — in either the fork or the original repo, so only the
 * neutral 🧑‍🤝‍🧑 (full set) is included. Round 15 — the same-sex
 * pairs (👨‍❤️‍👨 👩‍❤️‍👩 👨‍❤️‍💋‍👨 👩‍❤️‍💋‍👩 👭 👬) are
 * deliberately not included, per Kristin.
 */
val COUPLE_EMOJIS: List<String> = listOf(
    "👩‍❤️‍👨",
    "💑",
    "👩‍❤️‍💋‍👨",
    "💏",
    "👫",
    "🧑‍🤝‍🧑",
)

/**
 * Couples that take skin tones — hearts, kisses, neutral holding hands.
 * One per line: the 3-3-1 grouping tripped ktlint's argument-wrapping rule.
 */
val COUPLE_TONE_EMOJIS: List<String> = listOf(
    "👩‍❤️‍👨",
    "👩‍❤️‍💋‍👨",
    "🧑‍🤝‍🧑",
)

/**
 * Round 14 — the professions (woman/man/person × 16 jobs). ZWJ sequences;
 * the tone modifier follows the person component ([skinToneVariants]).
 */
val PROFESSION_EMOJIS: List<String> = listOf(
    "👩‍⚕️", "👨‍⚕️", "🧑‍⚕️", "👩‍🎓", "👨‍🎓", "🧑‍🎓", "👩‍🏫", "👨‍🏫",
    "🧑‍🏫", "👩‍⚖️", "👨‍⚖️", "🧑‍⚖️", "👩‍🌾", "👨‍🌾", "🧑‍🌾", "👩‍🍳",
    "👨‍🍳", "🧑‍🍳", "👩‍🔧", "👨‍🔧", "🧑‍🔧", "👩‍🏭", "👨‍🏭", "🧑‍🏭",
    "👩‍💼", "👨‍💼", "🧑‍💼", "👩‍🔬", "👨‍🔬", "🧑‍🔬", "👩‍💻", "👨‍💻",
    "🧑‍💻", "👩‍🎤", "👨‍🎤", "🧑‍🎤", "👩‍🎨", "👨‍🎨", "🧑‍🎨", "👩‍✈️",
    "👨‍✈️", "🧑‍✈️", "👩‍🚀", "👨‍🚀", "🧑‍🚀", "👩‍🚒", "👨‍🚒", "🧑‍🚒",
)

/**
 * Round 14 — the country flags (top 30) plus rainbow and pirate. Flag
 * sequences (regional indicators, or tag characters for 🇬🇧); no skin
 * tones. The 🇬🇧 file is a tag sequence (`1f3f4-e0067-…-e007f`),
 * handled by the same filename rule.
 */
val FLAG_EMOJIS: List<String> = listOf(
    "🇺🇸", "🇬🇧", "🇨🇦", "🇫🇷", "🇩🇪", "🇪🇸", "🇮🇹", "🇲🇽",
    "🇧🇷", "🇯🇵", "🇰🇷", "🇨🇳", "🇮🇳", "🇦🇺", "🇳🇱", "🇸🇪",
    "🇳🇴", "🇩🇰", "🇫🇮", "🇨🇭", "🇦🇹", "🇧🇪", "🇮🇪", "🇵🇹",
    "🇬🇷", "🇵🇱", "🇺🇦", "🇿🇦", "🇳🇿", "🇦🇷", "🏳️‍🌈", "🏴‍☠️",
)

/**
 * The curated Twemoji set, grouped into titled sections (same order as
 * before — most common first within each section). The grid renders one
 * header per section in the main screen's section-header style.
 */
val EMOJI_SECTIONS: List<EmojiSection> = listOf(
    EmojiSection(
        R.string.emoji_section_smileys,
        listOf(
            "😂", "🤣", "😊", "😍", "😘", "😅", "😁", "😉",
            "😋", "😎", "🥰", "😜", "🤔", "🙄", "😴", "🤯",
            "🥳", "😭", "😢", "😡", "🤗", "😇", "😏", "😌",
            "😬", "🤨", "🫠", "🥹", "😪", "😷", "🥵", "🥶",
            "😃", "😄", "😆", "😝", "😛", "🤪", "🙃", "😀",
            "🫡", "🤫", "🫢", "🫣", "🥴", "🤢", "🤮", "🤧",
            "🥱", "🤠", "🥸", "😈", "👻", "💀", "🤖", "👽",
            "🎃",
        ),
    ),
    EmojiSection(R.string.emoji_section_people, PEOPLE_EMOJIS),
    EmojiSection(R.string.emoji_section_families, FAMILY_EMOJIS),
    EmojiSection(R.string.emoji_section_couples, COUPLE_EMOJIS),
    EmojiSection(R.string.emoji_section_professions, PROFESSION_EMOJIS),
    EmojiSection(R.string.emoji_section_hands, HAND_EMOJIS),
    EmojiSection(
        R.string.emoji_section_hearts,
        listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🤎", "💔", "💕", "💖", "💗", "💓", "💞", "💘",
            "💝",
            "💟", "❣️", "🫀", "🩷", "🩵", "🩶",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_animals,
        listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈",
            "🙉", "🙊", "🐔", "🐧", "🐦", "🦄", "🐝", "🦋",
            "🐢", "🐍", "🐳", "🐬",
            "🦒", "🦘", "🦥", "🦦", "🦔", "🦇", "🦉", "🦅",
            "🦆", "🦢", "🦩", "🐙", "🦀", "🦞", "🐠", "🦖",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_food,
        listOf(
            "🍎", "🍐", "🍊", "🍋", "🍌", "🍉", "🍇", "🍓",
            "🫐", "🍒", "🍑", "🥭", "🍍", "🥥", "🥝", "🍅",
            "🍆", "🥑", "🥦", "🥕", "🌽", "🌶️", "🍕", "🍔",
            "🍟", "🌭", "🍿", "🍩", "🍪", "🎂", "🍰", "🧁",
            "🍫", "🍬", "🍭", "🍺", "🍷", "🥂", "🍾", "☕",
            "🧋", "🥤",
            "🥞", "🧇", "🥓", "🍗", "🌮", "🌯", "🥗", "🍝",
            "🍜", "🍣", "🍤", "🥟", "🍦", "🧃", "🥛", "🫖",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_activities,
        listOf(
            "⚽", "🏀", "🏈", "⚾", "🎾", "🏐", "🏉", "🎱",
            "🏓", "🏸", "🏆", "🎮", "🎲", "🧩", "🎯", "🎳",
            "🎸", "🎤", "🎧", "🎬", "🎨", "🎭", "🎪",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_objects,
        listOf(
            "🎢", "🎁", "🎈", "🎉", "🎊", "✨", "💡", "📱",
            "💻", "⌚", "📷", "🔑", "🔒", "✂️", "📌", "📍",
            "✏️", "📚", "💰", "💎", "💍", "👑", "🎩", "👓",
            "🕶️", "🧢", "👕", "👗", "👠", "👟", "🧸",
            "💌", "📧", "🎟️", "🎫", "🪩", "🎻", "🥁", "🎺",
            "🎷", "🎹", "📸", "📹", "📺", "📻", "⏰", "⌛",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_nature,
        listOf(
            "☀️", "🌤️", "⛅", "🌧️", "⛈️", "❄️", "☃️", "⛄",
            "🌈", "🔥", "💧", "🌊", "🌍", "🌙", "⭐", "🌟",
            "🌸", "🌹", "🌺", "🌻", "🌼", "🌷", "🌱", "🌲",
            "🌵", "🍀",
            "🌋", "🌌", "🌠", "🌡️", "🌬️", "🌫️", "🌨️", "🌩️",
            "🌦️", "🪐", "🌕", "🌝",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_symbols,
        listOf(
            "✅", "❌", "❓", "❗", "💯", "🔔", "🔕", "💬",
            "💭", "💤", "🛑", "⛔", "⚠️", "🚫",
            "➕", "➖", "✖️", "♾️", "©️", "®️", "™️", "🔱",
            "⚜️", "♻️", "✳️", "❇️",
        ),
    ),
    EmojiSection(R.string.emoji_section_flags, FLAG_EMOJIS),
)

/** Flat view of [EMOJI_SECTIONS], for callers that do not need sections. */
val EMOJI_GRID_EMOJIS: List<String> = EMOJI_SECTIONS.flatMap { it.emojis }

/**
 * Round 17 — the grid emojis whose Twemoji SVG is drawn entirely in
 * near-black (`#31373D`, or black by default): on the dark picker panel they
 * vanish. A scan of all 487 grid SVGs found 8 such files; only these 6 are
 * text symbols whose colour carries no meaning, so only these get a light
 * tint in dark mode (see `TwemojiImage`). 🖤 and 🕶️ are dark by design and
 * stay untouched — tinting them would change what they are.
 */
val DARK_GLYPH_EMOJIS: Set<String> = setOf(
    "➕",
    "➖",
    "✖️",
    "©️",
    "®️",
    "™️",
)

/**
 * Round 17 — true when the text is only emoji, up to [maxEmojis] of them
 * (default 3): a message that gets the big-emoji treatment in its bubble
 * (no background, enlarged glyph). A hand-rolled code-point scan, not
 * `BreakIterator`: the JDK one splits ZWJ sequences and skin tones, so a
 * detector built on it would disagree between local tests and the device
 * (see `dropLastGrapheme`). Counts grapheme clusters: a ZWJ chain, a
 * skin tone, VS16, a flag pair and a keycap each count as ONE emoji. Any
 * other character — letter, digit without its keycap mark, space inside
 * the text, punctuation — makes it not emoji-only.
 */
internal fun String.isEmojiOnlyMessage(maxEmojis: Int = 3): Boolean {
    val text = trim()
    if (text.isEmpty()) return false
    var count = 0
    var index = 0
    var valid = true
    var expectSecondRegional = false
    var justSawJoiner = false
    while (valid && index < text.length) {
        val codePoint = text.codePointAt(index)
        val charCount = Character.charCount(codePoint)
        when {
            codePoint == EMOJI_ZERO_WIDTH_JOINER -> justSawJoiner = true
            codePoint == EMOJI_VARIATION_SELECTOR ||
                codePoint in EMOJI_SKIN_TONES ||
                codePoint == EMOJI_KEYCAP_MARK -> {
                // Continuation of the current emoji: no count.
            }
            codePoint in EMOJI_REGIONAL_INDICATORS -> {
                // Flags are RI pairs; a pair counts as one emoji.
                if (expectSecondRegional) {
                    expectSecondRegional = false
                } else if (justSawJoiner) {
                    valid = false
                } else {
                    count++
                    expectSecondRegional = true
                }
                justSawJoiner = false
            }
            isKeycapBase(codePoint) -> {
                // A digit, # or * only counts with its keycap mark after
                // it — with an optional VS16 in between ("1" + VS16 +
                // U+20E3 is the usual form; VS16 is BMP, so one char).
                val afterBase = index + charCount
                val next = if (afterBase < text.length) text.codePointAt(afterBase) else -1
                val vs16Keycap = next == EMOJI_VARIATION_SELECTOR &&
                    afterBase + 1 < text.length &&
                    text.codePointAt(afterBase + 1) == EMOJI_KEYCAP_MARK
                val marked = next == EMOJI_KEYCAP_MARK || vs16Keycap
                if (marked && !justSawJoiner && !expectSecondRegional) {
                    count++
                } else {
                    valid = false
                }
                justSawJoiner = false
            }
            isEmojiBase(codePoint) -> {
                if (expectSecondRegional) {
                    valid = false
                } else if (!justSawJoiner) {
                    count++
                }
                justSawJoiner = false
            }
            else -> valid = false
        }
        if (count > maxEmojis) valid = false
        index += charCount
    }
    return valid && !expectSecondRegional && count in 1..maxEmojis
}

private fun isKeycapBase(codePoint: Int): Boolean =
    codePoint in '0'.code..'9'.code || codePoint == '#'.code || codePoint == '*'.code

/** Code points that can START an emoji (Unicode emoji-data ranges, pragmatic subset). */
private fun isEmojiBase(codePoint: Int): Boolean = when (codePoint) {
    0x00A9, 0x00AE, 0x203C, 0x2049, 0x2122, 0x2139 -> true
    in 0x2194..0x21AA, in 0x231A..0x231B, 0x2328, 0x2388, 0x23CF,
    in 0x23E9..0x23F3, in 0x23F8..0x23FA, 0x24C2,
    in 0x25AA..0x25AB, 0x25B6, 0x25C0, in 0x25FB..0x25FE,
    in 0x2600..0x27BF, in 0x2934..0x2935, in 0x2B05..0x2B07,
    in 0x2B1B..0x2B1C, 0x2B50, 0x2B55, 0x3030, 0x303D, 0x3297, 0x3299,
    in 0x1F000..0x1FAFF -> true
    else -> false
}

private const val EMOJI_ZERO_WIDTH_JOINER = 0x200D
private const val EMOJI_VARIATION_SELECTOR = 0xFE0F
private const val EMOJI_KEYCAP_MARK = 0x20E3
private val EMOJI_SKIN_TONES = 0x1F3FB..0x1F3FF
private val EMOJI_REGIONAL_INDICATORS = 0x1F1E6..0x1F1FF

val STICKER_EMOJIS: List<String> = listOf(
    "😂",
    "🤣",
    "😊",
    "😍",
    "😘",
    "😭",
    "😅",
    "😁",
    "😉",
    "😋",
    "😎",
    "🥰",
    "😜",
    "🤔",
    "😢",
    "😡",
    "🤗",
    "😇",
    "😏",
    "🙃",
    "🤯",
    "🥳",
    "😴",
    "🫠",
    "🥹",
    "🙈",
    "🙉",
    "🙊",
    "❤️",
    "💔",
    "💕",
    "🔥",
    "💯",
    "👍",
    "👏",
    "🙌",
    "💪",
    "🎉",
)

/**
 * Skin-tone modifiers, lightest to darkest (U+1F3FB–U+1F3FF). Built from
 * code points rather than written as literals: a modifier character is
 * invisible on its own, so a literal would be unreadable in source.
 */
private const val SKIN_TONE_LIGHT = 0x1F3FB
private const val SKIN_TONE_MEDIUM_LIGHT = 0x1F3FC
private const val SKIN_TONE_MEDIUM = 0x1F3FD
private const val SKIN_TONE_MEDIUM_DARK = 0x1F3FE
private const val SKIN_TONE_DARK = 0x1F3FF

val SKIN_TONE_MODIFIERS: List<String> = listOf(
    SKIN_TONE_LIGHT,
    SKIN_TONE_MEDIUM_LIGHT,
    SKIN_TONE_MEDIUM,
    SKIN_TONE_MEDIUM_DARK,
    SKIN_TONE_DARK,
).map { Character.toString(it) }

/** Emojis that ship skin-tone variants — hands, people, professions, and the tone-capable couples (round 14). */
val SKIN_TONE_BASES: Set<String> =
    (HAND_EMOJIS + PEOPLE_EMOJIS + PROFESSION_EMOJIS + COUPLE_TONE_EMOJIS).toSet()

/** Whether long-pressing [emoji] in the grid offers skin tones. */
fun supportsSkinTones(emoji: String): Boolean = emoji in SKIN_TONE_BASES

/**
 * The person emojis that take a tone modifier inside a ZWJ sequence — the
 * only person components in the set's sequences (professions, couples).
 * The modifier goes right after each of these, never after the other
 * components (the 🤝 in 🧑‍🤝‍🧑 takes none; the ❤️ in a couple keeps its
 * U+FE0F).
 */
private val ZWJ_PERSON_COMPONENTS: Set<String> = setOf("👨", "👩", "🧑")

/** Zero-width joiner, U+200D — the glue in ZWJ sequences (families, couples, professions, flags). */
private const val ZERO_WIDTH_JOINER = 0x200D

/**
 * The base emoji plus its five skin-tone variants, for the long-press
 * picker. For a single emoji the modifier replaces U+FE0F — "✌🏻" is U+270C
 * U+1F3FB, and [twemojiFileName] strips FE0F for singles either way, so
 * the asset is `270c-1f3fb.svg`. For a ZWJ sequence the modifier follows
 * each person component and the other components keep their FE0F:
 * "👩🏻‍⚕️" is U+1F469 U+1F3FB U+200D U+2695 U+FE0F, asset
 * `1f469-1f3fb-200d-2695-fe0f.svg`. The base itself stays first: the
 * default yellow is a valid pick too.
 */
fun skinToneVariants(base: String): List<String> {
    val zwj = ZERO_WIDTH_JOINER.toChar().toString()
    val parts = base.split(zwj)
    if (parts.size == 1) {
        val stripped = base.filter { it.code != VARIATION_SELECTOR_16 }
        return listOf(base) + SKIN_TONE_MODIFIERS.map { stripped + it }
    }
    return listOf(base) + SKIN_TONE_MODIFIERS.map { modifier ->
        parts.joinToString(zwj) { component ->
            val person = component.filter { it.code != VARIATION_SELECTOR_16 }
            if (person in ZWJ_PERSON_COMPONENTS) person + modifier else component
        }
    }
}

/**
 * Live emoji search (round 13 fix): English keywords per grid emoji,
 * lowercase, space-separated. Typing "cry" matches 😭 and 😢 ("cry") and
 * 😂 ("tears" only — it stays out of a "cry" search on purpose; tears of
 * joy are not crying). Matching is substring per word, so "cry" also finds
 * "crying". English only for now — the picker search is a typing aid, not
 * a translation layer.
 */
val EMOJI_KEYWORDS: Map<String, String> = mapOf(
    // Smileys
    "😂" to "laugh tears joy funny lol",
    "🤣" to "laugh rofl funny lol",
    "😊" to "smile happy blush",
    "😍" to "love heart eyes crush",
    "😘" to "kiss love wink",
    "😅" to "laugh sweat nervous relief",
    "😁" to "grin happy smile teeth",
    "😉" to "wink smile flirt",
    "😋" to "yum silly tongue tasty",
    "😎" to "cool sunglasses smile",
    "🥰" to "love smile hearts adore",
    "😜" to "silly tongue wink crazy",
    "🤔" to "think hmm confused wonder",
    "🙄" to "eyeroll annoyed whatever",
    "😴" to "sleep tired snore",
    "🤯" to "mind blown shock wow",
    "🥳" to "party celebrate birthday",
    "😭" to "cry sob tears sad",
    "😢" to "cry sad tear",
    "😡" to "angry mad rage",
    "🤗" to "hug love happy",
    "😇" to "angel innocent halo",
    "😏" to "smirk sly smile",
    "😌" to "calm relief peaceful",
    "😬" to "awkward grimace nervous",
    "🤨" to "suspicious skeptical eyebrow",
    "🫠" to "melt hot embarrassed",
    "🥹" to "holding back tears emotional",
    "😪" to "sleepy tired",
    "😷" to "sick mask ill",
    "🥵" to "hot sweat heat",
    "🥶" to "cold freeze ice",
    "😃" to "smile happy",
    "😄" to "smile laugh happy",
    "😆" to "laugh smile happy",
    "😝" to "tongue silly",
    "😛" to "tongue silly",
    "🤪" to "crazy silly zany",
    "🙃" to "upside down silly",
    "😀" to "smile happy grin",
    "🫡" to "salute respect yes sir",
    "🤫" to "shush quiet secret",
    "🫢" to "surprise shock hand mouth oops",
    "🫣" to "peek shy hiding",
    "🥴" to "woozy dizzy drunk",
    "🤢" to "nauseated sick gross",
    "🤮" to "vomit sick puke",
    "🤧" to "sneeze sick achoo",
    "🥱" to "yawn tired sleepy bored",
    "🤠" to "cowboy hat yeehaw",
    "🥸" to "disguise glasses mustache spy",
    "😈" to "devil evil smirk horns",
    "👻" to "ghost boo halloween spooky",
    "💀" to "skull death dead",
    "🤖" to "robot bot ai",
    "👽" to "alien ufo space",
    "🎃" to "pumpkin halloween jack lantern",
    // People
    "👶" to "baby infant child",
    "👦" to "boy child kid son",
    "👧" to "girl child kid daughter",
    "🧑" to "person adult human",
    "👨" to "man adult male guy",
    "👩" to "woman adult female lady",
    "🧓" to "older person elderly senior",
    "👴" to "old man grandpa elderly senior",
    "👵" to "old woman grandma elderly senior",
    "👱" to "blond blonde person hair",
    "👲" to "skullcap person hat",
    "👳" to "turban person hat",
    "👮" to "police officer cop",
    "👷" to "construction worker builder hard hat",
    "💂" to "guard soldier royal",
    "🕵️" to "detective spy sleuth",
    "👰" to "bride veil wedding married",
    "🤵" to "groom tuxedo wedding married",
    "🎅" to "santa claus christmas",
    "🤶" to "mrs claus christmas santa",
    "🦸" to "superhero hero cape",
    "🦹" to "supervillain villain evil",
    "🧙" to "mage wizard witch magic",
    "🧚" to "fairy magic wings",
    "🧛" to "vampire dracula fangs",
    "🧜" to "merperson mermaid merman sea",
    "🧝" to "elf fantasy ears magic",
    "💆" to "massage spa face relax",
    "💇" to "haircut barber salon hair",
    "🚶" to "walking walk pedestrian",
    "🏃" to "running run jog sprint",
    "💃" to "dancing dance woman flamenco",
    "🕺" to "dancing dance man disco",
    "🧖" to "sauna steam spa relax",
    "🧗" to "climbing rock climb",
    "🤸" to "cartwheel gymnastics flip",
    "🏄" to "surfing surf wave",
    "🏊" to "swimming swim pool",
    "🚴" to "biking cycling bike ride",
    "🏋️" to "weightlifting gym lift weights",
    "🤾" to "handball sport ball",
    "🤽" to "water polo sport pool ball",
    "🧘" to "yoga meditation lotus calm",
    "🧎" to "kneeling person",
    "🧍" to "standing person",
    "🏌️" to "golf golfer sport",
    "⛹️" to "basketball ball sport",
    "🚣" to "rowing boat row",
    // Families
    "👨‍👩‍👦" to "family man woman boy",
    "👨‍👩‍👧" to "family man woman girl",
    "👨‍👩‍👧‍👦" to "family man woman girl boy",
    "👨‍👦" to "family man boy dad son",
    "👨‍👧" to "family man girl dad daughter",
    "👨‍👧‍👦" to "family man girl boy dad",
    "👩‍👦" to "family woman boy mom son",
    "👩‍👧" to "family woman girl mom daughter",
    "👩‍👧‍👦" to "family woman girl boy mom",
    "👪" to "family",
    // Couples
    "👩‍❤️‍👨" to "couple love heart woman man",
    "💑" to "couple love heart",
    "👩‍❤️‍💋‍👨" to "kiss couple love woman man",
    "💏" to "kiss couple love",
    "👫" to "couple holding hands man woman",
    "🧑‍🤝‍🧑" to "holding hands people couple friends",
    // Professions
    "👩‍⚕️" to "woman doctor nurse health worker medical",
    "👨‍⚕️" to "man doctor nurse health worker medical",
    "🧑‍⚕️" to "health worker doctor nurse medical person",
    "👩‍🎓" to "woman student graduate school college",
    "👨‍🎓" to "man student graduate school college",
    "🧑‍🎓" to "student graduate school college person",
    "👩‍🏫" to "woman teacher school",
    "👨‍🏫" to "man teacher school",
    "🧑‍🏫" to "teacher school person",
    "👩‍⚖️" to "woman judge court law",
    "👨‍⚖️" to "man judge court law",
    "🧑‍⚖️" to "judge court law person",
    "👩‍🌾" to "woman farmer farm",
    "👨‍🌾" to "man farmer farm",
    "🧑‍🌾" to "farmer farm person",
    "👩‍🍳" to "woman cook chef kitchen",
    "👨‍🍳" to "man cook chef kitchen",
    "🧑‍🍳" to "cook chef kitchen person",
    "👩‍🔧" to "woman mechanic wrench fix",
    "👨‍🔧" to "man mechanic wrench fix",
    "🧑‍🔧" to "mechanic wrench fix person",
    "👩‍🏭" to "woman factory worker industrial",
    "👨‍🏭" to "man factory worker industrial",
    "🧑‍🏭" to "factory worker industrial person",
    "👩‍💼" to "woman office worker business briefcase",
    "👨‍💼" to "man office worker business briefcase",
    "🧑‍💼" to "office worker business briefcase person",
    "👩‍🔬" to "woman scientist lab science",
    "👨‍🔬" to "man scientist lab science",
    "🧑‍🔬" to "scientist lab science person",
    "👩‍💻" to "woman technologist coder computer developer",
    "👨‍💻" to "man technologist coder computer developer",
    "🧑‍💻" to "technologist coder computer developer person",
    "👩‍🎤" to "woman singer music mic",
    "👨‍🎤" to "man singer music mic",
    "🧑‍🎤" to "singer music mic person",
    "👩‍🎨" to "woman artist paint art",
    "👨‍🎨" to "man artist paint art",
    "🧑‍🎨" to "artist paint art person",
    "👩‍✈️" to "woman pilot plane fly",
    "👨‍✈️" to "man pilot plane fly",
    "🧑‍✈️" to "pilot plane fly person",
    "👩‍🚀" to "woman astronaut space rocket",
    "👨‍🚀" to "man astronaut space rocket",
    "🧑‍🚀" to "astronaut space rocket person",
    "👩‍🚒" to "woman firefighter fire truck",
    "👨‍🚒" to "man firefighter fire truck",
    "🧑‍🚒" to "firefighter fire truck person",
    // Hands
    "👍" to "thumbs up like good yes approve",
    "👎" to "thumbs down dislike no bad",
    "👌" to "ok perfect good",
    "✌️" to "peace victory v",
    "🤞" to "fingers crossed luck hope",
    "🤟" to "rock love you horns",
    "🤙" to "call me hang loose shaka",
    "👏" to "clap applause bravo",
    "🙌" to "celebrate praise hooray hands up",
    "🙏" to "pray please thanks",
    "💪" to "strong muscle flex power",
    "👋" to "wave hello hi bye",
    "🤝" to "handshake deal agree",
    "👊" to "fist bump punch",
    "✊" to "fist power solidarity",
    "👈" to "point left",
    "👉" to "point right",
    "👆" to "point up",
    "👇" to "point down",
    "☝️" to "point up one finger",
    "🫰" to "hand fingers crossed money",
    "🫵" to "hand pointing you finger",
    "🫶" to "heart hands love",
    "🤲" to "palms up hands together pray",
    "👐" to "open hands hug",
    "🫳" to "palm down hand",
    "🫴" to "palm up hand",
    // Hearts
    "❤️" to "red heart love",
    "🧡" to "orange heart love",
    "💛" to "yellow heart love",
    "💚" to "green heart love",
    "💙" to "blue heart love",
    "💜" to "purple heart love",
    "🖤" to "black heart love",
    "🤍" to "white heart love",
    "🤎" to "brown heart love",
    "💔" to "broken heart sad breakup",
    "💕" to "two hearts love",
    "💖" to "sparkling heart love",
    "💗" to "growing heart love",
    "💓" to "beating heart love",
    "💞" to "revolving hearts love",
    "💘" to "arrow heart love cupid",
    "💝" to "gift heart love",
    "💟" to "heart decoration",
    "❣️" to "heart exclamation love",
    "🫀" to "heart anatomical organ",
    "🩷" to "pink heart love",
    "🩵" to "light blue heart love",
    "🩶" to "grey gray heart love",
    // Animals
    "🐶" to "dog puppy",
    "🐱" to "cat kitten",
    "🐭" to "mouse",
    "🐹" to "hamster",
    "🐰" to "bunny rabbit",
    "🦊" to "fox",
    "🐻" to "bear",
    "🐼" to "panda",
    "🐨" to "koala",
    "🐯" to "tiger",
    "🦁" to "lion",
    "🐮" to "cow",
    "🐷" to "pig",
    "🐸" to "frog",
    "🐵" to "monkey",
    "🙈" to "see no evil monkey",
    "🙉" to "hear no evil monkey",
    "🙊" to "speak no evil monkey",
    "🐔" to "chicken",
    "🐧" to "penguin",
    "🐦" to "bird",
    "🦄" to "unicorn",
    "🐝" to "bee",
    "🦋" to "butterfly",
    "🐢" to "turtle",
    "🐍" to "snake",
    "🐳" to "whale",
    "🐬" to "dolphin",
    "🦒" to "giraffe",
    "🦘" to "kangaroo",
    "🦥" to "sloth slow",
    "🦦" to "otter",
    "🦔" to "hedgehog",
    "🦇" to "bat halloween vampire",
    "🦉" to "owl bird wise",
    "🦅" to "eagle bird",
    "🦆" to "duck bird",
    "🦢" to "swan bird",
    "🦩" to "flamingo bird pink",
    "🐙" to "octopus sea",
    "🦀" to "crab sea",
    "🦞" to "lobster sea food",
    "🐠" to "tropical fish sea",
    "🦖" to "t rex dinosaur",
    // Food
    "🍎" to "apple fruit",
    "🍐" to "pear fruit",
    "🍊" to "orange fruit",
    "🍋" to "lemon fruit",
    "🍌" to "banana fruit",
    "🍉" to "watermelon fruit",
    "🍇" to "grapes fruit",
    "🍓" to "strawberry fruit",
    "🫐" to "blueberries fruit",
    "🍒" to "cherries fruit",
    "🍑" to "peach fruit",
    "🥭" to "mango fruit",
    "🍍" to "pineapple fruit",
    "🥥" to "coconut fruit",
    "🥝" to "kiwi fruit",
    "🍅" to "tomato",
    "🍆" to "eggplant",
    "🥑" to "avocado",
    "🥦" to "broccoli",
    "🥕" to "carrot",
    "🌽" to "corn",
    "🌶️" to "pepper spicy hot",
    "🍕" to "pizza",
    "🍔" to "burger hamburger",
    "🍟" to "fries french fries",
    "🌭" to "hot dog",
    "🍿" to "popcorn",
    "🍩" to "donut doughnut",
    "🍪" to "cookie",
    "🎂" to "cake birthday",
    "🍰" to "cake slice dessert",
    "🧁" to "cupcake",
    "🍫" to "chocolate",
    "🍬" to "candy",
    "🍭" to "lollipop candy",
    "🍺" to "beer",
    "🍷" to "wine",
    "🥂" to "cheers champagne toast",
    "🍾" to "champagne bottle celebrate",
    "☕" to "coffee",
    "🧋" to "bubble tea boba",
    "🥤" to "drink soda cup",
    "🥞" to "pancakes breakfast",
    "🧇" to "waffle breakfast",
    "🥓" to "bacon breakfast meat",
    "🍗" to "chicken leg meat food",
    "🌮" to "taco mexican food",
    "🌯" to "burrito wrap mexican food",
    "🥗" to "salad green healthy food",
    "🍝" to "spaghetti pasta italian food",
    "🍜" to "noodles ramen soup food",
    "🍣" to "sushi japanese food",
    "🍤" to "shrimp tempura food",
    "🥟" to "dumpling food",
    "🍦" to "ice cream soft serve dessert",
    "🧃" to "juice box drink",
    "🥛" to "milk glass drink",
    "🫖" to "teapot tea",
    // Activities
    "⚽" to "soccer football",
    "🏀" to "basketball",
    "🏈" to "american football",
    "⚾" to "baseball",
    "🎾" to "tennis",
    "🏐" to "volleyball",
    "🏉" to "rugby",
    "🎱" to "pool billiards 8 ball",
    "🏓" to "ping pong table tennis",
    "🏸" to "badminton",
    "🏆" to "trophy win award",
    "🎮" to "game video game controller",
    "🎲" to "dice game",
    "🧩" to "puzzle",
    "🎯" to "target dart bullseye",
    "🎳" to "bowling",
    "🎸" to "guitar music",
    "🎤" to "mic microphone karaoke sing",
    "🎧" to "headphones music",
    "🎬" to "movie film clapper",
    "🎨" to "art paint palette",
    "🎭" to "theater drama masks",
    "🎪" to "circus tent",
    // Objects
    "🎢" to "roller coaster",
    "🎁" to "gift present",
    "🎈" to "balloon",
    "🎉" to "party popper celebrate",
    "🎊" to "confetti celebrate",
    "✨" to "sparkles magic stars",
    "💡" to "light bulb idea",
    "📱" to "phone mobile iphone",
    "💻" to "laptop computer",
    "⌚" to "watch",
    "📷" to "camera photo",
    "🔑" to "key",
    "🔒" to "lock locked",
    "✂️" to "scissors cut",
    "📌" to "pin pushpin",
    "📍" to "location pin map",
    "✏️" to "pencil write",
    "📚" to "books read",
    "💰" to "money bag dollar rich",
    "💎" to "diamond gem",
    "💍" to "ring engagement wedding",
    "👑" to "crown royal king queen",
    "🎩" to "top hat magic",
    "👓" to "glasses",
    "🕶️" to "sunglasses cool",
    "🧢" to "cap hat",
    "👕" to "shirt tshirt",
    "👗" to "dress",
    "👠" to "heel shoe",
    "👟" to "sneaker shoe",
    "🧸" to "teddy bear toy",
    "💌" to "love letter mail",
    "📧" to "email mail",
    "🎟️" to "ticket admission",
    "🎫" to "ticket",
    "🪩" to "disco ball mirror party",
    "🎻" to "violin music",
    "🥁" to "drum music",
    "🎺" to "trumpet music horn",
    "🎷" to "saxophone music jazz",
    "🎹" to "piano music keyboard",
    "📸" to "camera flash photo",
    "📹" to "video camera film",
    "📺" to "tv television",
    "📻" to "radio",
    "⏰" to "alarm clock morning",
    "⌛" to "hourglass time sand",
    // Nature
    "☀️" to "sun sunny",
    "🌤️" to "sun cloud partly cloudy",
    "⛅" to "cloud sun",
    "🌧️" to "rain cloud",
    "⛈️" to "thunder storm rain",
    "❄️" to "snow snowflake winter",
    "☃️" to "snowman winter",
    "⛄" to "snowman winter",
    "🌈" to "rainbow",
    "🔥" to "fire flame hot lit",
    "💧" to "water drop",
    "🌊" to "wave ocean sea",
    "🌍" to "earth world globe",
    "🌙" to "moon night crescent",
    "⭐" to "star",
    "🌟" to "star glowing",
    "🌸" to "cherry blossom flower",
    "🌹" to "rose flower",
    "🌺" to "hibiscus flower",
    "🌻" to "sunflower flower",
    "🌼" to "flower",
    "🌷" to "tulip flower",
    "🌱" to "seedling plant sprout",
    "🌲" to "tree pine evergreen",
    "🌵" to "cactus",
    "🍀" to "clover lucky four leaf",
    "🌋" to "volcano eruption lava",
    "🌌" to "milky way galaxy stars night",
    "🌠" to "shooting star wish",
    "🌡️" to "thermometer temperature hot cold",
    "🌬️" to "wind blow face",
    "🌫️" to "fog mist",
    "🌨️" to "snow cloud winter",
    "🌩️" to "lightning cloud storm",
    "🌦️" to "sun rain cloud",
    "🪐" to "planet saturn ring space",
    "🌕" to "full moon night",
    "🌝" to "full moon face night",
    // Symbols
    "✅" to "check yes correct",
    "❌" to "cross no wrong x",
    "❓" to "question what",
    "❗" to "exclamation important",
    "💯" to "100 hundred percent perfect",
    "🔔" to "bell notification",
    "🔕" to "bell mute silent notification off",
    "💬" to "speech bubble chat comment",
    "💭" to "thought bubble think",
    "💤" to "sleep zzz",
    "🛑" to "stop sign",
    "⛔" to "no entry prohibited",
    "⚠️" to "warning caution alert",
    "🚫" to "prohibited no banned",
    "➕" to "plus add math",
    "➖" to "minus subtract math",
    "✖️" to "multiply x math",
    "♾️" to "infinity forever",
    "©️" to "copyright",
    "®️" to "registered",
    "™️" to "trademark tm",
    "🔱" to "trident",
    "⚜️" to "fleur de lis",
    "♻️" to "recycle green",
    "✳️" to "asterisk star",
    "❇️" to "sparkle star",
    // Flags
    "🇺🇸" to "flag usa america united states",
    "🇬🇧" to "flag uk britain england united kingdom",
    "🇨🇦" to "flag canada",
    "🇫🇷" to "flag france french",
    "🇩🇪" to "flag germany german",
    "🇪🇸" to "flag spain spanish",
    "🇮🇹" to "flag italy italian",
    "🇲🇽" to "flag mexico mexican",
    "🇧🇷" to "flag brazil brazilian",
    "🇯🇵" to "flag japan japanese",
    "🇰🇷" to "flag korea korean south",
    "🇨🇳" to "flag china chinese",
    "🇮🇳" to "flag india indian",
    "🇦🇺" to "flag australia australian",
    "🇳🇱" to "flag netherlands dutch holland",
    "🇸🇪" to "flag sweden swedish",
    "🇳🇴" to "flag norway norwegian",
    "🇩🇰" to "flag denmark danish",
    "🇫🇮" to "flag finland finnish",
    "🇨🇭" to "flag switzerland swiss",
    "🇦🇹" to "flag austria austrian",
    "🇧🇪" to "flag belgium belgian",
    "🇮🇪" to "flag ireland irish",
    "🇵🇹" to "flag portugal portuguese",
    "🇬🇷" to "flag greece greek",
    "🇵🇱" to "flag poland polish",
    "🇺🇦" to "flag ukraine ukrainian",
    "🇿🇦" to "flag south africa african",
    "🇳🇿" to "flag new zealand",
    "🇦🇷" to "flag argentina argentinian",
    "🏳️‍🌈" to "flag rainbow pride lgbt",
    "🏴‍☠️" to "flag pirate skull",
)

/**
 * Whether [emoji] matches [query]: every word of the query must appear as
 * a substring of the emoji's keywords. The caller shows the sectioned grid
 * while the query is blank; this only runs on a non-blank query.
 */
fun emojiMatchesQuery(emoji: String, query: String): Boolean {
    val keywords = EMOJI_KEYWORDS[emoji] ?: return false
    return query.trim().lowercase(java.util.Locale.ROOT).split(" ")
        .filter { it.isNotEmpty() }
        .all { word -> word in keywords }
}

/** Variation selector stripped from Twemoji filenames (see above). */
private const val VARIATION_SELECTOR_16 = 0xFE0F

/**
 * Twemoji asset filename for [unicode], e.g. "1f602" for 😂, "2764" for ❤️.
 * Round 14 — U+FE0F is stripped ONLY for a single-codepoint emoji; a
 * multi-codepoint sequence keeps it (the fork's files do: man health
 * worker is `1f468-200d-2695-fe0f.svg`, rainbow flag is
 * `1f3f3-fe0f-200d-1f308.svg`). Stripping it for sequences was the round-14
 * expansion's first bug: every FE0F-bearing ZWJ file 404'd.
 */
fun twemojiFileName(unicode: String): String {
    val codePoints = ArrayList<Int>()
    var index = 0
    while (index < unicode.length) {
        val codePoint = unicode.codePointAt(index)
        codePoints.add(codePoint)
        index += Character.charCount(codePoint)
    }
    val single = codePoints.count { it != VARIATION_SELECTOR_16 } == 1
    return codePoints
        .filter { !single || it != VARIATION_SELECTOR_16 }
        .joinToString("-") { it.toString(16) }
}

/** `file:///android_asset/` URI of the bundled SVG for [unicode]. */
fun twemojiSvgAssetUri(unicode: String): String =
    "file:///android_asset/twemoji/svg/${twemojiFileName(unicode)}.svg"

/** `file:///android_asset/` URI of the bundled 72x72 PNG for [unicode]. */
fun twemojiPngAssetUri(unicode: String): String =
    "file:///android_asset/twemoji/png/${twemojiFileName(unicode)}.png"

/**
 * Local file URI for a sticker's Twemoji graphic: the bundled 72x72 PNG
 * under `assets/twemoji/png/`.
 *
 * This identifies the bundled asset (Coil renders it). It is NOT directly
 * readable by the MMS send path, which reads attachments through
 * `ContentResolver.openInputStream` — an asset is not a file, so that call
 * cannot open a `file:///android_asset/` URI. To send a sticker, call
 * [stickerSendUri], which copies the PNG to a real file and returns a
 * FileProvider `content://` URI the send path accepts.
 */
fun stickerAssetUri(unicode: String): String = twemojiPngAssetUri(unicode)

/**
 * Renders the sticker's bundled SVG to a 512px PNG in the outgoing-MMS
 * cache (`cacheDir/mms_outgoing/`, covered by the FileProvider paths) and
 * returns a `content://` URI the MMS send path can read via
 * `ContentResolver.openInputStream`.
 *
 * Round 16 — the bundled PNGs are only 72x72, and the thread bubble shows
 * them at 240dp, so sent stickers looked grainy and stretched. Rendering
 * the vector at 512px (about 2x the bubble size) keeps them crisp. The
 * renderer is AndroidSVG, already a dependency through `coil-svg` (and
 * already ProGuard-kept). When the SVG render fails for any reason, the
 * old 72px PNG copy is the fallback — a grainy sticker still beats no
 * sticker. Returns `null` on IO failure or when the emoji has no bundled
 * asset (only [STICKER_EMOJIS] do). The file is reused across sends, so
 * no duplicates pile up in the cache; the `-512` suffix in the name keeps
 * a stale 72px file from an older build from being reused.
 */
suspend fun stickerSendUri(context: Context, unicode: String): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = twemojiFileName(unicode)
            val dir = File(context.cacheDir, "mms_outgoing").apply { mkdirs() }
            val target = File(dir, "sticker-$name-512.png")
            if (!target.exists()) {
                val rendered = renderStickerSvg(context, name, target)
                if (!rendered) {
                    context.assets.open("twemoji/png/$name.png").use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
            runCatching {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    target,
                )
            }.getOrElse { Uri.fromFile(target) }
        }.getOrNull()
    }

/**
 * Renders the bundled Twemoji SVG for [name] into [target] as a
 * [STICKER_SEND_PX] PNG. `false` on any failure — the caller falls back
 * to the bundled 72px PNG. Never throws.
 */
private fun renderStickerSvg(context: Context, name: String, target: File): Boolean =
    runCatching {
        val svg = context.assets.open("twemoji/svg/$name.svg").use { SVG.getFromInputStream(it) }
        svg.documentWidth = STICKER_SEND_PX.toFloat()
        svg.documentHeight = STICKER_SEND_PX.toFloat()
        val bitmap = Bitmap.createBitmap(STICKER_SEND_PX, STICKER_SEND_PX, Bitmap.Config.ARGB_8888)
        try {
            svg.renderToCanvas(Canvas(bitmap))
            val ok = target.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            check(ok) { "PNG compress failed" }
        } finally {
            bitmap.recycle()
        }
        true
    }.onFailure {
        // Never leave a partial file behind: `stickerSendUri` reuses an
        // existing target, and a truncated PNG would be sent as-is.
        runCatching { target.delete() }
    }.getOrDefault(false)

/** Send-time sticker render size: ~2x the 240dp thread bubble, crisp on every density. */
private const val STICKER_SEND_PX = 512

/**
 * Local Coil loader with the SVG decoder, remembered for the lifetime of
 * the calling composable. Same pattern as the video-frame loader in
 * [MediaAttachmentBubble]: a local loader leaves the app-wide Coil setup
 * untouched, and `RealImageLoader` appends the default fetchers/decoders
 * on top of the added factory.
 */
@Composable
fun rememberTwemojiImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(SvgDecoder.Factory()) }
            .build()
    }
}

/**
 * Local Coil loader with the GIF decoder, remembered for the lifetime of
 * the calling composable. Same pattern as [rememberTwemojiImageLoader]:
 * a local loader leaves the app-wide Coil setup untouched, and
 * `RealImageLoader` appends the default fetchers/decoders on top of the
 * added factory. Used by the GIF search grid ([GifGrid]) so thumbnails
 * animate instead of showing a static first frame.
 */
@Composable
fun rememberGifImageLoader(): ImageLoader {
    val context = LocalContext.current
    return remember(context) {
        ImageLoader.Builder(context)
            .components { add(GifDecoder.Factory()) }
            .build()
    }
}
