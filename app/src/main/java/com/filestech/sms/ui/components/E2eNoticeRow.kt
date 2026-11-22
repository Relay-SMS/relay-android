package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.data.local.db.entity.E2eNoticeKind
import com.filestech.sms.e2e.E2eNoticeUi
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.RelayText

/**
 * One in-thread E2E notice (design §6.4): a centered system row in
 * the day separator's pill styling — a quiet pill on the surface
 * fill, 13sp secondary text — so notices read as thread furniture,
 * not as messages. Only [E2eNoticeKind.KEY_CHANGED_VERIFIED] is
 * tappable: it opens the safety-number sheet so the user can
 * re-compare the number.
 */
@Composable
fun E2eNoticeRow(
    notice: E2eNoticeUi,
    contactName: String,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val text = when (notice.kind) {
        E2eNoticeKind.ACTIVATED -> stringResource(R.string.e2e_notice_activated)
        E2eNoticeKind.KEY_CHANGED -> stringResource(R.string.e2e_notice_key_changed, contactName)
        E2eNoticeKind.KEY_CHANGED_VERIFIED ->
            stringResource(R.string.e2e_notice_key_changed_verified, contactName)
        E2eNoticeKind.DEACTIVATED -> stringResource(R.string.e2e_notice_deactivated, contactName)
    }
    E2eSystemRow(
        text = text,
        onClick = if (notice.kind == E2eNoticeKind.KEY_CHANGED_VERIFIED) onVerify else null,
        modifier = modifier,
    )
}

/**
 * The undecryptable-message placeholder (design §6.5): the same
 * centered system row, no lock, and tapping it does nothing in v1.
 */
@Composable
fun E2eDecryptFailedRow(modifier: Modifier = Modifier) {
    E2eSystemRow(
        text = stringResource(R.string.e2e_decrypt_failed),
        onClick = null,
        modifier = modifier,
    )
}

/**
 * The shared system-row shell: a centered pill in the day
 * separator's styling (same surface, same text style, so the two
 * can never drift apart visually), tappable only when [onClick]
 * is given.
 */
@Composable
private fun E2eSystemRow(
    text: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            FixedTypeSize {
                Text(
                    text = text,
                    style = RelayText.SystemNote,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
