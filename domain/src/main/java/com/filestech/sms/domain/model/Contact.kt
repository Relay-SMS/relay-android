package com.filestech.sms.domain.model

/**
 * A system contact: the display name, its phone numbers, and the
 * thumbnail photo URI (null when the contact has no photo).
 *
 * Slice 5 — [photoUpdatedAt] is the photo-staleness version
 * (`ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP`),
 * filled only by the detailed list read; null elsewhere. It is
 * coarse (any edit to the contact bumps it, not just a photo
 * change) — the desktop re-downloads the photo on any bump, which
 * is never stale and occasionally a redundant few-KB download.
 */
data class Contact(
    val id: Long?,
    val displayName: String?,
    val phones: List<PhoneAddress>,
    val photoUri: String? = null,
    val photoUpdatedAt: Long? = null,
) {
    val firstPhone: PhoneAddress? get() = phones.firstOrNull()
}
