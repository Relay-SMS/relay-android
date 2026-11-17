package com.filestech.sms.sync.client

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R

/**
 * Client mode — the no-SIM nudge card: on a SIM-less device with no
 * default-SMS role, the empty conversation list and the Linked
 * devices screen show this instead of leaving the user with an
 * empty app that cannot message. Tapping starts the link flow (the
 * mode-switch confirm, then the pairing QR) — no mode change happens
 * without the tap.
 */
@Composable
fun NoSimNudgeCard(
    onLink: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onLink,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Devices,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.client_nudge_no_sim),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = Icons.Outlined.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
