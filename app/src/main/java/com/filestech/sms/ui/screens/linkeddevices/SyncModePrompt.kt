package com.filestech.sms.ui.screens.linkeddevices

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.filestech.sms.R
import com.filestech.sms.sync.SyncProtocol
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue

/**
 * Client mode — the sync-mode prompt: "How much should sync?"
 * Asked once per hub, on first connect after linking, before the
 * first `sync_request`. The desktop's `SyncModePrompt`, mirrored
 * exactly: two radio rows (Full sync / Just sync), nothing
 * pre-selected, Continue disabled until a pick, and the footer
 * pointing at Re-sync. Non-dismissible — there is no sync until
 * the user chooses, and no default to fall back to.
 */
@Composable
fun SyncModePromptDialog(
    onChooseMode: (String) -> Unit,
) {
    // Nothing pre-selected: the choice is the user's, and a
    // pre-selected default would silently sync a whole history (or
    // silently skip it) on an accidental tap.
    var selected by remember { mutableStateOf<String?>(null) }
    RelayDialog(
        onDismissRequest = {},
        title = stringResource(R.string.client_sync_mode_title),
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
        content = {
            SyncModeOptionRow(
                title = stringResource(R.string.client_sync_mode_full_title),
                body = stringResource(R.string.client_sync_mode_full_body),
                selected = selected == SyncProtocol.SyncRequest.FULL,
                onClick = { selected = SyncProtocol.SyncRequest.FULL },
            )
            SyncModeOptionRow(
                title = stringResource(R.string.client_sync_mode_new_title),
                body = stringResource(R.string.client_sync_mode_new_body),
                selected = selected == SyncProtocol.SyncRequest.NEW_ONLY,
                onClick = { selected = SyncProtocol.SyncRequest.NEW_ONLY },
            )
            Text(
                text = stringResource(R.string.client_sync_mode_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_continue),
                enabled = selected != null,
                confirm = true,
                onClick = { selected?.let(onChooseMode) },
            )
        },
    )
}

/** One option row: the picker's radio geometry (label + body hint). */
@Composable
private fun SyncModeOptionRow(
    title: String,
    body: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            colors = RadioButtonDefaults.colors(selectedColor = brandBlue()),
        )
        Spacer(modifier = Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = RelayText.MessageBody,
                color = cs.onSurface,
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
