package com.filestech.sms.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayText

/**
 * v1.3.0 — Dialog "Plus" affichée quand l'utilisateur veut une réaction emoji autre que
 * les 8 quick-pick de [EmojiReactionPickerSheet]. Un champ rempli autofocus laisse
 * l'utilisateur ouvrir son clavier emoji système (touche 🌐 ou icône emoji du clavier)
 * et choisir n'importe quel emoji. Le bouton OK est désactivé tant que la saisie est vide.
 *
 * Pas de dépendance ajoutée (Google emoji2-emojipicker rejeté — ~300 KB pour ce besoin
 * occasionnel, cohérent avec le pattern "APK fin, zéro tracker" de Relay).
 */
@Composable
fun EmojiCustomDialog(
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val cs = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    val sanitized = remember(value) { sanitizeReactionInput(value) }
    val isValidEmoji = remember(sanitized) { sanitized.isLikelyEmoji() }

    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.reaction_picker_other_title),
        // Round 13 — the emoji popup family is fully rounded (48dp), matching
        // the reaction picker sheet; the shared dialog default stays 28dp.
        shape = RoundedCornerShape(48.dp),
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(R.string.reaction_picker_other_body),
                    style = RelayText.Timestamp,
                    color = cs.onSurfaceVariant,
                )
                RelayTextField(
                    value = value,
                    onValueChange = { new ->
                        // Cap à 12 char UTF-16 — un emoji complexe (drapeau, famille ZWJ)
                        // tient typiquement en 2–8 char UTF-16. Au-delà l'utilisateur tape
                        // du texte, refusé. Note : `.trim()` à la VALIDATION, pas à chaque
                        // keystroke (sinon Compose recompose et le curseur peut sauter).
                        if (new.length <= 12) value = new
                    },
                    placeholder = stringResource(R.string.reaction_picker_other_hint),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester),
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.action_save),
                onClick = { onPicked(sanitized) },
                enabled = isValidEmoji,
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

/**
 * v1.3.0 audit Q4/F2 — normalise la saisie : trim whitespace, retire les caractères de
 * contrôle BiDi/RLO (U+202A..U+202E, U+2066..U+2069) qui pourraient inverser visuellement
 * la conversation alentour, et retire les Byte Order Marks. Pas de filtre `length` ici :
 * le champ cap déjà à 12 char UTF-16.
 */
private fun sanitizeReactionInput(input: String): String {
    if (input.isEmpty()) return ""
    return input.trim().filterNot { c ->
        val code = c.code
        // BiDi overrides + isolates + BOM. Tout le reste (emoji + ZWJ + variation selectors)
        // est préservé pour les emojis composés.
        code in 0x202A..0x202E || code in 0x2066..0x2069 || code == 0xFEFF
    }
}

/**
 * v1.3.0 audit Q4/F2 — refuse les saisies qui ne ressemblent pas à un emoji : doit contenir
 * au moins un codepoint dans les blocs emoji standards, et ne contient ni lettre/chiffre
 * ASCII ni caractères HTML/JS dangereux. Refuse aussi les chaînes composées uniquement de
 * ZWJ + variation selectors (qui rendraient un badge invisible non-retirable).
 */
private fun String.isLikelyEmoji(): Boolean {
    if (isEmpty()) return false
    if (any { it.isLetterOrDigit() || it in "<>&\"'\\" }) return false
    var hasGlyph = false
    var i = 0
    while (i < length) {
        val cp = codePointAt(i)
        i += Character.charCount(cp)
        // Codepoints "structurels" qui ne forment pas un glyphe à eux seuls.
        val isJoinerOrSelector = cp == 0x200D || cp in 0xFE00..0xFE0F
        if (isJoinerOrSelector) continue
        // Plages emoji standard Unicode 14+ : Symbols & Pictographs, Emoticons, Misc Symbols,
        // Dingbats, Supplemental Symbols, Transport & Map, Regional Indicators (drapeaux),
        // Tags (drapeaux subdivisions).
        val inEmojiBlock = cp in 0x1F300..0x1FAFF ||
            cp in 0x2600..0x27BF ||
            cp in 0x2300..0x23FF ||
            cp in 0x1F000..0x1F02F ||
            cp in 0x1F0A0..0x1F0FF ||
            cp in 0x1F100..0x1F1FF || // regional indicators
            cp in 0xE0020..0xE007F    // tags
        if (inEmojiBlock) hasGlyph = true
    }
    return hasGlyph
}
