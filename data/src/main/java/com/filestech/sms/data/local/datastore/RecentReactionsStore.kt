package com.filestech.sms.data.local.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.filestech.sms.core.ext.splitGraphemeClusters
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

private val Context.recentReactionsDataStore by preferencesDataStore(name = "recent_reactions")

/**
 * Round 12 — the emojis the user most recently reacted with, most recent
 * first, for the quick-reaction bar on a bubble tap. This is UI state, not
 * a user-facing setting, so it lives in its own small DataStore file
 * instead of [AppSettings] — same `preferencesDataStore` pattern, no new
 * storage mechanism.
 *
 * Stored as one newline-joined string (`reactions.recent`): a set would
 * lose the recency order, and single emojis never contain a newline.
 * Multi-emoji reactions are split into grapheme clusters on the way in so
 * a ZWJ family / flag / skin-tone stays atomic and each emoji is tracked
 * on its own.
 */
@Singleton
class RecentReactionsStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore: DataStore<Preferences> = context.recentReactionsDataStore

    /** Most recent first; empty when nothing has been reacted with yet. */
    val flow: Flow<List<String>> = dataStore.data
        .map { prefs ->
            prefs[KEY_RECENT]?.split(DELIMITER)?.filter { it.isNotBlank() } ?: emptyList()
        }
        .catch { t ->
            Timber.w(t, "RecentReactionsStore: read failed, serving empty")
            emit(emptyList())
        }

    /**
     * Records [emoji] (one emoji, or a multi-emoji reaction string) as most
     * recently used: its grapheme clusters move to the front, deduped, and
     * the list is capped at [MAX_RECENT_REACTIONS].
     */
    suspend fun recordUsed(emoji: String) {
        val clusters = emoji.splitGraphemeClusters().filter { it.isNotBlank() }
        if (clusters.isEmpty()) return
        runCatching {
            dataStore.edit { prefs ->
                val current = prefs[KEY_RECENT]?.split(DELIMITER)
                    ?.filter { it.isNotBlank() } ?: emptyList()
                val updated = (clusters + current.filter { it !in clusters })
                    .take(MAX_RECENT_REACTIONS)
                prefs[KEY_RECENT] = updated.joinToString(DELIMITER)
            }
        }.onFailure { t ->
            Timber.w(t, "RecentReactionsStore: recordUsed failed")
        }
    }

    companion object {
        /** How many recent emojis are kept — the quick bar shows this many. */
        const val MAX_RECENT_REACTIONS = 6

        private val KEY_RECENT = stringPreferencesKey("reactions.recent")
        private const val DELIMITER = "\n"
    }
}
