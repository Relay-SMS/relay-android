package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * Signal-style — la ligne « heure • statut » ne vit PLUS sous la bulle : elle est
 * rendue en petit, en bas à droite, À L'INTÉRIEUR de la bulle, alignée sur la
 * dernière ligne de texte. Le libellé vient de [libelleDeStatut].
 *
 * Affichée si [showTimestamp] (début d'une rafale) ou si l'envoi a échoué — un échec
 * se dit toujours. [textColor] est la couleur du corps de la bulle appelante (blanc
 * sur bulle sortante texte, `onSurfaceVariant` sur bulle vocale sortante sans fond…),
 * atténuée ici sauf en cas d'échec, où la couleur d'erreur reste pleine pour que
 * l'échec se voie. Un MMS en échec n'y promet plus « appuyer pour réessayer » : un
 * MMS ne se relance pas (cf. [libelleDeStatut]).
 */
@Composable
internal fun BubbleTimestamp(
    message: Message,
    showTimestamp: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!showTimestamp && message.status != Message.Status.FAILED) return
    val cs = MaterialTheme.colorScheme
    val label = libelleDeStatut(message)?.let { statut ->
        statut.codeOperateur?.let { stringResource(statut.res, it) } ?: stringResource(statut.res)
    }
    val formatters = rememberChatFormatters()
    // Audit P-Q7 (v1.2.0) : l'heure formatée est mise en cache, pas recalculée à chaque recomposition.
    val time = remember(message.date) { formatters.time.format(Date(message.date)) }
    Row(
        modifier = Modifier.fillMaxWidth().then(modifier),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (label != null) "$time • $label" else time,
            color = if (message.status == Message.Status.FAILED) {
                cs.error
            } else {
                textColor.copy(alpha = 0.75f)
            },
            style = MaterialTheme.typography.labelSmall,
        )
    }
}
