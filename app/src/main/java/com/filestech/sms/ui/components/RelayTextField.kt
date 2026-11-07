package com.filestech.sms.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.placeholderColor

/**
 * Filled text field of the Relay design system: no border, surface fill, 10dp corners by
 * default ([cornerRadius] is 18dp for the composer pill), placeholder in
 * [placeholderColor] (theme-aware: the fixed tertiary failed AA on the light field),
 * cursor in [RelayColors.Blue]. Focus changes no border — the cursor colour alone
 * marks the focused field.
 */
@Composable
fun RelayTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    cornerRadius: Dp = 10.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = RelayText.MessageBody,
) {
    val cs = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val textColor = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.38f)
    // Phase 8 — le tertiaire fixe `#9A9A9A` donnait 2.58:1 sur le fond de champ clair ;
    // voir [placeholderColor]. L'alpha 0.38f du champ désactivé est conservé (texte
    // inactif, hors champ WCAG).
    val placeholderBase = placeholderColor(cs)
    val placeholderColor = placeholderBase.copy(alpha = if (enabled) 1f else 0.38f)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(cs.surfaceVariant)
            .padding(contentPadding),
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textColor),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(RelayColors.Blue),
            decorationBox = { innerTextField ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    leadingIcon?.invoke()
                    Box(modifier = Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(
                                text = placeholder,
                                style = textStyle,
                                color = placeholderColor,
                                maxLines = if (singleLine) 1 else Int.MAX_VALUE,
                            )
                        }
                        innerTextField()
                    }
                    trailingIcon?.invoke()
                }
            },
        )
    }
}

/**
 * Filled search field: 36dp high, 10dp corners, 16dp magnifier leading, placeholder in
 * [placeholderColor], blue cursor. The trailing clear affordance appears only while
 * [query] is non-empty.
 */
@Composable
fun RelaySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    RelayTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.height(36.dp),
        placeholder = placeholder,
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        cornerRadius = 10.dp,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.Search,
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClear)
                        .semantics {
                            contentDescription = clearContentDescription
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        },
    )
}
