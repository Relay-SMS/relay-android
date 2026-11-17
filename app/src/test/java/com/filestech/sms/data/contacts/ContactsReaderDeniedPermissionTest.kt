package com.filestech.sms.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Contacts-permission fix — `ContactsReader.openPhotoStream`
 * returns null when the provider refuses the lookup, instead of
 * letting the `SecurityException` escape.
 *
 * The lookup used to sit outside the try in `openPhotoStream`, so a
 * denied READ_CONTACTS threw out of a function whose contract is
 * "null when unreadable". The first test fails on the old code (the
 * exception escapes); the second is the positive control — without
 * it, a fake provider that was never queried would make the first
 * test pass vacuously.
 *
 * JUnit 4 under Robolectric, like `ContactCardSourceTest`: a plain
 * unit test cannot reach this code at all — `lookupByPhone` builds
 * its URI with `Uri.encode` / `Uri.withAppendedPath`, both stubs
 * that throw on the plain JVM before the resolver is ever touched.
 * A fake provider is registered under the real authority, so the
 * class under test traverses a real `ContentResolver`; Robolectric's
 * own stub provider returns null rather than throwing, so the
 * denied shape needs the throwing fake below.
 *
 * NOTE for the harness: `test-fb5.sh app robo` has a pre-existing
 * compile failure (`TextScalePersistenceTest`), so compile this
 * file directly with kotlinc.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = android.app.Application::class, sdk = [33])
class ContactsReaderDeniedPermissionTest {

    /** A provider that refuses every read, the way Android does without READ_CONTACTS. */
    class RefusingContacts : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            throw SecurityException("Permission Denial: requires android.permission.READ_CONTACTS")
        }

        override fun getType(uri: Uri): String = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    /** A provider that answers one contact, Alice, with a photo URI but no photo bytes. */
    class AnsweringContacts : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val colonnes = arrayOf(
                ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.DISPLAY_NAME,
                ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            )
            val curseur = MatrixCursor(colonnes)
            curseur.addRow(arrayOf<Any?>(1L, "Alice", PHOTO_URI))
            return curseur
        }

        override fun getType(uri: Uri): String = ContactsContract.Contacts.CONTENT_ITEM_TYPE
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    @Test
    fun `openPhotoStream returns null when the provider refuses the lookup`() {
        val reader = readerWith(RefusingContacts::class.java)

        assertThat(reader.openPhotoStream(PHONE)).isNull()
    }

    @Test
    fun `the lookup still reads the contact when the provider answers`() {
        val reader = readerWith(AnsweringContacts::class.java)

        val contact = reader.lookupByPhone(PHONE)

        assertThat(contact?.displayName).isEqualTo("Alice")
        assertThat(contact?.photoUri).isEqualTo(PHOTO_URI)
        // The stream half: the fake provider serves no photo bytes,
        // so the stream is unreadable — still null, not a throw (the
        // pre-existing catch, untouched by the guard fix).
        assertThat(reader.openPhotoStream(PHONE)).isNull()
    }

    private fun <T : ContentProvider> readerWith(provider: Class<T>): ContactsReader {
        Robolectric.buildContentProvider(provider).create(ContactsContract.AUTHORITY)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ContactsReader(ctx)
    }

    companion object {
        private const val PHONE = "+33612345678"
        private const val PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
    }
}
