package com.filestech.sms.invite

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 36 — "Invite friends": the invite message is Kristin's
 * exact wording, so the constant is pinned by a test — a
 * rewording here changes what every user sends to their
 * friends. The share intent itself is not exercised: building
 * an `Intent` needs the Android framework, which plain JVM
 * tests do not have.
 */
class InviteFriendsTest {

    @Test
    fun `the invite message is Kristins exact wording`() {
        assertThat(InviteFriends.MESSAGE).isEqualTo(
            "Join me on Relay for private SMS messaging across your devices. https://getrelaysms.com",
        )
    }

    @Test
    fun `the invite message ends with the website`() {
        assertThat(InviteFriends.MESSAGE).endsWith("https://getrelaysms.com")
    }
}
