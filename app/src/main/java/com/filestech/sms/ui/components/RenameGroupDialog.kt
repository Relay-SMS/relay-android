package com.filestech.sms.ui.components

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
import com.filestech.sms.domain.model.GroupName
import com.filestech.sms.ui.theme.RelayText

/**
 * v1.28.3 — nommer ou renommer un groupe. Le même dialogue sert au menu du fil et à l'appui
 * long dans la liste : une seule surface, une seule règle ([GroupName.normalize]).
 *
 * Le texte d'aide dit ce que l'utilisateur ne peut pas deviner : le nom reste sur ce téléphone.
 * Sans lui, « Pat voit toujours mon numéro » passerait pour un défaut.
 *
 * @param current le nom choisi actuel, `null` s'il n'y en a pas.
 * @param onConfirm reçoit la saisie brute ; l'appelant la normalise. Vide = retirer le nom.
 */
@Composable
fun RenameGroupDialog(
    current: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var saisie by rememberSaveable { mutableStateOf(current.orEmpty()) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.rename_group_title),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RelayTextField(
                    value = saisie,
                    onValueChange = { saisie = it.take(GroupName.MAX) },
                    singleLine = true,
                    placeholder = stringResource(R.string.rename_group_placeholder),
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        text = "${saisie.length} / ${GroupName.MAX}",
                        style = RelayText.Timestamp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.rename_group_hint),
                    style = RelayText.Timestamp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { onConfirm(saisie) },
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
