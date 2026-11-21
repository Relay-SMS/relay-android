package com.filestech.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.filestech.sms.core.mms.MmsConstants
import com.filestech.sms.core.result.AppError
import com.filestech.sms.core.result.Outcome
import com.filestech.sms.domain.categories.MessageCategory
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.model.SendReport
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.domain.repository.ScheduledMessageRepository
import com.filestech.sms.domain.repository.SetReactionResult
import com.filestech.sms.domain.sender.DefaultSmsAppChecker
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.usecase.BlockNumberUseCase
import com.filestech.sms.domain.usecase.DeleteScheduledMessageUseCase
import com.filestech.sms.domain.usecase.EnvoyerMessageUseCase
import com.filestech.sms.domain.usecase.RetrySendUseCase
import com.filestech.sms.domain.usecase.ScheduleMessageUseCase
import com.filestech.sms.domain.usecase.SendMediaMmsUseCase
import com.filestech.sms.domain.usecase.SendReactionUseCase
import com.filestech.sms.domain.usecase.UnblockNumberUseCase
import com.filestech.sms.sync.ActionResult
import com.filestech.sms.sync.AttachmentData
import com.filestech.sms.sync.AttachmentOpenResult
import com.filestech.sms.sync.AvatarOpenResult
import com.filestech.sms.sync.CancelResult
import com.filestech.sms.sync.ContactReadiness
import com.filestech.sms.sync.CreateConversationResult
import com.filestech.sms.sync.MessageState
import com.filestech.sms.sync.Readiness
import com.filestech.sms.sync.ScheduleResult
import com.filestech.sms.sync.SendAttachmentInput
import com.filestech.sms.sync.SendResult
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedContact
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import com.filestech.sms.sync.UploadResult
import com.filestech.sms.sync.VpnAddressProvider
import com.filestech.sms.ui.components.stickerSendUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.domain.model.MessageState as DomainMessageState

/**
 * Slice 2 glue (SYNC_PROTOCOL.md, glue point 4): the
 * [SyncMessageSource] implementation — the one place that knows both
 * the sync wire DTOs and the app's repositories, so the sync package
 * itself keeps importing no app code.
 *
 * It lives in the app module, not the data module, on purpose: the
 * data module cannot see the app's sync package (module layering
 * runs app → data, never the reverse), and the sync package cannot
 * move into the data module without dragging the transport along.
 * App-module glue, next to the other system seams.
 *
 * Slice 3 adds the send + write-back surface. Sends are serialized
 * by one mutex at this singleton's level (not per session — two
 * linked devices could otherwise race each other) and deduped on
 * the durable client key stamped on the message row, so a retry
 * after a lost ack never sends the same text twice.
 *
 * Slice 4 adds the attachment-byte surface: downloads (open an
 * attachment's bytes behind the same membership + vault guard the
 * write-back actions use), upload staging (`cacheDir/sync_uploads/`,
 * one dir per send key, 24 h TTL sweep, size + SHA-256 verified
 * before a part becomes sendable), and the extended send (uploads
 * and stickers mapped to MMS payloads after the dedup check, before
 * the router call — the router, the 300 KB cap, the group paths,
 * and the key-on-one-row rule are all inherited, unchanged).
 *
 * Phase 2 adds the scheduled-send surface: a desktop schedule goes
 * through the phone's own [ScheduleMessageUseCase] (same store, same
 * WorkManager worker, same fire path), with the desktop's key stored
 * on the scheduled row and stamped on the message row at fire time.
 * The dedup is the Slice 3 shape — the scheduled row first, then the
 * sent row for a key that already fired — under the same mutex, so a
 * retry after a lost ack never schedules the same text twice. A
 * desktop cancel goes through the phone's own
 * [DeleteScheduledMessageUseCase] (cancel the scheduled work + delete
 * the row), so the key is free again afterwards — no tombstone.
 *
 * Slice 5 adds the contact-sync surface: the snapshot maps the
 * provider's rows to wire DTOs (normalized numbers, blank names
 * dropped, photo version only when a photo exists), the avatar
 * opens the thumbnail stream for one number, and the readiness
 * check is separate from [readiness] — contacts failing must never
 * fail message sync.
 *
 * Create conversation adds [createConversation]: the desktop's
 * new-conversation request goes through the phone's own composer
 * path (`findOrCreate`, then `setCustomName` when a name was
 * typed), so the thread exists immediately, with no message
 * needed, and a retry returns the existing conversation instead
 * of a duplicate.
 *
 * Round 3 adds [setContactPhoto]: a photo the desktop picked for a
 * contact goes to the contact's PHOTO row (the same `PhoneLookup`
 * the UI uses, so the photo lands on the contact the phone shows
 * for the number). WRITE_CONTACTS is checked first — a background
 * service cannot show a permission dialog, so a missing grant is
 * an error the desktop shows, and the user grants it from the
 * Linked devices screen.
 *
 * Category mutes add [observeMutedCategories], [mutedCategories]
 * and [setCategoryMuted]: the phone's own AppSettings holds the
 * muted-category set, the glue only maps the domain enum to the
 * lowercase wire strings — explicit `when` mappings, never
 * `name.lowercase()` (locale). An unknown wire category is
 * refused with `invalid_request`, never a crash.
 */
@Singleton
class RepositorySyncMessageSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ConversationRepository,
    private val defaultSmsAppChecker: DefaultSmsAppChecker,
    private val envoyerMessage: EnvoyerMessageUseCase,
    private val retrySend: RetrySendUseCase,
    private val sendReaction: SendReactionUseCase,
    private val settings: AppSettingsSource,
    private val scheduledMessageRepository: ScheduledMessageRepository,
    private val scheduleMessageUseCase: ScheduleMessageUseCase,
    private val deleteScheduledMessage: DeleteScheduledMessageUseCase,
    private val contactsRepository: ContactRepository,
    private val blockNumber: BlockNumberUseCase,
    private val unblockNumber: UnblockNumberUseCase,
    // The tunnel-address provider, or null in tests (which never
    // need a tunnel address — [tunnelIp] then answers null).
    private val vpnAddressProvider: VpnAddressProvider? = null,
) : SyncMessageSource {

    /** Serializes all send handling — see the class KDoc. */
    private val sendMutex = Mutex()

    /**
     * In-flight uploads, keyed by sanitized client key + index (see
     * [uploadKey]). Guarded by [sendMutex]: uploads for one send are
     * sequential anyway, and two devices uploading concurrently must
     * not interleave in one staging dir. A completed upload leaves
     * the map — the send finds its file on disk by name, so a
     * restart between upload and send still sends.
     */
    private val uploads = mutableMapOf<String, UploadSession>()

    /** Last reaction SMS dispatched per message, for the phone's 60 s same-emoji dedup. */
    private val recentReactionDispatches = mutableMapOf<Long, ReactionDispatch>()

    override suspend fun conversations(): List<SyncedConversation> {
        val counts = repository.messageCountsByConversation()
        return allConversations().first()
            .sortedByDescending { it.lastMessageAt }
            .map { it.toSynced(counts[it.id] ?: 0) }
    }

    override fun observeConversations(): Flow<List<SyncedConversation>> =
        allConversations().map { list ->
            val counts = repository.messageCountsByConversation()
            list.sortedByDescending { it.lastMessageAt }
                .map { it.toSynced(counts[it.id] ?: 0) }
        }

    /**
     * `observeAll`'s parameter means "archived ONLY", not "include
     * archived" — both halves are needed for the whole list. Vault
     * conversations are excluded by the DAO itself (`in_vault = 0`),
     * so sync can never leak them, by construction.
     */
    private fun allConversations(): Flow<List<Conversation>> =
        combine(
            repository.observeAll(false),
            repository.observeAll(true),
        ) { active, archived -> active + archived }

    override suspend fun messagesBefore(
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedMessage> =
        repository.messagesBefore(conversationId, beforeId, limit).map { it.toSynced() }

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage> =
        repository.messagesAfter(conversationId, afterId).map { it.toSynced() }

    override suspend fun totalMessageCount(): Long = repository.totalMessageCount()

    override suspend fun messageCountsByConversation(): Map<Long, Int> =
        repository.messageCountsByConversation()

    override suspend fun maxMessageId(): Long = repository.maxMessageId()

    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return repository.observeMessageStates(ids).map { states ->
            states.map { it.toSynced() }
        }
    }

    override suspend fun readiness(): Readiness {
        val smsGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!smsGranted) return Readiness.NoSmsPermission
        if (repository.totalMessageCount() == 0L) return Readiness.MirrorEmpty
        return Readiness.Ready
    }

    override suspend fun isDefaultSmsApp(): Boolean = defaultSmsAppChecker.isDefault()

    override suspend fun tunnelIp(): String? = vpnAddressProvider?.currentTunnelIpv4()

    // -- Slice 5: contact sync ----------------------------------------

    /**
     * Contacts-permission fix — a refused read THROWS [SecurityException]
     * on purpose, it is never swallowed into an empty list: an empty list
     * would read as "this phone has zero contacts" and wipe the desktop's
     * contacts. Both callers handle the throw: [com.filestech.sms.sync.SyncEngine]
     * maps it to a `contacts_error` frame (`hub_no_contacts_access`, with
     * Retry on the desktop) and the live-delta path in `SyncServer` skips
     * the delta. Do not catch it here.
     */
    override suspend fun contacts(): List<SyncedContact> {
        return contactsRepository.listAllDetailed().mapNotNull { it.toSynced() }
    }

    override suspend fun openAvatar(address: String): AvatarOpenResult {
        return try {
            val stream = contactsRepository.openPhotoStream(address)
            if (stream == null) AvatarOpenResult.Unknown else AvatarOpenResult.Opened(stream)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The provider read failed (permission revoked mid-flight,
            // provider died) — the photo is unreadable, not a crash,
            // and the engine answers `unknown_attachment` for it.
            // (The log carries no address: contact numbers never
            // reach the log.)
            Timber.w(e, "RepositorySyncMessageSource: avatar open failed")
            AvatarOpenResult.Unknown
        }
    }

    override suspend fun contactsReadiness(): ContactReadiness {
        // NOT `ContextCompat.checkSelfPermission`: core 1.19's
        // version takes a pre-33 branch (for POST_NOTIFICATIONS)
        // that calls `TextUtils.equals` — a Stub! on plain-JVM unit
        // tests, which is exactly why the location permission tests
        // fail locally. The framework `checkSelfPermission` is API
        // 23+ and minSdk is 26, so the direct call is precisely
        // what ContextCompat does on device for READ_CONTACTS (the
        // special case does not apply to it) — and it stays
        // mockable in tests without touching `Process`.
        val granted =
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return ContactReadiness.NoContactsPermission
        // Belt and braces: the permission can be revoked between the
        // check and the read, and the provider throws
        // `SecurityException` when it is denied — a throw from the
        // probe read is not-ready, never a crash. The probe is the
        // cheap Phone-table query; the snapshot read that follows is
        // the detailed one.
        return try {
            contactsRepository.listAll()
            ContactReadiness.Ready
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the
            // read (or the provider refuses despite it) — not-ready,
            // never a crash.
            Timber.d(e, "RepositorySyncMessageSource: contacts probe refused")
            ContactReadiness.NoContactsPermission
        }
    }

    // -- Round 3: contact photo from the desktop -----------------------

    override suspend fun setContactPhoto(address: String, photoBytes: ByteArray): ActionResult {
        // The direct `checkSelfPermission` (not ContextCompat's —
        // see `contactsReadiness`): the framework call stays
        // mockable in tests and is precisely what ContextCompat does
        // on device for WRITE_CONTACTS. A background service cannot
        // show a permission dialog, so a missing grant is an error
        // the desktop shows — the user grants it from the Linked
        // devices screen (or the phone's app settings) and picks
        // again. Never a crash, never a silent drop.
        val granted =
            context.checkSelfPermission(Manifest.permission.WRITE_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) return noContactsWrite()
        return try {
            when (contactsRepository.setContactPhoto(address, photoBytes)) {
                ContactPhotoWriteResult.Ok -> ActionResult(ok = true)
                ContactPhotoWriteResult.NotFound -> ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONTACT,
                    message = "There's no contact for this number on the phone.",
                )
                ContactPhotoWriteResult.NoPermission -> noContactsWrite()
                ContactPhotoWriteResult.Failed -> ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.INTERNAL,
                    message = "The photo couldn't be saved on the phone.",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            // The permission was revoked between the check and the
            // write — not-granted, never a crash.
            Timber.d(e, "RepositorySyncMessageSource: contact photo write refused")
            noContactsWrite()
        } catch (e: Exception) {
            Timber.w(e, "RepositorySyncMessageSource: set contact photo failed")
            ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = "The photo couldn't be saved on the phone.",
            )
        }
    }

    // -- Slice 3: send + write-back -----------------------------------

    override suspend fun sendMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        replyToMessageId: Long?,
        attachments: List<SendAttachmentInput>?,
    ): SendResult {
        val hasAttachments = !attachments.isNullOrEmpty()
        if (body.isBlank() && !hasAttachments) {
            // The desktop refuses blank bodies client-side, so this is
            // a client bug, not a user error — the generic code fits.
            // A blank body WITH attachments is a photo-only send, and
            // is allowed (the phone's own composer sends those too).
            return SendResult.Failed(
                SyncProtocol.MessageFailed.INTERNAL,
                "The message could not be sent.",
            )
        }
        if (body.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_SEND_BODY_BYTES) {
            return SendResult.Failed(
                SyncProtocol.MessageFailed.MESSAGE_TOO_LONG,
                "The message is too long to send.",
            )
        }
        val conversation = resolveActionTarget(conversationId)
            ?: return SendResult.Failed(
                SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                "Conversation not found.",
            )
        return sendMutex.withLock {
            val existing = repository.findMessageByClientMessageId(clientMessageId)
            if (existing != null) {
                return@withLock storedResult(existing)
            }
            // Dedup first: a seen key never consults the uploads.
            // Only an unseen key maps its attachments — a missing
            // staging file refuses the send as `upload_expired`
            // (the desktop re-uploads, then re-sends, same key),
            // never as a half-built MMS.
            val payloads = if (!attachments.isNullOrEmpty()) {
                when (val mapped = mapAttachments(clientMessageId, attachments)) {
                    is AttachmentMapping.Ok -> mapped.payloads
                    is AttachmentMapping.Failed ->
                        return@withLock SendResult.Failed(mapped.code, mapped.message)
                }
            } else {
                emptyList()
            }
            // Non-cancellable: a dropped session mid-send must not
            // strand the message between the mirror write and the
            // radio dispatch. The send completes, the ack is lost,
            // and the desktop's reconnect retry hits the dedup above.
            val outcome = withContext(NonCancellable) {
                envoyerMessage(
                    recipients = conversation.addresses,
                    body = body,
                    attachments = payloads,
                    replyToMessageId = replyToMessageId,
                    clientMessageId = clientMessageId,
                )
            }
            mapSendOutcome(outcome)
        }
    }

    /**
     * The dedup answer for a key this phone has already seen. Pending
     * / sent / delivered → the stored result, never a re-send. Failed
     * → re-dispatch the existing row via the phone's own retry path
     * (it opens a numbered attempt, so late receipts from the first
     * try can't corrupt the second).
     */
    private suspend fun storedResult(existing: Message): SendResult {
        if (existing.status != Message.Status.FAILED) {
            return SendResult.Sent(existing.id, existing.status.name.lowercase())
        }
        return when (val retried = retrySend(existing.id)) {
            is Outcome.Success -> SendResult.Sent(existing.id, Message.Status.PENDING.name.lowercase())
            is Outcome.Failure -> when (retried.error) {
                AppError.RecipientBlocked -> SendResult.Failed(
                    SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                    "The recipient is blocked.",
                )
                // A group MMS row or a group echo can't be
                // re-dispatched — the phone can't retry those either
                // (tap-to-retry is SMS-only). The message stays
                // failed; the user sends it again from the phone.
                AppError.MmsRetryUnsupported, AppError.GroupEchoRetryUnsupported -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "This message can't be sent again automatically. Send it again from the phone.",
                )
                else -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "The message could not be sent.",
                )
            }
        }
    }

    private suspend fun mapSendOutcome(outcome: Outcome<SendReport>): SendResult {
        if (outcome is Outcome.Failure) {
            return when (outcome.error) {
                AppError.NotDefaultSmsApp -> SendResult.Failed(
                    SyncProtocol.MessageFailed.HUB_NOT_DEFAULT_SMS,
                    "Relay on the phone isn't your default messaging app.",
                )
                AppError.RecipientBlocked -> SendResult.Failed(
                    SyncProtocol.MessageFailed.BLOCKED_RECIPIENT,
                    "The recipient is blocked.",
                )
                else -> SendResult.Failed(
                    SyncProtocol.MessageFailed.SEND_FAILED,
                    "The message could not be sent.",
                )
            }
        }
        val report = (outcome as Outcome.Success).value
        // The row the desktop shows: the group echo for a broadcast
        // group send, else the single dispatched row (a group MMS
        // dispatches exactly one row — the group row).
        val messageId = report.groupEchoId ?: report.dispatched.firstOrNull()
            ?: return SendResult.Failed(
                SyncProtocol.MessageFailed.SEND_FAILED,
                "The message could not be sent.",
            )
        // The ack status is the row's status at ack time: normally
        // PENDING, but a broadcast group echo is frozen SENT (all
        // dispatched) or FAILED (some failed) at write time.
        val status = repository.findMessageById(messageId)?.status?.name?.lowercase()
            ?: Message.Status.PENDING.name.lowercase()
        return SendResult.Sent(messageId, status, report.blocked.size)
    }

    // -- Phase 2: scheduled sends --------------------------------------

    override suspend fun scheduleMessage(
        clientMessageId: String,
        conversationId: Long,
        body: String,
        sendAt: Long,
    ): ScheduleResult {
        // One validation expression (not three early returns — the
        // Detekt return cap): a blank body (the desktop refuses blank
        // bodies client-side, so this is a client bug — and unlike
        // `sendMessage` there is no photo-only exception, since a
        // scheduled send from the desktop carries no attachments), a
        // body over the wire cap, or a time in the past (the
        // scheduler itself refuses a past time too — this is the
        // clearer message for it).
        val validation = when {
            body.isBlank() -> ScheduleResult.Failed(
                SyncProtocol.MessageFailed.INTERNAL,
                "The message could not be scheduled.",
            )
            body.toByteArray(Charsets.UTF_8).size > SyncProtocol.MAX_SEND_BODY_BYTES ->
                ScheduleResult.Failed(
                    SyncProtocol.MessageFailed.MESSAGE_TOO_LONG,
                    "The message is too long to send.",
                )
            sendAt <= System.currentTimeMillis() -> ScheduleResult.Failed(
                SyncProtocol.MessageFailed.INTERNAL,
                "The scheduled time must be in the future.",
            )
            else -> null
        }
        if (validation != null) return validation
        val conversation = resolveActionTarget(conversationId)
            ?: return ScheduleResult.Failed(
                SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                "Conversation not found.",
            )
        return sendMutex.withLock {
            // Dedup BEFORE creating anything: a scheduled row
            // already carrying this key answers with its id — never
            // a second scheduled message. A key whose message
            // already fired is covered by the Slice 3 row dedup
            // (same key on the sent row): the desktop reconciles it
            // via `message_new` carrying the key, and the ack
            // echoes the requested time (a sent row carries no
            // scheduled time of its own).
            val scheduled = scheduledMessageRepository.findByClientMessageId(clientMessageId)
            if (scheduled != null) {
                return@withLock ScheduleResult.Scheduled(scheduled.id, scheduled.scheduledAt)
            }
            val sent = repository.findMessageByClientMessageId(clientMessageId)
            if (sent != null) {
                return@withLock ScheduleResult.Scheduled(sent.id, sendAt)
            }
            // The phone's own scheduler does the work — same store,
            // same WorkManager job, same fire path as a schedule
            // made on the phone. No default-SMS refusal here: the
            // phone's scheduler accepts while another app holds the
            // role and only fails at fire time, so this mirrors it.
            when (
                val outcome = scheduleMessageUseCase(
                    conversationId = conversation.id,
                    addresses = conversation.addresses,
                    body = body,
                    whenEpochMillis = sendAt,
                    subId = null,
                    clientMessageId = clientMessageId,
                )
            ) {
                is Outcome.Success -> ScheduleResult.Scheduled(outcome.value, sendAt)
                is Outcome.Failure -> ScheduleResult.Failed(
                    SyncProtocol.MessageFailed.INTERNAL,
                    "The message could not be scheduled.",
                )
            }
        }
    }

    override suspend fun cancelScheduledMessage(clientMessageId: String): CancelResult =
        sendMutex.withLock {
            try {
                // Idempotent: no row carrying this key means the
                // schedule is already gone — cancelled, deleted, or
                // never scheduled — or its message already fired (the
                // key now lives on the sent row, and the desktop
                // reconciled it via `message_new`). Either way the
                // schedule is gone: ack, don't fail.
                val scheduled = scheduledMessageRepository.findByClientMessageId(clientMessageId)
                    ?: return@withLock CancelResult.Cancelled
                // The phone's own delete path: cancel the scheduled
                // work + delete the row (and its attachments — a
                // file a sent message still cites is kept by the
                // citation count, so deleting a fired row's trace
                // never empties a bubble). The row is gone, so the
                // key is free for a later schedule with the same key.
                deleteScheduledMessage(scheduled.id)
                CancelResult.Cancelled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A genuine internal failure — the lookup or the
                // delete itself threw — is the only cancel that
                // answers `message_failed`.
                Timber.w(e, "RepositorySyncMessageSource: cancel scheduled failed")
                CancelResult.Failed(
                    SyncProtocol.MessageFailed.INTERNAL,
                    "The scheduled message could not be cancelled.",
                )
            }
        }

    // -- Create conversation from the desktop ---------------------------

    override suspend fun createConversation(
        addresses: List<String>,
        customName: String?,
    ): CreateConversationResult {
        // Blank entries carry no number (`PhoneAddress.of` normalizes
        // them to empty) — drop them, and refuse a list with nothing
        // left instead of creating a thread with no recipients.
        val parsed = addresses.map { PhoneAddress.of(it) }
            .filter { it.normalized.isNotEmpty() }
        if (parsed.isEmpty()) {
            return CreateConversationResult.Failed(
                SyncProtocol.MessageFailed.INVALID_REQUEST,
                "Choose at least one contact to start a conversation.",
            )
        }
        // The phone's own composer path: `findOrCreate` creates the
        // thread immediately (no message needed) and returns the
        // existing conversation for the same member set, so a retry
        // after a lost ack never duplicates the thread.
        return when (val outcome = repository.findOrCreate(parsed)) {
            is Outcome.Failure -> CreateConversationResult.Failed(
                SyncProtocol.MessageFailed.INTERNAL,
                "The conversation could not be created.",
            )
            is Outcome.Success -> {
                val created = outcome.value
                val name = customName?.trim()
                if (!name.isNullOrEmpty()) {
                    repository.setCustomName(created.id, name)
                }
                // `setCustomName` writes after `findOrCreate`
                // returned, so the created row's custom name (and
                // its computed title) would be stale — re-read the
                // row so the desktop opens the conversation with
                // its name. Falls back to the created row.
                val fresh = if (!name.isNullOrEmpty()) {
                    repository.findById(created.id) ?: created
                } else {
                    created
                }
                // The real count, from the same read `conversations()`
                // uses — for an already-existing group the count must
                // be real so the desktop opens it with its history.
                val count = repository.messageCountsByConversation()[created.id] ?: 0
                CreateConversationResult.Created(fresh.toSynced(count))
            }
        }
    }

    // -- Slice 4: attachment bytes --------------------------------------

    override suspend fun openAttachment(
        attachmentId: Long,
        messageId: Long,
    ): AttachmentOpenResult {
        val attachment = resolveDownloadTarget(attachmentId, messageId)
            ?: return AttachmentOpenResult.Unknown
        return withContext(Dispatchers.IO) {
            try {
                // Both read paths the thread UI already handles: a
                // live MMS file under `filesDir/mms_attachments/`, or
                // an imported part at `content://mms/part/{id}`.
                val stream = if (attachment.localUri.startsWith("content://")) {
                    context.contentResolver.openInputStream(Uri.parse(attachment.localUri))
                } else {
                    File(attachment.localUri).inputStream()
                }
                if (stream == null) {
                    AttachmentOpenResult.Unreadable
                } else {
                    AttachmentOpenResult.Opened(AttachmentData(attachment.sizeBytes, stream))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // File gone, part URI won't open, permission revoked —
                // the row exists but the bytes don't.
                Timber.w(e, "RepositorySyncMessageSource: attachment %s unreadable", attachmentId)
                AttachmentOpenResult.Unreadable
            }
        }
    }

    /**
     * Resolves the attachment a download may read: existing, of
     * [messageId], in a non-vault conversation — or null. Same
     * membership guard as `setReaction` / `setPinnedMessage`: a stale
     * or malicious client must not pull vault bytes by id, and all
     * three failures look the same, so ids can't be probed.
     */
    private suspend fun resolveDownloadTarget(attachmentId: Long, messageId: Long): Attachment? {
        val attachment = repository.findAttachmentById(attachmentId)
            ?.takeIf { it.messageId == messageId }
            ?: return null
        val message = repository.findMessageById(messageId) ?: return null
        resolveActionTarget(message.conversationId) ?: return null
        return attachment
    }

    override suspend fun beginUpload(
        clientMessageId: String,
        index: Int,
        mimeType: String,
        fileName: String?,
        sizeBytes: Long,
        sha256: String,
        kind: String?,
    ): UploadResult = sendMutex.withLock {
        // Round 36 phase 2 — note uploads (`kind: "note"`) store
        // originals, so they get the 25 MB note cap; anything else
        // keeps the 280 KB carrier cap (a file over it could never
        // be sent as MMS anyway).
        val cap = if (kind == SyncProtocol.UPLOAD_KIND_NOTE) {
            SyncProtocol.MAX_NOTE_ATTACHMENT_UPLOAD_BYTES
        } else {
            MmsConstants.CARRIER_PAYLOAD_CAP_BYTES
        }
        if (sizeBytes > cap) {
            return UploadResult.Failed(
                SyncProtocol.AttachmentUploadError.UPLOAD_TOO_LARGE,
                "This file is too big to send.",
            )
        }
        if (!isValidUploadStart(index, mimeType, sizeBytes, sha256)) {
            return UploadResult.Failed(
                SyncProtocol.AttachmentUploadError.INTERNAL,
                "This file couldn't be uploaded.",
            )
        }
        withContext(Dispatchers.IO) {
            try {
                sweepStaleUploads(context.cacheDir, uploads)
                val dir = uploadDir(context.cacheDir, clientMessageId).apply { mkdirs() }
                val target = File(dir, stagedFileName(index, fileName, mimeType))
                val part = File(dir, "$index.part")
                // A re-begin (lost `done` ack) restarts the upload
                // from scratch: any previous partial or completed
                // file for this index is discarded, never resumed.
                part.delete()
                target.delete()
                uploads[uploadKey(clientMessageId, index)] =
                    UploadSession(dir, part, target, sizeBytes, sha256)
                UploadResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "RepositorySyncMessageSource: beginUpload failed")
                UploadResult.Failed(
                    SyncProtocol.AttachmentUploadError.INTERNAL,
                    "This file couldn't be uploaded.",
                )
            }
        }
    }

    override suspend fun appendUploadChunk(
        clientMessageId: String,
        index: Int,
        offset: Long,
        bytes: ByteArray,
        final: Boolean,
    ): UploadResult = sendMutex.withLock {
        val key = uploadKey(clientMessageId, index)
        val session = uploads[key]
            ?: return UploadResult.Failed(
                SyncProtocol.AttachmentUploadError.INTERNAL,
                "This file couldn't be uploaded.",
            )
        withContext(Dispatchers.IO) {
            try {
                val staged = if (session.partFile.exists()) session.partFile.length() else 0L
                if (offset != staged) {
                    // A gap means a lost chunk — the upload cannot be
                    // repaired, only restarted from `beginUpload`.
                    return@withContext UploadResult.Failed(
                        SyncProtocol.AttachmentUploadError.INTERNAL,
                        "This file couldn't be uploaded.",
                    )
                }
                FileOutputStream(session.partFile, true).use { it.write(bytes) }
                if (!final) return@withContext UploadResult.Ok
                // Final chunk: the staged bytes must match the size
                // and SHA-256 promised at `beginUpload` before the
                // part becomes the sendable file.
                val verified = session.partFile.length() == session.sizeBytes &&
                    sha256Hex(session.partFile)?.equals(session.sha256, ignoreCase = true) == true
                if (!verified) {
                    session.partFile.delete()
                    uploads.remove(key)
                    return@withContext UploadResult.Failed(
                        SyncProtocol.AttachmentUploadError.UPLOAD_BAD_CHECKSUM,
                        "This file didn't arrive intact. Try again.",
                    )
                }
                if (!session.partFile.renameTo(session.targetFile)) {
                    session.partFile.delete()
                    uploads.remove(key)
                    return@withContext UploadResult.Failed(
                        SyncProtocol.AttachmentUploadError.INTERNAL,
                        "This file couldn't be uploaded.",
                    )
                }
                uploads.remove(key)
                UploadResult.Ok
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "RepositorySyncMessageSource: appendUploadChunk failed")
                UploadResult.Failed(
                    SyncProtocol.AttachmentUploadError.INTERNAL,
                    "This file couldn't be uploaded.",
                )
            }
        }
    }

    /**
     * Maps one media send's entries to MMS payloads, after the dedup
     * check and before the router call. Upload entries read their
     * staged file (missing → `upload_expired`); sticker entries are
     * rendered by the phone and copied to a staging temp file. An
     * entry with neither or both kinds is a client bug.
     */
    private suspend fun mapAttachments(
        clientMessageId: String,
        inputs: List<SendAttachmentInput>,
    ): AttachmentMapping {
        val payloads = mutableListOf<SendMediaMmsUseCase.AttachmentPayload>()
        for ((position, input) in inputs.withIndex()) {
            when {
                input.uploadIndex != null && input.stickerEmoji == null -> {
                    // The staged name is recomputed from the wire
                    // fields, so no in-memory map is needed — a
                    // restart between upload and send still sends.
                    val file = File(
                        uploadDir(context.cacheDir, clientMessageId),
                        stagedFileName(input.uploadIndex, input.fileName, input.mimeType),
                    )
                    if (!file.exists() || file.length() == 0L) {
                        return AttachmentMapping.Failed(
                            SyncProtocol.MessageFailed.UPLOAD_EXPIRED,
                            "The photo didn't reach the phone — send it again.",
                        )
                    }
                    payloads += SendMediaMmsUseCase.AttachmentPayload(
                        file = file,
                        mimeType = input.mimeType ?: "application/octet-stream",
                        width = input.width,
                        height = input.height,
                        durationMs = input.durationMs,
                    )
                }
                input.stickerEmoji != null && input.uploadIndex == null -> {
                    val payload = stickerPayload(clientMessageId, position, input.stickerEmoji)
                        ?: return AttachmentMapping.Failed(
                            SyncProtocol.MessageFailed.SEND_FAILED,
                            "That sticker isn't available on the phone.",
                        )
                    payloads += payload
                }
                else -> {
                    return AttachmentMapping.Failed(
                        SyncProtocol.MessageFailed.INTERNAL,
                        "The message could not be sent.",
                    )
                }
            }
        }
        return AttachmentMapping.Ok(payloads)
    }

    /**
     * Renders a sticker to a staging temp file: the phone renders the
     * emoji ([stickerSendUri], 512 px PNG) and the glue copies the
     * returned content URI via `ContentResolver` — the send path
     * takes files, not URIs. `null` when the emoji has no bundled
     * asset or the copy fails.
     */
    private suspend fun stickerPayload(
        clientMessageId: String,
        position: Int,
        emoji: String,
    ): SendMediaMmsUseCase.AttachmentPayload? {
        val uri = stickerSendUri(context, emoji) ?: return null
        val file = withContext(Dispatchers.IO) {
            try {
                val dir = uploadDir(context.cacheDir, clientMessageId).apply { mkdirs() }
                val target = File(dir, "sticker-$position.png")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                } ?: return@withContext null
                target.takeIf { it.exists() && it.length() > 0L }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "RepositorySyncMessageSource: sticker copy failed")
                null
            }
        } ?: return null
        return SendMediaMmsUseCase.AttachmentPayload(
            file = file,
            mimeType = "image/png",
            // The render size `stickerSendUri` produces.
            width = STICKER_PX,
            height = STICKER_PX,
        )
    }

    override suspend fun markRead(conversationId: Long): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.markRead(conversationId)
        return ActionResult(ok = true)
    }
    override suspend fun markAllRead(): ActionResult {
        repository.markAllRead()
        return ActionResult(ok = true)
    }

    override suspend fun setPinned(conversationId: Long, pinned: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setPinned(conversationId, pinned)
        return ActionResult(ok = true)
    }

    override suspend fun setArchived(conversationId: Long, archived: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setArchived(conversationId, archived)
        return ActionResult(ok = true)
    }

    override suspend fun setMuted(conversationId: Long, muted: Boolean): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        repository.setMuted(conversationId, muted)
        return ActionResult(ok = true)
    }

    override suspend fun setBlocked(conversationId: Long, blocked: Boolean): ActionResult {
        val conversation = resolveActionTarget(conversationId) ?: return unknownConversation()
        // Blocking is per number, not a conversation flag: the phone
        // marks a conversation blocked only when ALL its addresses
        // are blocked, so a block/unblock applies to every address,
        // like the list screen's block does. Each address is
        // attempted on its own so one failure doesn't drop the rest;
        // a total failure answers `internal` so the desktop reverts,
        // and the live conversation update converges a partial one.
        if (conversation.addresses.isEmpty()) return ActionResult(ok = true)
        val applied = conversation.addresses.count { addr ->
            runCatching {
                if (blocked) {
                    blockNumber.invoke(addr.raw, conversation.displayName)
                } else {
                    unblockNumber.invoke(addr.raw)
                }
            }.getOrNull() is Outcome.Success
        }
        if (applied == 0) {
            return ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.INTERNAL,
                message = if (blocked) {
                    "Couldn't block this conversation."
                } else {
                    "Couldn't unblock this conversation."
                },
            )
        }
        return ActionResult(ok = true)
    }

    // -- Category mutes ----------------------------------------------

    override fun observeMutedCategories(): Flow<Set<String>> =
        settings.flow.map { it.conversations.mutedCategories.mapToWire() }

    override suspend fun mutedCategories(): Set<String>? =
        settings.hydratedOrNull()?.conversations?.mutedCategories?.mapToWire()

    override suspend fun setCategoryMuted(category: String, muted: Boolean): ActionResult {
        val parsed = category.toMessageCategory()
            ?: return ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.INVALID_REQUEST,
                message = "Unknown category.",
            )
        settings.update { current ->
            val mutedSet = if (muted) {
                current.conversations.mutedCategories + parsed
            } else {
                current.conversations.mutedCategories - parsed
            }
            current.copy(conversations = current.conversations.copy(mutedCategories = mutedSet))
        }
        return ActionResult(ok = true)
    }

    /** The muted set as lowercase wire strings (the `category_mutes` alphabet). */
    private fun Set<MessageCategory>.mapToWire(): Set<String> = map { it.toWire() }.toSet()

    /**
     * Lowercase wire name for one category — explicit, never
     * `name.lowercase()`: the default-locale lowercase of `PERSONAL`
     * is wrong in Turkish (dotted I), and the wire alphabet is fixed.
     */
    private fun MessageCategory.toWire(): String = when (this) {
        MessageCategory.PERSONAL -> "personal"
        MessageCategory.OTP -> "otp"
        MessageCategory.PROMOTIONAL -> "promotional"
        MessageCategory.TRANSACTIONS -> "transactions"
        MessageCategory.OTHER -> "other"
    }

    /** Parses a wire category; unknown strings are null (refused, never a crash). */
    private fun String.toMessageCategory(): MessageCategory? = when (this) {
        "personal" -> MessageCategory.PERSONAL
        "otp" -> MessageCategory.OTP
        "promotional" -> MessageCategory.PROMOTIONAL
        "transactions" -> MessageCategory.TRANSACTIONS
        "other" -> MessageCategory.OTHER
        else -> null
    }

    override suspend fun setReaction(conversationId: Long, messageId: Long, emoji: String?): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        // The message must belong to the named conversation: without
        // this, a stale or malicious client could set a reaction on a
        // message in a vault conversation (`setReaction` itself has no
        // vault guard — it is a local-state write, not a read).
        val message = repository.findMessageById(messageId)
        if (message == null || message.conversationId != conversationId) {
            return ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                message = "Message not found.",
            )
        }
        val previousEmoji = message.reactionEmoji
        // The phone's order: local state first, then the SMS. If the
        // SMS fails, the local reaction stays set. No dispatch is
        // needed when nothing changed (Noop) or when a removal has no
        // previous emoji to name in the removal SMS.
        val dispatch = when (val result = repository.setReaction(messageId, emoji)) {
            is SetReactionResult.First -> ReactionDispatch(result.messageId, result.emoji, false)
            is SetReactionResult.Changed -> ReactionDispatch(messageId, result.to, false)
            SetReactionResult.Removed ->
                previousEmoji?.let { ReactionDispatch(messageId, it, true) }
            SetReactionResult.Noop -> null
        }
        return dispatch?.let { dispatchReactionSms(it) } ?: ActionResult(ok = true)
    }

    /**
     * The phone's reaction-dispatch rules, in the same order the
     * thread screen applies them: no SMS when the user turned
     * reactions-to-recipient off, no re-send of the same reaction
     * within 60 s, and no removal SMS when a reaction came back
     * between the set and the dispatch. The local reaction is already
     * set in every case — only the SMS is gated. Our own messages
     * send too (the use case resolves the conversation's recipients).
     */
    private suspend fun dispatchReactionSms(dispatch: ReactionDispatch): ActionResult {
        val sending = (settings.hydratedOrNull() ?: AppSettings()).sending
        // The `when` short-circuits in order, so the anti-race re-read
        // only happens when the earlier gates passed.
        val skipSms = when {
            !sending.sendReactionsToRecipient -> true
            isRecentDuplicate(dispatch, recentReactionDispatches) -> true
            dispatch.isRemoval ->
                repository.findMessageById(dispatch.messageId)?.reactionEmoji != null
            else -> false
        }
        if (skipSms) return ActionResult(ok = true)
        recentReactionDispatches[dispatch.messageId] =
            dispatch.copy(timestamp = System.currentTimeMillis())
        return when (
            sendReaction(dispatch.messageId, dispatch.emoji, sending.reactionFormat, dispatch.isRemoval)
        ) {
            is Outcome.Success -> ActionResult(ok = true)
            is Outcome.Failure -> ActionResult(
                ok = false,
                code = SyncProtocol.MessageFailed.SEND_FAILED,
                message = "The reaction was saved on the phone but could not be sent.",
            )
        }
    }

    override suspend fun setPinnedMessage(conversationId: Long, messageId: Long?): ActionResult {
        if (resolveActionTarget(conversationId) == null) return unknownConversation()
        if (messageId != null) {
            // Same membership guard as `setReaction`: only a message
            // of this thread can be pinned in it.
            val message = repository.findMessageById(messageId)
            if (message == null || message.conversationId != conversationId) {
                return ActionResult(
                    ok = false,
                    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
                    message = "Message not found.",
                )
            }
            repository.setPinnedMessage(conversationId, messageId, System.currentTimeMillis())
        } else {
            repository.setPinnedMessage(conversationId, null, null)
        }
        return ActionResult(ok = true)
    }

    /** The conversation an action may touch: existing and never vault. */
    private suspend fun resolveActionTarget(conversationId: Long): Conversation? =
        repository.findById(conversationId)?.takeIf { !it.inVault }

    companion object {
        /** The render size `stickerSendUri` produces (512 px PNG). */
        private const val STICKER_PX = 512
    }
}

/** One reaction SMS dispatch, as recorded for the 60 s dedup. */
private data class ReactionDispatch(
    val messageId: Long,
    val emoji: String,
    val isRemoval: Boolean,
    val timestamp: Long = 0L,
)

/** Slice 4 — one upload in progress: what `beginUpload` promised, and where its bytes land. */
private data class UploadSession(
    val dir: File,
    val partFile: File,
    val targetFile: File,
    val sizeBytes: Long,
    val sha256: String,
)

/** Slice 4 — one media send's entries, mapped — or the failure that refuses the send. */
private sealed interface AttachmentMapping {
    data class Ok(val payloads: List<SendMediaMmsUseCase.AttachmentPayload>) : AttachmentMapping
    data class Failed(val code: String, val message: String) : AttachmentMapping
}

/**
 * The `unknown_conversation` action failure. File scope, not a class
 * member: it touches no class state, and the class is at the Detekt
 * function limit.
 */
private fun unknownConversation(): ActionResult = ActionResult(
    ok = false,
    code = SyncProtocol.MessageFailed.UNKNOWN_CONVERSATION,
    message = "Conversation not found.",
)

/**
 * The `hub_no_contacts_write` action failure (round 3, item 9):
 * WRITE_CONTACTS is not granted, so the phone cannot save a photo
 * the desktop picked. File scope, like [unknownConversation].
 */
private fun noContactsWrite(): ActionResult = ActionResult(
    ok = false,
    code = SyncProtocol.MessageFailed.HUB_NO_CONTACTS_WRITE,
    message = "Relay on the phone needs permission to change contacts. " +
        "Allow it in the phone's app settings, then pick the photo again.",
)

/** Same window as the thread screen's reaction burst guard (60 s). */
private const val REACTION_DEDUP_WINDOW_MS = 60_000L

/**
 * The 60 s same-emoji dedup, mirroring the thread screen's burst
 * guard. File scope, not a class member: the dispatch log arrives as
 * a parameter, and the class is at the Detekt function limit.
 */
private fun isRecentDuplicate(
    dispatch: ReactionDispatch,
    recentDispatches: MutableMap<Long, ReactionDispatch>,
): Boolean {
    val now = System.currentTimeMillis()
    recentDispatches.entries.removeAll { now - it.value.timestamp > REACTION_DEDUP_WINDOW_MS }
    val last = recentDispatches[dispatch.messageId]
    return last != null && last.emoji == dispatch.emoji && last.isRemoval == dispatch.isRemoval &&
        now - last.timestamp < REACTION_DEDUP_WINDOW_MS
}

/** Lowercase hex, no separators — the wire's SHA-256 shape. */
private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val chars = CharArray(size * 2)
    forEachIndexed { index, byte ->
        val value = byte.toInt() and 0xFF
        chars[index * 2] = digits[value shr 4]
        chars[index * 2 + 1] = digits[value and 0x0F]
    }
    return chars.concatToString()
}

// -- Slice 4: upload staging helpers (file scope, not class members:
// the class is at the Detekt function limit, and these need no class
// state beyond what their parameters carry) --------------------------

/** Staging root under `cacheDir`, one subdir per send key. */
private const val UPLOADS_DIR = "sync_uploads"

/** Staging dirs older than this are swept on the next upload (24 h). */
private const val UPLOAD_TTL_MS = 24L * 60 * 60 * 1000

/** Staged file names are capped at this many stem chars (the extension is kept). */
private const val MAX_STAGED_STEM_CHARS = 100

/** An extension longer than this is treated as part of the stem, not an extension. */
private const val MAX_EXTENSION_CHARS = 10

/** A start frame is usable when the index, size, MIME, and digest are all present and sane. */
private fun isValidUploadStart(index: Int, mimeType: String, sizeBytes: Long, sha256: String): Boolean =
    index >= 0 && sizeBytes > 0L && mimeType.isNotBlank() && sha256.isNotBlank()

/** The staging dir for one send key: `cacheDir/sync_uploads/<sanitized key>/`. */
internal fun uploadDir(cacheDir: File, clientMessageId: String): File =
    File(File(cacheDir, UPLOADS_DIR), sanitizeClientKey(clientMessageId))

/** The in-flight upload key: the sanitized client key + the attachment index. */
internal fun uploadKey(clientMessageId: String, index: Int): String =
    "${sanitizeClientKey(clientMessageId)}:$index"

/**
 * The client key is client-controlled: only key-safe chars survive,
 * so it can never escape the staging root (`..` becomes `__`). A
 * UUID passes through unchanged.
 */
internal fun sanitizeClientKey(clientMessageId: String): String =
    clientMessageId
        .map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .takeIf { it.isNotBlank() } ?: "upload"

/**
 * The staged file's name: the client-supplied name, reduced to a
 * bare basename (path separators stripped — a malicious client must
 * never pick the path), prefixed with the index so two attachments
 * with the same name in one send can't overwrite each other. A
 * missing or unusable name falls back to `attachment-<index>` plus
 * the MIME extension. The stem is capped (the extension is kept) so
 * over-long names can't break the filesystem limit.
 */
internal fun stagedFileName(index: Int, fileName: String?, mimeType: String?): String {
    val base = fileName
        ?.substringAfterLast('/')
        ?.substringAfterLast('\\')
        ?.filter { it.code >= 0x20 }
        ?.trim()
        .orEmpty()
    if (base.isEmpty() || base == "." || base == "..") {
        val ext = extensionForMime(mimeType)?.let { ".$it" }.orEmpty()
        return "attachment-$index$ext"
    }
    val ext = base.substringAfterLast('.', "")
        .takeIf { it.isNotEmpty() && it.length <= MAX_EXTENSION_CHARS }
        ?.let { ".$it" }
        .orEmpty()
    val stem = if (ext.isNotEmpty()) base.dropLast(ext.length) else base
    return "$index-${stem.take(MAX_STAGED_STEM_CHARS)}$ext"
}

/**
 * The extension a staged file gets when the client sent no usable
 * name — a small fixed map, not the framework's `MimeTypeMap`
 * (which needs the Android runtime; this runs on plain-JVM tests
 * too).
 */
private fun extensionForMime(mimeType: String?): String? = when (mimeType?.lowercase()) {
    "image/jpeg" -> "jpg"
    "image/png" -> "png"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "image/heic", "image/heif" -> "heic"
    "video/mp4" -> "mp4"
    "video/3gpp" -> "3gp"
    "audio/mpeg" -> "mp3"
    "audio/ogg" -> "ogg"
    "audio/wav", "audio/x-wav" -> "wav"
    "audio/mp4" -> "m4a"
    "audio/3gpp" -> "3gp"
    "audio/amr" -> "amr"
    "application/pdf" -> "pdf"
    "text/plain" -> "txt"
    else -> null
}

/**
 * Deletes staging dirs older than 24 h — uploads whose send never
 * came (cancelled, socket died). Runs on every new upload; the
 * in-flight map drops the swept sessions with their dirs.
 */
private fun sweepStaleUploads(
    cacheDir: File,
    uploads: MutableMap<String, UploadSession>,
    now: Long = System.currentTimeMillis(),
) {
    val root = File(cacheDir, UPLOADS_DIR)
    val dirs = root.listFiles()?.filter { it.isDirectory } ?: return
    for (dir in dirs) {
        if (now - dir.lastModified() > UPLOAD_TTL_MS && dir.deleteRecursively()) {
            uploads.entries.removeAll { it.value.dir == dir }
        }
    }
}

/**
 * SHA-256 hex of a staged file, streamed (note uploads run to
 * 25 MB, so the streaming pass matters). `null` when the file
 * can't be read.
 */
private fun sha256Hex(file: File): String? = try {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(SyncProtocol.ATTACHMENT_CHUNK_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
    }
    digest.digest().toHex()
} catch (e: Exception) {
    Timber.w(e, "RepositorySyncMessageSource: staged file hash failed")
    null
}

/** Domain → wire mapping. Ids are decimal strings of the Room ids. */
private fun Conversation.toSynced(messageCount: Int): SyncedConversation = SyncedConversation(
    id = id.toString(),
    threadId = threadId,
    addresses = addresses.map { it.raw },
    displayName = displayName,
    customName = customName,
    title = title,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unread = unreadCount,
    pinned = pinned,
    archived = archived,
    muted = muted,
    blocked = blocked,
    messageCount = messageCount,
    pinnedMessageId = pinnedMessageId?.toString(),
    pinnedMessageAt = pinnedMessageAt,
)

private fun Message.toSynced(): SyncedMessage = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = address,
    body = body,
    type = type.name.lowercase(),
    direction = direction.name.lowercase(),
    date = date,
    dateSent = dateSent,
    read = read,
    starred = starred,
    status = status.name.lowercase(),
    errorCode = errorCode,
    replyToMessageId = replyToMessageId?.toString(),
    reaction = reactionEmoji,
    attachments = attachments.map { it.toSynced() },
    clientMessageId = clientMessageId,
)

private fun Attachment.toSynced(): SyncedAttachment = SyncedAttachment(
    id = id.toString(),
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
)

private fun DomainMessageState.toSynced(): MessageState = MessageState(
    id = id,
    conversationId = conversationId,
    status = status.name.lowercase(),
    reaction = reactionEmoji,
    read = read,
    starred = starred,
)

/**
 * Slice 5 — domain → wire mapping. A contact with a blank name is
 * dropped (a contact with no name is not useful to display), and so
 * is one with no usable numbers. Numbers are the `PhoneAddress`
 * normalized forms (the phone's own `normalizePhone`, with the
 * alphanumeric fallback) — the normalized form is the join key on
 * both sides. `photoUpdatedAt` rides only when a photo exists.
 * [SyncedContact.contactId] is the server's internal diff key —
 * `@Transient`, never on the wire.
 */
private fun Contact.toSynced(): SyncedContact? {
    val name = displayName?.takeIf { it.isNotBlank() } ?: return null
    val phones = phones.map { it.normalized }.filter { it.isNotEmpty() }.distinct()
    if (phones.isEmpty()) return null
    val hasPhoto = photoUri != null
    return SyncedContact(
        displayName = name,
        phones = phones,
        hasPhoto = hasPhoto,
        photoUpdatedAt = if (hasPhoto) photoUpdatedAt else null,
        contactId = id ?: 0L,
    )
}
