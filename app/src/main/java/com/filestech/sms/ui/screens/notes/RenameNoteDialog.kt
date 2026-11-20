package com.filestech.sms.ui.screens.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.domain.model.NOTE_TITLE_MAX_LENGTH
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.theme.RelayText

/**
 * Notes — the rename dialog: [RenameGroupDialog]'s shape (text
 * field + character counter + Save / Cancel), with the note strings.
 * The name is REQUIRED for a note, so Save stays disabled while the
 * field is blank — a group allows clearing its name, a note does not.
 */
@Composable
fun RenameNoteDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var saisie by rememberSaveable { mutableStateOf(current) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.note_rename_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayTextField(
                    value = saisie,
                    onValueChange = { saisie = it.take(NOTE_TITLE_MAX_LENGTH) },
                    singleLine = true,
                    placeholder = stringResource(R.string.note_rename_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "${saisie.length} / $NOTE_TITLE_MAX_LENGTH",
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { onConfirm(saisie) },
                confirm = true,
                enabled = saisie.isNotBlank(),
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
