package com.filestech.sms.data.contacts

import android.Manifest
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.util.LruCache
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lightweight cache around the system contact provider.
 *
 * We resolve `phoneNumber → displayName` lazily; the cache is invalidated whenever
 * the provider notifies a change ([invalidate], called from the ContentObserver
 * `MainApplication` registers at startup).
 *
 * A miss is only cached while `READ_CONTACTS` is granted: with the permission
 * denied the query comes back empty for every number, and caching that "miss"
 * would keep the fallback showing after the grant until the process restarts —
 * a grant is not a provider change, so the ContentObserver never invalidates
 * it. The lookup itself still runs (and still lets a `SecurityException`
 * propagate, which the block policy needs to tell "couldn't look" apart from
 * "unknown sender"); only the negative-cache write is gated.
 *
 * **Thread-safety.** This is a Hilt `@Singleton` consumed concurrently from receiver threads
 * (SmsDeliverReceiver, MmsDownloadedReceiver), Worker threads (TelephonySyncWorker), and
 * viewModelScopes (ConversationsViewModel.refreshContactNames).
 *
 * Slice 5 adds the detailed list read ([listAllDetailed]) and the photo
 * stream ([openPhotoStream]) for contact sync — the snapshot source
 * and the avatar bytes. Both go through the same provider the UI
 * already reads; no new permission, no new dependency.
 *
 * **v1.6.1 (audit PERF-07).** Le précédent `ConcurrentHashMap` non-borné laissait le
 * cache grossir indéfiniment : chaque numéro inconnu rencontré (SMS spam, codes 2FA,
 * livraisons) s'accumulait pour la durée de vie du processus. Sur deux ans d'usage =
 * potentiellement 10 000+ entrées ≈ 3 Mo heap permanent. Remplacé par [LruCache] (qui
 * est thread-safe et déjà disponible via `android.util` — pas de dep additionnelle).
 * Bornes choisies pour rester sous ~500 KB heap dans le pire cas : 500 entrées
 * positives × ~250 B = 125 KB ; 1000 entrées négatives × 50 B = 50 KB.
 */
@Singleton
class ContactsReader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val cache = LruCache<String, Contact>(CONTACT_CACHE_MAX)
    private val negativeCache = LruCache<String, Boolean>(NEGATIVE_CACHE_MAX)

    fun lookupByPhone(rawPhone: String): Contact? {
        val key = rawPhone.normalizePhone()
        cache.get(key)?.let { return it }
        if (negativeCache.get(key) != null) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(rawPhone))
        val proj = arrayOf(
            ContactsContract.PhoneLookup._ID,
            ContactsContract.PhoneLookup.DISPLAY_NAME,
            ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
        )
        var contact: Contact? = null
        resolver.query(uri, proj, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                contact = Contact(
                    id = c.getLong(0),
                    displayName = c.getString(1),
                    phones = listOf(PhoneAddress.of(rawPhone)),
                    photoUri = c.getString(2),
                )
            }
        }
        // v1.6.1 (audit QUAL-05) — smart-cast post if(contact != null) suffit, pas
        // besoin du !! qui trompait sur l'intention.
        val resolved = contact
        if (resolved != null) {
            cache.put(key, resolved)
        } else if (hasContactsPermission()) {
            // Only a miss the provider could actually answer is worth
            // caching — see the class KDoc. With the permission denied
            // the lookup stays uncached, so the first lookup after the
            // grant re-queries instead of replaying a stale miss.
            negativeCache.put(key, true)
        }
        return resolved
    }

    /** Read all visible contacts that have at least one phone number. Used by the new-message picker. */
    fun listAll(): List<Contact> {
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.PHOTO_THUMBNAIL_URI,
        )
        val rows = mutableMapOf<Long, Contact>()
        resolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            proj,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val name = c.getString(1)
                val number = c.getString(2) ?: continue
                val photo = c.getString(3)
                val existing = rows[id]
                if (existing == null) {
                    rows[id] = Contact(id, name, listOf(PhoneAddress.of(number)), photo)
                } else {
                    rows[id] = existing.copy(phones = existing.phones + PhoneAddress.of(number))
                }
            }
        }
        return rows.values.toList()
    }

    /**
     * Slice 5 — [listAll] plus the photo-staleness version
     * ([Contact.photoUpdatedAt]) per contact. The timestamp lives on
     * the Contacts table, not the Phone view, so it cannot be
     * projected from the [listAll] query: this is a second,
     * lightweight query on `ContactsContract.Contacts.CONTENT_URI`
     * for `_ID` + `CONTACT_LAST_UPDATED_TIMESTAMP`, joined in memory
     * by contact id. The existing [listAll] is untouched — the
     * compose picker keeps using it.
     */
    fun listAllDetailed(): List<Contact> {
        val timestamps = mutableMapOf<Long, Long>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                ContactsContract.Contacts._ID,
                ContactsContract.Contacts.CONTACT_LAST_UPDATED_TIMESTAMP,
            ),
            null,
            null,
            null,
        )?.use { c ->
            while (c.moveToNext()) {
                timestamps[c.getLong(0)] = c.getLong(1)
            }
        }
        return listAll().map { contact ->
            val updatedAt = contact.id?.let { timestamps[it] }
            if (updatedAt == null) contact else contact.copy(photoUpdatedAt = updatedAt)
        }
    }

    /**
     * Slice 5 — opens the thumbnail photo stream for the contact
     * behind [address], the way the phone's own UI reads it:
     * [lookupByPhone] (the same lookup, so the desktop's avatar for
     * a number is the contact the phone would show for it), then
     * `ContentResolver.openInputStream` on the thumbnail URI. Null
     * when there is no contact, no photo, or an unreadable stream.
     * The caller closes the stream.
     */
    fun openPhotoStream(address: String): InputStream? {
        // Contacts-permission fix — the lookup throws
        // `SecurityException` when READ_CONTACTS is denied, and it
        // used to sit outside the try below: a denied permission
        // escaped this function despite its "null when unreadable"
        // contract. The lookup itself stays throwing (the block
        // policy needs the exception to tell "couldn't look" from
        // "unknown sender") — only this call site swallows it, the
        // way `setPhoto` already does for its own lookup.
        val photoUri = try {
            lookupByPhone(address)?.photoUri
        } catch (e: SecurityException) {
            Timber.d("ContactsReader: photo lookup denied (%s)", e.javaClass.simpleName)
            return null
        } ?: return null
        return try {
            resolver.openInputStream(Uri.parse(photoUri))
        } catch (e: Exception) {
            // File gone, URI won't open, permission revoked — the
            // photo is unreadable, not a crash. The log carries the
            // exception TYPE only, never its message: a provider
            // error message can embed the photo URI, and contact
            // data never reaches the log.
            Timber.d("ContactsReader: photo stream unreadable (%s)", e.javaClass.simpleName)
            null
        }
    }

    /**
     * Round 3 — writes the photo for the contact behind [address]
     * (the same [lookupByPhone] the UI uses, so the desktop's photo
     * lands on the contact the phone shows for the number). An
     * existing PHOTO row is replaced, never duplicated; a contact
     * with no photo row gets one, on its first raw contact (a Data
     * row hangs off a raw contact, not the aggregate). The provider
     * bumps `CONTACT_LAST_UPDATED_TIMESTAMP` on the write, so the
     * next contact snapshot carries the new photo version.
     * [ContactPhotoWriteResult.NotFound] when there is no contact
     * (the phone never creates one for a photo set);
     * [ContactPhotoWriteResult.NoPermission] when WRITE_CONTACTS is
     * not granted. The cache invalidates on success: the cached
     * contact carries the old photo URI.
     */
    fun setPhoto(address: String, photoBytes: ByteArray): ContactPhotoWriteResult {
        val contactId = try {
            lookupByPhone(address)?.id
        } catch (e: SecurityException) {
            // READ_CONTACTS revoked (or never granted): the lookup
            // itself throws, and the write cannot proceed either.
            // The log carries the exception TYPE only, never its
            // message: a provider error message can embed contact
            // data, and contact data never reaches the log.
            Timber.d("ContactsReader: photo lookup failed (%s)", e.javaClass.simpleName)
            return ContactPhotoWriteResult.NoPermission
        }
        return try {
            // No contact, or a contact with no raw contact to hang
            // the Data row off: nothing to attach the photo to.
            val rawContactId = contactId?.let { findRawContactId(it) }
                ?: return ContactPhotoWriteResult.NotFound
            val values = ContentValues().apply {
                put(ContactsContract.CommonDataKinds.Photo.PHOTO, photoBytes)
            }
            val updated = resolver.update(
                ContactsContract.Data.CONTENT_URI,
                values,
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(
                    contactId.toString(),
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                ),
            )
            val written = if (updated == 0) {
                values.put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                values.put(
                    ContactsContract.Data.MIMETYPE,
                    ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE,
                )
                resolver.insert(ContactsContract.Data.CONTENT_URI, values) != null
            } else {
                true
            }
            if (written) {
                invalidate()
                ContactPhotoWriteResult.Ok
            } else {
                ContactPhotoWriteResult.Failed
            }
        } catch (e: SecurityException) {
            // WRITE_CONTACTS revoked between the check and the
            // write — not-granted, never a crash.
            Timber.d("ContactsReader: photo write denied (%s)", e.javaClass.simpleName)
            ContactPhotoWriteResult.NoPermission
        } catch (e: Exception) {
            // The log carries the exception TYPE only, never its
            // message: a provider error message can embed contact
            // data, and contact data never reaches the log.
            Timber.d("ContactsReader: photo write failed (%s)", e.javaClass.simpleName)
            ContactPhotoWriteResult.Failed
        }
    }

    /** The first raw contact of a contact (the photo row hangs off a raw contact, not the aggregate). */
    private fun findRawContactId(contactId: Long): Long? {
        var rawId: Long? = null
        resolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID} = ?",
            arrayOf(contactId.toString()),
            null,
        )?.use { c ->
            if (c.moveToFirst()) rawId = c.getLong(0)
        }
        return rawId
    }

    fun invalidate() {
        cache.evictAll()
        negativeCache.evictAll()
    }

    private fun hasContactsPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED

    private companion object {
        /** Max positive entries kept in RAM ; ~500 × 250 B = 125 KB ceiling. */
        const val CONTACT_CACHE_MAX = 500

        /** Max "no contact" entries kept in RAM ; bornes le pire cas SMS spam / 2FA. */
        const val NEGATIVE_CACHE_MAX = 1000
    }
}
