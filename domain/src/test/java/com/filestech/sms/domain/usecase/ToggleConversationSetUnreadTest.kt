package com.filestech.sms.domain.usecase

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Long-press "Mark unread / Mark read" (desktop parity):
 * [ToggleConversationStateUseCase.setUnread] dispatches to the
 * repository's mark-unread when asked for unread, and to the
 * plain mark-read otherwise. The store semantics (flip the
 * latest incoming message + recompute) are pinned in
 * `ConversationRepositoryClientBranchTest`; this pins the toggle.
 */
class ToggleConversationSetUnreadTest {

    private fun useCase(repo: FakeConversationRepository) =
        ToggleConversationStateUseCase(
            repo,
            FakeVaultMover(),
            FakeBlockedNumberRepository(),
            FakeClientActionGateway(clientMode = false),
        )

    @Test
    fun `setUnread true marks the conversation unread`() {
        runBlocking {
            val repo = FakeConversationRepository()

            useCase(repo).setUnread(7L, true)

            assertThat(repo.markUnreadIds).containsExactly(7L)
            assertThat(repo.markReadIds).isEmpty()
        }
    }

    @Test
    fun `setUnread false marks the conversation read`() {
        runBlocking {
            val repo = FakeConversationRepository()

            useCase(repo).setUnread(7L, false)

            assertThat(repo.markReadIds).containsExactly(7L)
            assertThat(repo.markUnreadIds).isEmpty()
        }
    }

    @Test
    fun `toggling twice marks unread then read in order`() {
        runBlocking {
            val repo = FakeConversationRepository()
            val useCase = useCase(repo)

            useCase.setUnread(7L, true)
            useCase.setUnread(7L, false)

            assertThat(repo.markUnreadIds).containsExactly(7L)
            assertThat(repo.markReadIds).containsExactly(7L)
        }
    }
}
