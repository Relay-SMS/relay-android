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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.placeholderColor
import com.filestech.sms.ui.theme.textFieldCursorColor

/**
 * Filled text field of the Relay design system: no border, surface fill, 10dp corners by
 * default ([cornerRadius] is 24dp for the composer pill), placeholder in
 * [placeholderColor] (theme-aware: the fixed tertiary failed AA on the light field),
 * cursor in [textFieldCursorColor]. Focus changes no border — the cursor colour alone
 * marks the focused field.
 *
 * The cursor deliberately does NOT use the fixed brand blue: in the dark scheme that
 * blue measures 2.16:1 on the dark field background, so a 2dp cursor in it is
 * effectively invisible. The lightened blue tried next in dark mode (`#7AA0F5`,
 * 5.59:1) still read as invisible on the phone, so dark mode now uses white;
 * light mode keeps the brand blue (`primary`), which works fine there.
 *
 * The outer [Box] centres its content vertically: without a `contentAlignment`
 * the 32dp decoration row sat top-aligned inside a 48dp pill, so the icons
 * and the text sat 8dp above the bar's centre (feedback round 5, measured on
 * the dark-mode search screenshot).
 *
 * [focusRequester] (when given) is attached to the inner text field itself, so a
 * caller can move focus into the field — e.g. the conversations search pill, which
 * requests focus the moment it slides open.
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
    focusRequester: FocusRequester? = null,
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
        contentAlignment = Alignment.Center,
    ) {
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().then(
                focusRequester?.let { Modifier.focusRequester(it) } ?: Modifier,
            ),
            enabled = enabled,
            readOnly = readOnly,
            textStyle = textStyle.copy(color = textColor),
            singleLine = singleLine,
            maxLines = maxLines,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interactionSource,
            cursorBrush = SolidColor(textFieldCursorColor(cs)),
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
 * [placeholderColor], cursor in `textFieldCursorColor` (see [RelayTextField]). The trailing
 * clear affordance appears only while [query] is non-empty.
 *
 * [focusRequester] (when given) is attached to the inner text field itself, so a
 * caller can move focus into the field when it appears.
 */
@Composable
fun RelaySearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    onClear: () -> Unit,
    clearContentDescription: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
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
        focusRequester = focusRequester,
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
