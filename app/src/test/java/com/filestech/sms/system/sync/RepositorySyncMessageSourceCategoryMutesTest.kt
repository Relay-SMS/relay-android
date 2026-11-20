package com.filestech.sms.system.sync

import android.content.Context
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.domain.usecase.UnblockNumberUseCase
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Category mutes — the glue's mapping between AppSettings
 * (`conversations.mutedCategories`, the domain enum) and the
 * lowercase wire strings. The enum-to-wire table is explicit, so
 * every name is pinned here; an unknown wire category is refused
 * with `invalid_request`, never a crash.
 */
class RepositorySyncMessageSourceCategoryMutesTest {

    private val settings = FakeSettings()

    // Typed as the interface, like the other glue tests.
    private val source: SyncMessageSource = RepositorySyncMessageSource(
        context = mockk<Context>(),
        repository = mockk<ConversationRepository>(),
        defaultSmsAppChecker = mockk<DefaultSmsAppChecker>(),
        envoyerMessage = mockk<EnvoyerMessageUseCase>(),
        retrySend = mockk<RetrySendUseCase>(),
        sendReaction = mockk<SendReactionUseCase>(),
        settings = settings,
        scheduledMessageRepository = mockk<ScheduledMessageRepository>(),
        scheduleMessageUseCase = mockk<ScheduleMessageUseCase>(),
        deleteScheduledMessage = mockk<DeleteScheduledMessageUseCase>(),
        contactsRepository = mockk<ContactRepository>(),
        blockNumber = mockk<BlockNumberUseCase>(),
        unblockNumber = mockk<UnblockNumberUseCase>(),
    )

    @Test fun `observeMutedCategories maps the settings set to wire strings`() = runTest {
        settings.update { current ->
            current.copy(
                conversations = current.conversations.copy(
                    mutedCategories = setOf(MessageCategory.OTP, MessageCategory.PERSONAL),
                ),
            )
        }
        assertThat(source.observeMutedCategories().first()).isEqualTo(setOf("otp", "personal"))
    }

    @Test fun `observeMutedCategories re-emits when the settings change`() = runTest {
        val collected = async { source.observeMutedCategories().take(2).toList() }
        runCurrent()
        settings.update { current ->
            current.copy(
                conversations = current.conversations.copy(
                    mutedCategories = setOf(MessageCategory.OTP),
                ),
            )
        }
        assertThat(collected.await()).containsExactly(emptySet<String>(), setOf("otp")).inOrder()
    }

    @Test fun `mutedCategories returns the wire set`() = runTest {
        settings.update { current ->
            current.copy(
                conversations = current.conversations.copy(
                    mutedCategories = setOf(MessageCategory.TRANSACTIONS),
                ),
            )
        }
        assertThat(source.mutedCategories()).isEqualTo(setOf("transactions"))
    }

    @Test fun `mutedCategories returns null when settings are unreadable`() = runTest {
        settings.hydratedValue = null
        assertThat(source.mutedCategories()).isNull()
    }

    @Test fun `setCategoryMuted mutes a category in settings`() = runTest {
        val result = source.setCategoryMuted("otp", true)
        assertThat(result.ok).isTrue()
        assertThat(settings.backing.value.conversations.mutedCategories)
            .isEqualTo(setOf(MessageCategory.OTP))
    }

    @Test fun `setCategoryMuted unmutes a category in settings`() = runTest {
        settings.update { current ->
            current.copy(
                conversations = current.conversations.copy(
                    mutedCategories = setOf(MessageCategory.OTP, MessageCategory.PERSONAL),
                ),
            )
        }
        val result = source.setCategoryMuted("otp", false)
        assertThat(result.ok).isTrue()
        assertThat(settings.backing.value.conversations.mutedCategories)
            .isEqualTo(setOf(MessageCategory.PERSONAL))
    }

    @Test fun `setCategoryMuted refuses an unknown category and changes nothing`() = runTest {
        val result = source.setCategoryMuted("spam", true)
        assertThat(result.ok).isFalse()
        assertThat(result.code).isEqualTo(SyncProtocol.MessageFailed.INVALID_REQUEST)
        assertThat(settings.backing.value.conversations.mutedCategories).isEmpty()
    }

    @Test fun `setCategoryMuted maps every wire name`() = runTest {
        for (wire in listOf("personal", "otp", "promotional", "transactions", "other")) {
            assertThat(source.setCategoryMuted(wire, true).ok).isTrue()
        }
        assertThat(settings.backing.value.conversations.mutedCategories).isEqualTo(
            setOf(
                MessageCategory.PERSONAL,
                MessageCategory.OTP,
                MessageCategory.PROMOTIONAL,
                MessageCategory.TRANSACTIONS,
                MessageCategory.OTHER,
            ),
        )
    }
}

/** An [AppSettingsSource] backed by a [MutableStateFlow], for glue tests. */
private class FakeSettings(
    initial: AppSettings = AppSettings(),
) : AppSettingsSource {
    val backing = MutableStateFlow(initial)

    /** What [hydratedOrNull] answers; null simulates unreadable settings. */
    var hydratedValue: AppSettings? = initial

    override val flow: Flow<AppSettings> = backing
    override val state: StateFlow<AppSettings> = backing

    override suspend fun hydratedOrNull(): AppSettings? = hydratedValue

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(backing.value)
        backing.value = next
        hydratedValue = next
    }
}
