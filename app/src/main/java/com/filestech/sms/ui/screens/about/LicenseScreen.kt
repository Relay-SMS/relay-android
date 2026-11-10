package com.filestech.sms.ui.screens.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.theme.RelayColors

/**
 * License / copyright notice screen, reached from the License link on About.
 *
 * Feedback round 8 — the License link used to open the Apache license in the
 * browser. It now slides to this screen, which states Relay's own copyright
 * on its original work and the upstream attribution (see
 * THIRD_PARTY_NOTICES.md). Same grouped-list page base as Settings and
 * About, same slide-in transition as the thread (300 ms, see AppRoot).
 *
 * The notice is a legal text provided in English, so the heading and body
 * strings carry the same English text in every locale — they are not
 * translated, because a translation could change their meaning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseScreen(onBack: () -> Unit) {
    // Grouped-list page base: #F5F5F5 in light, #1B1B1B in dark — the same
    // canvas Settings and About sit on (see RelaySection). Picked from the
    // surface luminance so it follows the active scheme, dark variants
    // included.
    val pageBackground = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        RelayColors.CanvasDark
    } else {
        RelayColors.Surface
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_license)) },
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
                .padding(horizontal = 16.dp, vertical = 24.dp),
        ) {
            Text(
                text = stringResource(R.string.license_notice_heading),
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.SemiBold,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.license_notice_body),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
