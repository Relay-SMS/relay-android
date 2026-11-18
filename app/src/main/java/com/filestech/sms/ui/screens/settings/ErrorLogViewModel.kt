package com.filestech.sms.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.filestech.sms.crashlog.CrashReportStore
import com.filestech.sms.crashlog.CrashReportSummary
import com.filestech.sms.di.IoDispatcher
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Error log screen (Settings → Advanced) — the saved crash reports.
 *
 * Reads are small file scans on the IO dispatcher; the store never
 * throws, so there is no error state to surface — a failed read
 * just shows an empty list. The selected report's full text is
 * loaded on tap and held until the user goes back to the list.
 */
@HiltViewModel
class ErrorLogViewModel @Inject constructor(
    private val store: CrashReportStore,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    /** One report opened for reading, with its full text. */
    data class SelectedReport(val summary: CrashReportSummary, val content: String)

    private val _reports = MutableStateFlow<List<CrashReportSummary>>(emptyList())
    val reports: StateFlow<List<CrashReportSummary>> = _reports.asStateFlow()

    private val _selected = MutableStateFlow<SelectedReport?>(null)
    val selected: StateFlow<SelectedReport?> = _selected.asStateFlow()

    init {
        refresh()
    }

    /** Re-reads the report list (e.g. after returning to the screen). */
    fun refresh() {
        viewModelScope.launch(ioDispatcher) {
            _reports.value = store.listReports()
        }
    }

    /** Loads one report's full text for the detail view. */
    fun select(summary: CrashReportSummary) {
        viewModelScope.launch(ioDispatcher) {
            store.readReport(summary.id)?.let { content ->
                _selected.value = SelectedReport(summary, content)
            }
        }
    }

    /** Goes back from the detail view to the list. */
    fun clearSelection() {
        _selected.value = null
    }

    /** Deletes every saved report and returns to the (empty) list. */
    fun clearAll() {
        viewModelScope.launch(ioDispatcher) {
            store.clearAll()
            _selected.value = null
            _reports.value = emptyList()
        }
    }
}
