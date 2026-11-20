package com.filestech.sms.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.viewerChoiceDataStore by preferencesDataStore(name = "viewer_choices")

/**
 * Round 33 — the media type an attachment opens as: the remembered
 * viewer is per type, so an image viewer never captures a video
 * open. Only these three types get Relay's own picker — anything
 * else (a PDF, a vCard) keeps the plain system intent.
 */
enum class ViewerKind {
    IMAGE, VIDEO, AUDIO;

    companion object {
        /** The kind for a MIME type, or `null` when the type has no Relay picker. */
        fun fromMimeType(mimeType: String): ViewerKind? = when {
            mimeType.startsWith("image/", ignoreCase = true) -> IMAGE
            mimeType.startsWith("video/", ignoreCase = true) -> VIDEO
            mimeType.startsWith("audio/", ignoreCase = true) -> AUDIO
            else -> null
        }
    }
}

/** The remembered viewer package per kind (`null` = Relay asks every time). */
data class ViewerChoices(
    val image: String? = null,
    val video: String? = null,
    val audio: String? = null,
) {
    fun forKind(kind: ViewerKind): String? = when (kind) {
        ViewerKind.IMAGE -> image
        ViewerKind.VIDEO -> video
        ViewerKind.AUDIO -> audio
    }

    /** Whether any viewer is remembered at all. */
    val anyRemembered: Boolean get() = image != null || video != null || audio != null
}

/**
 * Round 33 — the app the user picked with "Always" in Relay's own
 * viewer picker, per media type. The system resolver cannot be
 * trusted to offer "Always" for a non-top app (it ranks by usage
 * and never promotes some apps), so Relay remembers the choice
 * itself and opens directly with the remembered package.
 *
 * Its own small DataStore file (`viewer_choices`), like
 * [RecentReactionsStore]: this is UI state, not a user-facing
 * setting in [AppSettings] — the Advanced screen only clears it.
 * The primary constructor takes the [DataStore] so each JVM test
 * gets its own file (see `SettingsRepository`); the `@Inject`
 * constructor takes the application `Context` like every other
 * store.
 */
@Singleton
class ViewerChoiceStore(
    private val dataStore: DataStore<Preferences>,
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.viewerChoiceDataStore)

    /** The remembered viewers, live; all `null` when nothing is remembered. */
    val flow: Flow<ViewerChoices> = dataStore.data
        .map { prefs ->
            ViewerChoices(
                image = prefs[KEY_IMAGE],
                video = prefs[KEY_VIDEO],
                audio = prefs[KEY_AUDIO],
            )
        }
        .catch { t ->
            Timber.w(t, "ViewerChoiceStore: read failed, serving none remembered")
            emit(ViewerChoices())
        }

    /** Remembers [packageName] as the viewer for [kind] ("Always"). */
    suspend fun remember(kind: ViewerKind, packageName: String) {
        runCatching {
            dataStore.edit { prefs -> prefs[keyFor(kind)] = packageName }
        }.onFailure { t ->
            Timber.w(t, "ViewerChoiceStore: remember failed")
        }
    }

    /** Forgets the viewer for [kind] — the picker shows again on the next open. */
    suspend fun clear(kind: ViewerKind) {
        runCatching {
            dataStore.edit { prefs -> prefs.remove(keyFor(kind)) }
        }.onFailure { t ->
            Timber.w(t, "ViewerChoiceStore: clear failed")
        }
    }

    /** Forgets every remembered viewer (Settings → Advanced). */
    suspend fun clearAll() {
        runCatching {
            dataStore.edit { prefs ->
                prefs.remove(KEY_IMAGE)
                prefs.remove(KEY_VIDEO)
                prefs.remove(KEY_AUDIO)
            }
        }.onFailure { t ->
            Timber.w(t, "ViewerChoiceStore: clearAll failed")
        }
    }

    private fun keyFor(kind: ViewerKind): Preferences.Key<String> = when (kind) {
        ViewerKind.IMAGE -> KEY_IMAGE
        ViewerKind.VIDEO -> KEY_VIDEO
        ViewerKind.AUDIO -> KEY_AUDIO
    }

    companion object {
        private val KEY_IMAGE = stringPreferencesKey("viewer.image")
        private val KEY_VIDEO = stringPreferencesKey("viewer.video")
        private val KEY_AUDIO = stringPreferencesKey("viewer.audio")
    }
}
