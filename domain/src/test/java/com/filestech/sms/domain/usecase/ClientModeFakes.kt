package com.filestech.sms.domain.usecase

import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.clientmode.ClientAction
import com.filestech.sms.domain.clientmode.ClientActionGateway
import com.filestech.sms.domain.clientmode.ClientSendGateway
import com.filestech.sms.domain.clientmode.NoteSaveAttachment
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.MessageSearchHit
import com.filestech.sms.domain.model.MessageState
import com.filestech.sms.domain.model.MessageWindow
import com.filestech.sms.domain.model.MessageWindowSpec
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.domain.repository.ConversationDeleteResult
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.domain.repository.VaultPurgeResult
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import com.filestech.sms.domain.vault.VaultMover
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * Client mode C3 — the shared fakes for the client-branch tests.
 * The domain module has no mocking library by design, so the
 * gateways are hand-written recording fakes.
 */
internal class FakeSettings(
    mode: DeviceMode = DeviceMode.HUB,
    groupMms: Boolean = false,
) : AppSettingsSource {
    private val value = AppSettings().copy(
        mode = mode,
        sending = AppSettings().sending.copy(groupMms = groupMms),
    )
    override val flow: Flow<AppSettings> = flowOf(value)
    override val state: StateFlow<AppSettings> = MutableStateFlow(value)
    override suspend fun hydratedOrNull(): AppSettings = value
    override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
}

internal data class RecordedSend(
    val localConversationId: Long?,
    val recipients: List<PhoneAddress>,
    val body: String,
    val replyToLocalMessageId: Long?,
    val clientMessageId: String?,
)

/** Client mode C5 — one recorded `sendMedia` call (the media branch of the router). */
internal data class RecordedMediaSend(
    val localConversationId: Long?,
    val recipients: List<PhoneAddress>,
    val body: String,
    val attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
    val replyToLocalMessageId: Long?,
    val clientMessageId: String?,
)

internal data class RecordedSchedule(
    val localConversationId: Long?,
    val recipients: List<PhoneAddress>,
    val body: String,
    val sendAt: Long,
    val clientMessageId: String,
)

internal class FakeClientSendGateway(
    private val clientMode: Boolean,
    var sendTextResult: Outcome<SendReport> =
        Outcome.Success(SendReport(dispatched = listOf(1L), failed = emptyList(), blocked = emptyList())),
    var sendMediaResult: Outcome<SendReport> =
        Outcome.Success(SendReport(dispatched = listOf(1L), failed = emptyList(), blocked = emptyList())),
    var scheduleResult: Boolean = true,
    var cancelResult: Boolean = true,
) : ClientSendGateway {
    val sendTextCalls = mutableListOf<RecordedSend>()
    val sendMediaCalls = mutableListOf<RecordedMediaSend>()
    val scheduleCalls = mutableListOf<RecordedSchedule>()
    val cancelCalls = mutableListOf<String>()

    override suspend fun isClientMode(): Boolean = clientMode

    override suspend fun sendText(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> {
        sendTextCalls += RecordedSend(localConversationId, recipients, body, replyToLocalMessageId, clientMessageId)
        return sendTextResult
    }

    override suspend fun sendMedia(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        attachments: List<SendMediaMmsUseCase.AttachmentPayload>,
        replyToLocalMessageId: Long?,
        clientMessageId: String?,
    ): Outcome<SendReport> {
        sendMediaCalls += RecordedMediaSend(
            localConversationId,
            recipients,
            body,
            attachments,
            replyToLocalMessageId,
            clientMessageId,
        )
        return sendMediaResult
    }

    override suspend fun scheduleMessage(
        localConversationId: Long?,
        recipients: List<PhoneAddress>,
        body: String,
        sendAt: Long,
        clientMessageId: String,
    ): Boolean {
        scheduleCalls += RecordedSchedule(localConversationId, recipients, body, sendAt, clientMessageId)
        return scheduleResult
    }

    override suspend fun cancelScheduled(clientMessageId: String): Boolean {
        cancelCalls += clientMessageId
        return cancelResult
    }

    override suspend fun saveNoteMessage(
        localNoteId: Long,
        body: String,
        attachments: List<NoteSaveAttachment>,
        clientMessageId: String?,
    ): Outcome<Long> = Outcome.Success(1L)
}

internal class FakeClientActionGateway(
    private val clientMode: Boolean,
    var sendResult: Boolean = true,
    var createResult: Outcome<Long> = Outcome.Success(42L),
) : ClientActionGateway {
    val actions = mutableListOf<ClientAction>()
    val reverts = mutableListOf<(suspend () -> Unit)?>()
    val createCalls = mutableListOf<Pair<List<String>, String?>>()

    override suspend fun isClientMode(): Boolean = clientMode

    override suspend fun sendAction(action: ClientAction, revert: (suspend () -> Unit)?): Boolean {
        actions += action
        reverts += revert
        // Mirrors the real sender: a frame that cannot go out runs
        // the revert immediately.
        if (!sendResult) revert?.invoke()
        return sendResult
    }

    override suspend fun createConversation(addresses: List<String>, customName: String?): Outcome<Long> {
        createCalls += addresses to customName
        return createResult
    }

    override suspend fun createNote(title: String): Outcome<Long> = createResult

    override suspend fun deleteNote(localNoteId: Long): Outcome<Unit> = Outcome.Success(Unit)

    override suspend fun deleteNoteMessage(
        localNoteId: Long,
        localMessageId: Long,
    ): Outcome<Unit> = Outcome.Success(Unit)

    /** Simulates the hub answering `ok:false` for the last action. */
    suspend fun revertLast() {
        reverts.lastOrNull()?.invoke()
    }
}

/**
 * A [ConversationRepository] fake that only answers lookups: the
 * client-branch tests need [findById] / [findMessageById], nothing
 * else. Every other method fails loudly, so a use case that
 * reaches for more is caught, not silently satisfied — except
 * the read-mark methods, which record their calls for the
 * unread-toggle tests.
 */
internal class FakeConversationRepository(
    private val conversationsById: Map<Long, Conversation> = emptyMap(),
    private val messagesById: Map<Long, Message> = emptyMap(),
) : ConversationRepository {
    val markReadIds = mutableListOf<Long>()
    val markUnreadIds = mutableListOf<Long>()
    override fun observeAll(includeArchived: Boolean): Flow<List<Conversation>> = flowOf(emptyList())
    override fun observeVault(): Flow<List<Conversation>> = flowOf(emptyList())
    override fun observeOne(id: Long): Flow<Conversation?> = flowOf(conversationsById[id])
    override fun observeVaultHidden(id: Long): Flow<Boolean> = flowOf(false)
    override fun observeMessages(conversationId: Long): Flow<List<Message>> = flowOf(emptyList())
    override fun observeMessagesWindow(
        conversationId: Long,
        window: Flow<MessageWindowSpec>,
    ): Flow<MessageWindow> = TODO("not needed by this test")

    override fun observeSearchInConversation(conversationId: Long, query: String): Flow<List<Long>> =
        flowOf(emptyList())

    override fun observeMedia(conversationId: Long): Flow<List<Attachment>> = flowOf(emptyList())
    override fun observeFiles(conversationId: Long): Flow<List<Attachment>> = flowOf(emptyList())
    override fun observeUnreadConversationCount(): Flow<Int> = flowOf(0)
    override suspend fun findOrCreate(addresses: List<PhoneAddress>): Outcome<Conversation> =
        TODO("not needed by this test")

    override suspend fun setPinned(id: Long, pinned: Boolean) = TODO("not needed by this test")
    override suspend fun setArchived(id: Long, archived: Boolean) = TODO("not needed by this test")
    override suspend fun setMuted(id: Long, muted: Boolean) = TODO("not needed by this test")
    override suspend fun setPinnedMessage(id: Long, messageId: Long?, pinnedAt: Long?) =
        TODO("not needed by this test")

    override suspend fun findById(id: Long): Conversation? = conversationsById[id]
    override suspend fun bulkMoveToVault(ids: List<Long>, inVault: Boolean): Int =
        TODO("not needed by this test")

    override suspend fun setDraft(id: Long, draft: String?) = TODO("not needed by this test")
    override suspend fun setAppearance(id: Long, bubbleColorArgb: Int?, avatarUri: String?) =
        TODO("not needed by this test")

    override suspend fun setCustomName(id: Long, name: String?) = TODO("not needed by this test")
    override suspend fun clearStaleDisplayName(conversationId: Long, address: String) =
        TODO("not needed by this test")

    override suspend fun markRead(id: Long) {
        markReadIds += id
    }
    override suspend fun markUnread(id: Long) {
        markUnreadIds += id
    }
    override suspend fun markAllRead() = TODO("not needed by this test")
    override suspend fun delete(id: Long): ConversationDeleteResult = TODO("not needed by this test")
    override suspend fun deleteAllInVault(
        force: Boolean,
        apresPurge: suspend (VaultPurgeResult) -> Unit,
    ): VaultPurgeResult = TODO("not needed by this test")

    override suspend fun deleteMessage(messageId: Long) = TODO("not needed by this test")
    override suspend fun setMessageStarred(messageId: Long, starred: Boolean) =
        TODO("not needed by this test")

    override fun observeMessageSearch(query: String, archivedOnly: Boolean): Flow<List<MessageSearchHit>> =
        flowOf(emptyList())

    override suspend fun findMessageById(id: Long): Message? = messagesById[id]
    override suspend fun findMessageForResend(id: Long): Message? = messagesById[id]
    override suspend fun findMessageByClientMessageId(key: String): Message? = null
    override suspend fun findAttachmentById(id: Long): Attachment? = null
    override suspend fun setReaction(messageId: Long, emoji: String?): SetReactionResult =
        TODO("not needed by this test")

    override suspend fun countMessagesToPurge(olderThanDays: Int): Int = 0
    override suspend fun purgeHistoryNow(olderThanDays: Int): Int = 0
    override suspend fun messagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<Message> =
        emptyList()

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<Message> = emptyList()
    override suspend fun totalMessageCount(): Long = 0L
    override suspend fun messageCountsByConversation(): Map<Long, Int> = emptyMap()
    override suspend fun maxMessageId(): Long = 0L
    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> = flowOf(emptyList())
}

/** An in-memory [BlockedNumberRepository]: block/unblock actually flip the set. */
internal class FakeBlockedNumberRepository : BlockedNumberRepository {
    private val blocked = mutableSetOf<String>()

    override fun observe(): Flow<List<BlockedNumber>> = flowOf(emptyList())
    override suspend fun isBlocked(rawNumber: String): Boolean = rawNumber in blocked
    override suspend fun block(rawNumber: String, label: String?): Outcome<Unit> {
        blocked += rawNumber
        return Outcome.Success(Unit)
    }

    override suspend fun unblock(rawNumber: String): Outcome<Unit> {
        blocked -= rawNumber
        return Outcome.Success(Unit)
    }

    override suspend fun mirrorFromSystem(rawNumber: String): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun blockedNormalizedSnapshot(): Set<String> = blocked.toSet()
    override suspend fun blockedRawSnapshot(): List<String> = blocked.toList()
}

/** A [VaultMover] that never moves anything (the block tests never touch the vault). */
internal class FakeVaultMover : VaultMover {
    override suspend fun moveToVault(conversationId: Long): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun moveOutOfVault(conversationId: Long): Outcome<Unit> = Outcome.Success(Unit)
    override suspend fun requestMoveToVault(conversationId: Long, intoVault: Boolean): Outcome<Unit> =
        Outcome.Success(Unit)

    override suspend fun requestBulkMoveToVault(ids: List<Long>, intoVault: Boolean): Outcome<Int> =
        Outcome.Success(0)
}
