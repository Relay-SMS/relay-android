package com.filestech.sms.domain.reaction

/**
 * v1.4.1 — pure-Kotlin decoder for incoming SMS bodies that look like a reaction sent
 * back by another Relay instance, by Apple Messages, or by Google Messages.
 *
 * Formats decoded (see [decode] and [decodeGoogleFormat]) :
 *
 *   - **`Reacted <emoji> to «<preview>»`** — the standard Tapback shape produced by
 *     Relay's own [com.filestech.sms.domain.usecase.SendReactionUseCase
 *     .buildTapbackBody]. The preview may end with `…` if Relay had to truncate
 *     the original body to fit the UCS-2 70-char segment cap. The trailing `…` is
 *     stripped before exposing [DecodedReaction.previewPrefix] so the caller can
 *     match it as a `LIKE prefix%` against stored outgoing message bodies.
 *
 *   - **`Reacted <emoji>`** (no `to «…»` tail) — produced by Relay when reacting to
 *     a message with no text body (a voice MMS / image without caption). The caller
 *     should match the most recent outgoing message in the conversation (ordered by
 *     date desc) as the target.
 *
 *   - **Apple Messages tapbacks** — `Loved / Liked / Disliked / Laughed at /
 *     Emphasized / Questioned "…"` (and their `Removed … from "…"` forms). Apple does
 *     NOT use the `Reacted … to …` shape, which is why these used to arrive as plain
 *     text bubbles saying e.g. `Loved "your text"`.
 *
 *   - **Google Messages reactions** — framed with invisible characters (U+200A
 *     framing, U+200B separators for an add, U+200C for a removal). Decoded by
 *     [decodeGoogleFormat], which MUST be called on the raw SMS body: the receiver
 *     strips U+200B from the body before [decode] runs, and the framing would be
 *     gone by then.
 *
 * **Pure emojis are NOT decoded.** Relay's "emoji only" reaction mode (cf.
 * `SendingSettings.reactionEmojiOnly`) sends just the emoji byte sequence — but a
 * real text message containing only an emoji ("👍 great job!") is ambiguous and we
 * MUST NOT silently swallow it as a reaction. Users on emoji-only mode trade
 * cross-SMS-Tech badge rendering for legacy-OEM cleanliness.
 *
 * Stateless object, no Android dependency, fully unit-testable.
 */
object IncomingReactionDecoder {

    /**
     * Result of a successful decode.
     *
     * @property emoji  the verbatim emoji string the remote user reacted with. May be a
     *   multi-codepoint cluster (ZWJ family, flag, skin-tone modifier). Caller stores it
     *   in `messages.reaction_emoji` as-is.
     * @property previewPrefix the body prefix of the original message the remote reacted
     *   to, or `null` when the wire format had no `to «…»` segment (target = most recent
     *   outgoing in the conversation). The `…` truncation marker is stripped.
     * @property kind  signals to the caller how strict the matching against an outgoing
     *   message must be. [Kind.Tapback] = full verbose Tapback wire format, always safe
     *   to bind to ANY recent outgoing in the conversation. [Kind.EmojiOnly] = the body
     *   contained nothing but one-to-two emojis, which is ambiguous with a real "❤️"
     *   message — caller MUST require a recent outgoing within a tight time window
     *   (see [EMOJI_ONLY_REACT_WINDOW_MS]) to avoid promoting a genuine emoji message
     *   into a fake reaction.
     */
    data class DecodedReaction(
        val emoji: String,
        val previewPrefix: String?,
        val kind: Kind,
        /**
         * v1.6.2 — `true` quand l'encoder a tronqué le body original (preview suivi de
         * `…`) faute de tenir dans 1 segment UCS-2. Permet au matcher
         * [com.filestech.sms.data.repository.ConversationMirror.applyIncomingReaction]
         * de distinguer :
         *   - non tronqué : le `previewPrefix` est LE body complet → match EXACT
         *     (élimine l'ambiguïté quand plusieurs OUTGOING partagent un même
         *     préfixe court : "Hello" vs "Hello world").
         *   - tronqué : seul un préfixe est connu → match `STARTS WITH` reste l'unique
         *     stratégie possible (avec l'ambiguïté résiduelle inhérente au protocole).
         *
         * `false` pour [Kind.EmojiOnly] (pas de preview).
         */
        val wasTruncated: Boolean = false,
        /**
         * `true` when the remote user REMOVED a reaction (`Removed a heart from
         * "…"`, Google Messages U+200C framing…). The [emoji] is the one being
         * removed. The caller clears the badge instead of setting it, and must
         * not post a "reacted" notification for it.
         */
        val isRemoval: Boolean = false,
    ) {
        enum class Kind { Tapback, EmojiOnly }
    }

    /**
     * v1.4.1 — time window applied to [Kind.EmojiOnly] candidates : the caller only
     * promotes the SMS to a reaction badge if there is an outgoing message in the
     * conversation strictly younger than this. 5 min covers the realistic UX of a
     * friend reacting right after we typed something, while preventing a "❤️" SMS
     * sent days later (unrelated) from being misread as a reaction to our last DM.
     *
     * `internal` because the only consumer is [com.filestech.sms.data.repository
     * .ConversationMirror.applyIncomingReaction] in the same Gradle module —
     * keeping it out of the public surface follows the project's minimal-visibility
     * convention.
     */
    const val EMOJI_ONLY_REACT_WINDOW_MS: Long = 5 * 60 * 1_000L

    /**
     * Max number of UTF-16 code units accepted as a pure-emoji body. 16 covers up
     * to 2 ZWJ clusters (a man-woman-girl family = 7 code units, two of those = 14).
     * Anything longer is unlikely to be a reaction — almost certainly text.
     */
    private const val EMOJI_ONLY_MAX_UNITS: Int = 16

    /**
     * v1.5.1 — defensive cap on decoder input length. A Tapback always fits in a single
     * UCS-2 SMS segment (70 chars) ; even with the longest preview Relay emits, the
     * body stays under 100 chars. We accept up to [MAX_DECODE_INPUT_LENGTH] to be lenient
     * with multi-cluster emojis but reject anything longer outright. This neutralises a
     * theoretical ReDoS on [TAPBACK_WITH_PREVIEW_REGEX] : an attacker sending a multi-part
     * SMS like `Reacted ❤️ to «aaaa…` (no closing guillemet, 3000 chars) would otherwise
     * force the regex engine into catastrophic backtracking on the non-greedy quantifier.
     */
    private const val MAX_DECODE_INPUT_LENGTH: Int = 400

    /**
     * Tries to decode [body] as a reaction-back. Returns `null` when [body] is plain
     * text — caller falls back to the regular "store as incoming SMS" path.
     *
     * Tries to decode [body] as a reaction-back. Returns `null` when [body] is plain
     * text — caller falls back to the regular "store as incoming SMS" path.
     *
     * The body is tried against each known wire format in turn ([ATTEMPT_STEPS]).
     * A step reports [DecodeAttempt.Found] (stop — this is the reaction),
     * [DecodeAttempt.NotMatched] (try the next format) or [DecodeAttempt.Invalid]
     * (the body matched a format but failed its guards — stop and return `null`,
     * exactly as the early `return null`s of the previous implementation did).
     * Splitting the chain into one small function per format keeps every
     * function under Detekt's ReturnCount limit (max 4).
     */
    fun decode(body: String): DecodedReaction? {
        if (body.length > MAX_DECODE_INPUT_LENGTH) return null
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return resolveAttempts(trimmed)
    }

    private fun resolveAttempts(trimmed: String): DecodedReaction? {
        for (attempt in ATTEMPT_STEPS) {
            when (val result = attempt(trimmed)) {
                is DecodeAttempt.Found -> return result.reaction
                DecodeAttempt.Invalid -> return null
                DecodeAttempt.NotMatched -> Unit
            }
        }
        return null
    }

    /**
     * Outcome of one decode attempt. The split exists because the historical
     * implementation returned `null` outright for a matched-but-invalid body
     * (e.g. an empty preview), while a non-match fell through to the next
     * format — collapsing the two would change what decodes.
     */
    private sealed interface DecodeAttempt {
        data class Found(val reaction: DecodedReaction) : DecodeAttempt
        object NotMatched : DecodeAttempt
        object Invalid : DecodeAttempt
    }

    /**
     * Known wire formats, in the order [decode] tries them. The order matters:
     * the French named form is a strict super-pattern of the anonymous one, and
     * the Apple tapbacks are tried before the generic emoji+quote shape.
     */
    private val ATTEMPT_STEPS: List<(String) -> DecodeAttempt> = listOf(
        { trimmed -> decodeWithPreview(trimmed, READABLE_FR_NAMED_WITH_PREVIEW_REGEX, 2, 3) },
        { trimmed -> decodeNoPreview(trimmed, READABLE_FR_NAMED_NO_PREVIEW_REGEX, 2) },
        { trimmed -> decodeWithPreview(trimmed, READABLE_FR_WITH_PREVIEW_REGEX, 1, 2) },
        { trimmed -> decodeNoPreview(trimmed, READABLE_FR_NO_PREVIEW_REGEX, 1) },
        ::decodeIosTapback,
        ::decodeGenericRemoval,
        ::decodeEmojiWithQuote,
        { trimmed -> decodeWithPreview(trimmed, READABLE_FR_LEGACY_WITH_PREVIEW_REGEX, 1, 2) },
        { trimmed -> decodeNoPreview(trimmed, READABLE_FR_LEGACY_NO_PREVIEW_REGEX, 1) },
        { trimmed -> decodeWithPreview(trimmed, TAPBACK_WITH_PREVIEW_REGEX, 1, 2) },
        { trimmed -> decodeNoPreview(trimmed, TAPBACK_NO_PREVIEW_REGEX, 1) },
        ::decodeEmojiOnly,
    )

    /**
     * Shared shape for the `… with preview` formats (`Reacted <emoji> to
     * «<preview>»`, the French readable forms, the legacy form): the emoji is
     * group [emojiGroup], the preview group [previewGroup]. A matched body
     * with an empty emoji or preview is [DecodeAttempt.Invalid], not a
     * fall-through.
     */
    private fun decodeWithPreview(
        trimmed: String,
        regex: Regex,
        emojiGroup: Int,
        previewGroup: Int,
    ): DecodeAttempt =
        regex.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[emojiGroup].trim()
            val rawPreview = m.groupValues[previewGroup]
            val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
            when {
                emoji.isEmpty() || preview.isEmpty() -> DecodeAttempt.Invalid
                else -> DecodeAttempt.Found(
                    DecodedReaction(
                        emoji = emoji,
                        previewPrefix = preview,
                        kind = DecodedReaction.Kind.Tapback,
                        wasTruncated = rawPreview.trimEnd().endsWith(TRUNCATION_MARKER),
                    ),
                )
            }
        } ?: DecodeAttempt.NotMatched

    /**
     * Shared shape for the `… without preview` formats: the emoji is group
     * [emojiGroup] and must contain a non-ASCII codepoint (a real emoji
     * always does; "Reacted hello" must not decode). A matched body with an
     * ASCII-only token is [DecodeAttempt.Invalid].
     */
    private fun decodeNoPreview(
        trimmed: String,
        regex: Regex,
        emojiGroup: Int,
    ): DecodeAttempt =
        regex.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[emojiGroup]
            when {
                emoji.isEmpty() || emoji.all { it.code < 128 } -> DecodeAttempt.Invalid
                else -> DecodeAttempt.Found(
                    DecodedReaction(
                        emoji = emoji,
                        previewPrefix = null,
                        kind = DecodedReaction.Kind.Tapback,
                    ),
                )
            }
        } ?: DecodeAttempt.NotMatched

    /**
     * Apple Messages tapbacks (English): `Loved / Liked / Disliked / Laughed
     * at / Emphasized / Questioned "…"` and their `Removed … from "…"` forms.
     * Quotes are curly “ ” or straight " (carriers strip punctuation). An
     * empty preview falls through to the next tapback verb, then to the next
     * format — it is not a hard rejection.
     */
    private fun decodeIosTapback(trimmed: String): DecodeAttempt =
        IOS_TAPBACK_REGEXES.firstNotNullOfOrNull { (regex, emoji, isRemoval) ->
            regex.matchEntire(trimmed)?.let { m ->
                val rawPreview = m.groupValues[1]
                val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
                if (preview.isEmpty()) {
                    null
                } else {
                    DecodeAttempt.Found(
                        DecodedReaction(
                            emoji = emoji,
                            previewPrefix = preview,
                            kind = DecodedReaction.Kind.Tapback,
                            wasTruncated = rawPreview.trimEnd().endsWith(TRUNCATION_MARKER),
                            isRemoval = isRemoval,
                        ),
                    )
                }
            }
        } ?: DecodeAttempt.NotMatched

    /**
     * Apple Messages generic removal: `Removed <emoji> from "…"`. The
     * captured token must contain a non-ASCII codepoint so a sentence like
     * `Removed the meeting from "Friday"` cannot match. Guard failures fall
     * through to the next format.
     */
    private fun decodeGenericRemoval(trimmed: String): DecodeAttempt =
        GENERIC_REMOVED_REGEX.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[1]
            val rawPreview = m.groupValues[2]
            val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
            when {
                emoji.isEmpty() || emoji.all { it.code < 128 } -> DecodeAttempt.NotMatched
                preview.isEmpty() -> DecodeAttempt.NotMatched
                else -> DecodeAttempt.Found(
                    DecodedReaction(
                        emoji = emoji,
                        previewPrefix = preview,
                        kind = DecodedReaction.Kind.Tapback,
                        wasTruncated = rawPreview.trimEnd().endsWith(TRUNCATION_MARKER),
                        isRemoval = true,
                    ),
                )
            }
        } ?: DecodeAttempt.NotMatched

    /**
     * v1.9.0 — Emoji + quote compact format: `<emoji> «<preview>»`. The
     * emoji must contain a real emoji char ([isLikelyEmojiChar]) — the old
     * ASCII guard wrongly accepted an accented French word (`été «aperçu»`).
     * Fast-path: skip the regex when there is no closing guillemet (the
     * non-greedy pattern backtracks on pathological input).
     */
    private fun decodeEmojiWithQuote(trimmed: String): DecodeAttempt {
        if (!trimmed.contains('»') && trimmed.lastIndexOf('"') <= 0) {
            return DecodeAttempt.NotMatched
        }
        return EMOJI_WITH_QUOTE_REGEX.matchEntire(trimmed)?.let { m ->
            val emoji = m.groupValues[1]
            val rawPreview = m.groupValues[2]
            val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
            when {
                emoji.isEmpty() || emoji.none { isLikelyEmojiChar(it) } ->
                    DecodeAttempt.NotMatched
                preview.isEmpty() -> DecodeAttempt.NotMatched
                else -> DecodeAttempt.Found(
                    DecodedReaction(
                        emoji = emoji,
                        previewPrefix = preview,
                        kind = DecodedReaction.Kind.Tapback,
                        wasTruncated = rawPreview.trimEnd().endsWith(TRUNCATION_MARKER),
                    ),
                )
            }
        } ?: DecodeAttempt.NotMatched
    }

    /**
     * v1.4.1 — emoji-only candidate (SendingSettings.reactionEmojiOnly =
     * true). The body contains nothing but one or two emojis; tagged
     * [Kind.EmojiOnly] so the caller requires a tight time window before
     * promoting it to a reaction badge.
     */
    private fun decodeEmojiOnly(trimmed: String): DecodeAttempt =
        if (looksLikeEmojiOnly(trimmed)) {
            DecodeAttempt.Found(
                DecodedReaction(
                    emoji = trimmed,
                    previewPrefix = null,
                    kind = DecodedReaction.Kind.EmojiOnly,
                ),
            )
        } else {
            DecodeAttempt.NotMatched
        }

    /**
     * Google Messages reaction format, decoded from the RAW SMS body.
     *
     * The body is framed with invisible characters: it starts and ends with
     * U+200A (hair space), the emoji sits between two separator chars, and the
     * quoted original text sits between the next two U+200A. The separator is
     * U+200B for an add and U+200C for a removal:
     *
     *   add:     `\u200A…\u200B<emoji>\u200B…\u200A<original>\u200A…\u200A`
     *   removal: `\u200A…\u200C<emoji>\u200C…\u200A<original>\u200A…\u200A`
     *
     * MUST be called on the raw body: the receiver strips U+200B (via
     * `stripInvisibleChars`) before [decode] runs, which destroys the
     * framing. Returns `null` when the body is not Google-framed. The
     * segment checks (no separator / framing char inside a skipped segment)
     * keep stray invisible-char soup from misclassifying as a reaction.
     */
    fun decodeGoogleFormat(body: String): DecodedReaction? {
        if (body.length > MAX_DECODE_INPUT_LENGTH) return null
        if (!body.startsWith(GOOGLE_FRAME) || !body.endsWith(GOOGLE_FRAME)) {
            return null
        }
        return parseGoogleFramed(body, GOOGLE_ADD_SEPARATOR, isRemoval = false)
            ?: parseGoogleFramed(body, GOOGLE_REMOVE_SEPARATOR, isRemoval = true)
    }

    private fun parseGoogleFramed(
        body: String,
        separator: Char,
        isRemoval: Boolean,
    ): DecodedReaction? =
        googleQuotePositions(body, separator)?.let { positions ->
            val emoji = body.substring(positions.firstSep + 1, positions.secondSep)
            val rawPreview = body.substring(positions.openQuote + 1, positions.closeQuote)
            val preview = rawPreview.removeSuffix(TRUNCATION_MARKER).trim()
            val emojiInvalid = emoji.isEmpty() || emoji.none { isLikelyEmojiChar(it) }
            val framingInvalid = emoji.any { it == GOOGLE_FRAME } || preview.isEmpty()
            if (emojiInvalid || framingInvalid) {
                null
            } else {
                DecodedReaction(
                    emoji = emoji,
                    previewPrefix = preview,
                    kind = DecodedReaction.Kind.Tapback,
                    wasTruncated = rawPreview.trimEnd().endsWith(TRUNCATION_MARKER),
                    isRemoval = isRemoval,
                )
            }
        }

    /** Positions of the Google framing markers: the two separators around the
     * emoji and the two frame chars around the quoted original text. */
    private data class GoogleQuotePositions(
        val firstSep: Int,
        val secondSep: Int,
        val openQuote: Int,
        val closeQuote: Int,
    )

    /**
     * Locates the Google framing markers, or `null` when the body is not a
     * complete, well-formed Google reaction: a missing marker, a skipped
     * segment containing a separator or frame char, or no trailing segment
     * after the closing quote. The checks are grouped into small booleans so
     * no single condition grows complex, and each substring is only read once
     * its bounds are known to be valid — [String.substring] would throw on an
     * out-of-range slice.
     */
    private fun googleQuotePositions(
        body: String,
        separator: Char,
    ): GoogleQuotePositions? {
        val firstSep = body.indexOf(separator, startIndex = 1)
        val secondSep = body.indexOf(separator, startIndex = firstSep + 1)
        val openQuote = body.indexOf(GOOGLE_FRAME, startIndex = secondSep + 1)
        val closeQuote = body.indexOf(GOOGLE_FRAME, startIndex = openQuote + 1)
        val separatorsFound = firstSep >= 0 && secondSep >= 0
        val quotesFound = openQuote >= 0 && closeQuote >= 0
        val headClean = firstSep >= 0 &&
            !body.substring(1, firstSep).any { it == separator || it == GOOGLE_FRAME }
        val middleClean = openQuote >= 0 &&
            !body.substring(secondSep + 1, openQuote)
                .any { it == separator || it == GOOGLE_FRAME }
        val tailClean = closeQuote >= 0 && closeQuote < body.length - 1 &&
            !body.substring(closeQuote + 1, body.length - 1)
                .any { it == separator || it == GOOGLE_FRAME }
        val indicesValid = separatorsFound && quotesFound
        val segmentsValid = headClean && middleClean && tailClean
        return if (indicesValid && segmentsValid) {
            GoogleQuotePositions(firstSep, secondSep, openQuote, closeQuote)
        } else {
            null
        }
    }

    /**
     * Pure-emoji heuristic — no third-party emoji regex dep. Accepted iff :
     *   - non-empty, no whitespace anywhere (a sentence with an emoji is rejected),
     *   - length ≤ [EMOJI_ONLY_MAX_UNITS] UTF-16 units (≈ 2 ZWJ clusters max),
     *   - every UTF-16 unit is non-ASCII (real emojis live above U+007F ; the only
     *     ASCII chars that ever appear in emoji clusters are the digit/asterisk/hash
     *     keycap bases like `1️⃣` which are still preceded by the variation selector
     *     U+FE0F — those are not single-tap reactions in practice and skipping them
     *     is a fair trade for safety).
     */
    private fun looksLikeEmojiOnly(text: String): Boolean {
        if (text.isEmpty() || text.length > EMOJI_ONLY_MAX_UNITS) return false
        for (c in text) {
            if (c.isWhitespace() || c.code < 128) return false
        }
        return true
    }

    /** Lone Unicode horizontal ellipsis Relay appends when the preview was truncated. */
    private const val TRUNCATION_MARKER: String = "…"

    /** Google Messages framing char (U+200A hair space): opens and closes the body. */
    private const val GOOGLE_FRAME: Char = '\u200A'

    /** Google Messages separator for an added reaction (U+200B zero width space). */
    private const val GOOGLE_ADD_SEPARATOR: Char = '\u200B'

    /** Google Messages separator for a removed reaction (U+200C zero width non-joiner). */
    private const val GOOGLE_REMOVE_SEPARATOR: Char = '\u200C'

    /**
     * Apple Messages tapbacks (English), as data: the verb the add uses, the
     * emoji it maps to, and the verb the removal uses. Adding a locale means
     * adding rows here, not new regexes. Wordings are the ones Apple Messages
     * sends over SMS to non-iMessage recipients.
     */
    private data class IosTapback(
        val addVerb: String,
        val emoji: String,
        val removeVerb: String,
    )

    private val IOS_TAPBACKS = listOf(
        IosTapback("Loved", "❤️", "Removed a heart from"),
        IosTapback("Liked", "👍", "Removed a like from"),
        IosTapback("Disliked", "👎", "Removed a dislike from"),
        IosTapback("Laughed at", "😂", "Removed a laugh from"),
        IosTapback("Emphasized", "‼️", "Removed an exclamation from"),
        IosTapback("Questioned", "❓", "Removed a question from"),
    )

    /**
     * Compiled from [IOS_TAPBACKS]: one (regex, emoji, isRemoval) triple per
     * add/remove form. The preview group is non-greedy with `DOT_MATCHES_ALL`
     * (same as the Tapback regex); the 400-char input cap bounds backtracking.
     */
    private val IOS_TAPBACK_REGEXES: List<Triple<Regex, String, Boolean>> =
        IOS_TAPBACKS.flatMap { tapback ->
            listOf(
                Triple(
                    Regex(
                        "^${Regex.escape(tapback.addVerb)}\\s+[“\"](.+?)[”\"]$",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                    tapback.emoji,
                    false,
                ),
                Triple(
                    Regex(
                        "^${Regex.escape(tapback.removeVerb)}\\s+[“\"](.+?)[”\"]$",
                        RegexOption.DOT_MATCHES_ALL,
                    ),
                    tapback.emoji,
                    true,
                ),
            )
        }

    /**
     * Apple Messages generic removal: `Removed <emoji> from "…"`. The emoji
     * slot is guarded in [decode] (must contain a non-ASCII codepoint) so a
     * sentence like `Removed the meeting from "Friday"` cannot match.
     */
    private val GENERIC_REMOVED_REGEX = Regex(
        """^Removed\s+(\S+)\s+from\s+[“"](.+?)[”"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Matches `Reacted <emoji> to «<preview>»` with both standard Unicode guillemets
     * `«»` (what Relay always emits) and a defensive ASCII fallback using the plain
     * double-quote `"..."` — covers carriers / OEMs that occasionally strip non-ASCII
     * punctuation from SMS bodies. The emoji group is non-greedy and the preview group
     * accepts any char (including new lines, defensively) because Relay sanitises
     * but a third-party SMS app on the other end might inject CRLF that the parser
     * should still survive.
     */
    private val TAPBACK_WITH_PREVIEW_REGEX = Regex(
        """^Reacted\s+(.+?)\s+to\s+[«"](.+?)[»"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * Matches `Reacted <emoji>` with no trailing `to «…»` segment. The capture group
     * is `\S+` (single token, no whitespace) so a casual sentence like
     * `"Reacted to your post earlier today."` cannot slip through ; the `decode`
     * function further rejects pure-ASCII tokens (real emoji always carry at least
     * one non-ASCII codepoint).
     */
    private val TAPBACK_NO_PREVIEW_REGEX = Regex("""^Reacted\s+(\S+)$""")

    /**
     * v1.8.1 — French readable WITH sender name + preview :
     * `<Nom> a réagi par <emoji> à votre message : «<preview>»`.
     * Capture groups : (1) name, (2) emoji, (3) preview.
     *
     * The name group is `[^\s].{0,38}[^\s]|[^\s]` — at most 40 chars, no leading
     * nor trailing whitespace, can be any non-control character (the encoder
     * already sanitises names via [SenderNameProvider]). Must NOT contain "a réagi"
     * to avoid greedily eating the rest of the sentence (use non-greedy plus the
     * explicit " a réagi" separator).
     */
    private val READABLE_FR_NAMED_WITH_PREVIEW_REGEX = Regex(
        """^(.{1,40}?)\s+a\s+réagi\s+par\s+(.+?)\s+à\s+votre\s+message\s*:\s*[«"](.+?)[»"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * v1.8.1 — French readable WITH sender name, no preview (MMS image only) :
     * `<Nom> a réagi par <emoji> à votre message`.
     */
    private val READABLE_FR_NAMED_NO_PREVIEW_REGEX = Regex(
        """^(.{1,40}?)\s+a\s+réagi\s+par\s+(\S+)\s+à\s+votre\s+message$""",
    )

    /**
     * v1.8.1 (wording neutral) — French readable WITHOUT name, with preview :
     * `Réagi par <emoji> à votre message : «<preview>»`.
     */
    private val READABLE_FR_WITH_PREVIEW_REGEX = Regex(
        """^Réagi\s+par\s+(.+?)\s+à\s+votre\s+message\s*:\s*[«"](.+?)[»"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * v1.8.1 — French readable WITHOUT name, no preview :
     * `Réagi par <emoji> à votre message`.
     */
    private val READABLE_FR_NO_PREVIEW_REGEX = Regex(
        """^Réagi\s+par\s+(\S+)\s+à\s+votre\s+message$""",
    )

    /**
     * v1.8.0 LEGACY — kept for retro-compat. Format
     * `J'ai réagi par <emoji> à : «<preview>»` emitted by v1.8.0 only.
     */
    private val READABLE_FR_LEGACY_WITH_PREVIEW_REGEX = Regex(
        """^J['’]ai\s+réagi\s+par\s+(.+?)\s+à\s*:\s*[«"](.+?)[»"]$""",
        RegexOption.DOT_MATCHES_ALL,
    )

    /**
     * v1.8.0 LEGACY — `J'ai réagi par <emoji>` no preview.
     */
    private val READABLE_FR_LEGACY_NO_PREVIEW_REGEX = Regex(
        """^J['’]ai\s+réagi\s+par\s+(\S+)$""",
    )

    /**
     * v1.9.0 — format compact "emoji + citation" : `<emoji> «<preview>»`.
     * Emoji = 1ʳᵉ token sans whitespace, preview entre guillemets typographiques
     * `«»` (ou `"..."` ASCII en fallback gateway).
     *
     * **Audit fix SEC-6** : la classe négative `[^»"]+` remplace `.+?` pour
     * éliminer tout risque de backtracking catastrophique sur input
     * pathologique (ex: `❤️ «aaaa...` sans guillemet fermant). L'engine
     * ne peut plus revenir en arrière puisque chaque caractère est soit
     * accepté soit non-matchant définitivement. Plus de `DOT_MATCHES_ALL`
     * (les réactions sont mono-ligne).
     */
    private val EMOJI_WITH_QUOTE_REGEX = Regex(
        """^(\S+)\s+[«"]([^»"]{1,200})[»"]$""",
    )

    /**
     * v1.9.0 audit fix SEC-7 — heuristique "est-ce un vrai char emoji ?".
     *
     * Accepte :
     *  - High surrogate (`U+D800..U+DBFF`) : plane supplémentaire 1F000+,
     *    couvre 99% des emojis modernes (😀 👍 ❤️‍🔥 etc.)
     *  - Plage BMP `U+2300..U+27BF` : Miscellaneous Technical, Symbols,
     *    Dingbats — couvre ❤ ☀ ⚠ ✂ ✅ ❌ et autres emojis BMP avant
     *    plane supplémentaire
     *  - ZWJ (`U+200D`) et Variation Selector-16 (`U+FE0F`) : combinés
     *    avec autres codepoints emoji (composition de séquences emoji)
     *
     * Rejette explicitement :
     *  - Lettres accentuées latin-1 (`é`=0xE9, `à`=0xE0, `ç`=0xE7…) qui
     *    avaient l'ancien guard `code >= 128` ✓ et accepté un mot français
     *    comme "été" en tant qu'emoji.
     *  - ASCII pur.
     *
     * Note : strict mais robuste. Un emoji isolé du Latin Extended-A (rare)
     * pourrait être rejeté, mais ce sont des cas que Relay n'émet pas
     * (encoder utilise le picker système qui produit du plane supplémentaire
     * pour la quasi-totalité des emojis usuels).
     */
    private fun isLikelyEmojiChar(c: Char): Boolean {
        if (c.isHighSurrogate()) return true
        val code = c.code
        return code in 0x2300..0x27BF || code == 0x200D || code == 0xFE0F
    }
}
