package com.filestech.sms.ui.screens.conversations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.brandBlue

/**
 * The first-run import progress: the count line plus the bar, in the
 * same look as the client-mode sync bar ([ClientSyncProgressRow] —
 * count line, 8 dp spacer, [LinearProgressIndicator]).
 *
 * Lives in its own file because `ConversationsScreen` sits at the
 * Detekt complexity ceiling: the count-line and determinate /
 * indeterminate branches live here, and [ImportingPlaceholder] stays
 * a dumb render that just passes the numbers through.
 *
 * - Total known (`total > 0`): "N of M" (the title already says
 *   "Importing your messages", so the bare count reads like the
 *   client bar's — it reuses `client_sync_count`) + a determinate bar.
 *   The total is counted up front, so the bar is meaningful from the
 *   very first frame — no more bare spinner while the first page lands.
 * - Total unknown: the plain "N messages imported…" line while pages
 *   land, the "Reading your existing SMS…" line before the first one,
 *   and an indeterminate bar either way.
 */
@Composable
fun ImportProgressContent(imported: Int, total: Int?) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = if (total != null && total > 0) {
                stringResource(R.string.client_sync_count, imported, total)
            } else if (imported > 0) {
                stringResource(R.string.conversations_importing_count, imported)
            } else {
                stringResource(R.string.conversations_importing_body)
            },
            style = RelayText.Preview,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (total != null && total > 0) {
            val fraction = (imported.toFloat() / total).coerceIn(0f, 1f)
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
