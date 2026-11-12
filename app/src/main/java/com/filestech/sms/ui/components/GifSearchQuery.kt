package com.filestech.sms.ui.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Round 13 — the GIF tab's submitted search, shared between the picker panel
 * and [GifGrid].
 *
 * The search field lives in the picker panel (owned by the panel, with its own
 * placeholder per tab), so it cannot pass the query to [GifGrid] as a
 * parameter — the grid's signature is fixed. The panel writes the submitted
 * text here instead; the grid reads it.
 *
 * Two ways in: the panel's live search ([GifLiveSearchEffect], debounced
 * ~500 ms after the user stops typing, 2+ characters) and the explicit
 * submit (IME search action / enter key). Either way a search costs one
 * API call; the debounce keeps typing from burning the shared rate limit.
 */
object GifSearchQuery {
    private val _submittedQuery = MutableStateFlow("")
    val submittedQuery: StateFlow<String> = _submittedQuery.asStateFlow()

    /**
     * Called by the picker panel when the user submits (IME search action /
     * enter key) or when the debounced live search fires. Never call this
     * from `onValueChange` directly — that would search on every keystroke
     * and burn the shared rate limit. Submit `""` (or [clear]) to go back
     * to trending.
     */
    fun submit(query: String) {
        _submittedQuery.value = query
    }

    /** Back to trending: clears the submitted query. */
    fun clear() {
        _submittedQuery.value = ""
    }
}
