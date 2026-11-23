package com.filestech.sms.domain.repository

import com.filestech.sms.domain.model.Contact
import java.io.InputStream

interface ContactRepository {
    suspend fun lookupByPhone(rawPhone: String): Contact?
    suspend fun listAll(): List<Contact>

    /**
     * Slice 5 — [listAll] plus the photo-staleness version
     * ([Contact.photoUpdatedAt]) per contact, from the Contacts
     * table. The contact-sync snapshot source; the compose picker
     * keeps using [listAll].
     */
    suspend fun listAllDetailed(): List<Contact>

    /**
     * Slice 5 — opens the photo stream for the contact behind
     * [address] (same `PhoneLookup` the UI uses): the FULL-SIZE
     * photo first, the thumbnail as the fallback when the full
     * photo is missing or unreadable (round 43 — the desktop
     * shows the avatar at 96dp+, where the thumbnail pixelates).
     * Null when there is no contact, no photo, or an unreadable
     * stream. The caller closes the stream.
     */
    suspend fun openPhotoStream(address: String): InputStream?

    /**
     * Round 3 — writes a contact photo picked on the desktop: the
     * prepared 512 px JPEG for [address], resolved with the same
     * `PhoneLookup` the UI uses. [ContactPhotoWriteResult.NotFound]
     * when no contact exists for the number — the phone never
     * creates a contact for a photo set.
     * [ContactPhotoWriteResult.NoPermission] when WRITE_CONTACTS is
     * not granted (or was revoked mid-write).
     */
    suspend fun setContactPhoto(address: String, photoBytes: ByteArray): ContactPhotoWriteResult
}

/** Round 3 — what [ContactRepository.setContactPhoto] did. */
sealed interface ContactPhotoWriteResult {
    /** The photo is on the contact. */
    data object Ok : ContactPhotoWriteResult

    /** No contact exists for the number — nothing to attach the photo to. */
    data object NotFound : ContactPhotoWriteResult

    /** WRITE_CONTACTS is not granted (or was revoked mid-write). */
    data object NoPermission : ContactPhotoWriteResult

    /** Anything else — the provider refused, or the write failed. */
    data object Failed : ContactPhotoWriteResult
}
