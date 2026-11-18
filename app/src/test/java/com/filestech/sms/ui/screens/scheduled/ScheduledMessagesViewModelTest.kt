package com.filestech.sms.ui.screens.scheduled

import app.cash.turbine.test
import com.filestech.sms.data.local.datastore.SettingsRepository
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.ScheduledMessage
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.domain.usecase.CancelScheduledMessageUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.RetryScheduledMessageUseCase
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Client mode C3 — the scheduled list on a linked device shows only
 * client-created schedules (the rows carrying a send key). Hub-created
 * schedules are invisible in V1: no `list_scheduled` frame exists to
 * fetch them, and a hub-mode leftover must not be offered for cancel
 * here — cancelling it would only cancel the local record.
 */
class ScheduledMessagesViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun schedule(id: Long, key: String?) = ScheduledMessage(
        id = id,
        conversationId = 7L,
        addresses = listOf(PhoneAddress.of("+33612345678")),
        body = "later $id",
        scheduledAt = 1700000000000,
        subId = null,
        state = ScheduledMessage.State.PENDING,
        createdAt = 1699999999999,
        clientMessageId = key,
    )

    private fun viewModel(mode: DeviceMode): ScheduledMessagesViewModel {
        val repo = mockk<ScheduledMessageRepository>()
        every { repo.observePending() } returns flowOf(
            listOf(schedule(1L, "key-1"), schedule(2L, null), schedule(3L, "key-3")),
        )
        every { repo.observeFailed() } returns flowOf(
            listOf(schedule(4L, null), schedule(5L, "key-5")),
        )
        val settings = mockk<SettingsRepository>()
        every { settings.flow } returns flowOf(AppSettings(mode = mode))
        return ScheduledMessagesViewModel(
            repo,
            mockk<CancelScheduledMessageUseCase>(relaxed = true),
            mockk<RetryScheduledMessageUseCase>(relaxed = true),
            mockk<DeleteScheduledMessageUseCase>(relaxed = true),
            settings,
            // Scheduled avatars — the recipients flow is WhileSubscribed
            // and these tests never collect it, so a relaxed mock is enough.
            mockk<ContactRepository>(relaxed = true),
            dispatcher,
        )
    }

    @Test fun `client mode shows only client-created schedules`() = runTest(dispatcher) {
        val vm = viewModel(DeviceMode.CLIENT)

        // No initial empty item: under the unconfined Main dispatcher
        // the sharing coroutine runs the combine to completion
        // synchronously during subscription, so the first item the
        // collector sees is already the loaded, filtered list.
        vm.pending.test {
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 3L).inOrder()
        }
        vm.failed.test {
            assertThat(awaitItem().map { it.id }).containsExactly(5L).inOrder()
        }
    }

    @Test fun `hub mode shows every schedule`() = runTest(dispatcher) {
        val vm = viewModel(DeviceMode.HUB)

        vm.pending.test {
            assertThat(awaitItem().map { it.id }).containsExactly(1L, 2L, 3L).inOrder()
        }
        vm.failed.test {
            assertThat(awaitItem().map { it.id }).containsExactly(4L, 5L).inOrder()
        }
    }
}
