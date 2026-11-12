// EmojiSection lives in this file with its data (EMOJI_SECTIONS) rather than
// in a file of its own — the file-name rule is suppressed for the whole file.
@file:Suppress("MatchingDeclarationName")

package com.filestech.sms.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.decode.SvgDecoder
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
 * Filename rule (verified against the fork): the emoji's code points in
 * lowercase hex joined with `-`, with U+FE0F stripped — the red heart is
 * `2764.svg`, not `2764-fe0f.svg`. The grid set is single-codepoint emojis
 * only (no ZWJ sequences, no flags); skin-tone variants are base +
 * modifier, e.g. `1f44d-1f3fb.svg`, and the same rule maps them.
 */

/** One titled section of the emoji grid. */
data class EmojiSection(
    @androidx.annotation.StringRes val titleRes: Int,
    val emojis: List<String>,
)

/**
 * The hand emojis — every one of them ships skin-tone variants (see
 * [SKIN_TONE_MODIFIERS]), so the list lives here once and the Hands section
 * below reuses it. The set has no people emojis yet (no man/woman/person),
 * so hands are the only skin-tone-capable cells in the grid.
 */
val HAND_EMOJIS: List<String> = listOf(
    "👍", "👎", "👌", "✌️", "🤞", "🤟", "🤙", "👏",
    "🙌", "🙏", "💪", "👋", "🤝", "👊", "✊", "👈",
    "👉", "👆", "👇", "☝️",
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
        ),
    ),
    EmojiSection(R.string.emoji_section_hands, HAND_EMOJIS),
    EmojiSection(
        R.string.emoji_section_hearts,
        listOf(
            "❤️", "🧡", "💛", "💚", "💙", "💜", "🖤", "🤍",
            "🤎", "💔", "💕", "💖", "💗", "💓", "💞", "💘",
            "💝",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_animals,
        listOf(
            "🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼",
            "🐨", "🐯", "🦁", "🐮", "🐷", "🐸", "🐵", "🙈",
            "🙉", "🙊", "🐔", "🐧", "🐦", "🦄", "🐝", "🦋",
            "🐢", "🐍", "🐳", "🐬",
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
        ),
    ),
    EmojiSection(
        R.string.emoji_section_nature,
        listOf(
            "☀️", "🌤️", "⛅", "🌧️", "⛈️", "❄️", "☃️", "⛄",
            "🌈", "🔥", "💧", "🌊", "🌍", "🌙", "⭐", "🌟",
            "🌸", "🌹", "🌺", "🌻", "🌼", "🌷", "🌱", "🌲",
            "🌵", "🍀",
        ),
    ),
    EmojiSection(
        R.string.emoji_section_symbols,
        listOf(
            "✅", "❌", "❓", "❗", "💯", "🔔", "🔕", "💬",
            "💭", "💤", "🛑", "⛔", "⚠️", "🚫",
        ),
    ),
)

/** Flat view of [EMOJI_SECTIONS], for callers that do not need sections. */
val EMOJI_GRID_EMOJIS: List<String> = EMOJI_SECTIONS.flatMap { it.emojis }

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

/** Hand emojis that ship skin-tone variants — exactly [HAND_EMOJIS]. */
val SKIN_TONE_BASES: Set<String> = HAND_EMOJIS.toSet()

/** Whether long-pressing [emoji] in the grid offers skin tones. */
fun supportsSkinTones(emoji: String): Boolean = emoji in SKIN_TONE_BASES

/**
 * The base emoji plus its five skin-tone variants, for the long-press
 * picker. ✌️/☝️ carry U+FE0F; the tone modifier replaces it — "✌🏻" is
 * U+270C U+1F3FB, and [twemojiFileName] strips FE0F either way, so the
 * asset is `270c-1f3fb.svg`. The base itself stays first: the default
 * yellow is a valid pick too.
 */
fun skinToneVariants(base: String): List<String> {
    val stripped = base.filter { it.code != VARIATION_SELECTOR_16 }
    return listOf(base) + SKIN_TONE_MODIFIERS.map { stripped + it }
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

/** Twemoji asset filename for [unicode], e.g. "1f602" for 😂, "2764" for ❤️. */
fun twemojiFileName(unicode: String): String {
    val parts = ArrayList<String>()
    var index = 0
    while (index < unicode.length) {
        val codePoint = unicode.codePointAt(index)
        if (codePoint != VARIATION_SELECTOR_16) {
            parts.add(codePoint.toString(16))
        }
        index += Character.charCount(codePoint)
    }
    return parts.joinToString("-")
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
 * Copies the sticker's bundled PNG out of assets into the outgoing-MMS
 * cache (`cacheDir/mms_outgoing/`, covered by the FileProvider paths) and
 * returns a `content://` URI the MMS send path can read via
 * `ContentResolver.openInputStream`. Returns `null` on IO failure or when
 * the emoji has no bundled PNG (only [STICKER_EMOJIS] do). The file is
 * reused across sends, so no duplicates pile up in the cache.
 */
suspend fun stickerSendUri(context: Context, unicode: String): Uri? =
    withContext(Dispatchers.IO) {
        runCatching {
            val name = twemojiFileName(unicode)
            val dir = File(context.cacheDir, "mms_outgoing").apply { mkdirs() }
            val target = File(dir, "sticker-$name.png")
            if (!target.exists()) {
                context.assets.open("twemoji/png/$name.png").use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
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
