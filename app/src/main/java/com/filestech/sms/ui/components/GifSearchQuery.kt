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
 * Submit-only: the panel calls [submit] when the user submits a search (IME
 * search action / enter key) — never from `onValueChange`, never on every
 * keystroke. A search costs exactly one API call, and the beta-key rate limit
 * is shared by every user of the app. If live search is ever added, it must
 * be debounced (~500 ms after the user stops typing) before calling [submit].
 */
object GifSearchQuery {
    private val _submittedQuery = MutableStateFlow("")
    val submittedQuery: StateFlow<String> = _submittedQuery.asStateFlow()

    /**
     * Called by the picker panel's search field ONLY when the user submits
     * (IME search action / enter key). Never call this from `onValueChange` —
     * that would search on every keystroke and burn the shared rate limit.
     * Submit `""` (or [clear]) to go back to trending.
     */
    fun submit(query: String) {
        _submittedQuery.value = query
    }

    /** Back to trending: clears the submitted query. */
    fun clear() {
        _submittedQuery.value = ""
    }
}
