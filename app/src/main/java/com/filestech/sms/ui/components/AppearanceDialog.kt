package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.BubbleColorPalette
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.onBrandBlue

/**
 * Chat colors — the per-conversation outgoing-bubble colour picker,
 * the desktop picker's mobile twin (same name, same swatch
 * presentation). The user picks one of [BubbleColorPalette.OPTIONS]
 * (a WCAG-safe palette, no free picker, so white text always
 * contrasts); BRAND_BLUE means "default" and saves as `null`.
 *
 * The contact avatar used to live here too (v1.11.0); it moved to
 * the camera badge on the details side panel's avatar, which owns
 * the gallery pick + persistable-URI flow now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AppearanceDialog(
    currentBubbleColorArgb: Int?,
    onDismiss: () -> Unit,
    onConfirm: (bubbleColorArgb: Int?) -> Unit,
) {
    val palette = BubbleColorPalette.OPTIONS
    // BRAND_BLUE en première position = sélection équivalente à reset (null
    // → bleu marque). On normalise le state local au défaut si current=null.
    var pickedColor by remember(currentBubbleColorArgb) {
        mutableStateOf(currentBubbleColorArgb?.let { Color(it) } ?: BubbleColorPalette.BRAND_BLUE)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.appearance_dialog_title)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.appearance_section_bubble_color),
                    style = MaterialTheme.typography.titleSmall,
                )
                Spacer(Modifier.height(8.dp))
                // v1.11.0 audit U3 — FlowRow pour s'adapter aux petits écrans
                // (320dp Galaxy A03s, Redmi 9A) où 8 × 36dp chips dépassaient
                // la largeur disponible dans un AlertDialog (margins 48dp).
                val pickedArgb = pickedColor.toArgb()
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    palette.forEach { option ->
                        val name = stringResource(option.nameRes)
                        val isSelected = option.color.toArgb() == pickedArgb
                        // Round 20 — the BRAND_BLUE chip means "default", and
                        // the default bubble is dynamic when dynamic colours
                        // are on, so the chip previews [brandBlue] (with its
                        // [onBrandBlue] check) instead of the fixed `#1E4FD8`.
                        // The pick itself still stores BRAND_BLUE, which the
                        // save below maps back to `null` (reset to default).
                        val isBrandDefault = option.color == BubbleColorPalette.BRAND_BLUE
                        ColorChip(
                            color = if (isBrandDefault) brandBlue() else option.color,
                            checkTint = if (isBrandDefault) onBrandBlue() else Color.White,
                            selected = isSelected,
                            contentName = name,
                            onClick = { pickedColor = option.color },
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Feedback round 7 — pill shape, non-bold label, like the other
            // large buttons app-wide.
            Button(
                onClick = {
                    // Sélection BRAND_BLUE = équivalent à reset couleur côté persistance.
                    val argbToSave = if (pickedColor.toArgb() == BubbleColorPalette.BRAND_BLUE.toArgb()) {
                        null
                    } else {
                        pickedColor.toArgb()
                    }
                    onConfirm(argbToSave)
                },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(
                    containerColor = brandBlue(),
                    contentColor = onBrandBlue(),
                ),
            ) {
                Text(
                    stringResource(R.string.action_save),
                    fontWeight = FontWeight.Normal,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, shape = RoundedCornerShape(50)) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

@Composable
private fun ColorChip(
    color: Color,
    selected: Boolean,
    contentName: String,
    onClick: () -> Unit,
    /**
     * Round 20 — tint of the check on a selected chip. White by default
     * (every palette colour is calibrated against white); the caller passes
     * [onBrandBlue] for the BRAND_BLUE chip, whose fill is the dynamic
     * primary when dynamic colours are on.
     */
    checkTint: Color = Color.White,
) {
    val borderColor = MaterialTheme.colorScheme.onSurface
    // v1.11.0 audit U1 — a11y TalkBack : un Box cliquable sans nom n'est pas
    // accessible aux malvoyants. On pose un contentDescription explicite +
    // role + selected pour que TalkBack annonce "Bleu marque, sélectionnée"
    // au lieu de "non étiqueté".
    val selectedSuffix = if (selected) {
        " " + stringResource(com.filestech.sms.R.string.appearance_color_selected)
    } else {
        ""
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .semantics {
                contentDescription = contentName + selectedSuffix
                this.selected = selected
                role = Role.RadioButton
            }
            .background(color, CircleShape)
            .then(
                if (selected) Modifier.border(
                    width = 2.dp,
                    color = borderColor,
                    shape = CircleShape,
                ) else Modifier,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                painter = painterResource(R.drawable.ic_checkmark),
                contentDescription = null,
                tint = checkTint,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
