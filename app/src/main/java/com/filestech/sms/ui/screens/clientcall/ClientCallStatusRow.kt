package com.filestech.sms.ui.screens.clientcall

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.sync.client.ClientCallPhase

/**
 * Client mode — tablet calls: the outgoing dial state, floating
 * over the top of the thread it belongs to (beside the pinned
 * banner's spot): "Calling…" while the dial is in flight, "On call"
 * once the phone reports the call off-hook. A quiet row, not a
 * banner — the call is on the phone, and the thread stays usable
 * underneath. The caller wraps it in `AnimatedVisibility` and only
 * shows it for a call whose number matches this thread.
 */
@Composable
fun ClientCallStatusRow(phase: ClientCallPhase) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.weight(1f))
        Icon(
            painter = painterResource(R.drawable.ic_call_solid),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(
                if (phase == ClientCallPhase.DIALING) {
                    R.string.client_call_dialing
                } else {
                    R.string.client_call_active
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
    }
}
