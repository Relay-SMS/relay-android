package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.filestech.sms.R

/**
 * Round 32 — the schedule flow's message step: the details panel's
 * Schedule row opens this first when the composer draft is empty,
 * so scheduling never needs a draft (the desktop popup's message
 * field). The typed text rides to `scheduleSend` as a body
 * override — the composer draft is never touched.
 *
 * @param allowEmpty the composer holds pending attachments:
 * Continue stays enabled with an empty message (an MMS without a
 * caption is a legitimate scheduled send).
 * @param onConfirm receives the typed text (possibly blank when
 * [allowEmpty]); the caller advances to the date picker.
 */
@Composable
fun ScheduleMessageDialog(
    allowEmpty: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.thread_schedule_message_title),
        content = {
            RelayTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = stringResource(R.string.thread_schedule_message_hint),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_continue),
                enabled = text.isNotBlank() || allowEmpty,
                onClick = { onConfirm(text) },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = onDismiss,
            )
        },
    )
}
