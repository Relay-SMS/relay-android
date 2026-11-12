package com.filestech.sms.domain.categories

import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Cases for [MessageClassifier]. The negative cases are the point: a
 * personal message must never be misfiled into Transactions or Promotional
 * — a missed promo costs one message in the wrong tab, a misfiled personal
 * message hides a real person.
 */
class MessageClassifierTest {

    private val regularNumber = PhoneAddress.of("+15551234567")
    private val otherNumber = PhoneAddress.of("0612345678")
    private val shortCode = PhoneAddress.of("12345")
    private val alphanumeric = PhoneAddress.of("AMAZON")
    private val weirdSender = PhoneAddress.of("123*")

    // ──────────────── OTP ────────────────

    @Test fun `otp wins over marketing text and promo sender`() {
        assertThat(
            MessageClassifier.classify(
                body = "Huge sale! 50% off everything! Your verification code is 123456",
                sender = alphanumeric,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.OTP)
    }

    @Test fun `bare code message is otp`() {
        assertThat(
            MessageClassifier.classify(
                body = "123456",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.OTP)
    }

    // ──────────────── Transactions ────────────────

    @Test fun `bank alert is transactions`() {
        assertThat(
            MessageClassifier.classify(
                body = "Payment received: \$45.00. Your account balance is \$1,234.56.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    @Test fun `delivery update is transactions`() {
        assertThat(
            MessageClassifier.classify(
                body = "Your package is out for delivery today.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    @Test fun `appointment reminder is transactions`() {
        assertThat(
            MessageClassifier.classify(
                body = "Reminder: your appointment is tomorrow at 10am.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    @Test fun `affirm charge notice is transactions`() {
        assertThat(
            MessageClassifier.classify(
                body = "Affirm: You'll be charged \$138.98 on Oct 20 from Bank Account " +
                    "or your maytag-us plan. Tap to pay: https://www.affirm.com/account/",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    @Test fun `french delivery update is transactions`() {
        assertThat(
            MessageClassifier.classify(
                body = "Votre colis est en cours de livraison, merci !",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.TRANSACTIONS)
    }

    // ──────────────── Promotional ────────────────

    @Test fun `marketing text is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Limited time offer! 20% off all shoes. Shop now, sale ends Sunday! " +
                    "Reply STOP to opt out.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `political text is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Election Day is Tuesday. Vote for a better future - your vote matters!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `vote plus party word is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Republicans voted for Medicaid cuts. Local Democrats are helping " +
                    "PA families get medical debt relief. https://demswork.com/ " +
                    "-Stronger Nation Stop to End",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `claim your free dvd text is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Claim your FREE DVD box set of the online course from Hillsdale " +
                    "College: \"The Genesis Story\" >> hilltext.com/nbq7KQ3 STOPtoEND",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `political survey text is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey, it's Emma. We're reaching out today to get Americans' " +
                    "opinions on our relationship with Israel. Curious what you think. " +
                    "Can we chat? Stop2End",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `french marketing text is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Offre spéciale : -20% sur tout le magasin !",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `short code sender is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi",
                sender = shortCode,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `alphanumeric sender is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi there!",
                sender = alphanumeric,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    // ──────────────── Cold sales pitch (round 17) ────────────────

    @Test fun `roofing cold pitch is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey Kristin it's Maria with Roofing Pros. We're doing no-cost " +
                    "roof inspections in Springfield. Do you have any roof issues " +
                    "at 123 Main St?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `offer only pitch is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "We're doing no-cost roof inspections in your area this week. " +
                    "Reply YES to book.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `business intro plus service is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, it's Tom with Bright Solar. We're offering panel installs " +
                    "in your neighborhood this month. Interested?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `free estimate pitch is promotional`() {
        assertThat(
            MessageClassifier.classify(
                body = "Free estimates on all window replacements this month! Call now.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `friend introducing himself stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, it's Frank",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `friend from the gym stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, it's Frank from the gym",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `with the kids stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, it's Sarah with the kids, we're at the park",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `roofer recommendation stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, it's Frank from the gym, do you know a good roofer?",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `contact sending a pitch stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey Kristin it's Maria with Roofing Pros. We're doing no-cost " +
                    "roof inspections in Springfield. Do you have any roof issues " +
                    "at 123 Main St?",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `group pitch stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey Kristin it's Maria with Roofing Pros. We're doing no-cost " +
                    "roof inspections in Springfield. Do you have any roof issues " +
                    "at 123 Main St?",
                sender = null,
                isContact = false,
                isGroup = true,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Personal ────────────────

    @Test fun `contact is personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hey, are we still on for dinner tonight?",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `unknown regular number is personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Hi, this is Sam from the gym - great meeting you today!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `group is personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "See you all on Saturday!",
                sender = null,
                isContact = false,
                isGroup = true,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Word boundaries and weak words ────────────────

    @Test fun `delivery inside deliveryman does not match`() {
        assertThat(
            MessageClassifier.classify(
                body = "The deliveryman left a note at the door.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `paid for lunch stays personal for a contact`() {
        assertThat(
            MessageClassifier.classify(
                body = "I paid for lunch today, you can get the next one.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `paid for lunch stays personal for an unknown number`() {
        assertThat(
            MessageClassifier.classify(
                body = "I paid for lunch today, you can get the next one.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `spanish on my way stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Estoy en camino, llego en diez minutos.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `vote alone stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Did you vote today? The line at my polling place was so long.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `party word alone stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "My uncle is a Democrat and we argue about it every Thanksgiving.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `contact talking politics stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Did you vote for the Democrat or the Republican? I voted early.",
                sender = regularNumber,
                isContact = true,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `group talking politics stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "Did you vote for the Democrat or the Republican? I voted early.",
                sender = null,
                isContact = false,
                isGroup = true,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `free alone stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "The concert was free, you should have come with us!",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `charged my phone stays personal`() {
        assertThat(
            MessageClassifier.classify(
                body = "I charged my phone before we left, so I'm good for the day.",
                sender = regularNumber,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.PERSONAL)
    }

    // ──────────────── Other ────────────────

    @Test fun `empty body with unclassifiable sender is other`() {
        assertThat(
            MessageClassifier.classify(
                body = "",
                sender = weirdSender,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.OTHER)
    }

    @Test fun `null body and null sender is other`() {
        assertThat(
            MessageClassifier.classify(
                body = null,
                sender = null,
                isContact = false,
                isGroup = false,
            ),
        ).isEqualTo(MessageCategory.OTHER)
    }

    // ──────────────── classifyConversation ────────────────

    @Test fun `conversation with otp preview is otp`() {
        val conversation = conversation(
            preview = "Your verification code is 123456",
            addresses = listOf(regularNumber),
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.OTP)
    }

    @Test fun `group conversation is personal`() {
        val conversation = conversation(
            preview = "See you all on Saturday!",
            addresses = listOf(regularNumber, otherNumber),
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `conversation from unknown regular number is personal`() {
        val conversation = conversation(
            preview = "Hi, this is Sam from the gym - great meeting you today!",
            addresses = listOf(regularNumber),
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PERSONAL)
    }

    @Test fun `conversation from short code is promotional`() {
        val conversation = conversation(
            preview = "Hi",
            addresses = listOf(shortCode),
        )
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.PROMOTIONAL)
    }

    @Test fun `conversation with no preview and no addresses is other`() {
        val conversation = conversation(preview = null, addresses = emptyList())
        assertThat(MessageClassifier.classifyConversation(conversation))
            .isEqualTo(MessageCategory.OTHER)
    }

    private fun conversation(
        preview: String?,
        addresses: List<PhoneAddress>,
        displayName: String? = null,
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
    )
}
