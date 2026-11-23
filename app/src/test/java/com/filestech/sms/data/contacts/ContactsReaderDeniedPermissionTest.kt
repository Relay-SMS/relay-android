package com.filestech.sms.data.contacts

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.ContactsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException

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
 * Round 43 — the same file also covers the full-size photo: the
 * lookup now reads `PHOTO_URI` too (the answering fake returns
 * all four projected columns), and `openPhotoStream` opens the
 * full photo first with the thumbnail as the fallback. The
 * photo-bytes fakes serve their bytes through `openFile` (a
 * temp file + `ParcelFileDescriptor.open`, which Robolectric's
 * shadow implements for real), so the stream tests read real
 * bytes, not a mocked resolver.
 *
 * Round 44 — the lookup also reads `PhoneLookup.NUMBER` (the
 * fifth projected column) and verifies the returned number
 * against the queried one before believing the hit: Android's
 * provider matches on the last 7 digits, so a row whose number
 * only shares the suffix must be rejected. The answering fakes
 * return the queried number as NUMBER; two new providers cover
 * the rejection and the "first strict match wins" iteration.
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

    /** A provider that answers one contact, Alice, with both photo URIs but no photo bytes. */
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
                ContactsContract.PhoneLookup.PHOTO_URI,
                // Round 44 — the matched number: the queried number
                // itself, so the strict check accepts the row.
                ContactsContract.PhoneLookup.NUMBER,
            )
            val curseur = MatrixCursor(colonnes)
            curseur.addRow(arrayOf<Any?>(1L, "Alice", THUMB_PHOTO_URI, FULL_PHOTO_URI, PHONE))
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

    /**
     * Round 43 — the shared query half for the photo-bytes
     * providers: one contact, Alice, with both photo URIs. The
     * subclasses differ only in which URIs serve bytes through
     * `openFile`.
     */
    abstract class PhotoContacts : ContentProvider() {
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
                ContactsContract.PhoneLookup.PHOTO_URI,
                // Round 44 — the matched number: the queried number
                // itself, so the strict check accepts the row.
                ContactsContract.PhoneLookup.NUMBER,
            )
            val curseur = MatrixCursor(colonnes)
            curseur.addRow(arrayOf<Any?>(1L, "Alice", THUMB_PHOTO_URI, FULL_PHOTO_URI, PHONE))
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

        /** Serves [bytes] as a readable file descriptor (a temp file — the shadow opens real files). */
        protected fun bytesAsDescriptor(bytes: ByteArray): ParcelFileDescriptor {
            val file = File.createTempFile("relay-photo-test", ".bin")
            file.writeBytes(bytes)
            file.deleteOnExit()
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    /** Serves bytes for BOTH photo URIs — the stream must be the full photo's. */
    class FullAndThumbContacts : PhotoContacts() {
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            when (uri.toString()) {
                FULL_PHOTO_URI -> bytesAsDescriptor(FULL_BYTES)
                THUMB_PHOTO_URI -> bytesAsDescriptor(THUMB_BYTES)
                else -> throw FileNotFoundException("no photo at $uri")
            }
    }

    /** Serves bytes for the thumbnail only — the full open fails, the stream must fall back. */
    class ThumbOnlyContacts : PhotoContacts() {
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? =
            when (uri.toString()) {
                THUMB_PHOTO_URI -> bytesAsDescriptor(THUMB_BYTES)
                else -> throw FileNotFoundException("no photo at $uri")
            }
    }

    /**
     * Round 44 — the 7-digit false positive, as Android's provider
     * serves it: one row, Alice, whose number shares the last 7
     * digits with the queried number but is a different number.
     * The lookup must reject the row, not return Alice.
     */
    class SevenDigitFalsePositiveContacts : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val curseur = MatrixCursor(LOOKUP_COLUMNS)
            curseur.addRow(arrayOf<Any?>(1L, "Alice", null, null, "(215) 842-3232"))
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

    /**
     * Round 44 — two rows: Alice first (7-digit match only), then
     * Bob (the strictly matching number, in another format). The
     * lookup must skip Alice and return Bob.
     */
    class SecondRowMatchesContacts : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val curseur = MatrixCursor(LOOKUP_COLUMNS)
            curseur.addRow(arrayOf<Any?>(1L, "Alice", null, null, "(215) 842-3232"))
            curseur.addRow(arrayOf<Any?>(2L, "Bob", null, null, "(610) 842-3232"))
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
        assertThat(contact?.photoUri).isEqualTo(THUMB_PHOTO_URI)
        assertThat(contact?.photoFullUri).isEqualTo(FULL_PHOTO_URI)
        // The stream half: the fake provider serves no photo bytes,
        // so the stream is unreadable — still null, not a throw (the
        // pre-existing catch, untouched by the guard fix).
        assertThat(reader.openPhotoStream(PHONE)).isNull()
    }

    @Test
    fun `openPhotoStream reads the full photo when both are served`() {
        val reader = readerWith(FullAndThumbContacts::class.java)

        val bytes = checkNotNull(reader.openPhotoStream(PHONE)).use { it.readBytes() }

        assertThat(bytes).isEqualTo(FULL_BYTES)
    }

    @Test
    fun `openPhotoStream falls back to the thumbnail when the full photo is unreadable`() {
        val reader = readerWith(ThumbOnlyContacts::class.java)

        val bytes = checkNotNull(reader.openPhotoStream(PHONE)).use { it.readBytes() }

        assertThat(bytes).isEqualTo(THUMB_BYTES)
    }

    @Test
    fun `lookupByPhone rejects a hit whose number only shares the last 7 digits`() {
        val reader = readerWith(SevenDigitFalsePositiveContacts::class.java)

        assertThat(reader.lookupByPhone("+16108423232")).isNull()
    }

    @Test
    fun `lookupByPhone returns the first row whose number matches strictly`() {
        val reader = readerWith(SecondRowMatchesContacts::class.java)

        val contact = reader.lookupByPhone("+16108423232")

        assertThat(contact?.displayName).isEqualTo("Bob")
        assertThat(contact?.id).isEqualTo(2L)
    }

    private fun <T : ContentProvider> readerWith(provider: Class<T>): ContactsReader {
        Robolectric.buildContentProvider(provider).create(ContactsContract.AUTHORITY)
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return ContactsReader(ctx)
    }

    companion object {
        private const val PHONE = "+33612345678"
        private const val THUMB_PHOTO_URI = "content://com.android.contacts/contacts/1/photo"
        private const val FULL_PHOTO_URI = "content://com.android.contacts/display_photo/1"
        private val FULL_BYTES = "FULL".toByteArray()
        private val THUMB_BYTES = "THUMB".toByteArray()

        /** Round 44 — the five columns `lookupByPhone` projects, in order. */
        private val LOOKUP_COLUMNS = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
            ContactsContract.PhoneLookup.PHOTO_URI,
            ContactsContract.PhoneLookup.NUMBER,
        )
    }
}
