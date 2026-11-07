package com.filestech.sms.ui.screens.recovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * Shown instead of the navigation graph when the database cannot be provisioned at all.
 *
 * Without it the app crash-loops with no explanation. On an application whose users chose it for
 * its vault and panic mode, "it does not start" reads as "I lost everything" — so the screen states
 * plainly what happened, that the messages are still on the device, and what to do next.
 *
 * Deliberately offers **no destructive action**. Wiping is available from the app's own settings
 * once it starts, and offering a one-tap "erase everything" to someone who has just been told their
 * app is broken is how data gets destroyed by panic rather than by choice.
 */
@Composable
fun DatabaseRecoveryScreen(cause: Throwable) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = RelayColors.Error,
                modifier = Modifier.size(64.dp),
            )
            Text(
                text = stringResource(R.string.db_recovery_title),
                style = RelayText.ThreadTitle,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.db_recovery_body),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.db_recovery_hint),
                style = RelayText.Preview,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // The exception message carries no message content, no phone number and no key — only
            // the database file name and byte counts (see LegacyZeroKeyRekey.Failure). Surfacing it
            // is what makes a bug report actionable.
            Text(
                text = cause.message.orEmpty(),
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // Deliberately still no action button: there is no safe action to offer here — wiping
            // is available from the app's own settings once it starts, and offering a one-tap
            // "erase everything" to someone who has just been told their app is broken is how
            // data gets destroyed by panic rather than by choice.
        }
    }
}
