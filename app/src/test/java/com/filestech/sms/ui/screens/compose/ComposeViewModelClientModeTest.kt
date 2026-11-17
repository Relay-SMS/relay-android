package com.filestech.sms.ui.screens.compose

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.google.common.truth.Truth.assertThat
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — the compose flow on a linked device: an unnamed
 * create goes through the repo (local thread first, wire only when
 * absent), a named group create sends the name on the
 * `create_conversation` frame (a local-only rename would be
 * overwritten by the next sync), and a refused create surfaces
 * `CreateFailed` instead of opening a thread.
 */
class ComposeViewModelClientModeTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeClientActions(
        var clientMode: Boolean = true,
        var createResult: Outcome<Long> = Outcome.Success(11L),
    ) : ClientActionGateway {
        var createCalls = mutableListOf<Pair<List<String>, String?>>()

        override suspend fun isClientMode(): Boolean = clientMode

        override suspend fun sendAction(
            action: ClientAction,
            revert: (suspend () -> Unit)?,
        ): Boolean = true

        override suspend fun createConversation(
            addresses: List<String>,
            customName: String?,
        ): Outcome<Long> {
            createCalls += addresses to customName
            return createResult
        }
    }

    private class Harness(
        val vm: ComposeViewModel,
        val conversationRepo: ConversationRepository,
        val clientActions: FakeClientActions,
    )

    private fun harness(clientMode: Boolean = true): Harness {
        val contactRepo = mockk<ContactRepository>(relaxed = true)
        val conversationRepo = mockk<ConversationRepository>(relaxed = true)
        val clientActions = FakeClientActions(clientMode = clientMode)
        val vm = ComposeViewModel(
            SavedStateHandle(),
            contactRepo,
            conversationRepo,
            dispatcher,
            clientActions,
        )
        return Harness(vm, conversationRepo, clientActions)
    }

    private fun conversation(id: Long) = Conversation(
        id = id,
        threadId = null,
        addresses = listOf(PhoneAddress.of("+33612345678")),
        displayName = "Alice",
        lastMessageAt = 0L,
        lastMessagePreview = null,
        unreadCount = 0,
        pinned = false,
        archived = false,
        muted = false,
        inVault = false,
        draft = null,
    )

    @Test fun `a named group create sends the name on the frame`() = runTest(dispatcher) {
        val h = harness()
        h.vm.addRecipient("+33612345678")
        h.vm.addRecipient("+33687654321")

        h.vm.events.test {
            h.vm.createConversation("Weekend")
            advanceUntilIdle()

            assertThat(h.clientActions.createCalls).containsExactly(
                listOf("+33612345678", "+33687654321") to "Weekend",
            )
            assertThat(awaitItem()).isEqualTo(ComposeViewModel.Event.ConversationCreated(11L))
            coVerify(exactly = 0) { h.conversationRepo.findOrCreate(any()) }
            coVerify(exactly = 0) { h.conversationRepo.setCustomName(any(), any()) }
        }
    }

    @Test fun `an unnamed create goes through the repo and never sends a frame`() = runTest(dispatcher) {
        val h = harness()
        coEvery { h.conversationRepo.findOrCreate(any()) } returns
            Outcome.Success(conversation(7L))
        h.vm.addRecipient("+33612345678")

        h.vm.events.test {
            h.vm.createConversation(null)
            advanceUntilIdle()

            assertThat(h.clientActions.createCalls).isEmpty()
            assertThat(awaitItem()).isEqualTo(ComposeViewModel.Event.ConversationCreated(7L))
        }
    }

    @Test fun `a refused create emits CreateFailed and opens nothing`() = runTest(dispatcher) {
        val h = harness()
        h.clientActions.createResult = Outcome.Failure(AppError.ClientOffline)
        h.vm.addRecipient("+33612345678")
        h.vm.addRecipient("+33687654321")

        h.vm.events.test {
            h.vm.createConversation("Weekend")
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(
                ComposeViewModel.Event.CreateFailed(AppError.ClientOffline),
            )
        }
    }

    @Test fun `isClientMode reflects the gateway`() = runTest(dispatcher) {
        val client = harness(clientMode = true)
        advanceUntilIdle()
        assertThat(client.vm.isClientMode.value).isTrue()

        val hub = harness(clientMode = false)
        advanceUntilIdle()
        assertThat(hub.vm.isClientMode.value).isFalse()
    }

    @Test fun `hub mode still renames locally after the create`() = runTest(dispatcher) {
        val h = harness(clientMode = false)
        coEvery { h.conversationRepo.findOrCreate(any()) } returns
            Outcome.Success(conversation(7L))
        h.vm.addRecipient("+33612345678")
        h.vm.addRecipient("+33687654321")

        h.vm.events.test {
            h.vm.createConversation("Weekend")
            advanceUntilIdle()

            assertThat(awaitItem()).isEqualTo(ComposeViewModel.Event.ConversationCreated(7L))
            assertThat(h.clientActions.createCalls).isEmpty()
            coVerify { h.conversationRepo.setCustomName(7L, "Weekend") }
        }
    }
}
