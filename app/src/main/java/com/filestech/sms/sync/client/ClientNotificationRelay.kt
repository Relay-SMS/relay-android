package com.filestech.sms.sync.client

import android.content.Context
import androidx.annotation.StringRes
import com.filestech.sms.R
import com.filestech.sms.data.local.db.dao.ConversationDao
import com.filestech.sms.data.local.db.dao.MessageDao
import com.filestech.sms.data.repository.SyncedMessageResult
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedMessage
import com.filestech.sms.system.notifications.ActiveConversationTracker
import com.filestech.sms.system.notifications.IncomingMessageNotifier
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C4 — the frame-driven notification relay: a live
 * `message_new`, already written into Room by the mirror (getting
 * local ids), becomes a notification via the existing
 * [IncomingMessageNotifier.notifyIncoming] — unchanged and
 * frame-agnostic: it re-reads Room for the vault / mute / category /
 * preview gates, so those live there, not here.
 *
 * Only the engine's live-message path calls this — bulk
 * `messages_batch` inserts never notify (a 500-message re-sync
 * fires no popups). The relay's own gates, ported from the desktop:
 * - only incoming messages notify (an outgoing message is this
 *   account's own send, from this device or another linked one);
 * - an echo of this device's own send is suppressed: the mirror
 *   reconciled it onto the optimistic row (not inserted), and the
 *   row found by `client_message_id` is that row;
 * - a live message for an unknown conversation is skipped (its
 *   `conversation_updated` hasn't landed — no title/mute/category
 *   to decide on);
 * - a message for the conversation currently open is skipped (the
 *   user is already looking at it).
 * Text-less messages get a media label (Photo / Video / Voice
 * message / Audio / File / GIF) from the first attachment's MIME
 * type; a message with neither text nor attachments shows an empty
 * body. Notification tap / inline reply / mark-read ride the
 * notifier's existing intents, and the reply + mark-read inherit
 * the C3 wire branches for free.
 *
 * Never throws: a relay failure must not kill the engine's frame
 * loop — the message is already in Room either way.
 */
@Singleton
class ClientNotificationRelay @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val activeConversationTracker: ActiveConversationTracker,
    private val notifier: IncomingMessageNotifier,
    @ApplicationContext private val context: Context,
) {

    /** One live message's notification target: the local ids + the body to show. */
    private data class RelayTarget(
        val address: String,
        val body: String,
        val localMessageId: Long,
        val localConversationId: Long,
    )

    /** Notifies for one live `message_new` — or suppresses it, by the rules above. */
    suspend fun onLiveMessage(wire: SyncedMessage, result: SyncedMessageResult) {
        runCatching { relay(wire, result) }
            .onFailure { Timber.w(it, "ClientNotificationRelay: notification failed") }
    }

    private suspend fun relay(wire: SyncedMessage, result: SyncedMessageResult) {
        if (!isNotifiable(wire, result)) return
        val target = resolveTarget(wire, result) ?: return
        notifier.notifyIncoming(
            target.address,
            target.body,
            target.localMessageId,
            target.localConversationId,
        )
    }

    /** Incoming, and not an echo of this device's own send. */
    private suspend fun isNotifiable(wire: SyncedMessage, result: SyncedMessageResult): Boolean =
        parseMessageDirection(wire.direction) == MessageDirection.INCOMING &&
            !isOwnSendEcho(wire, result)

    /**
     * An echo of this device's own send: the live message carries a
     * client key, the mirror reconciled it (not inserted — the
     * optimistic row already existed), and the row that key finds is
     * this message's row. A keyed message that INSERTED is another
     * linked device's send — the direction gate covers it (outgoing).
     */
    private suspend fun isOwnSendEcho(wire: SyncedMessage, result: SyncedMessageResult): Boolean {
        val key = wire.clientMessageId ?: return false
        if (result.inserted) return false
        return messageDao.findByClientMessageId(key)?.id == result.localId
    }

    /**
     * The notification target, or null when there is nothing to
     * notify for: the message row vanished, its conversation is
     * unknown (its `conversation_updated` hasn't landed), or the
     * conversation is open on screen.
     */
    private suspend fun resolveTarget(
        wire: SyncedMessage,
        result: SyncedMessageResult,
    ): RelayTarget? {
        val row = messageDao.findById(result.localId) ?: return null
        val conversation = conversationDao.findById(row.conversationId) ?: return null
        if (activeConversationTracker.isActive(conversation.id)) return null
        return RelayTarget(
            address = wire.address,
            body = notificationBody(wire),
            localMessageId = result.localId,
            localConversationId = conversation.id,
        )
    }

    /** The message text, or the media label for a text-less message. */
    private fun notificationBody(wire: SyncedMessage): String {
        if (wire.body.isNotBlank()) return wire.body
        val label = mediaLabelFor(wire.attachments) ?: return ""
        return context.getString(label.resId)
    }
}

/**
 * Client mode C4 — the media label for a text-less message, from the
 * first attachment's MIME type (the desktop's `mediaOnlyLabel`,
 * ported): `image/gif` reads as GIF (the desktop's sync path calls
 * every image a photo); the AMR / 3GPP family reads as a voice
 * message; anything else unrecognized reads as a file. Null when the
 * message has no attachments at all.
 *
 * No sticker label: nothing on the wire distinguishes a sticker from
 * a photo (both arrive as an image attachment), so a sticker reads as
 * a photo here — the same gap the desktop's sync path has.
 */
internal enum class MediaLabel(@StringRes val resId: Int) {
    PHOTO(R.string.client_notif_media_photo),
    VIDEO(R.string.client_notif_media_video),
    VOICE(R.string.client_notif_media_voice),
    AUDIO(R.string.client_notif_media_audio),
    FILE(R.string.client_notif_media_file),
    GIF(R.string.client_notif_media_gif),
}

/** Client mode C4 — the media label for a text-less message (see [MediaLabel]). */
internal fun mediaLabelFor(attachments: List<SyncedAttachment>): MediaLabel? {
    val mime = attachments.firstOrNull()?.mimeType?.lowercase(Locale.ROOT) ?: return null
    return when {
        mime == MIME_GIF -> MediaLabel.GIF
        mime.startsWith(MIME_IMAGE_PREFIX) -> MediaLabel.PHOTO
        mime.startsWith(MIME_VIDEO_PREFIX) -> MediaLabel.VIDEO
        mime in VOICE_MIME_TYPES -> MediaLabel.VOICE
        mime.startsWith(MIME_AUDIO_PREFIX) -> MediaLabel.AUDIO
        else -> MediaLabel.FILE
    }
}

private const val MIME_GIF = "image/gif"
private const val MIME_IMAGE_PREFIX = "image/"
private const val MIME_VIDEO_PREFIX = "video/"
private const val MIME_AUDIO_PREFIX = "audio/"

/** The voice-message MIME family (the desktop's `syncAttachmentLabel` set). */
private val VOICE_MIME_TYPES = setOf(
    "audio/amr",
    "audio/x-amr",
    "audio/amr-wb",
    "audio/3gpp",
)
