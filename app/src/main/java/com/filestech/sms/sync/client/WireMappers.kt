package com.filestech.sms.sync.client

import com.filestech.sms.data.repository.SyncedAttachmentData
import com.filestech.sms.data.repository.SyncedConversationData
import com.filestech.sms.data.repository.SyncedMessageData
import com.filestech.sms.domain.model.MessageDirection
import com.filestech.sms.domain.model.MessageStatus
import com.filestech.sms.domain.model.MessageType
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage

/**
 * Client mode — wire → mirror mapping. Pure functions, unit-tested.
 *
 * The wire carries ids as decimal strings and enums as lowercase
 * names; the data module only ever sees parsed values (Long ids,
 * domain enums), so the mirror never has to guess. A row whose id
 * does not parse is dropped (null) — a malformed id can never be
 * the upsert key, and the engine logs the drop. An attachment whose
 * id does not parse is dropped on its own — one bad attachment must
 * not lose its message.
 *
 * Enum fallbacks never throw: an unknown type reads as SMS, an
 * unknown direction as incoming, and an unknown status as
 * RECEIVED for an incoming message / SENT for an outgoing one
 * (the states a message in that direction has necessarily passed).
 */

/** Parses a wire id (a decimal string) or null when it is not a number. */
fun parseHubId(raw: String): Long? = raw.toLongOrNull()

/** `"mms"` → MMS, anything else (incl. unknown) → SMS. */
fun parseMessageType(wire: String): MessageType =
    if (wire == "mms") MessageType.MMS else MessageType.SMS

/** `"outgoing"` → OUTGOING, anything else (incl. unknown) → INCOMING. */
fun parseMessageDirection(wire: String): MessageDirection =
    if (wire == "outgoing") MessageDirection.OUTGOING else MessageDirection.INCOMING

/**
 * The wire status name → [MessageStatus]. Unknown names fall back
 * to the state the direction implies (RECEIVED incoming, SENT
 * outgoing) — a message in that direction has necessarily passed
 * it, so the bubble never shows a wrong state for long.
 */
fun parseMessageStatus(wire: String, direction: MessageDirection): MessageStatus = when (wire) {
    "pending" -> MessageStatus.PENDING
    "sent" -> MessageStatus.SENT
    "delivered" -> MessageStatus.DELIVERED
    "failed" -> MessageStatus.FAILED
    "received" -> MessageStatus.RECEIVED
    "scheduled" -> MessageStatus.SCHEDULED
    else -> if (direction == MessageDirection.INCOMING) {
        MessageStatus.RECEIVED
    } else {
        MessageStatus.SENT
    }
}

/** Wire attachment metadata → mirror data, or null when its id does not parse. */
fun SyncedAttachment.toData(): SyncedAttachmentData? {
    val hubId = parseHubId(id) ?: return null
    return SyncedAttachmentData(
        hubAttachmentId = hubId,
        mimeType = mimeType,
        fileName = fileName,
        sizeBytes = sizeBytes,
        width = width,
        height = height,
        durationMs = durationMs,
    )
}

/** Wire message → mirror data, or null when its id or conversation id does not parse. */
fun SyncedMessage.toData(): SyncedMessageData? {
    val hubId = parseHubId(id) ?: return null
    val hubConvId = parseHubId(conversationId) ?: return null
    val direction = parseMessageDirection(direction)
    return SyncedMessageData(
        hubMessageId = hubId,
        hubConversationId = hubConvId,
        address = address,
        body = body,
        type = parseMessageType(type),
        direction = direction,
        date = date,
        dateSent = dateSent,
        read = read,
        starred = starred,
        status = parseMessageStatus(status, direction),
        errorCode = errorCode,
        replyToHubMessageId = replyToMessageId?.let(::parseHubId),
        reaction = reaction,
        clientMessageId = clientMessageId,
        attachments = attachments.mapNotNull { it.toData() },
    )
}

/** Wire conversation → mirror data, or null when its id does not parse. */
fun SyncedConversation.toData(): SyncedConversationData? {
    val hubId = parseHubId(id) ?: return null
    return SyncedConversationData(
        hubConversationId = hubId,
        title = title,
        displayName = displayName,
        customName = customName,
        addresses = addresses,
        lastMessagePreview = lastMessagePreview,
        lastMessageAt = lastMessageAt,
        unreadCount = unread,
        pinned = pinned,
        muted = muted,
        archived = archived,
        isBlocked = blocked,
        pinnedMessageHubId = pinnedMessageId?.let(::parseHubId),
        pinnedMessageAt = pinnedMessageAt,
    )
}
