package com.filestech.sms.ui.screens.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.filestech.sms.R
import com.filestech.sms.ui.components.RelaySectionLabel
import com.filestech.sms.ui.components.RelaySettingsGroup

/**
 * Help screen, reached from Settings ("Help", right above "About").
 *
 * Feedback round 5 — the "Quick help" section moved out of About onto its
 * own screen, reformatted: one section per recipe, an old-style section
 * heading and numbered steps in `bodyLarge` with room to breathe (12dp under the
 * heading, 8dp between steps, 24dp between recipes), instead of the cramped
 * 13sp steps the About card used. Same grouped-list page base as Settings
 * and About, same slide-in transition as the thread (300 ms, see AppRoot).
 *
 * v1.26.1 (audit C4) — `isPanicDecoy` is passed without a default, on
 * purpose: a `false` default would be a permissive fallback, and this
 * screen must never name the vault or the emergency mode in a decoy
 * session. The compiler therefore requires every future entry point to
 * decide explicitly, as on [AboutScreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpScreen(onBack: () -> Unit, isPanicDecoy: Boolean) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.help_title)) },
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
        // Feedback round 10 — the recipes now use the Seca grouped-list
        // style: each title is a section label and the steps sit in a
        // single segment of a [RelaySettingsGroup] (see [HelpRecipeSection]).
        // The section label owns the gap above its header, so the outer
        // column only keeps a bottom breathing room.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp),
        ) {
            helpRecipes(isPanicDecoy).forEach { recipe ->
                HelpRecipeSection(title = recipe.title, steps = recipe.steps)
            }
        }
    }
}

/**
 * One recipe: the title as a section label and the numbered steps in a
 * single segment of a [RelaySettingsGroup]. The steps are plain text
 * lines in that one segment, not separate rows, so no divider separates
 * them. The step numbers use the same colour as the step text
 * (on-surface).
 */
@Composable
private fun HelpRecipeSection(title: String, steps: List<String>) {
    RelaySectionLabel(text = title)
    RelaySettingsGroup {
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                steps.forEachIndexed { i, step ->
                    Row(
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${i + 1}.",
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = step,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  Static data — moved verbatim from AboutScreen (feedback round 5)
// ─────────────────────────────────────────────────────────────────────────────

private data class HelpRecipe(
    val title: String,
    val steps: List<String>,
    val sensitive: Boolean = false,
)

/**
 * v1.27.13 (audit de coherence) — **meme filtre que les fonctions d'About**,
 * qui manquait ici quand l'aide vivait encore dans [AboutScreen].
 *
 * L'audit C4 de la v1.26.1 avait retire du bloc « Fonctionnalites » les entrees qui nomment le
 * coffre et le mode urgence, parce que cet ecran etait le dernier a decrire en clair ce que tout
 * le reste du mode leurre s'emploie a nier. Le bloc « Aide », vingt lignes plus bas dans le meme
 * fichier, n'avait pas ete traite : en session leurre, il affichait encore une fiche intitulee
 * « Mettre une conversation au coffre » avec son mode d'emploi complet, jusqu'a
 * « Reglages -> Securite pour configurer le coffre ».
 *
 * Une correction qui ne traite qu'une des deux listes d'un meme ecran laisse le defaut entier :
 * ce n'est pas la liste qui trahissait, c'est le sujet.
 */
@Composable
private fun helpRecipes(isPanicDecoy: Boolean): List<HelpRecipe> = listOf(
    HelpRecipe(
        title = stringResource(R.string.about_help_default_title),
        steps = listOf(
            stringResource(R.string.about_help_default_step1),
            stringResource(R.string.about_help_default_step2),
        ),
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_voice_title),
        steps = listOf(
            stringResource(R.string.about_help_voice_step1),
            stringResource(R.string.about_help_voice_step2),
            stringResource(R.string.about_help_voice_step3),
        ),
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_vault_title),
        steps = listOf(
            stringResource(R.string.about_help_vault_step1),
            stringResource(R.string.about_help_vault_step2),
            stringResource(R.string.about_help_vault_step3),
        ),
        sensitive = true,
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_block_title),
        steps = listOf(
            stringResource(R.string.about_help_block_step1),
            stringResource(R.string.about_help_block_step2),
            stringResource(R.string.about_help_block_step3),
        ),
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_translate_title),
        steps = listOf(
            stringResource(R.string.about_help_translate_step1),
            stringResource(R.string.about_help_translate_step2),
            stringResource(R.string.about_help_translate_step3),
            stringResource(R.string.about_help_translate_step4),
        ),
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_emergency_title),
        steps = listOf(
            stringResource(R.string.about_help_emergency_step1),
            stringResource(R.string.about_help_emergency_step2),
            stringResource(R.string.about_help_emergency_step3),
            stringResource(R.string.about_help_emergency_step4),
        ),
        sensitive = true,
    ),
    HelpRecipe(
        title = stringResource(R.string.about_help_update_title),
        steps = listOf(
            stringResource(R.string.about_help_update_step1),
            stringResource(R.string.about_help_update_step2),
            stringResource(R.string.about_help_update_step3),
        ),
    ),
).filterNot { isPanicDecoy && it.sensitive }
