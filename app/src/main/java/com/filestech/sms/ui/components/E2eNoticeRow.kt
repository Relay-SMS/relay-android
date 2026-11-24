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
import com.filestech.sms.e2e.memberDisplayName
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.RelayText

/**
 * One in-thread E2E notice (design §6.4): a centered system row in
 * the day separator's pill styling — a quiet pill on the surface
 * fill, 13sp secondary text — so notices read as thread furniture,
 * not as messages. Only [E2eNoticeKind.KEY_CHANGED_VERIFIED] is
 * tappable: it opens the safety-number sheet so the user can
 * re-compare the number. The group kinds (round 49, design §13)
 * are never tappable — there is no group safety sheet in v1 — and
 * resolve their member name from the notice [E2eNoticeUi.subject]
 * through [memberDisplayName] (the contact name, the number as
 * fallback); a group notice with no subject falls back to the
 * thread's [contactName] (the recorders always set a subject for
 * the member kinds, so this is a belt-and-braces path).
 */
@Composable
fun E2eNoticeRow(
    notice: E2eNoticeUi,
    contactName: String,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    memberNames: Map<String, String> = emptyMap(),
) {
    val memberName = notice.subject?.let { memberDisplayName(it, memberNames) } ?: contactName
    val text = when (notice.kind) {
        E2eNoticeKind.ACTIVATED -> stringResource(R.string.e2e_notice_activated)
        E2eNoticeKind.KEY_CHANGED -> stringResource(R.string.e2e_notice_key_changed, contactName)
        E2eNoticeKind.KEY_CHANGED_VERIFIED ->
            stringResource(R.string.e2e_notice_key_changed_verified, contactName)
        E2eNoticeKind.DEACTIVATED -> stringResource(R.string.e2e_notice_deactivated, contactName)
        E2eNoticeKind.GROUP_ACTIVATED -> stringResource(R.string.e2e_notice_group_activated)
        E2eNoticeKind.GROUP_DEACTIVATED ->
            stringResource(R.string.e2e_notice_group_deactivated, memberName)
        E2eNoticeKind.GROUP_KEY_CHANGED ->
            stringResource(R.string.e2e_notice_group_key_changed, memberName)
        E2eNoticeKind.GROUP_KEY_CHANGED_VERIFIED ->
            stringResource(R.string.e2e_notice_group_key_changed_verified, memberName)
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
 * Round 48 — the decrypt-pending placeholder: an encrypted-media
 * message whose key message has not arrived yet. The same
 * centered system row as the failed row, no lock, tapping does
 * nothing. The row flips to the decrypted bubble the moment the
 * key lands (the thread collects the key Flow) — or to the
 * failed row if the key never arrives within 24 hours.
 */
@Composable
fun E2eMediaDecryptPendingRow(modifier: Modifier = Modifier) {
    E2eSystemRow(
        text = stringResource(R.string.e2e_media_decrypt_pending),
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
