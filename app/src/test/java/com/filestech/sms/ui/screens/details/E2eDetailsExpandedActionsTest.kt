package com.filestech.sms.ui.screens.details

import com.filestech.sms.data.local.db.entity.E2ePeerState
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round 45 — the encryption box's expanded area: which actions show
 * per peer state. Non-encrypted states offer the two manual retries
 * (data SMS first, then the regular-SMS handshake); ENCRYPTED offers
 * the safety-number sheet instead — a retry is meaningless once a
 * session is live. An action only appears when its handler exists.
 */
class E2eDetailsExpandedActionsTest {

    @Test
    fun `encrypted shows only the safety number action`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.ENCRYPTED,
                hasRetryProbe = true,
                hasRetryViaSms = true,
                hasOpenVerification = true,
            ),
        ).isEqualTo(listOf(E2eDetailsExpandedAction.ViewSafetyNumber))
    }

    @Test
    fun `encrypted without a verification handler shows nothing`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.ENCRYPTED,
                hasRetryProbe = true,
                hasRetryViaSms = true,
                hasOpenVerification = false,
            ),
        ).isEmpty()
    }

    @Test
    fun `probing shows both retries with the data SMS retry first`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.PROBING,
                hasRetryProbe = true,
                hasRetryViaSms = true,
                hasOpenVerification = true,
            ),
        ).isEqualTo(
            listOf(
                E2eDetailsExpandedAction.RetryProbe,
                E2eDetailsExpandedAction.RetryViaSms,
            ),
        )
    }

    @Test
    fun `unknown shows both retries`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.UNKNOWN,
                hasRetryProbe = true,
                hasRetryViaSms = true,
                hasOpenVerification = true,
            ),
        ).isEqualTo(
            listOf(
                E2eDetailsExpandedAction.RetryProbe,
                E2eDetailsExpandedAction.RetryViaSms,
            ),
        )
    }

    @Test
    fun `not relay shows both retries`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.NOT_RELAY,
                hasRetryProbe = true,
                hasRetryViaSms = true,
                hasOpenVerification = true,
            ),
        ).isEqualTo(
            listOf(
                E2eDetailsExpandedAction.RetryProbe,
                E2eDetailsExpandedAction.RetryViaSms,
            ),
        )
    }

    @Test
    fun `a missing data SMS handler hides only that retry`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.UNKNOWN,
                hasRetryProbe = false,
                hasRetryViaSms = true,
                hasOpenVerification = true,
            ),
        ).isEqualTo(listOf(E2eDetailsExpandedAction.RetryViaSms))
    }

    @Test
    fun `a missing SMS handler hides only that retry`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.UNKNOWN,
                hasRetryProbe = true,
                hasRetryViaSms = false,
                hasOpenVerification = true,
            ),
        ).isEqualTo(listOf(E2eDetailsExpandedAction.RetryProbe))
    }

    @Test
    fun `no handlers shows nothing for a non-encrypted peer`() {
        assertThat(
            e2eDetailsExpandedActions(
                peerState = E2ePeerState.PROBING,
                hasRetryProbe = false,
                hasRetryViaSms = false,
                hasOpenVerification = false,
            ),
        ).isEmpty()
    }
}
