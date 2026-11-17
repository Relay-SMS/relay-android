package com.filestech.sms.ui.screens.conversations

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.sync.client.ClientConnectionState
import com.filestech.sms.sync.client.ClientSyncEngine
import com.filestech.sms.sync.client.ClientSyncPhase
import com.filestech.sms.sync.client.ClientSyncUiState
import com.filestech.sms.sync.client.shouldShowSyncProgress
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.theme.brandBlue

/**
 * Client mode C2 — the sync states above the conversation list
 * (main list only, never Archived): the hub-not-default notice, the
 * sync progress row, the sync error row with Retry, the
 * choose-how-much affordance while prompting, and a quiet offline
 * line while the hub is unreachable. Hub mode renders nothing —
 * the engine's state is IDLE there.
 */
@Composable
fun ClientSyncStatesIfNeeded(
    syncUi: ClientSyncUiState,
    isClientMode: Boolean,
    archived: Boolean,
    onRetry: () -> Unit,
    onOpenLinkedDevices: () -> Unit,
) {
    if (!isClientMode || archived) return
    val connected = syncUi.connection is ClientConnectionState.Connected
    Column(modifier = Modifier.fillMaxWidth()) {
        // The hub phone isn't the default SMS app, so new messages
        // won't reach this device live. Appears and clears with the
        // hub's role (`hub_status` / `sync_started`). The fix
        // happens on the phone, so there is no button here.
        if (syncUi.hubIsDefaultSms == false && connected) {
            ClientSyncNoticeCard(text = stringResource(R.string.client_hub_not_default))
        }
        when (syncUi.phase) {
            ClientSyncPhase.Error -> ClientSyncErrorRow(
                syncUi = syncUi,
                onRetry = onRetry,
            )

            ClientSyncPhase.Prompting -> ClientSyncPromptRow(
                onClick = onOpenLinkedDevices,
            )

            ClientSyncPhase.Syncing -> {
                if (shouldShowSyncProgress(syncUi)) {
                    ClientSyncProgressRow(syncUi = syncUi)
                }
            }

            ClientSyncPhase.Idle,
            ClientSyncPhase.Live,
            -> Unit
        }
        // Linked, but the hub is not reachable right now (a drop,
        // or a reconnect in flight). A quiet line, not a banner —
        // the list below is still readable, it is just not live.
        val linkedAndSettled = syncUi.hubId != null &&
            (syncUi.phase == ClientSyncPhase.Idle || syncUi.phase == ClientSyncPhase.Live)
        if (!connected && linkedAndSettled) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Outlined.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(
                    text = stringResource(R.string.client_sync_offline),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The hub-not-default notice: the default-app banner's card, without the button. */
@Composable
private fun ClientSyncNoticeCard(text: String) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(modifier = Modifier.size(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimaryContainer,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** "Syncing messages… X of Y" + the progress bar (the desktop's sync dialog, as a row). */
@Composable
private fun ClientSyncProgressRow(syncUi: ClientSyncUiState) {
    val total = syncUi.total
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.client_sync_progress),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (total != null) {
                Text(
                    text = stringResource(R.string.client_sync_count, syncUi.imported, total),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (total != null && total > 0) {
            val fraction = (syncUi.imported.toFloat() / total).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
                color = brandBlue(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = brandBlue(),
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** The sync error row: the title, the message (or the default), and Retry. */
@Composable
private fun ClientSyncErrorRow(
    syncUi: ClientSyncUiState,
    onRetry: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val message = when {
        syncUi.errorCode == ClientSyncEngine.ERROR_NOT_CONNECTED ->
            stringResource(R.string.client_sync_error_not_connected)

        syncUi.errorMessage != null -> syncUi.errorMessage
        else -> stringResource(R.string.client_sync_error_default)
    }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.errorContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.client_sync_error_title),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onErrorContainer,
                )
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onErrorContainer,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            RelayTextButton(
                text = stringResource(R.string.action_retry),
                onClick = onRetry,
            )
        }
    }
}

/** While prompting: the affordance that opens Linked devices, where the prompt lives. */
@Composable
private fun ClientSyncPromptRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.client_sync_prompt_action),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        androidx.compose.material3.Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
    }
}
