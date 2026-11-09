package com.filestech.sms.ui.screens.about

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material.icons.outlined.Warning
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.filestech.sms.BuildConfig
import com.filestech.sms.R
import com.filestech.sms.ui.components.RelayRow
import com.filestech.sms.ui.components.RelaySection
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.theme.BrandBlue
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText

/**
 * About screen in the grouped-list treatment of the Relay design system: same section/row
 * primitives as Settings (canvas rows, 50dp, chevron trailing, blue leading glyphs), privacy
 * badges as quiet chips, version and credit lines as 13sp secondary text. No copy changes —
 * only the presentation moved onto the shared kit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    isPanicDecoy: Boolean,
    // Feedback round 8 — the License link opens the License screen (the
    // copyright notice), instead of the Apache license in the browser.
    onOpenLicense: () -> Unit,
) {
    val context = LocalContext.current
    var tapCount by remember { mutableStateOf(0) }

    // Grouped-list page base: #F5F5F5 in light, #1B1B1B in dark — the section rows sit on
    // the canvas (see RelaySection). Picked from the surface luminance so it follows the
    // active scheme, dark variants included.
    val pageBackground = if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) {
        RelayColors.CanvasDark
    } else {
        RelayColors.Surface
    }

    Scaffold(
        containerColor = pageBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about_title)) },
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
                .padding(vertical = 24.dp),
        ) {
            HeaderBlock(
                onCheckUpdate = {
                    safeStartActivity(
                        context,
                        Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_URL)),
                    )
                },
                onVersionTap = { tapCount++ },
                tapCount = tapCount,
            )

            RelaySection(title = stringResource(R.string.about_section_privacy)) {
                RelayRow(
                    title = stringResource(R.string.about_privacy_headline),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                )
                // Feedback round 5 — the four privacy badges ("No ads",
                // "No tracker", "No data collected", "Zero sharing") lived
                // here; they are removed, and the last two move down to the
                // "Zero ads, zero trackers" feature below.
            }

            RelaySection(title = stringResource(R.string.about_section_features)) {
                // Feedback round 8 — every row but the first draws the
                // divider above it, like the Settings cards (see [RelayRow]).
                features(isPanicDecoy).forEachIndexed { index, f ->
                    FeatureRow(icon = f.icon, label = f.label, desc = f.desc, dividerAbove = index > 0)
                }
            }

            // Feedback round 5 — the Author section lived here (name + role
            // in its own row); it duplicated the "Based on SMS Tech by
            // Patrice Haltaya" credit already in the footer below, so it
            // was removed. The footer credit is the one that matters.

            // Feedback round 5 — Quick Help moved out of About onto its own
            // Help screen, reached from Settings (see HelpScreen); this
            // section is gone from here.
            RelaySection(title = stringResource(R.string.about_security_title)) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.about_security_body),
                        style = RelayText.Preview,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            RelaySection(title = stringResource(R.string.about_permissions_title)) {
                // Feedback round 8 — every row but the first draws the
                // divider above it, like the Settings cards (see [RelayRow]).
                permissions(isPanicDecoy).forEachIndexed { index, p ->
                    RelayRow(title = p.name, subtitle = p.why, dividerAbove = index > 0)
                }
            }

            RelaySection(title = stringResource(R.string.about_section_links)) {
                LinkItem(
                    icon = Icons.Outlined.Code,
                    label = stringResource(R.string.about_source_code),
                    onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(REPO_URL))) },
                )
                // Feedback round 8 — every row but the first draws the
                // divider above it, like the Settings cards. Source code is
                // always the first row of this card, so it stays without one.
                LinkItem(
                    icon = Icons.Outlined.BugReport,
                    label = stringResource(R.string.about_report_issue),
                    onClick = {
                        // Fork: issues go to the SMS Relay repo, not to the upstream author's inbox.
                        safeStartActivity(
                            context,
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/realquink/relay/issues/new")
                            )
                        )
                    },
                    dividerAbove = true,
                )
                LinkItem(
                    icon = Icons.Outlined.Language,
                    label = stringResource(R.string.about_website),
                    supporting = WEBSITE_URL,
                    onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(WEBSITE_URL))) },
                    dividerAbove = true,
                )
                // Feedback round 8 — the License link opens the License
                // screen (Relay's copyright notice + the SMS Tech
                // attribution) instead of the Apache license in the browser,
                // and it carries no subtext now.
                LinkItem(
                    icon = Icons.Outlined.Gavel,
                    label = stringResource(R.string.about_license),
                    onClick = onOpenLicense,
                    dividerAbove = true,
                )
                // v1.28.13 — la politique et les conditions existent dans les cinq langues de
                // l'application : le lien ouvre celle de la langue AFFICHÉE, l'anglais à défaut.
                // Masqués en session leurre (revue de sécurité de la 1.28.13) : ces deux documents
                // décrivent le mode urgence et le Safety call, que le reste de cet écran tait en leurre
                // (fonctions, aide, permissions). Un agresseur ne peut pas savoir qu'ils manquent.
                val langue = LocalConfiguration.current.locales[0].language
                if (!isPanicDecoy) {
                    LinkItem(
                        icon = Icons.Outlined.PrivacyTip,
                        label = stringResource(R.string.about_privacy),
                        onClick = { ouvrirTexteJuridique(context, "PRIVACY", langue) },
                        dividerAbove = true,
                    )
                    LinkItem(
                        icon = Icons.Outlined.Description,
                        label = stringResource(R.string.about_terms),
                        onClick = { ouvrirTexteJuridique(context, "TERMS", langue) },
                        dividerAbove = true,
                    )
                }
                // 2026-10-29 — soutenir le projet : la page de dons du site, ouverte dans le navigateur
                // comme les autres liens (l'application n'a toujours pas la permission Internet).
                // Visible aussi en session leurre : une page publique de dons ne révèle rien.
                LinkItem(
                    icon = Icons.Outlined.VolunteerActivism,
                    label = stringResource(R.string.about_support),
                    supporting = stringResource(R.string.about_support_desc),
                    onClick = { safeStartActivity(context, Intent(Intent.ACTION_VIEW, urlPageDeDons(langue).toUri())) },
                    dividerAbove = true,
                )
            }

            Spacer(Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.about_credits_body, AUTHOR_NAME),
                style = RelayText.Timestamp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
            )
            // Feedback round 8 — the copyright lines are gone from here:
            // "Relay UI © 2026 Relay Messaging" and "© 2026 Patrice
            // Haltaya. Released under the Apache License 2.0." now live in
            // the License screen (reached from the License link above). The
            // "Based on SMS Tech" credit stays: it is the visible in-app
            // Apache attribution.
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Header
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun HeaderBlock(
    onCheckUpdate: () -> Unit,
    onVersionTap: () -> Unit,
    tapCount: Int,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Feedback round 5 — Relay interim logo: the white chat bubble
        // (adaptive-icon foreground vector, tail bottom-right) on a
        // brand-blue rounded tile, the same treatment as the splash. The
        // old upstream PNG (`sms_tech_icon`) is no longer used here.
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BrandBlue)
                .padding(11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_launcher_foreground),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(Modifier.size(14.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = RelayText.LargeTitle,
        )
        Spacer(Modifier.size(4.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primaryContainer)
                .clickable { onVersionTap() }
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(
                text = "v${BuildConfig.VERSION_NAME}",
                // Phase 8 — le texte vit sur `primaryContainer` : `primary` donnait 1.40:1
                // sur `#004788` en sombre (5.56:1 en clair). `onPrimaryContainer` donne
                // 7.23:1 en sombre et 14.41:1 en clair.
                color = cs.onPrimaryContainer,
                style = RelayText.Timestamp.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        if (tapCount >= 7) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = stringResource(R.string.about_easter_egg),
                style = RelayText.Timestamp,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(R.string.about_tagline_short),
            style = RelayText.Timestamp,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.size(14.dp))
        RelayTextButton(
            text = stringResource(R.string.about_check_updates),
            onClick = onCheckUpdate,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Features
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FeatureRow(icon: ImageVector, label: String, desc: String?, dividerAbove: Boolean = false) {
    RelayRow(
        title = label,
        subtitle = desc,
        subtitleTopPadding = 2.dp,
        // Feedback round 6 — the glyph centres on the title line, not on
        // the whole row, like the main Settings screen (see [RelayRow]):
        // the icons were still vertically centred.
        leadingIconTopAligned = true,
        // Feedback round 8 — the divider above every row but the first of
        // the card (see [RelayRow]).
        dividerAbove = dividerAbove,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Links (Source / Issue / Website / License / Privacy)
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun LinkItem(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    supporting: String? = null,
    dividerAbove: Boolean = false,
) {
    RelayRow(
        title = label,
        subtitle = supporting,
        subtitleTopPadding = 2.dp,
        onClick = onClick,
        // Feedback round 6 — top-aligned like the main Settings screen
        // (see [RelayRow]): the icons were still vertically centred.
        leadingIconTopAligned = true,
        // Feedback round 8 — the divider above every row but the first of
        // the card (see [RelayRow]).
        dividerAbove = dividerAbove,
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        },
        trailing = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
//  Helpers
// ─────────────────────────────────────────────────────────────────────────────

/** Audit U4: never crash if no Activity is registered for the intent. */
private fun safeStartActivity(context: android.content.Context, intent: android.content.Intent) {
    runCatching { context.startActivity(intent) }
        .onFailure { timber.log.Timber.w(it, "safeStartActivity: no handler for %s", intent.action) }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Static data
// ─────────────────────────────────────────────────────────────────────────────

private const val AUTHOR_NAME = "Patrice Haltaya"

// v1.27.10 (revue externe GitLab !38458) — le depot s'appelle `SMS-TECH`, pas `sms_tech` :
// ces deux liens rendaient 404 depuis toujours. Ce n'est pas une affaire de casse (GitHub
// l'ignore) mais de separateur, tiret contre soulignement. Seul RELEASES_URL etait juste, ce
// qui a masque le defaut : une des trois surfaces marchait.
private const val REPO_URL = "https://github.com/realquink/relay"
private const val RELEASES_URL = "https://github.com/realquink/relay/releases/latest"

// SMS Relay has no standalone website yet; the repo is the project page for now.
private const val WEBSITE_URL = "https://github.com/realquink/relay"
private const val LEGAL_BASE_URL = "https://github.com/realquink/relay/blob/main/"

/** Langues dans lesquelles PRIVACY et TERMS ont une traduction ; l'anglais est `PRIVACY.md`. */
private val LANGUES_DES_TEXTES_JURIDIQUES = setOf("fr", "de", "it", "es")

/**
 * v1.28.13 — `PRIVACY.md` / `TERMS.md` en anglais, `PRIVACY.fr.md`… pour les autres langues de
 * l'application. Les versions anglaise et française font foi ; les autres sont des traductions.
 */
internal fun urlTexteJuridique(document: String, langue: String): String {
    val suffixe = if (langue in LANGUES_DES_TEXTES_JURIDIQUES) ".$langue" else ""
    return "$LEGAL_BASE_URL$document$suffixe.md"
}

/** Langues dans lesquelles files-tech.com publie sa page de dons ; l'anglais à défaut. */
private val LANGUES_DE_LA_PAGE_DE_DONS = mapOf(
    "fr" to "https://www.files-tech.com/soutenir.php",
    "de" to "https://www.files-tech.com/de/soutenir.php",
)
private const val PAGE_DE_DONS_EN = "https://www.files-tech.com/en/soutenir.php"

/** 2026-10-29 — la page de dons dans la langue affichée si le site la publie, l'anglaise sinon. */
internal fun urlPageDeDons(langue: String): String = LANGUES_DE_LA_PAGE_DE_DONS[langue] ?: PAGE_DE_DONS_EN

private fun ouvrirTexteJuridique(context: android.content.Context, document: String, langue: String) {
    safeStartActivity(context, Intent(Intent.ACTION_VIEW, Uri.parse(urlTexteJuridique(document, langue))))
}

/**
 * @param sensitive v1.26.1 (audit C4) — la fonctionnalité ne doit PAS être nommée en session
 *   leurre. Marquer plutôt que filtrer à la main : une fonctionnalité de sécurité ajoutée
 *   plus tard hérite du bon comportement en posant un seul drapeau, au lieu d'être oubliée
 *   dans une liste d'exclusion tenue ailleurs.
 */
private data class Feature(
    val icon: ImageVector,
    val label: String,
    // Feedback round 5 — `null` = title-only row (the "No data
    // collected" / "Zero sharing" rows below have no description).
    val desc: String? = null,
    val sensitive: Boolean = false,
)

/**
 * v1.26.1 (audit C4) — en session leurre, on retire les fonctionnalités marquées `sensitive`.
 *
 * Cet écran était le dernier à décrire en clair ce que tout le reste du mode leurre s'emploie
 * à nier. Le texte du code panique dit mot pour mot « Second code qui ouvre l'app en mode
 * leurre — coffre invisible et inaccessible » : un agresseur qui ouvrait À propos apprenait
 * donc à la fois qu'un coffre existe et qu'un second code le lui cache. Le leurre désignait
 * ce qu'il cachait.
 *
 * On filtre les entrées plutôt que de masquer l'écran entier : une application SMS ordinaire
 * a un écran « À propos », et le faire disparaître serait en soi une anomalie visible.
 */
@androidx.compose.runtime.Composable
private fun features(isPanicDecoy: Boolean): List<Feature> = listOf(
    Feature(Icons.Outlined.ChatBubbleOutline, stringResource(R.string.about_feat_sms_label), stringResource(R.string.about_feat_sms_desc)),
    Feature(Icons.Outlined.Mic, stringResource(R.string.about_feat_voice_label), stringResource(R.string.about_feat_voice_desc)),
    Feature(
        Icons.Outlined.Security,
        stringResource(R.string.about_feat_vault_label),
        stringResource(R.string.about_feat_vault_desc),
        sensitive = true,
    ),
    Feature(
        Icons.Outlined.Shield,
        stringResource(R.string.about_feat_panic_label),
        stringResource(R.string.about_feat_panic_desc),
        sensitive = true,
    ),
    Feature(
        Icons.Outlined.Warning,
        stringResource(R.string.about_feat_emergency_label),
        stringResource(R.string.about_feat_emergency_desc),
        sensitive = true,
    ),
    Feature(Icons.Outlined.Fingerprint, stringResource(R.string.about_feat_biometric_label), stringResource(R.string.about_feat_biometric_desc)),
    Feature(Icons.AutoMirrored.Outlined.Reply, stringResource(R.string.about_feat_reply_label), stringResource(R.string.about_feat_reply_desc)),
    Feature(Icons.Outlined.Translate, stringResource(R.string.about_feat_translate_label), stringResource(R.string.about_feat_translate_desc)),
    Feature(Icons.Outlined.Block, stringResource(R.string.about_feat_block_label), stringResource(R.string.about_feat_block_desc)),
    Feature(Icons.Outlined.Search, stringResource(R.string.about_feat_search_label), stringResource(R.string.about_feat_search_desc)),
    Feature(Icons.Outlined.PictureAsPdf, stringResource(R.string.about_feat_pdf_label), stringResource(R.string.about_feat_pdf_desc)),
    Feature(Icons.Outlined.Backup, stringResource(R.string.about_feat_backup_label), stringResource(R.string.about_feat_backup_desc)),
    Feature(Icons.Outlined.DarkMode, stringResource(R.string.about_feat_themes_label), stringResource(R.string.about_feat_themes_desc)),
    Feature(Icons.Outlined.GraphicEq, stringResource(R.string.about_feat_ui_label), stringResource(R.string.about_feat_ui_desc)),
    Feature(Icons.Outlined.QuestionAnswer, stringResource(R.string.about_feat_noads_label), stringResource(R.string.about_feat_noads_desc)),
    // Feedback round 5 — the two privacy claims moved here from the badges
    // at the top of this screen: title-only rows under "Zero ads, zero
    // trackers", same style as the other features.
    Feature(Icons.Outlined.Security, stringResource(R.string.about_badge_no_collection)),
    Feature(Icons.Outlined.PrivacyTip, stringResource(R.string.about_badge_no_sharing)),
).filterNot { isPanicDecoy && it.sensitive }

/**
 * @property sensitive v1.28.13 — la ligne nomme le mode urgence : masquée en session leurre, comme
 *   les fonctions et l'aide du même écran (cf. [features], [HelpScreen]).
 */
private data class Permission(val name: String, val why: String, val sensitive: Boolean = false)

/**
 * v1.25.3 (audit H20) — les justificatifs de permissions étaient des littéraux **anglais** dans
 * le corps de l'écran : un utilisateur francophone lisait « Required for any SMS app. » au milieu
 * d'une page en français. Même patron de factory `@Composable` que [features] et [HelpScreen].
 *
 * Les noms de permissions restent des littéraux : ce sont les identifiants Android exacts, et les
 * traduire les rendrait invérifiables face à l'écran système des autorisations.
 */
@androidx.compose.runtime.Composable
private fun permissions(isPanicDecoy: Boolean): List<Permission> = listOf(
    Permission("SEND_SMS / RECEIVE_SMS / READ_SMS / WRITE_SMS", stringResource(R.string.about_perm_sms)),
    Permission("RECEIVE_MMS / RECEIVE_WAP_PUSH", stringResource(R.string.about_perm_mms)),
    Permission("READ_CONTACTS", stringResource(R.string.about_perm_contacts)),
    Permission("READ_PHONE_STATE / READ_PHONE_NUMBERS", stringResource(R.string.about_perm_phone_state)),
    Permission("POST_NOTIFICATIONS", stringResource(R.string.about_perm_notifications)),
    Permission("USE_BIOMETRIC", stringResource(R.string.about_perm_biometric)),
    // v1.3.5 G3 + audit F1 — SCHEDULE_EXACT_ALARM retiré du manifest (envoi planifié via
    // WorkManager.enqueueUniqueWork, pas AlarmManager.setExact*).
    // v1.28.13 — INTERNET retirée du manifeste : le MMS passe par le service MMS d'Android.
    Permission("RECORD_AUDIO", stringResource(R.string.about_perm_record_audio)),
    // v1.28.13 — les deux permissions « dangereuses » du mode urgence manquaient à cette liste,
    // intitulée « Permissions utilisées » : précisément les plus sensibles. Masquées en leurre.
    Permission(
        "ACCESS_FINE_LOCATION / ACCESS_COARSE_LOCATION",
        stringResource(R.string.about_perm_location),
        sensitive = true,
    ),
    Permission("CALL_PHONE", stringResource(R.string.about_perm_call_phone), sensitive = true),
    Permission("FOREGROUND_SERVICE", stringResource(R.string.about_perm_foreground_service)),
).filterNot { isPanicDecoy && it.sensitive }
