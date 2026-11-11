package com.filestech.sms.ui.components

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import coil.ImageLoader
import coil.decode.SvgDecoder
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
 * `2764.svg`, not `2764-fe0f.svg`. The set is single-codepoint emojis only
 * (no ZWJ sequences, no skin tones, no flags), so the mapping is trivial.
 */

val EMOJI_GRID_EMOJIS: List<String> = listOf(
    "😂",
    "🤣",
    "😊",
    "😍",
    "😘",
    "😅",
    "😁",
    "😉",
    "😋",
    "😎",
    "🥰",
    "😜",
    "🤔",
    "🙄",
    "😴",
    "🤯",
    "🥳",
    "😭",
    "😢",
    "😡",
    "🤗",
    "😇",
    "😏",
    "😌",
    "😬",
    "🤨",
    "🫠",
    "🥹",
    "😪",
    "😷",
    "🥵",
    "🥶",
    "😃",
    "😄",
    "😆",
    "😝",
    "😛",
    "🤪",
    "🙃",
    "😀",
    "👍",
    "👎",
    "👌",
    "✌️",
    "🤞",
    "🤟",
    "🤙",
    "👏",
    "🙌",
    "🙏",
    "💪",
    "👋",
    "🤝",
    "👊",
    "✊",
    "👈",
    "👉",
    "👆",
    "👇",
    "☝️",
    "❤️",
    "🧡",
    "💛",
    "💚",
    "💙",
    "💜",
    "🖤",
    "🤍",
    "🤎",
    "💔",
    "💕",
    "💖",
    "💗",
    "💓",
    "💞",
    "💘",
    "💝",
    "🐶",
    "🐱",
    "🐭",
    "🐹",
    "🐰",
    "🦊",
    "🐻",
    "🐼",
    "🐨",
    "🐯",
    "🦁",
    "🐮",
    "🐷",
    "🐸",
    "🐵",
    "🙈",
    "🙉",
    "🙊",
    "🐔",
    "🐧",
    "🐦",
    "🦄",
    "🐝",
    "🦋",
    "🐢",
    "🐍",
    "🐳",
    "🐬",
    "🍎",
    "🍐",
    "🍊",
    "🍋",
    "🍌",
    "🍉",
    "🍇",
    "🍓",
    "🫐",
    "🍒",
    "🍑",
    "🥭",
    "🍍",
    "🥥",
    "🥝",
    "🍅",
    "🍆",
    "🥑",
    "🥦",
    "🥕",
    "🌽",
    "🌶️",
    "🍕",
    "🍔",
    "🍟",
    "🌭",
    "🍿",
    "🍩",
    "🍪",
    "🎂",
    "🍰",
    "🧁",
    "🍫",
    "🍬",
    "🍭",
    "🍺",
    "🍷",
    "🥂",
    "🍾",
    "☕",
    "🧋",
    "🥤",
    "⚽",
    "🏀",
    "🏈",
    "⚾",
    "🎾",
    "🏐",
    "🏉",
    "🎱",
    "🏓",
    "🏸",
    "🏆",
    "🎮",
    "🎲",
    "🧩",
    "🎯",
    "🎳",
    "🎸",
    "🎤",
    "🎧",
    "🎬",
    "🎨",
    "🎭",
    "🎪",
    "🎢",
    "🎁",
    "🎈",
    "🎉",
    "🎊",
    "✨",
    "💡",
    "📱",
    "💻",
    "⌚",
    "📷",
    "🔑",
    "🔒",
    "✂️",
    "📌",
    "📍",
    "✏️",
    "📚",
    "💰",
    "💎",
    "💍",
    "👑",
    "🎩",
    "👓",
    "🕶️",
    "🧢",
    "👕",
    "👗",
    "👠",
    "👟",
    "🧸",
    "☀️",
    "🌤️",
    "⛅",
    "🌧️",
    "⛈️",
    "❄️",
    "☃️",
    "⛄",
    "🌈",
    "🔥",
    "💧",
    "🌊",
    "🌍",
    "🌙",
    "⭐",
    "🌟",
    "🌸",
    "🌹",
    "🌺",
    "🌻",
    "🌼",
    "🌷",
    "🌱",
    "🌲",
    "🌵",
    "🍀",
    "✅",
    "❌",
    "❓",
    "❗",
    "💯",
    "🔔",
    "🔕",
    "💬",
    "💭",
    "💤",
    "🛑",
    "⛔",
    "⚠️",
    "🚫",
)

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
