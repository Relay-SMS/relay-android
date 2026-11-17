package com.filestech.sms.data.local.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Client mode — the avatar cache directory under `filesDir`: the
 * downloaded contact photos live here, one file per number. Shared
 * by the contact sync (which writes them) and the mode-aware
 * `ContactRepository` (which serves them as `photoUri`).
 */
const val SYNCED_AVATARS_DIR_NAME = "synced_avatars"

/**
 * Client mode — one row per synced contact number: the hub's address
 * book as this device knows it. A SIM-less device typically has an
 * empty local contacts provider, so without this cache every thread
 * would show raw numbers.
 *
 * The number is the join key, in the hub's normalized form. Contacts
 * are never written into the local provider (that would pollute the
 * user's address book and needs `WRITE_CONTACTS`); this cache is
 * private to the app.
 *
 * C4 fills the table from the `contacts` snapshot + live deltas
 * (`ClientContactSync`); C2 created it as schema only.
 */
@Entity(tableName = "synced_contacts")
data class SyncedContactEntity(
    /** Normalized phone number — the join key. */
    @PrimaryKey
    @ColumnInfo(name = "number")
    val number: String,
    /** Display name from the hub's address book. */
    @ColumnInfo(name = "display_name") val displayName: String,
    /** Whether the contact has a photo on the hub. */
    @ColumnInfo(name = "has_photo") val hasPhoto: Boolean,
    /** Photo-staleness version (`CONTACT_LAST_UPDATED_TIMESTAMP`), when [hasPhoto]. */
    @ColumnInfo(name = "photo_updated_at") val photoUpdatedAt: Long? = null,
    /** Relative path of the downloaded avatar under `filesDir/synced_avatars/`. */
    @ColumnInfo(name = "avatar_local_path") val avatarLocalPath: String? = null,
    /** Local avatar choice (a photo picked on this device), if any. */
    @ColumnInfo(name = "local_avatar") val localAvatar: String? = null,
    /** The hub photo version the local choice was based on. */
    @ColumnInfo(name = "local_avatar_baseline") val localAvatarBaseline: String? = null,
)
