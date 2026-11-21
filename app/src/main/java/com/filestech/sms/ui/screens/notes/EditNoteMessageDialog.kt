package com.filestech.sms.ui.screens.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField

/**
 * Notes — the edit-entry dialog (round 36 phase 2):
 * [RenameNoteDialog]'s shape (text field + Save / Cancel), with
 * the edit strings. The field is multi-line (an entry's body can
 * be long — no character counter, unlike the title), and Save
 * stays disabled while the text is blank or unchanged.
 */
@Composable
fun EditNoteMessageDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var saisie by rememberSaveable { mutableStateOf(current) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.note_edit_entry_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayTextField(
                    value = saisie,
                    onValueChange = { saisie = it },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { onConfirm(saisie) },
                confirm = true,
                enabled = saisie.isNotBlank() && saisie != current,
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
