package com.filestech.sms.ui.screens.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.crashlog.CrashReportFormatter
import com.filestech.sms.crashlog.CrashReportSummary
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.security.copyToClipboardSensitive
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Error log screen, reached from Settings → Advanced.
 *
 * Lists the locally saved crash reports (date + crash type); tap
 * one to read its full text, with Copy and Share to send it to us.
 * Reports stay on the device — nothing here uploads anything, and
 * the privacy note at the top of the list says so. Clear all
 * deletes every saved report (with a confirm dialog: it cannot be
 * undone).
 *
 * Two states in one screen: the list, and one opened report. Back
 * (top bar or system) returns from the detail to the list first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ErrorLogScreen(
    onBack: () -> Unit,
    viewModel: ErrorLogViewModel = hiltViewModel(),
) {
    val reports by viewModel.reports.collectAsStateWithLifecycle()
    val selected by viewModel.selected.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showClearConfirm by remember { mutableStateOf(false) }
    val copiedText = stringResource(R.string.error_log_copied)
    val clearedText = stringResource(R.string.error_log_cleared)

    // A report may have landed while the process was dead; re-read
    // on entry so the list is never one crash behind.
    LaunchedEffect(Unit) { viewModel.refresh() }
    BackHandler(enabled = selected != null) { viewModel.clearSelection() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.error_log_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (selected != null) viewModel.clearSelection() else onBack()
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        val detail = selected
        if (detail != null) {
            ErrorLogDetail(
                report = detail,
                onCopy = {
                    context.copyToClipboardSensitive("crash-report", detail.content)
                    scope.launch { snackbarHost.showSnackbar(copiedText) }
                },
                onShare = { shareReport(context, detail.content) },
                modifier = Modifier.padding(padding),
            )
        } else {
            ErrorLogList(
                reports = reports,
                onSelect = { viewModel.select(it) },
                onClearAll = { showClearConfirm = true },
                modifier = Modifier.padding(padding),
            )
        }
    }

    if (showClearConfirm) {
        RelayDialog(
            onDismissRequest = { showClearConfirm = false },
            title = stringResource(R.string.error_log_clear_confirm_title),
            content = { Text(stringResource(R.string.error_log_clear_confirm_body)) },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        viewModel.clearAll()
                        showClearConfirm = false
                        scope.launch { snackbarHost.showSnackbar(clearedText) }
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showClearConfirm = false },
                )
            },
        )
    }
}

/** The list state: privacy note, report rows, Clear all. */
@Composable
private fun ErrorLogList(
    reports: List<CrashReportSummary>,
    onSelect: (CrashReportSummary) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // An "unexpected stop" entry (a session that died without a
    // crash) shows a readable title instead of its stable
    // crash-type identifier, which only exists to name the file.
    val unexpectedStopTitle = stringResource(R.string.error_log_unexpected_stop)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        Text(
            text = stringResource(R.string.error_log_info),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
        )
        if (reports.isEmpty()) {
            Text(
                text = stringResource(R.string.error_log_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        } else {
            RelaySettingsGroup {
                reports.forEach { report ->
                    val title = if (report.crashType == CrashReportFormatter.UNEXPECTED_STOP_TYPE) {
                        unexpectedStopTitle
                    } else {
                        report.crashType
                    }
                    item(onClick = { onSelect(report) }) {
                        RelaySettingRow(
                            title = title,
                            subtitle = formatReportDate(report.timestampMillis),
                            chevron = true,
                        )
                    }
                }
            }
            RelayDestructiveButton(
                text = stringResource(R.string.error_log_clear_all),
                onClick = onClearAll,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp),
            )
        }
    }
}

/** The detail state: one report's full text, with Copy and Share. */
@Composable
private fun ErrorLogDetail(
    report: ErrorLogViewModel.SelectedReport,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RelayTextButton(
                text = stringResource(R.string.action_copy),
                onClick = onCopy,
            )
            RelayTextButton(
                text = stringResource(R.string.error_log_share),
                onClick = onShare,
            )
        }
        Text(
            text = report.content,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = 8.dp),
        )
    }
}

/** Locale-aware date + time for a report row, e.g. "Nov 17, 2026, 10:54 PM". */
private fun formatReportDate(timestampMillis: Long): String {
    return DateFormat
        .getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.getDefault())
        .format(Date(timestampMillis))
}

/** Sends one report's text through the system share sheet. */
private fun shareReport(context: Context, content: String) {
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, content)
    }
    context.startActivity(Intent.createChooser(sendIntent, null))
}
