package com.filestech.sms.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.e2e.E2eDisplayFormat
import com.filestech.sms.ui.security.copyToClipboardSensitive
import com.filestech.sms.ui.theme.brandBlue
import com.filestech.sms.ui.theme.onBrandBlue

/**
 * The safety-number verification sheet (design §5.2): the 60-digit
 * safety number for one peer, in 3 lines of 4 groups, monospace and
 * tap-to-copy, with the explainer and the "Mark as verified" /
 * "Remove verification" actions (local flag only, no confirm).
 *
 * Opened from the details-panel encryption row, a key-change
 * notice, or the Settings verified-contacts list. [address] is the
 * peer's normalized address; [contactName] is the name the thread
 * shows (title or formatted number).
 */
@Composable
fun E2eVerificationSheet(
    address: String,
    contactName: String,
    onDismissRequest: () -> Unit,
    viewModel: E2eVerificationViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(address) { viewModel.open(address) }
    RelaySheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.e2e_verify_title),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(
                text = contactName,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
            when {
                state.loading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.loadFailed -> {
                    Text(
                        text = stringResource(R.string.e2e_verify_load_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
                else -> {
                    val number = state.safetyNumber
                    if (number != null) {
                        E2eSafetyNumberBox(
                            number = number,
                            onCopy = {
                                context.copyToClipboardSensitive(
                                    label = "safety number",
                                    text = number,
                                )
                                Toast.makeText(
                                    context,
                                    R.string.toast_copied,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            },
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        Text(
                            text = stringResource(
                                R.string.e2e_verify_explainer,
                                contactName,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 16.dp),
                        )
                        if (state.verified) {
                            Row(
                                modifier = Modifier.padding(top = 16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_lock_encrypted),
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(13.dp, 16.dp),
                                )
                                Text(
                                    text = stringResource(R.string.e2e_verify_verified),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(start = 8.dp),
                                )
                            }
                            Button(
                                onClick = viewModel::removeVerification,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = brandBlue(),
                                    contentColor = onBrandBlue(),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                            ) {
                                Text(text = stringResource(R.string.e2e_verify_remove))
                            }
                        } else {
                            Button(
                                onClick = viewModel::markVerified,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = brandBlue(),
                                    contentColor = onBrandBlue(),
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 16.dp),
                            ) {
                                Text(text = stringResource(R.string.e2e_verify_mark))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * The safety number itself: monospace, 3 lines of 4 groups, on a
 * quiet surface — tapping anywhere in the box (or the copy icon)
 * copies the number.
 */
@Composable
private fun E2eSafetyNumberBox(
    number: String,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cs.surfaceVariant)
            .clickable(onClick = onCopy)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = E2eDisplayFormat.formatSafetyNumberLines(number),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.e2e_verify_copy),
                tint = cs.onSurfaceVariant,
            )
        }
    }
}
