package com.filestech.sms.ui.screens.details

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 45 — the mute row's icon shows the CURRENT state: the bell
 * while unmuted, the muted bell once muted. The toggle carries the
 * state too; the icon is the at-a-glance read.
 */
class DetailsMuteIconTest {

    @Test
    fun `muted shows the muted bell`() {
        assertThat(muteIconFor(muted = true)).isEqualTo(Icons.Outlined.NotificationsOff)
    }

    @Test
    fun `unmuted shows the bell`() {
        assertThat(muteIconFor(muted = false)).isEqualTo(Icons.Outlined.Notifications)
    }
}
