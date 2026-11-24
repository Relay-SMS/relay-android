package com.filestech.sms.ui.screens.details

import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 47 — what a tap on the encryption box does. An encrypted
 * peer opens the safety-number sheet directly (no chevron, no
 * dropdown — the row IS the "View safety number" action); any
 * other state with expanded actions toggles the dropdown; with
 * none, the box is plain status.
 *
 * Round 48 — the direct tap no longer requires verification: an
 * unverified encrypted peer used to open a one-item dropdown, a
 * pointless extra tap once only verification is left.
 */
class E2eDetailsRowTapTest {

    @Test
    fun `encrypted peer opens the sheet directly`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.ENCRYPTED,
                hasOpenVerification = true,
                hasExpandedActions = false,
            ),
        ).isEqualTo(E2eDetailsRowTap.OpenVerification)
    }

    @Test
    fun `encrypted peer without a sheet handler has no tap`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.ENCRYPTED,
                hasOpenVerification = false,
                hasExpandedActions = false,
            ),
        ).isEqualTo(E2eDetailsRowTap.None)
    }

    @Test
    fun `encrypted peer opens the sheet directly even with expanded actions`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.ENCRYPTED,
                hasOpenVerification = true,
                hasExpandedActions = true,
            ),
        ).isEqualTo(E2eDetailsRowTap.OpenVerification)
    }

    @Test
    fun `non-encrypted peer with retries expands`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.PROBING,
                hasOpenVerification = false,
                hasExpandedActions = true,
            ),
        ).isEqualTo(E2eDetailsRowTap.Expand)
    }

    @Test
    fun `non-encrypted peer without retries has no tap`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.UNKNOWN,
                hasOpenVerification = false,
                hasExpandedActions = false,
            ),
        ).isEqualTo(E2eDetailsRowTap.None)
    }

    @Test
    fun `non-encrypted peer with a sheet handler and retries expands`() {
        assertThat(
            e2eDetailsRowTap(
                peerState = E2ePeerState.NOT_RELAY,
                hasOpenVerification = true,
                hasExpandedActions = true,
            ),
        ).isEqualTo(E2eDetailsRowTap.Expand)
    }
}
