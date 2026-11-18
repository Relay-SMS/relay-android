package com.filestech.sms.ui.screens.scheduled

import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The scheduled row's recipient resolution: a named contact shows the
 * name (and its photo when it has one), an unknown number shows the raw
 * number with the unknown flag (the grey person glyph in the row, like
 * a 1-to-1 conversation row).
 */
class ScheduledRecipientsTest {

    @Test
    fun `a named contact with a photo resolves to the name and photo`() {
        val contact = Contact(1L, "Alice", listOf(PhoneAddress.of(PHONE)), PHOTO_URI)

        val recipient = scheduledRecipient(PHONE, contact)

        assertThat(recipient.label).isEqualTo("Alice")
        assertThat(recipient.isUnknownContact).isFalse()
        assertThat(recipient.photoUri).isEqualTo(PHOTO_URI)
    }

    @Test
    fun `a named contact without a photo resolves to the name`() {
        val contact = Contact(1L, "Alice", listOf(PhoneAddress.of(PHONE)), null)

        val recipient = scheduledRecipient(PHONE, contact)

        assertThat(recipient.label).isEqualTo("Alice")
        assertThat(recipient.isUnknownContact).isFalse()
        assertThat(recipient.photoUri).isNull()
    }

    @Test
    fun `no contact resolves to the raw number with the unknown flag`() {
        val recipient = scheduledRecipient(PHONE, null)

        assertThat(recipient.label).isEqualTo(PHONE)
        assertThat(recipient.isUnknownContact).isTrue()
        assertThat(recipient.photoUri).isNull()
    }

    @Test
    fun `a contact with no usable name counts as unknown but keeps its photo`() {
        val contact = Contact(1L, "  ", listOf(PhoneAddress.of(PHONE)), PHOTO_URI)

        val recipient = scheduledRecipient(PHONE, contact)

        assertThat(recipient.label).isEqualTo(PHONE)
        assertThat(recipient.isUnknownContact).isTrue()
        assertThat(recipient.photoUri).isEqualTo(PHOTO_URI)
    }

    companion object {
        private const val PHONE = "+33612345678"
        private const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
    }
}
