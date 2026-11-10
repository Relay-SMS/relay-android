package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
 * Feedback round 4 — l'heure et le statut ont des règles INDÉPENDANTES :
 *
 *  - l'heure suit [showTimestamp] (une par rafale, sur son dernier message) ;
 *  - le statut suit [showStatus] (dernier message sortant d'une rafale).
 *
 * Avant, le statut ne s'affichait que là où l'heure s'affichait : une rafale
 * cachait donc tous les « Delivered » sauf sur le message horodaté — et ce
 * message est en général le plus récent, dont l'accusé de réception n'est pas
 * encore arrivé, si bien que le fil n'affichait que « Sent », jamais
 * « Delivered ». Un échec se dit toujours (heure + libellé), comme avant.
 *
 * Feedback round 4 — placement structurel : la ligne ne s'étire PLUS sur
 * toute la largeur (`fillMaxWidth` supprimé) — elle se dimensionne à son
 * contenu et l'appelant la cale à droite dans la bulle (`align(End)` dans la
 * `Column` de la bulle, `align(BottomEnd)` en surimpression sur une vignette).
 * C'est ce qui garantissait l'échappée hors bulle : la ligne pleine largeur
 * forçait la bulle à 320dp quand elle s'affichait, et sortait du fond dès que
 * la bulle restait étroite. [textColor] est la couleur du corps de la bulle
 * appelante (blanc sur bulle sortante texte, `onSurfaceVariant` sur bulle
 * vocale sortante sans fond…).
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
    showStatus: Boolean = false,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val label = libelleDeStatut(message)?.let { statut ->
        statut.codeOperateur?.let { stringResource(statut.res, it) } ?: stringResource(statut.res)
    }
    // Un échec se dit toujours, heure + libellé, comme avant.
    val isFailed = message.status == Message.Status.FAILED
    val showTime = showTimestamp || isFailed
    val showLabel = label != null && (showStatus || isFailed)
    if (!showTime && !showLabel) return
    val formatters = rememberChatFormatters()
    // Audit P-Q7 (v1.2.0) : l'heure formatée est mise en cache, pas recalculée à chaque recomposition.
    val time = remember(message.date) { formatters.time.format(Date(message.date)) }
    // Blanc = texte sur bulle sortante colorée : léger retrait seulement. Toute autre
    // couleur de corps (noir/blanc sur bulle entrante, secondaire sur fond clair) est
    // déjà à pleine opacité AA — l'atténuer la ferait passer sous le seuil.
    val metaColor = if (textColor == Color.White) textColor.copy(alpha = 0.85f) else textColor
    FixedTypeSize {
        Row(
            modifier = Modifier.then(modifier),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = listOfNotNull(
                    if (showTime) time else null,
                    if (showLabel) label else null,
                ).joinToString(" • "),
                color = metaColor,
                style = RelayText.BubbleMeta,
            )
        }
    }
}
