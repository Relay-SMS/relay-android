package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — `setBlocked` on a linked device: the local block
 * list is updated optimistically, then the `set_blocked` frame goes
 * to the hub; the sender reverts the local change when the frame
 * cannot go out. Hub mode refuses (it keeps its own block path).
 */
class ToggleConversationSetBlockedTest {

    private fun conversation(id: Long, vararg raw: String) = Conversation(
        id = id,
        threadId = null,
        addresses = raw.map { PhoneAddress.of(it) },
        displayName = "Groupe",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    private fun useCase(
        repo: FakeConversationRepository,
        blockedRepo: FakeBlockedNumberRepository,
        gateway: FakeClientActionGateway,
    ) = ToggleConversationStateUseCase(repo, FakeVaultMover(), blockedRepo, gateway)

    @Test
    fun `client block sends set_blocked and blocks every address locally`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        val gateway = FakeClientActionGateway(clientMode = true)
        val repo = FakeConversationRepository(
            conversationsById = mapOf(7L to conversation(7L, "+33600000001", "+33600000002")),
        )

        val res = useCase(repo, blockedRepo, gateway).setBlocked(7L, true)

        assertThat(res).isEqualTo(Outcome.Success(Unit))
        assertThat(gateway.actions).containsExactly(ClientAction.SetBlocked(7L, true))
        assertThat(blockedRepo.isBlocked("+33600000001")).isTrue()
        assertThat(blockedRepo.isBlocked("+33600000002")).isTrue()
    }

    @Test
    fun `client unblock sends set_blocked false and unblocks locally`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        blockedRepo.block("+33600000001")
        val gateway = FakeClientActionGateway(clientMode = true)
        val repo = FakeConversationRepository(
            conversationsById = mapOf(7L to conversation(7L, "+33600000001")),
        )

        val res = useCase(repo, blockedRepo, gateway).setBlocked(7L, false)

        assertThat(res).isEqualTo(Outcome.Success(Unit))
        assertThat(gateway.actions).containsExactly(ClientAction.SetBlocked(7L, false))
        assertThat(blockedRepo.isBlocked("+33600000001")).isFalse()
    }

    @Test
    fun `offline client block reverts the local blocks and fails`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        val gateway = FakeClientActionGateway(clientMode = true, sendResult = false)
        val repo = FakeConversationRepository(
            conversationsById = mapOf(7L to conversation(7L, "+33600000001")),
        )

        val res = useCase(repo, blockedRepo, gateway).setBlocked(7L, true)

        assertThat(res).isEqualTo(Outcome.Failure(AppError.ClientOffline))
        assertThat(blockedRepo.isBlocked("+33600000001")).isFalse()
    }

    @Test
    fun `a refused block restores only the addresses that actually changed`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        blockedRepo.block("+33600000001")
        val gateway = FakeClientActionGateway(clientMode = true)
        val repo = FakeConversationRepository(
            conversationsById = mapOf(7L to conversation(7L, "+33600000001", "+33600000002")),
        )
        val useCase = useCase(repo, blockedRepo, gateway)

        useCase.setBlocked(7L, true)
        assertThat(blockedRepo.isBlocked("+33600000002")).isTrue()
        // The hub answers `ok:false`: only the newly-blocked address reverts.
        gateway.revertLast()

        assertThat(blockedRepo.isBlocked("+33600000001")).isTrue()
        assertThat(blockedRepo.isBlocked("+33600000002")).isFalse()
    }

    @Test
    fun `client block of an unknown conversation fails without a frame`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        val gateway = FakeClientActionGateway(clientMode = true)
        val repo = FakeConversationRepository()

        val res = useCase(repo, blockedRepo, gateway).setBlocked(99L, true)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gateway.actions).isEmpty()
    }

    /** Hub mode keeps its own block path: this method refuses there. */
    @Test
    fun `hub mode setBlocked refuses`() = runBlocking {
        val blockedRepo = FakeBlockedNumberRepository()
        val gateway = FakeClientActionGateway(clientMode = false)
        val repo = FakeConversationRepository(
            conversationsById = mapOf(7L to conversation(7L, "+33600000001")),
        )

        val res = useCase(repo, blockedRepo, gateway).setBlocked(7L, true)

        assertThat(res).isInstanceOf(Outcome.Failure::class.java)
        assertThat(gateway.actions).isEmpty()
        assertThat(blockedRepo.isBlocked("+33600000001")).isFalse()
    }
}
