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
     * Slice 5 — opens the thumbnail photo stream for the contact
     * behind [address] (same `PhoneLookup` the UI uses). Null when
     * there is no contact, no photo, or an unreadable stream. The
     * caller closes the stream.
     */
    suspend fun openPhotoStream(address: String): InputStream?
}
