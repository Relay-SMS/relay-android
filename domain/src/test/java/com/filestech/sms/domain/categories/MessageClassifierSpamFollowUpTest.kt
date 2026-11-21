package com.filestech.sms.domain.categories

import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Spam follow-up cases for [MessageClassifier]: the eight samples
 * Kristin reported after round 35 (`~/workspace/user/files/morespam.txt`),
 * the new signal families (real-estate address pairs, opt-out footer
 * variants, the subscription-expiry scam), and their false-positive
 * guards. Split out of [MessageClassifierRound35Test] so neither class
 * trips the large-class rule.
 *
 * Sample 1 was already Promotional under the round-35 lists ("Reply
 * STOP to opt out"); it is pinned here so the whole batch is covered.
 * Samples 2–8 filed Personal before this round.
 */
class MessageClassifierSpamFollowUpTest {

    private val regularNumber = PhoneAddress.of("+15551234567")

    // ──────── Spam follow-up — the eight samples ────────

    @Test fun `survey plus opt out footer sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "'my name', we need to hear from more people under 50 on our national " +
                    "survey; please take it here: https://surveyus.co/wPzQkg\nThanks, Lea\n" +
                    "Reply STOP to opt out",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `getting rid of address sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey 'name', it's Diana. Wanted to see if getting rid of " +
                    "3220 Upper Valley Rd is something you'd consider?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `free tickets with stop equals end sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Be part of history this July 4th on the National Mall. Celebrate " +
                    "America's 250th at Salute to America 250. Free tickets: " +
                    "usa-250.co/5pt3ve\n\nstop=end",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `president event with stop equals end sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "See President Donald J. Trump on Wednesday, 6/24 at 7 PM at America " +
                    "Is Back! Celebrate America's 250th kickoff. Get FREE tickets: " +
                    "usa-250.co/eoucyo\n\nStop=End",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `roku subscription expiry scam sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Dear Customer, Your Roku TV subscription will expire on May 5th. " +
                    "Please call us at 818-937-3504 to continue your services. Thank you",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `survey with stop to stop sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "We're conducting a quick survey to learn more about Pennsylvanians' " +
                    "top priorities. Please complete it here: paact.co/o9zN\n\n-PA Rising\n\n" +
                    "Stop to stop",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `fundraising with text stop to quit sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "BREAKING: PBS is officially defunded. Help us fight Trump and fight " +
                    "back: no-surrender.us/npr-pbs-fr?t=ICH01U \n\n-No Surrender Fund\n" +
                    "Text STOP to quit",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `curious about your place at address sample is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey 'name', hope all is well. I'm curious about your place at " +
                    "3220 Upper Valley Rd. Mind if we chat this week? Thanks, Alex",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────── Spam follow-up — real-estate address pairs ────────

    @Test fun `getting rid of without an address stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I'm getting rid of my old couch, want it?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `your place without an address stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Can I come to your place at 5?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `getting rid of an address from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "We're getting rid of 3220 Upper Valley Rd, interested?",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `your place at an address from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I'm curious about your place at 3220 Upper Valley Rd",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────── Spam follow-up — opt-out footer variants ────────

    // The equals sign is what makes "stop=end" a footer: without it,
    // "Stop. End of story." is a sentence, not an opt-out.
    @Test fun `stop end of story stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Stop. End of story. I'm done talking about it.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `stop equals end with spaces is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "See you there. stop = end",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `stop to stop from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I had to stop to stop the bleeding",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `stop to quit from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Text STOP to quit",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────── Spam follow-up — subscription-expiry scam ────────

    @Test fun `subscription expiry without dear customer is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Your Netflix subscription will expire tomorrow, call us to renew.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `subscription expiry from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "My subscription will expire next week, should I renew?",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `dear customer from a contact stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Dear Customer, thanks for coming by today",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────── Spam follow-up — survey: the pair, not the word ────────

    // Deliberately NOT added: a survey + link pair would misfile a
    // class survey shared with its link ("can you take my survey for
    // class?"). Only the survey + opt-out-footer pair counts — sample
    // 6 files Promotional via "Stop to stop", not via its link.
    @Test fun `class survey with a link stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, can you take my survey for class? https://forms.example.com/abc",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `survey plus stop to stop is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Quick survey here: example.co/x Stop to stop",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }
}
