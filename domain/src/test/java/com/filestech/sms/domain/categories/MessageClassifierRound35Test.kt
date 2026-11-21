package com.filestech.sms.domain.categories

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 35 (spam protection) cases for [MessageClassifier]: the six
 * samples Kristin reported, the new keyword families, the false-positive
 * guards, the truncation case, and the sticky category. Split out of
 * [MessageClassifierTest] so neither class trips the large-class rule.
 */
class MessageClassifierRound35Test {

    private val regularNumber = PhoneAddress.of("+15551234567")
    private val otherNumber = PhoneAddress.of("0612345678")

    // ──────── Spam protection (round 35) — the six samples ────────
    //
    // Every sample Kristin reported, verbatim (names/addresses redacted as
    // 'my name' / 'my address' / 'my city' in the file they came from).
    // Each files Promotional from a stranger.

    @Test fun `real estate pitch sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi 'my name' Have you ever considered seIling 'my address'?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `survey spam sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, I'm Jade with SSRS. Can you take a quick non-partisan survey for ABC, " +
                    "the AP, CBS, CNN, Fox, MSNOW, & NBC? We'll send you \$5.\n\n" +
                    "1) Yes\n2) No (or END Survey)",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `shapiro campaign sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = shapiroSample(),
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // The 240-char preview of the Shapiro sample NOW files Promotional:
    // "voters" sits at char 66 and "governor" at char 171, so the new
    // lists catch the prefix too. (Under the old lists only the "Stop to
    // End" footer at ~char 550 matched, and the prefix filed Personal —
    // that is what the truncation case below still proves.)
    @Test fun `shapiro prefix now matches promotional via voters and governor`() {
        assertThat(
            MessageClassifier.classify(
                body = shapiroSample().take(240),
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `healing hands welcome sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Welcome to Healing Hands Massage's text marketing messages. " +
                    "Reply STOP to opt out at any point. Message and data rates may apply.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `tree service pitch sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hello 'my name', Slone with a nearby tree service company. " +
                    "We're providing affordable tree care in 'my city'. " +
                    "Would you like pricing for trimming or removal?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `hero homes first message sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey 'my name'! Just passing through and saw 'my address'. " +
                    "I'm a local investor and reaching out to see if it's available to own? " +
                    "-Hero Homes",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `hero homes follow up sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "'my name', Adam here with Hero Homes. Touching base again about " +
                    "'my address'. Do you have a moment this week? I only need a few minutes.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────── Round 35 — real-estate pitch family ────────

    @Test fun `considered selling your house is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Have you considered selling your house? We buy homes fast.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // "seiling" is the observed filter-evasion spelling of "selling"
    // (capital I for the first L): it folds to "seiling", not "selling",
    // so it needs its own variant in the pair rule too.
    @Test fun `seiling your house is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, are you seiling your house? We buy homes fast.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `cash offer on your property is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "We'd like to make a cash offer on your property this week.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `interested in buying your home is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "We are interested in buying your home, please call us back.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `selling your bike stays personal for a stranger`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, are you selling your bike? I saw your ad online.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `selling your bike stays personal for a contact`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, are you selling your bike? I saw your ad online.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `cash offer for a bike stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I have a cash offer for your bike, let me know if you're interested.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Round 35 — survey spam ────────────────

    @Test fun `survey plus gift card is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Take our survey and earn a \$10 gift card, it only takes 2 minutes!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // Documented choice: bare "survey" is not a signal — a stranger
    // asking for a class survey is a real person, not a campaign.
    @Test fun `class survey stays personal for a stranger`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, can you take my survey for class? It would really help me out.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `class survey stays personal for a contact`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, can you take my survey for class? It would really help me out.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Round 35 — political additions ────────────────

    @Test fun `polling place from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Find your polling place here: www.example.org/voting.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `polling place from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Find your polling place here: www.example.org/voting.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `governor from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "The governor announced new voting rules today, read more here.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `i voted today stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I voted today, it only took ten minutes!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `contact asking about the democrat stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Did you vote for the Democrat?",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // "elect" must not match inside a longer word. "election" matches
    // its own keyword, so the boundary is proven with "elected" and
    // "electrical" instead.
    @Test fun `elect does not match inside elected`() {
        assertThat(
            MessageClassifier.classify(
                body = "She was elected class president last year, good for her.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `elect does not match inside electrical`() {
        assertThat(
            MessageClassifier.classify(
                body = "The electrical work is done, the house looks great now.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `french electeur from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Cher électeur, votre voix compte, rendez-vous aux urnes dimanche.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `spanish votante from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Estimado votante, recuerde votar el martes, su voto importa.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `italian elettore from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Gentile elettore, le elezioni si avvicinano, informati qui.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `german wahler from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Liebe Wähler, Ihre Stimme zählt, gehen Sie zur Wahl.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────────────── Round 35 — cold pitch additions ────────────────

    @Test fun `im intro plus service is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, I'm Jade with Tree Pros. We do tree trimming in your area.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `im intro alone stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, I'm Jade with SSRS",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `im sarah with the kids stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, I'm Sarah with the kids, we're at the park",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `providing without a service stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "We're providing snacks for the party on Saturday, come hungry!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `would you like without services stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Would you like to come to dinner on Friday? Let me know.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Round 35 — pitch follow-up ────────────────

    @Test fun `touching base from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Just touching base about the house, let me know what you think.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `touching base from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Just touching base about the house, let me know what you think.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `do you have a moment from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Do you have a moment this week? I only need a few minutes.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `following up again from a stranger is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, following up again on the quote I sent last week.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `bare following up stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, just following up on the homework, did you finish it?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Round 35 — truncation ────────────────

    // The Shapiro sample no longer demonstrates truncation on its own:
    // "voters" sits at char 66 and "governor" at char 171, both inside
    // the 240-char preview. This constructed case keeps the point — a
    // promo signal ONLY past the cutoff is invisible to the tab list,
    // which is why the sticky category is computed from the full body
    // at receive time.
    @Test fun `opt out footer past the preview cutoff needs the full body`() {
        val body = "This is a long friendly neighborhood update about upcoming events. "
            .repeat(5) + "Reply STOP to opt out."
        assertThat(body.length).isGreaterThan(240)
        assertThat(
            MessageClassifier.classify(
                body = body,
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
        assertThat(
            MessageClassifier.classify(
                body = body.take(240),
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Round 35 — sticky category ────────────────

    @Test fun `next sticky stores the first promotional`() {
        assertThat(
            MessageClassifier.nextStickyCategory(null, MessageCategory.PROMOTIONAL),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `next sticky keeps promotional against a plain follow up`() {
        assertThat(
            MessageClassifier.nextStickyCategory(
                MessageCategory.PROMOTIONAL,
                MessageCategory.PERSONAL,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `next sticky lets otp overwrite promotional`() {
        assertThat(
            MessageClassifier.nextStickyCategory(
                MessageCategory.PROMOTIONAL,
                MessageCategory.OTP,
            ),
        ).isEqualTo(MessageCategory.OTP)
    }

    @Test fun `next sticky lets transactions overwrite promotional`() {
        assertThat(
            MessageClassifier.nextStickyCategory(
                MessageCategory.PROMOTIONAL,
                MessageCategory.TRANSACTIONS,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    @Test fun `next sticky stores a plain first message`() {
        assertThat(
            MessageClassifier.nextStickyCategory(null, MessageCategory.PERSONAL),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `next sticky lets a plain message overwrite otp`() {
        assertThat(
            MessageClassifier.nextStickyCategory(
                MessageCategory.OTP,
                MessageCategory.PERSONAL,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `sticky promotional wins over a plain preview`() {
        // A truly plain preview — one that would live-classify Personal
        // — so only the sticky value can file this Promotional.
        val conversation = conversation(
            preview = "Hey, just checking in",
            addresses = listOf(regularNumber),
            stickyCategory = MessageCategory.PROMOTIONAL,
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `sticky promotional does not apply to a contact`() {
        val conversation = conversation(
            preview = "Hey, just checking in",
            addresses = listOf(regularNumber),
            displayName = "Sam",
            stickyCategory = MessageCategory.PROMOTIONAL,
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `sticky promotional does not apply to a group`() {
        val conversation = conversation(
            preview = "Hey, just checking in",
            addresses = listOf(regularNumber, otherNumber),
            stickyCategory = MessageCategory.PROMOTIONAL,
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `sticky otp wins over a plain preview`() {
        val conversation = conversation(
            preview = "Thanks for your help!",
            addresses = listOf(regularNumber),
            stickyCategory = MessageCategory.OTP,
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.OTP)
    }

    @Test fun `sticky transactions wins over a plain preview`() {
        val conversation = conversation(
            preview = "Thanks for your help!",
            addresses = listOf(regularNumber),
            stickyCategory = MessageCategory.TRANSACTIONS,
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.TRANSACTIONS)
    }

    // The Shapiro campaign sample, verbatim (shared by the full-body
    // test and the 240-char prefix test).
    private fun shapiroSample(): String =
        "Shady Josh Shapiro has made a career out of lying to Pennsylvania voters.\n\n" +
            "When Shapiro's good friend and right-hand man was sexually harassing women " +
            "while working in the Governor's Office, Shapiro claimed ignorance, even as " +
            "his office deleted the victim's emails and coordinated a tax-payer funded " +
            "pay out.\n\n" +
            "How many others were silenced? We can't trust Shady Shapiro with 4 more years.\n\n" +
            "TOMORROW NOV 3, 2026 you can retire Shady Shapiro and elect STACY GARRITY as " +
            "PA's FIRST WOMAN GOVERNOR.\n\n" +
            "Find your polling place here: www.pagop.org/voting.\n\n" +
            "Stop to End"

    private fun conversation(
        preview: String?,
        addresses: List<PhoneAddress>,
        displayName: String? = null,
        stickyCategory: MessageCategory? = null,
    ): Conversation = Conversation(
        id = 1L,
        threadId = null,
        addresses = addresses,
        displayName = displayName,
        lastMessageAt = 0L,
        lastMessagePreview = preview,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
        stickyCategory = stickyCategory,
    )
}
