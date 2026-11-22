package com.filestech.sms.ui.screens.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.ui.components.E2eVerificationSheet
import com.filestech.sms.ui.components.RelayHint
import com.filestech.sms.ui.components.RelaySectionLabel
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup
import com.filestech.sms.ui.security.copyToClipboardSensitive

/**
 * Settings → Security & Privacy → Encryption (design §7.1): the
 * header explainer (exact scoping text), the informational status
 * row (NO toggle — encryption is automatic), our fingerprint
 * (grouped hex, tap to copy), the verified contacts (count +
 * expandable list; tapping one opens its safety-number sheet,
 * where unverify lives), and the vodozemac attribution.
 *
 * When the identity fails to load, the fingerprint row is
 * replaced by an "Encryption unavailable" row carrying the
 * reason, and the "Share this with a contact..." hint is hidden
 * — the screen must never look fine while encryption is dead.
 *
 * Hub-only: the row that opens this screen is hidden on a linked
 * device (a client holds no identity keys).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EncryptionScreen(
    onBack: () -> Unit,
    viewModel: EncryptionSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var verifiedExpanded by remember { mutableStateOf(false) }
    var verifyingContact by remember {
        mutableStateOf<EncryptionSettingsViewModel.VerifiedContact?>(null)
    }
    val copiedText = stringResource(R.string.toast_copied)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.e2e_encryption)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            // Header explainer — the exact §7.1 scoping text.
            Text(
                text = stringResource(R.string.e2e_settings_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
            RelaySettingsGroup {
                // Status — informational only, no toggle: encryption
                // is automatic when both people use Relay.
                item {
                    RelaySettingRow(
                        icon = Icons.Outlined.Lock,
                        title = stringResource(R.string.e2e_details_encrypted),
                        subtitle = stringResource(R.string.e2e_settings_status_subtitle),
                    )
                }
                // Our fingerprint — tap the row (or the copy icon)
                // to copy it. Absent until the keys load; when the
                // load fails, an unavailable row with the reason
                // takes its place instead of a silent gap.
                val fingerprint = state.fingerprint
                if (fingerprint != null) {
                    item(onClick = {
                        context.copyToClipboardSensitive(
                            label = "fingerprint",
                            text = fingerprint,
                        )
                        Toast.makeText(context, copiedText, Toast.LENGTH_SHORT).show()
                    }) {
                        RelaySettingRow(
                            icon = Icons.Outlined.Fingerprint,
                            title = stringResource(R.string.e2e_settings_fingerprint),
                            subtitle = fingerprint,
                            trailing = {
                                Icon(
                                    imageVector = Icons.Outlined.ContentCopy,
                                    contentDescription = stringResource(
                                        R.string.e2e_verify_copy,
                                    ),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            },
                        )
                    }
                } else if (state.fingerprintLoaded) {
                    item {
                        RelaySettingRow(
                            icon = Icons.Outlined.LockOpen,
                            title = stringResource(R.string.e2e_settings_unavailable),
                            subtitle = state.fingerprintError,
                        )
                    }
                }
            }
            // Only with a fingerprint to share: with no identity
            // loaded, the hint dangles with nothing above it.
            if (state.fingerprint != null) {
                RelayHint(text = stringResource(R.string.e2e_settings_fingerprint_hint))
            }
            RelaySectionLabel(
                text = stringResource(R.string.e2e_settings_verified_contacts),
            )
            RelaySettingsGroup {
                item(onClick = { verifiedExpanded = !verifiedExpanded }) {
                    RelaySettingRow(
                        icon = Icons.Outlined.VerifiedUser,
                        title = stringResource(R.string.e2e_settings_verified_contacts),
                        subtitle = if (state.verifiedContacts.isEmpty()) {
                            stringResource(R.string.e2e_settings_no_verified)
                        } else {
                            stringResource(
                                R.string.e2e_settings_verified_count,
                                state.verifiedContacts.size,
                            )
                        },
                        chevron = true,
                    )
                }
                if (verifiedExpanded) {
                    for (contact in state.verifiedContacts) {
                        val formattedNumber =
                            contact.address.formatPhoneNumberForDisplay()
                        item(onClick = { verifyingContact = contact }) {
                            RelaySettingRow(
                                title = contact.displayName,
                                // No subtitle when the name IS the
                                // number — it would just repeat the title.
                                subtitle = formattedNumber.takeIf {
                                    it != contact.displayName
                                },
                                chevron = true,
                            )
                        }
                    }
                }
            }
            // Attribution — the exact §7.1 wording, naming vodozemac.
            Text(
                text = stringResource(R.string.e2e_settings_attribution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp),
            )
        }
    }

    // The tapped verified contact's safety-number sheet — unverify
    // lives there ("Remove verification", no confirm).
    val verifying = verifyingContact
    if (verifying != null) {
        E2eVerificationSheet(
            address = verifying.address,
            contactName = verifying.displayName,
            onDismissRequest = { verifyingContact = null },
        )
    }
}
