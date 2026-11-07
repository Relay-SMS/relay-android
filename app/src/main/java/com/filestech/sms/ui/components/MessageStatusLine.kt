package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.filestech.sms.domain.model.Message
import com.filestech.sms.ui.theme.FixedTypeSize
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.util.rememberChatFormatters
import java.util.Date

/**
 * Relay — la ligne « heure • statut » ne vit PLUS sous la bulle : elle est
 * rendue en petit, en bas à droite, À L'INTÉRIEUR de la bulle, alignée sur la
 * dernière ligne de texte. Le libellé vient de [libelleDeStatut].
 *
 * Affichée si [showTimestamp] (début d'une rafale) ou si l'envoi a échoué — un échec
 * se dit toujours. [textColor] est la couleur du corps de la bulle appelante (blanc
 * sur bulle sortante texte, `onSurfaceVariant` sur bulle vocale sortante sans fond…).
 *
 * Phase 8 — contraste et taille fixe :
 *
 *  - L'heure est du méta-texte de mise en page : style [RelayText.BubbleMeta]
 *    (12sp, explicite — il échappe au coefficient « Taille du texte des messages »)
 *    dans [FixedTypeSize] (il échappe aussi à l'échelle de police d'Android).
 *    Avant, `labelSmall` (10sp) était multiplié par le coefficient, jusqu'à 21sp.
 *  - L'atténuation `alpha = 0.75f` faisait tomber le méta sous le seuil AA presque
 *    partout : blanc sur bulle sortante 4.44:1, `onSurfaceVariant` sur fond clair
 *    3.19:1 (bulle vocale sortante, pièce jointe). Sur fond de bulle coloré (texte
 *    blanc), on garde un léger retrait à 0.85f (5.26:1 sur `#1E4FD8`) ; partout
 *    ailleurs le méta prend la couleur du corps à pleine opacité — la couleur de
 *    base passe déjà (5.33:1 en clair sur fond blanc, 6.12:1 en sombre sur
 *    `#1B1B1B`).
 *  - Un échec ne passe PLUS par `error` : le rouge `#D7263D` donnait 1.34:1 sur la
 *    bulle sortante bleue, 4.09:1 sur la bulle entrante claire, 2.89:1 en sombre —
 *    illisible. Le libellé (« Échec », …) porte l'information, dans la couleur du
 *    corps à pleine opacité ; le tap-pour-réessayer reste l'action. Un MMS en échec
 *    n'y promet plus « appuyer pour réessayer » : un MMS ne se relance pas (cf.
 *    [libelleDeStatut]).
 */
@Composable
internal fun BubbleTimestamp(
    message: Message,
    showTimestamp: Boolean,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    if (!showTimestamp && message.status != Message.Status.FAILED) return
    val label = libelleDeStatut(message)?.let { statut ->
        statut.codeOperateur?.let { stringResource(statut.res, it) } ?: stringResource(statut.res)
    }
    val formatters = rememberChatFormatters()
    // Audit P-Q7 (v1.2.0) : l'heure formatée est mise en cache, pas recalculée à chaque recomposition.
    val time = remember(message.date) { formatters.time.format(Date(message.date)) }
    // Blanc = texte sur bulle sortante colorée : léger retrait seulement. Toute autre
    // couleur de corps (noir/blanc sur bulle entrante, secondaire sur fond clair) est
    // déjà à pleine opacité AA — l'atténuer la ferait passer sous le seuil.
    val metaColor = if (textColor == Color.White) textColor.copy(alpha = 0.85f) else textColor
    FixedTypeSize {
        Row(
            modifier = Modifier.fillMaxWidth().then(modifier),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (label != null) "$time • $label" else time,
                color = metaColor,
                style = RelayText.BubbleMeta,
            )
        }
    }
}
