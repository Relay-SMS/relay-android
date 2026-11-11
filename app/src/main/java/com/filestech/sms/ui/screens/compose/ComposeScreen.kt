package com.filestech.sms.ui.screens.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.domain.model.PhoneAddress
import com.filestech.sms.system.settings.ouvrirLaFicheDeLApplication
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import com.filestech.sms.ui.theme.textFieldCursorColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposeScreen(
    initialAddress: String?,
    /** v1.28.3 — mode groupe : chaque ligne ajoute une puce, rien n'ouvre de fil avant « Continuer ». */
    groupe: Boolean = false,
    onBack: () -> Unit,
    onConversationCreated: (Long) -> Unit,
    viewModel: ComposeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val filtered by viewModel.filtered.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    // Group picking, entered from the "Create a group" row on the new-message
    // screen (the navigation `groupe` mode is the same picking behaviour,
    // reached from the list). `rememberSaveable` so a rotation does not drop
    // the mode mid-pick; tapping the row again leaves the mode.
    var groupPicking by rememberSaveable { mutableStateOf(false) }
    // Either group entry point: rows show a selection circle and a tap
    // toggles the recipient instead of opening the thread.
    val picking = groupe || groupPicking
    // Normalized numbers already picked, for the circles' filled state.
    val selectedNumbers = remember(state.recipients) {
        state.recipients.map { it.normalized }.toSet()
    }
    LaunchedEffect(Unit) {
        viewModel.events.collect { e ->
            when (e) {
                is ComposeViewModel.Event.ConversationCreated -> onConversationCreated(e.id)
            }
        }
    }
    // v1.28.13 — relit les contacts au retour au premier plan tant que l'accès manque : on revient
    // le plus souvent de la fiche Android où l'on vient de l'accorder.
    val cycleDeVie = LocalLifecycleOwner.current.lifecycle
    val accesRefuse by rememberUpdatedState(state.contactsRefused)
    DisposableEffect(cycleDeVie) {
        val observateur = LifecycleEventObserver { _, evenement ->
            if (evenement == Lifecycle.Event.ON_RESUME && accesRefuse) viewModel.chargerContacts()
        }
        cycleDeVie.addObserver(observateur)
        onDispose { cycleDeVie.removeObserver(observateur) }
    }

    Scaffold(
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
        topBar = {
            if (groupe) {
                ComposeGroupHeader(onBack = onBack)
            } else {
                TopAppBar(
                    title = {
                        Text(stringResource(R.string.action_new_message))
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            }
        },
    ) { padding ->
        // v1.28.3 (audit global, X-07 — mesuré sur le S9) — la liste RÉSERVE la place du clavier.
        //
        // Sans `imePadding`, la colonne gardait la hauteur de l'écran entier et le clavier en
        // recouvrait le bas ; quand les résultats tenaient dans cette hauteur, rien ne défilait,
        // et les contacts sous le clavier — le plus souvent celui qu'on cherchait, en bas de la
        // liste courte — étaient inatteignables. Le fil et la feuille de transfert le faisaient
        // déjà ; cet écran, non.
        Column(modifier = Modifier.fillMaxSize().padding(padding).imePadding()) {
            RecipientField(
                query = viewModel.searchInput,
                onQueryChange = viewModel::setQuery,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            )
            // The picked recipients live OUTSIDE the search pill, as their
            // own wrapping row directly under it: chips inside the pill
            // made the box grow taller with every pick, which read as weird.
            // The search pill stays a fixed single line; the pills row only
            // shows while there is at least one recipient.
            if (state.recipients.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    state.recipients.forEach { addr ->
                        RecipientChip(
                            address = addr.raw,
                            onRemove = { viewModel.removeRecipient(addr) },
                        )
                    }
                }
            }
            LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // "Create a group" sits above "Create contact": tapping it
                // enters group-picking mode (a selection circle appears on
                // each row and a tap toggles that contact), tapping it again
                // leaves the mode. Same [ContactActionRow] as "Create
                // contact" so both lines restyle together later. Hidden in
                // the navigation group mode, which already is group picking.
                if (!groupe) {
                    item(key = "create-group") {
                        ContactActionRow(
                            title = stringResource(R.string.action_create_group),
                            subtitle = null,
                            icon = painterResource(R.drawable.ic_group_filled),
                            active = groupPicking,
                            onClick = { groupPicking = !groupPicking },
                        )
                    }
                }
                // Entrée persistante en tête de liste : ouvre l'éditeur de contacts du
                // système sur un formulaire vierge (choix produit : toujours vierge,
                // indépendamment de la recherche saisie). Placée au-dessus des résultats
                // pour rester atteignable même quand la liste est longue.
                item(key = "create-contact") {
                    ContactActionRow(
                        title = stringResource(R.string.compose_add_contact),
                        subtitle = null,
                        icon = painterResource(R.drawable.ic_contact_add),
                        onClick = {
                            if (!ContactIntents.createContact(context)) {
                                scope.launch {
                                    snackbarHost.showError(
                                        context.getString(R.string.phone_action_no_contacts),
                                    )
                                }
                            }
                        },
                    )
                }
                // v1.28.13 (MR F-Droid !38458) — sans `READ_CONTACTS`, la liste est vide parce
                // qu'on n'a pas pu la lire, pas parce qu'il n'y a personne : l'écran le dit, et
                // mène à la fiche Android de l'application. La fiche plutôt que la boîte de
                // dialogue système : après deux refus, Android ne l'affiche plus, et la ligne ne
                // ferait alors RIEN. La liste est relue au retour, cf. l'observateur ci-dessus.
                if (state.contactsRefused) {
                    item(key = "contacts-refused") {
                        ContactActionRow(
                            title = stringResource(R.string.compose_contacts_refused_title),
                            subtitle = stringResource(R.string.compose_contacts_refused_desc),
                            icon = rememberVectorPainter(Icons.Outlined.PersonOff),
                            onClick = { ouvrirLaFicheDeLApplication(context) },
                        )
                    }
                }
                // Free-entry row: lets the user pick a raw number (or anything they typed) when
                // it doesn't match any saved contact. Audit Q-BUG-1: previously the modifier ran
                // `.let { mod -> mod.also { /* clickable */ } }` — a no-op that left the row
                // visually clickable-looking but inert, breaking the whole new-conversation flow.
                // v1.27.9 — la saisie est capturée UNE fois : la ligne affiche, teste et
                // sélectionne la même valeur. À relire `viewModel.searchInput` dans le lambda
                // `.clickable`, un tap arrivé après une frappe aurait envoyé un numéro autre
                // que celui affiché sur la ligne.
                val input = viewModel.searchInput
                // X-06 — un nom tapé n'est pas une adresse : la ligne n'apparaît que pour un
                // numéro composable, cf. [ComposeViewModel.estComposable].
                if (ComposeViewModel.estComposable(input) && filtered.none { c -> c.firstPhone?.raw == input }) {
                    item {
                        ContactRow(
                            name = input,
                            detail = stringResource(
                                if (state.recipients.isEmpty() && !groupe) R.string.compose_use_this_number
                                else R.string.compose_add_to_group
                            ),
                            photoUri = null,
                            showSelection = picking,
                            selected = selectedNumbers.contains(PhoneAddress.of(input).normalized),
                            onClick = {
                                if (picking) {
                                    viewModel.toggleRecipient(input)
                                } else {
                                    viewModel.pickRecipient(input)
                                }
                                viewModel.setQuery("")
                            },
                        )
                    }
                }
                // Section label over the contact list, like the reference
                // screen: only while there are contacts to pick from.
                if (filtered.isNotEmpty()) {
                    item(key = "select-contact-label") {
                        Text(
                            text = stringResource(R.string.compose_select_contact),
                            style = RelayText.Preview,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
                items(filtered, key = { it.id ?: it.hashCode().toLong() }) { contact ->
                    val number = contact.firstPhone?.raw.orEmpty()
                    // Display-only formatting, same as the thread header:
                    // the reference screen shows (xxx) xxx-xxxx numbers.
                    val displayNumber = number.formatPhoneNumberForDisplay()
                    ContactRow(
                        name = contact.displayName ?: displayNumber,
                        detail = displayNumber,
                        photoUri = contact.photoUri,
                        showSelection = picking,
                        selected = selectedNumbers.contains(PhoneAddress.of(number).normalized),
                        onClick = {
                            if (picking) {
                                viewModel.toggleRecipient(number)
                            } else {
                                viewModel.pickRecipient(number)
                            }
                            viewModel.setQuery("")
                        },
                        enabled = number.isNotBlank(),
                    )
                }
            }
            RelayPrimaryButton(
                text = stringResource(R.string.action_continue),
                onClick = viewModel::createConversation,
                enabled = state.recipients.isNotEmpty(),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

/**
 * Group-mode header, same geometry as the thread header: 44dp row, back chevron 22dp in
 * the brand blue, 32dp group avatar, title 17sp/600, hairline divider below.
 */
@Composable
private fun ComposeGroupHeader(onBack: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .background(cs.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onBack)
                    .semantics { role = Role.Button },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = cs.primary,
                    modifier = Modifier.size(22.dp),
                )
            }
            RelayAvatar(
                label = stringResource(R.string.action_new_group),
                size = 32.dp,
                isGroup = true,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.action_new_group),
                style = RelayText.ThreadTitle,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = cs.outline)
    }
}

/**
 * The search pill of the new-message screen: one fixed 48dp single-line pill
 * ("Contact or phone number") holding only the search input. The picked
 * recipients deliberately do NOT live here — they render as their own row
 * below the pill (see [ComposeScreen]), because chips inside the pill made
 * the box grow taller with every pick. Tapping anywhere in the pill focuses
 * the input. The input still reads [ComposeViewModel.searchInput] directly
 * so the typed value comes back in the same recomposition (see
 * [ComposeViewModel.searchInput]).
 */
@Composable
private fun RecipientField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val pillInteraction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            // Feedback round 9 — the search field matches the search and
            // composer boxes: 48dp fixed height and a 24dp pill radius, with
            // the same 16dp horizontal padding. The height constraint sits
            // BEFORE the padding: measuring the content alone rendered the
            // box 48dp + 20dp of padding = 68dp tall and the 24dp radius
            // read as semi-rounded instead of a pill. Fixed height now —
            // the field never grows.
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surfaceVariant)
            .clickable(
                interactionSource = pillInteraction,
                indication = null,
                onClick = { focusRequester.requestFocus() },
            )
            .height(48.dp)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            textStyle = RelayText.MessageBody.copy(color = cs.onSurface),
            singleLine = true,
            // Cursor in `textFieldCursorColor`, not the fixed brand blue: the
            // fixed blue is near-invisible on the dark field background
            // (2.16:1); dark mode uses white, light mode the brand blue.
            cursorBrush = SolidColor(textFieldCursorColor(cs)),
            decorationBox = { innerTextField ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            text = stringResource(R.string.compose_search_contact),
                            style = RelayText.MessageBody,
                            color = RelayColors.TextTertiary,
                            maxLines = 1,
                        )
                    }
                    innerTextField()
                }
            },
        )
    }
}

/**
 * One recipient, as a large full pill below the search pill: the address and
 * a close glyph. Tapping it removes the recipient.
 *
 * Colours match the "Set as default" button in [DefaultAppBanner] (same dark/light
 * split from the surface luminance): dark mode gets the light `#E5EAFD`
 * background with dark-navy text, light mode the brand primary with white text.
 */
@Composable
private fun RecipientChip(address: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val surface = cs.surface
    val surfaceLuma = 0.2126f * surface.red + 0.7152f * surface.green +
        0.0722f * surface.blue
    val isDark = surfaceLuma < 0.5f
    val containerColor = if (isDark) Color(0xFFE5EAFD) else cs.primary
    val contentColor = if (isDark) Color(0xFF001A40) else cs.onPrimary
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(containerColor)
            .clickable(onClick = onRemove)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = address,
                style = RelayText.MessageBody,
                color = contentColor,
                maxLines = 1,
            )
            Icon(
                Icons.Outlined.Close,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * One contact row, in the conversation-list geometry: 72dp row, 16dp horizontal inset,
 * 48dp [RelayAvatar] leading, 12dp gap, name 17sp/500 over the number 15sp/400 in the
 * secondary colour. Tapping the row picks the contact (see the call sites: in group
 * picking it toggles the recipient instead). In group picking ([showSelection]) a
 * [SelectionCircle] sits at the trailing end, filled when the number is already a
 * recipient — the old trailing +person icon is gone, the row tap is the affordance.
 */
@Composable
private fun ContactRow(
    name: String,
    detail: String,
    photoUri: String?,
    onClick: () -> Unit,
    showSelection: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick)
            .height(72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelayAvatar(label = name, size = 48.dp, customUri = photoUri)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = RelayText.ConvoName,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.size(3.dp))
            Text(
                text = detail,
                style = RelayText.Preview,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showSelection) {
            Spacer(Modifier.width(12.dp))
            SelectionCircle(selected = selected)
        }
    }
}

/**
 * Group-picking selection circle: a 24dp ring when unselected, Kristin's
 * check-mark disc when selected. The disc is tinted from the surface
 * luminance (like [RecipientChip]): dark mode gets a WHITE disc, so the
 * check cutout shows the dark row background through it — exactly her
 * reference screenshot; light mode flips to a BRAND-PRIMARY disc, so the
 * cutout shows the light background as a white check. Display-only — the
 * row tap toggles it (see [ComposeViewModel.toggleRecipient]).
 */
@Composable
private fun SelectionCircle(selected: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            val surface = cs.surface
            val surfaceLuma = 0.2126f * surface.red + 0.7152f * surface.green +
                0.0722f * surface.blue
            Icon(
                painterResource(R.drawable.ic_check_circle),
                contentDescription = null,
                tint = if (surfaceLuma < 0.5f) Color.White else cs.primary,
                modifier = Modifier.size(24.dp),
            )
        } else {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .border(2.dp, cs.onSurfaceVariant, CircleShape),
            )
        }
    }
}

/**
 * One action row (create a contact, create a group, or the contacts-permission
 * notice): same 72dp geometry as [ContactRow], with the glyph in a 48dp circle
 * on the surfaceVariant background (dark grey in dark mode, light grey in
 * light mode — the reference screen's icon discs) tinted onSurfaceVariant,
 * and an optional second line. [active] tints the glyph in the brand primary
 * (used by "Create a group" while group picking is on). The icon is a [Painter]
 * so Kristin's own drawables can be used alongside Material icons (via
 * [rememberVectorPainter]). Both action lines go through this one component
 * so they restyle together.
 */
@Composable
private fun ContactActionRow(
    title: String,
    subtitle: String?,
    icon: Painter,
    onClick: () -> Unit,
    active: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .height(72.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = RelayText.ConvoName,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Spacer(Modifier.size(3.dp))
                Text(
                    text = subtitle,
                    style = RelayText.Preview,
                    color = cs.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
