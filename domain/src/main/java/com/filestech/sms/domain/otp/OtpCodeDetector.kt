package com.filestech.sms.domain.otp

/**
 * Detects login / authentication codes (one-time passcodes) in the body of an
 * incoming SMS, so the UI can offer a one-tap copy — in the message bubble
 * and in the notification for that message.
 *
 * **Pure function** : input = raw text, output = the code digits (or `null`).
 * No Android dependency, no I/O, no network. Testable as plain JVM, like
 * [com.filestech.sms.domain.smishing.SmishingDetector].
 *
 * **Never log the result.** A code is a credential: callers must pass it
 * straight to the clipboard / UI and never write it to a log, a database or
 * analytics. This detector itself logs nothing.
 *
 * # Two tiers, in order
 *
 * 1. **Explicit code context** — a code word sits next to the digits, in
 *    either order ("Your verification code is 123456", "123456 is your
 *    verification code"). Keywords cover the app's five languages (EN, FR,
 *    DE, ES, IT). 4–8 digits: the context is what makes a 4-digit code
 *    readable as a code rather than a year.
 * 2. **Standalone** — no code word anywhere, the digits stand on their own:
 *    the whole message is just the code ("123456", "123 456", "G-123456"),
 *    or a single 6–8 digit run in an otherwise number-free message.
 *
 * # What is deliberately NOT detected
 *
 * The doctrine is the same as the smishing detector's: a missed code costs
 * one manual copy, a false positive teaches the user to ignore the pill.
 *  - **4–5 digits embedded in a longer message without any code word**
 *    ("See you in 2026", "Your order 12345 ships today") — a year, an order
 *    or a reference far more often than a code. They still match through
 *    tier 1 as soon as any code word is present ("Your code is 1234").
 *  - **Dates, times, amounts** — "12/05/2026", "17:50", "12,50 €" are
 *    several short digit runs; tier 2 requires exactly one run, and the
 *    separators around a run ([SEPARATEUR_DE_NOMBRE]) disqualify fragments
 *    of a longer number.
 *  - **Long IDs and phone numbers** — 9+ digit runs never match (the digit
 *    groups are capped at 8 with a `(?!\d)` guard, so a 9-digit run cannot
 *    match as its first 8 digits), and runs glued to letters ("AB123456")
 *    or split into groups ("+33 6 12 34 56 78", an IBAN) are rejected.
 *  - **"Postal code"** — "Your postal code is 75001" must not surface a
 *    pill: the bare-`code` keyword refuses `postal` on either side.
 *
 * # Grouped codes
 *
 * Some senders split a 6-digit code ("123 456", "123-456"). The grouped form
 * is accepted in both tiers, and the returned value is always the digits
 * joined ("123456") — what the user pastes. A group is only a code when it
 * is exactly two groups of three: "1234 5678" and "12 345 678" are not.
 */
object OtpCodeDetector {

    /** Cap on the inspected body, like the smishing detector's: bounds the regexes. */
    private const val MAX_BODY_LENGTH = 2000

    /** Digits after a code word may sit at most this far away ("code : 123456"). */
    private const val MAX_CONTEXT_DISTANCE = 40

    /** Longest code the detector ever returns (8 digits; see [PLAIN_CODE_REGEX]). */
    private const val MAX_CODE_LENGTH = 8

    /**
     * Shortest run a standalone code may have. 4–5 digit runs embedded in a
     * sentence are a year, an order number or a short reference far more
     * often than a code — they only count with a code word (tier 1) or as
     * the whole message.
     */
    private const val MIN_STANDALONE_LENGTH = 6

    /**
     * Code words, longest first so the regex engine prefers the full phrase
     * ("verification code") over its tail ("code").
     *
     * The bare `code` only counts with a copula or a colon right after it
     * ("code is", "code:", "code est") — a lone "code" anywhere ("the code
     * above") is too weak a signal on its own. `postal` / `zip` on either
     * side disqualify it (see the class KDoc).
     */
    private val CODE_KEYWORDS = listOf(
        // EN — phrases
        "verification code", "confirmation code", "login code", "sign-in code",
        "signin code", "security code", "one-time code", "one time code",
        "authentication code", "auth code", "access code", "your code",
        // FR — phrases
        "code de vérification", "code de confirmation", "code de connexion",
        "code de sécurité", "code d'accès", "code d'authentification", "votre code",
        // DE — phrases
        "bestätigungscode", "verifizierungscode", "sicherheitscode",
        "anmeldecode", "login-code", "ihr code",
        // ES — phrases
        "código de verificación", "codigo de verificacion",
        "código de confirmación", "código de seguridad", "código de acceso",
        "tu código", "su código",
        // IT — phrases
        "codice di verifica", "codice di conferma", "codice di sicurezza",
        "codice di accesso", "il tuo codice",
        // Single words that on their own mean "code" in this context.
        "otp", "passcode",
        // Bare "code" with a copula / colon — the `(?!...)` guard below keeps
        // "code postal" out, the lookbehinds keep "postal code" / "zip code" out.
        """code\s+(?:is|est|lautet|ist|es|è)""",
        """code\s*[:=]""",
    )

    /**
     * One alternation over [CODE_KEYWORDS]. The `(?<!postal )` / `(?<!zip )`
     * guards only bite on the bare-`code` alternatives — the phrases cannot
     * start with "postal"/"zip" — but they are harmless on the others, so
     * they stay on the whole group rather than forking the regex in two.
     */
    private val KEYWORD_REGEX = Regex(
        """(?<!postal )(?<!zip )\b(?:${CODE_KEYWORDS.joinToString("|")})(?!\s+postal)\b""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A plain code run: 4–8 digits, neither preceded nor followed by a digit
     * or a letter (a 9-digit ID must not match as its first 8 digits, and
     * "AB123456" is a reference, not a code), and not one group of a spaced
     * digit sequence ("1234 5678" — see [isSpacedGroup]).
     */
    private val PLAIN_CODE_REGEX =
        Regex("""(?<![\d\p{L}])(\d{4,8})(?!\d)""")

    /** A split 6-digit code: exactly two groups of three ("123 456", "123-456"). */
    private val GROUPED_CODE_REGEX =
        Regex("""(?<![\d\p{L}])(\d{3})[ -](\d{3})(?!\d)""")

    /** A code with a single-letter prefix ("G-123456"), whole message only. */
    private val LETTER_DASH_CODE_REGEX = Regex("""^[A-Za-z]-(\d{4,8})$""")

    /** Any digit run, used to count how many numbers a message contains. */
    private val DIGIT_RUN_REGEX = Regex("""\d+""")

    /**
     * Returns the detected code digits (separators removed), or `null` when
     * the body carries no login/authentication code.
     */
    fun detect(body: String): String? {
        if (body.isBlank()) return null
        val text = if (body.length > MAX_BODY_LENGTH) body.substring(0, MAX_BODY_LENGTH) else body
        findContextCode(text)?.let { return it }
        return findStandaloneCode(text)
    }

    // ──────────────── Tier 1 : explicit code context ────────────────

    /**
     * A code word with the digits right after it ("Your code is 123456") or
     * right before it ("123456 is your verification code"). The first
     * keyword in the message wins — senders put the code next to the first
     * mention, and a later number is usually something else (an expiry, a
     * helpline).
     */
    private fun findContextCode(text: String): String? {
        val keyword = KEYWORD_REGEX.find(text) ?: return null
        // Digits after the keyword, within [MAX_CONTEXT_DISTANCE] chars.
        val after = text.substring(
            keyword.range.last + 1,
            minOf(text.length, keyword.range.last + 1 + MAX_CONTEXT_DISTANCE),
        )
        firstCodeRun(after)?.let { return it }
        // Digits before the keyword, same window ("123456 is your code").
        val beforeStart = maxOf(0, keyword.range.first - MAX_CONTEXT_DISTANCE)
        val before = text.substring(beforeStart, keyword.range.first)
        return lastCodeRun(before)
    }

    /**
     * First code-like run in [window]: a plain 4–8 digit run, else a grouped
     * 3+3. Runs that are one group of a spaced digit sequence are skipped —
     * "1234 5678" after "your code" is two halves of an 8-digit number, not
     * a code.
     */
    private fun firstCodeRun(window: String): String? {
        PLAIN_CODE_REGEX.findAll(window).forEach { match ->
            if (!isSpacedGroup(window, match.range)) return match.groupValues[1]
        }
        GROUPED_CODE_REGEX.find(window)?.let { match ->
            if (!isSpacedGroup(window, match.range)) {
                return match.groupValues[1] + match.groupValues[2]
            }
        }
        return null
    }

    /** Last code-like run in [window] (the digits-first order: "123456 is your code"). */
    private fun lastCodeRun(window: String): String? {
        PLAIN_CODE_REGEX.findAll(window).lastOrNull()?.let { match ->
            if (!isSpacedGroup(window, match.range)) return match.groupValues[1]
        }
        GROUPED_CODE_REGEX.findAll(window).lastOrNull()?.let { match ->
            if (!isSpacedGroup(window, match.range)) {
                return match.groupValues[1] + match.groupValues[2]
            }
        }
        return null
    }

    /**
     * True when the run at [range] touches another digit group across a
     * single space or hyphen — i.e. it is one cell of a longer spaced
     * number ("1234 5678", "12 345 678"), not a code on its own.
     */
    private fun isSpacedGroup(window: String, range: IntRange): Boolean {
        val before = range.first - 2
        if (before >= 0 && window[range.first - 1] in " -" && window[before].isDigit()) return true
        val after = range.last + 2
        if (after < window.length && window[range.last + 1] in " -" && window[after].isDigit()) return true
        return false
    }

    // ──────────────── Tier 2 : standalone ────────────────

    /**
     * No code word anywhere: the message itself must BE the code, or hold a
     * single 6–8 digit run and no other number at all.
     *
     * 4–5 digit runs are not detected here — embedded in a sentence they are
     * a year, an order number or a short reference far more often than a
     * code (see the class KDoc). As the whole message they are unambiguous
     * and do match.
     */
    private fun findStandaloneCode(text: String): String? {
        wholeMessageCode(text.trim())?.let { return it }
        return singleRunCode(text)
    }

    /**
     * The whole message is the code — "123456", "123 456", "G-123456".
     * Nothing else in the message means nothing else it could be.
     */
    private fun wholeMessageCode(trimmed: String): String? {
        PLAIN_CODE_REGEX.matchEntire(trimmed)?.let { return it.groupValues[1] }
        GROUPED_CODE_REGEX.matchEntire(trimmed)?.let {
            return it.groupValues[1] + it.groupValues[2]
        }
        return LETTER_DASH_CODE_REGEX.matchEntire(trimmed)?.groupValues?.get(1)
    }

    /**
     * One 6–8 digit run, and no other number in the message. A separator
     * glued to a digit on either side makes the run a fragment of a date, a
     * decimal or a phone number ("12-345678", "123456.78"), not a code.
     */
    private fun singleRunCode(text: String): String? {
        val runs = DIGIT_RUN_REGEX.findAll(text).toList()
        if (runs.size != 1) return null
        val run = runs.single()
        val valid = run.value.length in MIN_STANDALONE_LENGTH..MAX_CODE_LENGTH &&
            !isGluedToLetter(text, run.range) &&
            !isSeparatorNextToDigit(text, run.range.first - 1, -1) &&
            !isSeparatorNextToDigit(text, run.range.last + 1, 1)
        return if (valid) run.value else null
    }

    /** True when the run at [range] touches a letter on either side ("AB123456"). */
    private fun isGluedToLetter(text: String, range: IntRange): Boolean {
        val before = range.first - 1
        if (before >= 0 && text[before].isLetter()) return true
        val after = range.last + 1
        return after < text.length && text[after].isLetter()
    }

    /**
     * True when [index] holds a number separator (`-`, `.`, `,`, `:`, `/`)
     * with a digit on its other side — [step] is -1 to look left, +1 right.
     */
    private fun isSeparatorNextToDigit(text: String, index: Int, step: Int): Boolean {
        if (index < 0 || index >= text.length) return false
        if (text[index] !in "-.,:/") return false
        val beyond = index + step
        return beyond in text.indices && text[beyond].isDigit()
    }
}
