package com.filestech.sms.data.repository

import android.content.Context
import com.filestech.sms.core.ext.normalizePhone
import com.filestech.sms.data.local.db.dao.SyncedContactDao
import com.filestech.sms.data.local.db.entity.SYNCED_AVATARS_DIR_NAME
import com.filestech.sms.data.local.db.entity.SyncedContactEntity
import com.filestech.sms.di.IoDispatcher
import com.filestech.sms.domain.clientmode.ClientAvatarGateway
import com.filestech.sms.domain.clientmode.NoOpClientAvatarGateway
import com.filestech.sms.domain.model.Contact
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.domain.repository.ContactPhotoWriteResult
import com.filestech.sms.domain.repository.ContactRepository
import com.filestech.sms.domain.settings.AppSettings
import com.filestech.sms.domain.settings.AppSettingsSource
import com.filestech.sms.domain.settings.DeviceMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client mode C4 — the mode-aware [ContactRepository]: hub mode
 * delegates to [ContactRepositoryImpl] (the local provider); client
 * mode reads the synced-contacts cache (a SIM-less device typically
 * has an empty local provider, so without the cache every thread
 * would show raw numbers). One binding-line change; all call sites
 * (mirror display names, list/thread titles, notifier sender names,
 * compose picker) stay untouched.
 *
 * Client-mode rules:
 * - Contacts are NEVER written into the local provider (private
 *   cache only, no `WRITE_CONTACTS`); `setContactPhoto` is deferred
 *   to V2 (the `set_contact_photo` frame) and fails here.
 * - One [Contact] per synced row (per number): the compose picker
 *   shows one row per contact and picks its FIRST number, so
 *   grouping a two-number contact into one entry would hide its
 *   second number. A two-number contact appears twice, once per
 *   number.
 * - Photos are lazy (the desktop's request-on-render, ported): a
 *   contact with a photo but no cached file asks the hub for it
 *   ([ClientAvatarGateway]) while the lookup returns initials; the
 *   gateway dedupes, so a list render pass never storms the hub.
 *   The cached file is served as a `file://` photo URI (Coil loads
 *   it like the provider's thumbnail URIs).
 * - Lookups are NANP-tolerant (see [nanpEquivalentForms]): cache
 *   keys are the hub's normalized contact numbers, but message
 *   addresses arrive in whatever form the carrier used, so a
 *   contact stored nationally must still match a +1 sender — the
 *   exact-match lookup the hub's `PhoneLookup` never needed.
 */
@Singleton
class ModeAwareContactRepository @Inject constructor(
    private val local: ContactRepositoryImpl,
    private val syncedContactDao: SyncedContactDao,
    private val settings: AppSettingsSource,
    @ApplicationContext private val context: Context,
    @IoDispatcher private val io: CoroutineDispatcher,
    /**
     * Client mode C4 — the lazy avatar gateway. Default NoOp keeps
     * the hub tests compiling untouched; Hilt always provides the
     * real one (a missing binding fails the build).
     */
    private val avatarGateway: ClientAvatarGateway = NoOpClientAvatarGateway,
) : ContactRepository {

    private val avatarsDir: File get() = File(context.filesDir, SYNCED_AVATARS_DIR_NAME)

    override suspend fun lookupByPhone(rawPhone: String): Contact? = withContext(io) {
        if (!isClientMode()) {
            local.lookupByPhone(rawPhone)
        } else {
            lookupCached(rawPhone)
        }
    }

    override suspend fun hasContactsPermission(): Boolean = withContext(io) {
        // The permission gates the LOCAL provider lookup; client
        // mode never consults it (the stale-name heal no-ops in
        // client mode first), so delegating unconditionally is the
        // honest answer in both modes.
        local.hasContactsPermission()
    }

    override suspend fun listAll(): List<Contact> = withContext(io) {
        if (!isClientMode()) local.listAll() else listCached()
    }

    override suspend fun listAllDetailed(): List<Contact> = withContext(io) {
        if (!isClientMode()) local.listAllDetailed() else listCached()
    }

    override suspend fun openPhotoStream(address: String): InputStream? = withContext(io) {
        if (!isClientMode()) {
            local.openPhotoStream(address)
        } else {
            openCachedPhotoStream(address)
        }
    }

    override suspend fun setContactPhoto(
        address: String,
        photoBytes: ByteArray,
    ): ContactPhotoWriteResult = withContext(io) {
        if (!isClientMode()) {
            local.setContactPhoto(address, photoBytes)
        } else {
            // Client mode C4 — deferred to V2 (`set_contact_photo`
            // frame): a linked device never writes the local provider.
            ContactPhotoWriteResult.Failed
        }
    }

    private suspend fun isClientMode(): Boolean =
        (settings.hydratedOrNull() ?: AppSettings()).mode == DeviceMode.CLIENT

    /**
     * Client mode — the cache row for a normalized number: the exact
     * form first, then the NANP-equivalent forms ([nanpEquivalentForms]).
     * The extra reads only happen on an exact-match miss, so the
     * common case costs one query, unchanged.
     */
    private suspend fun findCachedRow(normalized: String): SyncedContactEntity? {
        for (form in nanpEquivalentForms(normalized)) {
            syncedContactDao.get(form)?.let { return it }
        }
        return null
    }

    /** Client mode — one cached contact by normalized number, or null. */
    private suspend fun lookupCached(rawPhone: String): Contact? {
        val row = findCachedRow(rawPhone.normalizePhone()) ?: return null
        val photoUri = cachedPhotoUri(row)
        if (row.hasPhoto && photoUri == null) requestAvatar(row.number)
        return toContact(row, photoUri)
    }

    /** Client mode — one [Contact] per synced row, display-name order. */
    private suspend fun listCached(): List<Contact> {
        val withPhotos = syncedContactDao.listAll().map { row -> row to cachedPhotoUri(row) }
        for ((row, photoUri) in withPhotos) {
            if (row.hasPhoto && photoUri == null) requestAvatar(row.number)
        }
        return withPhotos.map { (row, photoUri) -> toContact(row, photoUri) }
    }

    /** Client mode — the cached avatar file as a stream, or null. */
    private suspend fun openCachedPhotoStream(address: String): InputStream? {
        val row = findCachedRow(address.normalizePhone()) ?: return null
        val path = row.avatarLocalPath ?: return null
        val file = File(avatarsDir, path)
        if (!file.exists() || file.length() == 0L) return null
        return runCatching { FileInputStream(file) }.getOrNull()
    }

    /** The cached photo as a `file://` URI, or null when no usable file is cached. */
    private fun cachedPhotoUri(row: SyncedContactEntity): String? {
        val path = row.avatarLocalPath ?: return null
        val file = File(avatarsDir, path)
        if (!file.exists() || file.length() == 0L) return null
        return "file://${file.absolutePath}"
    }

    /** Asks the hub for one photo; a failure never breaks the lookup it rode on. */
    private suspend fun requestAvatar(number: String) {
        runCatching { avatarGateway.requestAvatar(number) }
            .onFailure { Timber.w(it, "ModeAwareContactRepository: avatar request failed") }
    }

    private fun toContact(row: SyncedContactEntity, photoUri: String?): Contact = Contact(
        id = null,
        displayName = row.displayName,
        phones = listOf(PhoneAddress.of(row.number)),
        photoUri = photoUri,
        photoUpdatedAt = row.photoUpdatedAt,
    )
}

/**
 * Client mode — the equivalent spellings of one normalized number,
 * exact form first: the forms [ModeAwareContactRepository] tries in
 * turn when the cache lookup misses.
 *
 * Why a fallback exists at all: cache keys are the hub's normalized
 * contact numbers (`PhoneAddress.normalized`), but message and
 * conversation addresses arrive in whatever form the carrier used —
 * a contact stored nationally ("514-555-0123") never exact-matches
 * a sender arriving as "+15145550123". The hub never sees this: its
 * `PhoneLookup` matches across country-code formats. The client's
 * exact-match cache lookup is the only place that needs the
 * tolerance, so it lives here and nowhere else.
 *
 * The rule — NANP only, by length class. Let D be the digits with a
 * leading "+" stripped:
 * - D is 10 digits with no "+" (a bare national number), or 11
 *   digits starting with "1" (with or without "+"): the number is
 *   NANP, and its three spellings — "5145550123", "+15145550123",
 *   "15145550123" — all denote the same number.
 * - Anything else has no equivalent forms: exact match only.
 *
 * The bounds that keep it from matching two different numbers:
 * - Short codes and alphanumeric senders never fall back: fewer
 *   than 10 digits (or non-digits at all, including the empty
 *   normalization of "Free"/"INFO") stay exact-only, so a 5-digit
 *   short code can never suffix-match a full number.
 * - A different country code never falls back: "+33…" is 11 digits
 *   starting with "3", not "1", so a French number never matches a
 *   NANP number even when the trailing digits coincide.
 * - A 10-digit number WITH a "+" ("+5145550123") is ambiguous — it
 *   could be another country's number (country code 51 is Peru) —
 *   so it stays exact-only; only a bare 10-digit number counts as
 *   a national form.
 *
 * Accepted edge: a bare 10-digit number is treated as a NANP
 * national number by construction — a contact from a country whose
 * national numbers are also 10 digits would match a NANP number
 * with the same digits. That is the same trade-off Android's own
 * `PhoneLookup` makes, and the numbers would have to collide
 * exactly.
 */
internal fun nanpEquivalentForms(normalized: String): List<String> {
    val digits = normalized.removePrefix("+")
    if (digits.isEmpty() || !digits.all { it.isDigit() }) return listOf(normalized)
    val national = when {
        digits.length == 10 && !normalized.startsWith("+") -> digits
        digits.length == 11 && digits[0] == '1' -> digits.substring(1)
        else -> return listOf(normalized)
    }
    return (listOf(normalized) + listOf(national, "+1$national", "1$national")).distinct()
}
