package com.filestech.sms.ui.screens.conversations

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.ui.components.ContactIntents
import com.filestech.sms.ui.components.ConversationRow
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.RelaySheet
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.components.showError
import com.filestech.sms.ui.theme.RelayColors
import com.filestech.sms.ui.theme.RelayText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/** Delay before retrying the search focus request (see the `LaunchedEffect` below). */
private const val SEARCH_FOCUS_RETRY_MS: Long = 100L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationsScreen(
    archived: Boolean = false,
    onOpenThread: (Long) -> Unit,
    onCompose: () -> Unit,
    onComposeGroup: () -> Unit = {},
    onOpenSettings: () -> Unit,
    onOpenVault: () -> Unit,
    onOpenArchived: () -> Unit,
    onOpenBlocked: () -> Unit,
    // Feedback round 8 — Scheduled messages moved here from Settings: it
    // lives in the 3-dot menu under "New group".
    onOpenScheduledMessages: () -> Unit = {},
    onBack: (() -> Unit)? = null,
    viewModel: ConversationsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // v1.26.1 (audit H18) — l'etat leurre vient du VERROU, pas du flux agrege : ce dernier
    // resservait sa valeur en cache (`isPanicDecoy = false`) le temps de redemarrer, ce qui
    // faisait apparaitre l'icone du Coffre en session leurre.
    val isPanicDecoy by viewModel.isPanicDecoy.collectAsStateWithLifecycle()
    val messageHits by viewModel.messageHits.collectAsStateWithLifecycle()
    val contactPhotos by viewModel.contactPhotos.collectAsStateWithLifecycle()
    val cs = MaterialTheme.colorScheme

    val defaultAppLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { viewModel.refreshDefaultStatus() }

    // Android still treats SMS perms as runtime even for the default app. Request the bundle
    // once we become default; the running TelephonySyncManager picks up new permissions on its
    // next observer fire (or via the periodic worker, whichever comes first).
    val context = androidx.compose.ui.platform.LocalContext.current
    val smsPermsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.requestSyncNow() }
    androidx.compose.runtime.LaunchedEffect(state.isDefaultSmsApp) {
        if (state.isDefaultSmsApp) {
            val needed = listOf(
                android.Manifest.permission.READ_SMS,
                android.Manifest.permission.RECEIVE_SMS,
                android.Manifest.permission.SEND_SMS,
                android.Manifest.permission.RECEIVE_MMS,
                android.Manifest.permission.READ_CONTACTS,
            ).filter {
                androidx.core.content.ContextCompat.checkSelfPermission(context, it) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            }
            if (needed.isNotEmpty()) {
                smsPermsLauncher.launch(needed.toTypedArray())
            } else {
                // v1.8.0 (fix bug S9 fresh install) — permissions déjà toutes
                // accordées (cas du `GRANTED_BY_ROLE` automatique Android qui
                // marche sur Pixel mais aussi sur Samsung One UI quand le rôle
                // SMS-default vient juste d'être accordé). Sans ce `else`, le
                // `smsPermsLauncher` n'était jamais déclenché et donc
                // `requestSyncNow()` n'était JAMAIS appelé → l'utilisateur
                // devait fermer/rouvrir l'app manuellement (au prochain cold-
                // start, `MainApplication.onCreate` re-tentait `start()` avec
                // `READ_SMS` désormais granted, et l'import démarrait enfin).
                //
                // En appelant `requestSyncNow()` directement ici, l'import
                // démarre dans la foulée du grant, sans nécessiter un 2ème
                // lancement de l'app. Mirror blocklist + filter inclus via
                // `TelephonySyncManager.runSync` (cf. v1.8.0 fix).
                viewModel.requestSyncNow()
            }
        }
    }

    // Re-evaluate the default-SMS-app banner whenever the screen comes back to foreground.
    // Covers the case where the user sets the default from Android Settings outside our app.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDefaultStatus()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var overflowOpen by remember { mutableStateOf(false) }
    // Round 4 — search opens on demand from the magnifier in the top bar instead of
    // living permanently under it. While it is open the list is a flat filtered list:
    // the Pinned/Chats section headers are hidden (see the LazyColumn below).
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    // Closes the pill, clears the query and drops focus (which hides the keyboard).
    // Shared by the pill's X button and by back.
    val closeSearch: () -> Unit = {
        searchOpen = false
        viewModel.clearQuery()
        focusManager.clearFocus()
    }
    androidx.compose.runtime.LaunchedEffect(searchOpen) {
        if (searchOpen) {
            // Feedback round 5 — the pill slides in: a request landing before
            // the field's focus node is attached is silently lost, leaving the
            // field unfocused with no cursor and no keyboard. Retry once after
            // the enter animation so opening search never strands it.
            val focused = runCatching { searchFocusRequester.requestFocus() }
                .getOrDefault(false)
            if (!focused) {
                delay(SEARCH_FOCUS_RETRY_MS)
                runCatching { searchFocusRequester.requestFocus() }
            }
        }
    }
    // Back closes the search pill before it leaves the screen. Registered before the
    // selection-mode handler below, so exiting selection still wins when both apply.
    androidx.activity.compose.BackHandler(enabled = searchOpen) { closeSearch() }
    // v1.8.0 — dialog de confirmation pour "Tout marquer comme lu".
    var showMarkAllReadConfirm by remember { mutableStateOf(false) }

    // v1.25.3 — état des deux actions propres aux conversations bloquées, qui restent
    // désormais dans la liste : suppression définitive et feuille Débloquer/Supprimer.
    var pendingDeleteBlocked by remember {
        mutableStateOf<com.filestech.sms.domain.model.Conversation?>(null)
    }
    var blockedActionsFor by remember {
        mutableStateOf<com.filestech.sms.domain.model.Conversation?>(null)
    }
    // v1.11.0 — Snackbar feedback pour les déplacements Vault. Utilise
    // SmsTechSnackbarHost (bleu marque succès / rouge erreur).
    val snackbarHost = remember { androidx.compose.material3.SnackbarHostState() }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // v1.25.3 (audit H16) — résolue ici plutôt que via `ctx.getString` dans le collecteur :
    // `LocalContextGetResourceValueCall` rate les changements de configuration (langue, thème)
    // parce que le `Context` capturé n'est pas relu à la recomposition.
    // v1.28.9 — même règle pour les deux messages de suppression conservée ; le choix du texte vit
    // dans [MessagesDErreur], l'écran étant au seuil de complexité de detekt.
    val messagesDErreur = MessagesDErreur(
        blocageEchoue = stringResource(R.string.snack_block_failed),
        suppressionCopieSysteme = stringResource(R.string.conversations_delete_vault_kept_system),
        suppressionLocale = stringResource(R.string.conversations_delete_kept_local),
    )

    // Le message partiel porte des arguments connus seulement à l'exécution : on hisse le
    // *format* ici (même raison que ci-dessus) et on l'applique dans le collecteur.
    val blockPartialFormat = stringResource(R.string.snack_block_partial)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ConversationsViewModel.Event.MovedToVault -> {
                    val msg = if (event.count <= 1) ctx.getString(R.string.vault_move_in_done)
                    else ctx.resources.getQuantityString(
                        R.plurals.vault_bulk_move_in_done, event.count, event.count,
                    )
                    snackbarHost.showSnackbar(msg)
                }
                is ConversationsViewModel.Event.MoveToVaultFailed ->
                    snackbarHost.showError(ctx.getString(R.string.vault_move_in_failed))
                is ConversationsViewModel.Event.Erreur ->
                    snackbarHost.showError(messagesDErreur.pour(event))
                is ConversationsViewModel.Event.BlockPartial ->
                    snackbarHost.showError(
                        String.format(
                            java.util.Locale.getDefault(),
                            blockPartialFormat,
                            event.blocked,
                            event.total,
                        ),
                    )
                is ConversationsViewModel.Event.IAmOkDoneWithSms -> {
                    // v1.14.0 audit SEC-2 — différencier succès partiel vs
                    // échec total. Si sent=0 mais failed>0, le reset a eu
                    // lieu mais aucun contact n'a reçu le SMS → l'user doit
                    // savoir pour relancer manuellement.
                    if (event.sent > 0) {
                        snackbarHost.showSnackbar(ctx.getString(R.string.emergency_i_am_ok_done))
                    } else {
                        snackbarHost.showError(ctx.getString(R.string.emergency_i_am_ok_send_failed))
                    }
                }
                is ConversationsViewModel.Event.IAmOkDoneNoSms ->
                    snackbarHost.showSnackbar(ctx.getString(R.string.emergency_i_am_ok_reset_only))
            }
        }
    }

    // v1.13.0 — système back en mode sélection ⇒ sortir du mode sélection,
    // pas quitter l'écran. Pattern Gmail / Google Messages : préserve l'écran.
    androidx.activity.compose.BackHandler(enabled = state.selectionMode) {
        viewModel.clearSelection()
    }

    Scaffold(
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
        topBar = topBar@{
            if (state.selectionMode && !isPanicDecoy) {
                // v1.13.0 — TopAppBar contextuelle en mode sélection : count + action
                // "Déplacer N vers le coffre" + croix annule. Masquée en PanicDecoy
                // (re-entry via le `combine` purge la sélection ; on dé-render aussi
                // côté UI pour ne pas laisser un état zombie pendant la transition).
                TopAppBar(
                    title = {
                        Text(
                            text = androidx.compose.ui.res.pluralStringResource(
                                id = R.plurals.conversations_selection_count,
                                count = state.selectedIds.size,
                                state.selectedIds.size,
                            ),
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = stringResource(R.string.action_cancel),
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.bulkMoveSelectedToVault() }) {
                            Icon(
                                Icons.Outlined.Lock,
                                contentDescription = stringResource(R.string.bulk_vault_move_in),
                            )
                        }
                    },
                    // Selection mode reads as a state of the list, not a new screen: the
                    // bar takes the same primary-container tint as the selected rows below.
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.primaryContainer,
                        titleContentColor = cs.onPrimaryContainer,
                        navigationIconContentColor = cs.onPrimaryContainer,
                        actionIconContentColor = cs.onPrimaryContainer,
                    ),
                )
                return@topBar
            }
            // Round 4 — the top bar swaps between the title bar and the search pill.
            // The pill slides in from the right over the title bar and slides back out
            // when the X closes it; the title bar just fades. Selection mode above
            // still wins over both.
            val sortMode = state.settings.conversations.sortMode
            Box {
                AnimatedVisibility(
                    visible = !searchOpen,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    TopAppBar(
                        title = {
                            // Title only — no logo next to it (Kristin, 2026-11-06). The old
                            // ic_app_logo image used to sit here in front of the name.
                            Text(
                                text = if (archived) stringResource(R.string.tab_archived)
                                else stringResource(R.string.app_name),
                            )
                        },
                        navigationIcon = {
                            onBack?.let {
                                IconButton(onClick = it) {
                                    Icon(
                                        Icons.AutoMirrored.Outlined.ArrowBack,
                                        contentDescription = stringResource(R.string.action_back),
                                    )
                                }
                            }
                        },
                        actions = {
                            // Feedback round 6 — the two header actions match the
                            // Figma home screen: 36dp hit boxes (the thread
                            // header's pattern) with a 9dp end inset, so the ⋮
                            // sits 27dp from the edge and the magnifier ~63dp.
                            // The 48dp IconButtons left too much air between
                            // the two icons.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(end = 9.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = { searchOpen = true })
                                        .semantics { role = Role.Button },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_search),
                                        contentDescription = stringResource(
                                            R.string.conversations_search_hint,
                                        ),
                                        // Round 5 — same tint as the overflow icon beside it
                                        // (default TopAppBar action colour, onSurfaceVariant).
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .clickable(onClick = { overflowOpen = true })
                                        .semantics { role = Role.Button },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    // Feedback round 7 — `ic_more_vert_tall`
                                    // instead of Material MoreVert: the drawn
                                    // glyph is 19.4dp tall, matching the search
                                    // magnifier beside it (MoreVert drew at
                                    // 16dp). Same 24dp size and
                                    // on-surface-variant tint as before.
                                    Icon(
                                        painter = painterResource(R.drawable.ic_more_vert_tall),
                                        contentDescription = stringResource(R.string.action_more),
                                        tint = cs.onSurfaceVariant,
                                        modifier = Modifier.size(24.dp),
                                    )
                                }
                            }
                            // Round 4 — rounded menu like the rest of the refreshed UI:
                            // 18dp, the same radius as the bubble long-press menu
                            // (BubbleMenuTrigger) and the theme's large shape.
                            DropdownMenu(
                                expanded = overflowOpen,
                                onDismissRequest = { overflowOpen = false },
                                shape = RoundedCornerShape(18.dp),
                                // Feedback round 6 — 10dp more breathing room on
                                // each side than the Material default, like the
                                // bubble menu and the thread overflow menu.
                                modifier = Modifier.padding(horizontal = 10.dp),
                            ) {
                                // v1.8.0 — action "Tout marquer comme lu". Utile quand l'user a
                                // lu ses messages dans une autre app SMS et que les badges SMS
                                // Tech persistent (cache `messages.read` désynchronisé du système).
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Check, contentDescription = null) },
                                    text = { Text(stringResource(R.string.action_mark_all_read)) },
                                    onClick = {
                                        overflowOpen = false
                                        showMarkAllReadConfirm = true
                                    },
                                )
                                // Ouvre l'éditeur de contacts du système sur un formulaire vierge
                                // (aucun numéro de contexte à ce niveau). Feedback snackbar si aucune
                                // app Contacts n'est installée. Partage le helper ContactIntents avec
                                // l'écran de composition.
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.PersonAdd, contentDescription = null) },
                                    text = { Text(stringResource(R.string.action_create_contact)) },
                                    onClick = {
                                        overflowOpen = false
                                        if (!ContactIntents.createContact(ctx)) {
                                            scope.launch {
                                                snackbarHost.showError(ctx.getString(R.string.phone_action_no_contacts))
                                            }
                                        }
                                    },
                                )
                                // v1.28.3 — créer un groupe ouvre le composeur en mode groupe : toucher
                                // un contact y ajoute une puce au lieu d'ouvrir le fil.
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Group, contentDescription = null) },
                                    text = { Text(stringResource(R.string.action_new_group)) },
                                    onClick = { overflowOpen = false; onComposeGroup() },
                                )
                                // Feedback round 8 — Scheduled messages moved here from
                                // Settings: it lives in this menu, under "New group".
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                    text = { Text(stringResource(R.string.settings_scheduled_messages)) },
                                    onClick = { overflowOpen = false; onOpenScheduledMessages() },
                                )
                                // Archived chats and the vault used to live as icons on the top bar;
                                // they now live here, so the bar stays a title plus the overflow.
                                // Same destinations, same behaviour — just reached from the menu.
                                // The vault stays hidden in a panic-decoy session (audit S-P0-1):
                                // hiding it entirely keeps the feature looking non-existent to a
                                // coerced observer, backed by the navigation guard and the
                                // repository-level filter.
                                if (!archived) {
                                    if (!isPanicDecoy) {
                                        DropdownMenuItem(
                                            leadingIcon = {
                                                Icon(Icons.Outlined.Lock, contentDescription = null)
                                            },
                                            text = { Text(stringResource(R.string.tab_vault)) },
                                            onClick = { overflowOpen = false; onOpenVault() },
                                        )
                                    }
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(Icons.Outlined.Inventory2, contentDescription = null)
                                        },
                                        text = { Text(stringResource(R.string.tab_archived)) },
                                        onClick = { overflowOpen = false; onOpenArchived() },
                                    )
                                }
                                DropdownMenuItem(
                                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                    text = { Text(stringResource(R.string.settings_title)) },
                                    onClick = { overflowOpen = false; onOpenSettings() },
                                )
                                // Sort section. Header is a non-interactive label rendered with
                                // `enabled = false` so the user can read it without it looking like
                                // an action item. The 3 sort options follow with a trailing check on
                                // the active mode.
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 4.dp),
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(R.string.sort_section),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    },
                                    onClick = { },
                                    enabled = false,
                                )
                                SortMenuItem(
                                    label = stringResource(R.string.sort_date),
                                    selected = sortMode == com.filestech.sms.domain.settings.SortMode.DATE,
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.setSortMode(com.filestech.sms.domain.settings.SortMode.DATE)
                                    },
                                )
                                SortMenuItem(
                                    label = stringResource(R.string.sort_unread),
                                    selected = sortMode == com.filestech.sms.domain.settings.SortMode.UNREAD_FIRST,
                                    onClick = {
                                        overflowOpen = false
                                        viewModel.setSortMode(com.filestech.sms.domain.settings.SortMode.UNREAD_FIRST)
                                    },
                                )
                            }
                        },
                    )
                }
                AnimatedVisibility(
                    visible = searchOpen,
                    enter = slideInHorizontally { it } + fadeIn(),
                    exit = slideOutHorizontally { it } + fadeOut(),
                ) {
                    SearchTopBar(
                        // v1.27.9 — état Compose lu directement : la frappe doit revenir
                        // au champ dans la MÊME recomposition (cf.
                        // [ConversationsViewModel.searchInput]). `state.query` arrivait
                        // après le debounce de 200 ms et réécrivait le champ avec du périmé.
                        query = viewModel.searchInput,
                        onQueryChange = viewModel::setQuery,
                        onClose = closeSearch,
                        focusRequester = searchFocusRequester,
                    )
                }
            }
        },
        floatingActionButton = {
            // Round 4 — 56dp rounded square in the fixed brand blue in both themes:
            // the pre-refresh default shape, restored after the refresh made it a
            // circle. Before the refresh (commit 9d69566) the button carried NO size
            // or shape modifier at all, so it rendered the Material 3 default — 56dp
            // with FloatingActionButtonDefaults.shape (baseline token CornerLarge,
            // i.e. the theme's Shapes.large, 18dp here). That default is restored
            // exactly, instead of a hardcoded radius. The brand blue is kept: filled
            // surfaces keep it, the dark scheme's lightened primary (#7AA0F5) is for
            // text and icons, not for filled tiles. White pencil on it is 6.63:1.
            FloatingActionButton(
                onClick = onCompose,
                modifier = Modifier.size(56.dp),
                shape = FloatingActionButtonDefaults.shape,
                containerColor = RelayColors.Blue,
                contentColor = Color.White,
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = stringResource(R.string.action_new_message),
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Round 4 — no search field here any more: it lives in the top bar as the
            // pill ([SearchTopBar]), opened from the magnifier. The filtering itself
            // is unchanged — the ViewModel still filters on the same query.
            if (!state.isDefaultSmsApp && !archived) {
                DefaultAppBanner(onSetDefault = {
                    viewModel.buildChangeDefaultIntent()?.let { intent ->
                        defaultAppLauncher.launch(intent)
                    }
                })
                // Feedback round 5 — breathing room between the prompt text and
                // the 1px divider below it: the banner's own vertical padding
                // is only 2dp, so the text sat almost on the divider. 12dp
                // matches the banner's internal 12dp icon-to-text spacer.
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
            }
            // v1.14.0 — Chip "Je vais bien" si déclenchement urgence < 30 min.
            //
            // ⚠️ v1.28.12 — le `&& !isPanicDecoy` n'est PAS redondant, et son absence était le
            // défaut le plus grave de cet écran.
            //
            // `state.showIAmOkChip` porte bien `!isPanic`, mais il vient du flux AGRÉGÉ, servi
            // par `stateIn(WhileSubscribed(5 s))`. Quand l'écran de verrouillage se dépile,
            // cette valeur EN CACHE — calculée avant le verrouillage, donc avec
            // `isPanicDecoy = false` — est resservie le temps que le `combine` (requête
            // SQLCipher + appel Binder, `flowOn(io)`) réémette.
            //
            // C'est EXACTEMENT le mécanisme que l'audit H18 (v1.26.1) a corrigé pour l'icône du
            // Coffre, en dérivant [ConversationsViewModel.isPanicDecoy] directement du verrou,
            // `Eagerly` — son KDoc le décrit mot pour mot. Le correctif a été posé sur ce seul
            // champ ; le bandeau est resté sur le flux agrégé. Six autres endroits de cet écran
            // gardent déjà sur la valeur fraîche (lignes 247, 321, 551, 559, 576) : celui-ci
            // était le septième, et le seul oublié.
            //
            // Ce qu'il coûtait : une victime déclenche l'urgence, les SMS partent, on lui prend
            // le téléphone et on la force à saisir le code panique. Le bandeau « Je vais bien »
            // s'affiche — et l'agresseur apprend qu'un appel à l'aide vient d'être envoyé.
            // L'icône du Coffre révélait qu'un coffre existe ; celui-ci révèle l'alerte.
            BandeauJeVaisBien(
                visible = state.showIAmOkChip && !isPanicDecoy,
                onConfirmer = viewModel::triggerIAmOk,
            )
            // v1.23.x — noms de contact partagés par ≥2 conversations : le numéro n'est affiché
            // sous le nom que pour celles-là. v1.28.3 (audit global C-03) — calculé dans le
            // ViewModel, sur IO, avec le filtre et le tri.
            val duplicateDisplayNames = state.duplicateDisplayNames
            when {
                state.isImporting -> ImportingPlaceholder(count = state.importedCount)
                state.isLoading -> Unit
                state.sansAucunResultat(messageHits) -> ConversationsEmptyState(
                    archived = archived,
                    filtered = state.filtered,
                    onCompose = onCompose,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 96.dp),
                    // Round 5 — 16dp between rows (was 8dp, round 4; 4dp, round 3).
                    // The 8dp still read as tight on the main screen. The same 16dp
                    // also lands around the section headers, so their paddings below
                    // stay small (see ConversationSectionHeader). Row height and
                    // section header spacing are untouched — only the gap grows.
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Relay — AUCUNE ligne de séparation entre les conversations :
                    // la liste se lit sans filets, comme dans Relay.
                    // v1.25.3 — les conversations dont tous les numéros sont bloqués ne sont plus
                    // ni supprimées ni masquées : elles descendent en fin de liste sous un
                    // en-tête dédié. Le tri d'origine est conservé à l'intérieur de chaque groupe
                    // (`partition` est stable), donc rien ne bouge pour qui n'a rien bloqué.
                    val (activeConversations, blockedConversations) =
                        state.conversations.partition { !it.blocked }
                    // Round 4 — pinned chats get their own "Pinned" section above
                    // "Chats", like Kristin's list screenshot (see
                    // [conversationSections]). While the search pill is open the
                    // headers are hidden and the list is flat.
                    val (pinnedConversations, otherConversations) =
                        splitPinnedSections(activeConversations, searching = searchOpen)

                    // One row renderer shared by the Pinned and Chats sections, so the
                    // two sections can never drift apart.
                    // Audit PERF-M2 (v1.14.8) — `contentType` stable indique au recycleur
                    // que toutes les rows partagent le même layout. Sans ce hint, Compose
                    // ne peut pas optimiser le recyclage entre rows sélectionnés / non-sél.
                    val conversationItem: @Composable LazyItemScope.(Conversation) -> Unit = { conv ->
                        val isSelected = state.selectedIds.contains(conv.id)
                        // v1.23.x — numéro affiché sous le nom seulement si ce nom est ambigu
                        // (partagé par ≥2 conversations) et que la conversation est 1-to-1.
                        val rowSubtitleNumber = conv.displayName
                            ?.takeIf { it in duplicateDisplayNames && conv.addresses.size == 1 }
                            ?.let { conv.addresses.firstOrNull()?.raw }
                        // Laggy scrolling: every one of these callbacks was a fresh lambda
                        // (and `rowActions` a fresh object) on every screen recomposition,
                        // so no row could ever skip — each `state` emission (incoming SMS,
                        // read change, sync tick) re-composed every visible row in full.
                        // Memoised on the values they actually capture, the row keeps the
                        // same parameter instances while its conversation is unchanged and
                        // Compose can skip it. The null-conditions below stay at the pass
                        // site, so a mode change still flips the row as before.
                        val openThreadCallback = remember(
                            conv.id,
                            state.selectionMode,
                            onOpenThread,
                        ) {
                            {
                                if (state.selectionMode) viewModel.toggleSelection(conv.id)
                                else onOpenThread(conv.id)
                            }
                        }
                        val deleteCallback: () -> Unit = remember(conv.id) {
                            {
                                viewModel.delete(conv.id)
                            }
                        }
                        val blockCallback: () -> Unit = remember(conv) {
                            {
                                viewModel.block(conv)
                            }
                        }
                        val moveToVaultCallback: () -> Unit = remember(conv.id) {
                            {
                                viewModel.moveConversationToVault(conv.id)
                            }
                        }
                        val rowActionsValue = remember(conv) {
                            ConversationRowActions(
                                onTogglePin = { viewModel.togglePinned(conv) },
                                onToggleArchive = { viewModel.toggleArchived(conv) },
                                onToggleMute = { viewModel.toggleMuted(conv) },
                                onRename = { nom -> viewModel.rename(conv, nom) },
                            )
                        }
                        val selectMultipleCallback = remember(conv.id) {
                            {
                                viewModel.toggleSelection(conv.id)
                            }
                        }
                        SwipeableConversationRow(
                            conversation = conv,
                            subtitleNumber = rowSubtitleNumber,
                            showAvatars = state.settings.conversations.showAvatars,
                            previewLines = state.settings.conversations.previewLines.coerceIn(1, 2),
                            contactPhotoUri = contactPhotos[conv.id],
                            // v1.13.0 — en mode sélection, tap = toggle de l'item, pas
                            // ouverture du thread. Hors mode sélection, comportement
                            // normal. Le swipe est désactivé en mode sélection pour
                            // éviter les conflits de gestes.
                            onOpenThread = openThreadCallback,
                            onDelete = deleteCallback,
                            onBlock = blockCallback,
                            // v1.11.0 — Trou #2 Vault polish : callback passé
                            // UNIQUEMENT hors PanicDecoy. L'item de menu n'apparaît
                            // donc pas en session decoy (cohérent avec masquage
                            // de l'icône cadenas top-bar). Désactivé aussi en mode
                            // sélection (l'action passe par la TopAppBar bulk).
                            onMoveToVault = if (isPanicDecoy || state.selectionMode) null else {
                                moveToVaultCallback
                            },
                            // v1.26.1 (audit F1) — mêmes conditions que le coffre : masqués en
                            // mode leurre et en mode sélection. Les trois gestes sont portés par
                            // UN SEUL objet nullable plutôt que par trois `if` séparés : une
                            // seule décision au lieu de trois identiques, et la complexité
                            // cyclomatique de cet écran reste sous le seuil du projet.
                            rowActions = if (isPanicDecoy || state.selectionMode) {
                                null
                            } else {
                                rowActionsValue
                            },
                            // v1.13.1 — restaure ActionsSheet legacy en long-press
                            // (quick actions Block/Delete/Move to vault). Ajoute en
                            // plus l'option "Sélectionner plusieurs" qui entre en
                            // mode sélection multi. Hors mode sélection seulement
                            // (en mode sélection le long-press toggle, comportement
                            // Gmail). PanicDecoy : pas de selection toggle non plus,
                            // l'ActionsSheet legacy sans item Vault est rendu.
                            onSelectMultiple = if (isPanicDecoy) null else {
                                selectMultipleCallback
                            },
                            selected = isSelected,
                            selectionMode = state.selectionMode,
                        )
                    }

                    conversationSections(
                        pinned = pinnedConversations,
                        other = otherConversations,
                        itemContent = conversationItem,
                    )

                    if (blockedConversations.isNotEmpty()) {
                        item(key = "header-blocked", contentType = "header") {
                            Text(
                                text = stringResource(R.string.conversations_section_blocked)
                                    .uppercase(Locale.ROOT),
                                style = RelayText.SectionHeader,
                                color = cs.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 20.dp,
                                    bottom = 6.dp,
                                ),
                            )
                        }
                        items(
                            blockedConversations,
                            key = { it.id },
                            contentType = { "blocked-row" },
                        ) { conv ->
                            // Ligne simple, sans balayage : le geste de balayage sert à répondre
                            // et à supprimer sur une conversation active. Sur une bloquée, les
                            // deux actions utiles sont explicites — corbeille rouge ici,
                            // « Débloquer » dans la feuille d'appui long.
                            ConversationRow(
                                conversation = conv,
                                showAvatars = state.settings.conversations.showAvatars,
                                previewLines = state.settings.conversations.previewLines
                                    .coerceIn(1, 2),
                                contactPhotoUri = contactPhotos[conv.id],
                                // v1.25.3 — les lignes bloquées ne participent PAS au mode
                                // sélection : elles utilisent `ConversationRow` nu, sans l'état
                                // visuel de sélection de `SwipeableConversationRow`. Les y
                                // inclure permettrait de sélectionner sans le voir, et une
                                // action groupée porterait sur des fils invisiblement cochés.
                                // Elles ont leurs deux actions propres, explicites.
                                onClick = { onOpenThread(conv.id) },
                                onLongClick = { blockedActionsFor = conv },
                                onDelete = { pendingDeleteBlocked = conv },
                            )
                        }
                    }

                    // v1.28.8 (issue #17) — les messages dont le TEXTE correspond, sous les
                    // conversations.
                    messageHitsSection(
                        hits = messageHits,
                        showAvatars = state.settings.conversations.showAvatars,
                        selectionMode = state.selectionMode,
                        contactPhotos = contactPhotos,
                        onOpenThread = onOpenThread,
                    )
                }
            }
        }
    }

    // v1.25.3 — dialogues propres aux conversations bloquées, extraits pour garder
    // [ConversationsScreen] sous le seuil de complexité cyclomatique de detekt.
    BlockedConversationDialogs(
        pendingDelete = pendingDeleteBlocked,
        actionsFor = blockedActionsFor,
        onDismissDelete = { pendingDeleteBlocked = null },
        onDismissActions = { blockedActionsFor = null },
        onConfirmDelete = { id -> pendingDeleteBlocked = null; viewModel.delete(id) },
        onUnblock = { conv -> blockedActionsFor = null; viewModel.unblock(conv) },
        onRequestDelete = { conv -> blockedActionsFor = null; pendingDeleteBlocked = conv },
    )

    // v1.8.0 — Dialog de confirmation pour "Tout marquer comme lu".
    // Action non destructive, dialog simple (pas d'autofocus complexe — c'est
    // de l'UX, pas une action irreversible).
    if (showMarkAllReadConfirm) {
        RelayDialog(
            onDismissRequest = { showMarkAllReadConfirm = false },
            title = stringResource(R.string.action_mark_all_read),
            content = {
                Text(
                    text = stringResource(R.string.mark_all_read_confirm_body),
                    style = RelayText.MessageBody,
                    color = cs.onSurface,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_confirm),
                    onClick = {
                        viewModel.markAllAsRead()
                        showMarkAllReadConfirm = false
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showMarkAllReadConfirm = false },
                )
            },
        )
    }
}

/**
 * Round 4 — splits the list into the "Pinned" section and the rest. While the
 * search pill is open the list stays flat: nothing is pinned out.
 */
private fun splitPinnedSections(
    conversations: List<Conversation>,
    searching: Boolean,
): Pair<List<Conversation>, List<Conversation>> {
    return if (searching) {
        emptyList<Conversation>() to conversations
    } else {
        conversations.partition { it.pinned }
    }
}

/**
 * Round 4 — emits the Pinned/Chats sections for the conversations list: the
 * pinned chats under a "Pinned" header, everything else under "Chats". No pinned
 * chats means no sections at all — just the plain list.
 */
private fun LazyListScope.conversationSections(
    pinned: List<Conversation>,
    other: List<Conversation>,
    itemContent: @Composable LazyItemScope.(Conversation) -> Unit,
) {
    if (pinned.isNotEmpty()) {
        item(key = "header-pinned", contentType = "header") {
            ConversationSectionHeader(
                text = stringResource(R.string.conversations_section_pinned),
                topPadding = 16.dp,
            )
        }
    }
    items(
        pinned,
        key = { it.id },
        contentType = { "row" },
        itemContent = itemContent,
    )
    if (pinned.isNotEmpty()) {
        item(key = "header-chats", contentType = "header") {
            ConversationSectionHeader(
                text = stringResource(R.string.conversations_section_chats),
                topPadding = 12.dp,
            )
        }
    }
    items(
        other,
        key = { it.id },
        contentType = { "row" },
        itemContent = itemContent,
    )
}

/**
 * Round 4 — the search pill that replaces the top bar while searching, like
 * Kristin's search-open screenshot: a full-width pill (48dp high, fully rounded),
 * "Search" placeholder, no magnifier inside, and Kristin's close-circle icon at
 * the right end — tapping it closes the pill (see `closeSearch` in
 * [ConversationsScreen]). The filtering itself is unchanged: the ViewModel still
 * filters on the same query, and while the pill is open the list is a flat
 * filtered list with no section headers.
 */
@Composable
private fun SearchTopBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
    focusRequester: FocusRequester,
) {
    val cs = MaterialTheme.colorScheme
    // Hoisted: stringResource is @Composable and cannot run inside .semantics {}.
    val closeLabel = stringResource(R.string.action_close)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RelayTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            placeholder = stringResource(R.string.conversations_search_short),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            cornerRadius = 24.dp,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            focusRequester = focusRequester,
            trailingIcon = {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onClose)
                        .semantics {
                            contentDescription = closeLabel
                            role = Role.Button
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_close_circle_outline),
                        contentDescription = null,
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            },
        )
    }
}

/**
 * Round 4 — "Pinned" / "Chats" section label, sentence case like Kristin's list
 * screenshot (unlike the Blocked header, which is uppercased). The LazyColumn's
 * 8dp spacing also lands around the header, so the paddings stay small: 12dp
 * below the label (4dp here + 8dp spacing) and, for the Chats header, 20dp above
 * it (12dp here + 8dp spacing).
 */
@Composable
private fun ConversationSectionHeader(text: String, topPadding: Dp) {
    Text(
        text = text,
        style = RelayText.SectionHeader,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(
            start = 16.dp,
            end = 16.dp,
            top = topPadding,
            bottom = 4.dp,
        ),
    )
}

/**
 * v1.14.0 — bandeau "Vous avez déclenché une alerte récemment / Je vais bien".
 * Affiché sur [ConversationsScreen] pendant 30 min post-trigger (visibilité
 * gérée côté ViewModel via `showIAmOkChip`). Tap → confirm dialog → reset
 * cooldown + (optionnel) SMS "Je vais bien" aux contacts SafetyCall.
 *
 * Une seule ligne discrète sur le fond de la liste, pas un bloc coloré : une icône
 * d'alerte, le libellé, et une action texte. L'icône garde sa couleur d'alerte —
 * c'est un état de sécurité, pas une décoration.
 */
@Composable
private fun IAmOkBanner(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    // Audit A11Y-M4 (v1.14.8) — `mergeDescendants` + `contentDescription` sur le conteneur :
    // TalkBack annonce une SEULE entité sémantique cohérente ("Alerte urgence déclenchée
    // récemment, Je vais bien") au lieu d'enchaîner icône (silencieuse) + texte + bouton.
    // Critique sur un flow d'urgence : l'user en situation de stress doit comprendre la zone
    // au premier coup d'audition TalkBack.
    val a11yTitle = stringResource(R.string.emergency_i_am_ok_chip_title)
    val a11yAction = stringResource(R.string.emergency_i_am_ok_chip_action)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "$a11yTitle. $a11yAction"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.WarningAmber,
            contentDescription = null,
            tint = com.filestech.sms.ui.theme.BrandDanger,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.size(12.dp))
        Text(
            text = stringResource(R.string.emergency_i_am_ok_chip_title),
            style = RelayText.Preview,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(8.dp))
        RelayTextButton(
            text = stringResource(R.string.emergency_i_am_ok_chip_action),
            onClick = onClick,
            confirm = true,
        )
    }
}

/**
 * Soft "set me as default" banner rendered above the list when the app is not yet the system
 * default SMS handler. Information nudge — not a failure — so we paint it in the brand-blue
 * family (`primaryContainer` + `primary`) instead of error red. The lighter container alpha
 * keeps the line visible without competing with the conversation list below.
 */
/**
 * v1.28.12 — extrait de [ConversationsScreen], qui atteignait le plafond de complexite du
 * projet. L'extraction n'est pas cosmetique : elle place la garde [visible] a UN seul endroit,
 * la ou l'ancienne forme la laissait au milieu de quarante branches.
 *
 * ⚠️ [visible] doit porter la garde du mode leurre, et pas seulement `state.showIAmOkChip`.
 * Ce champ vient du flux AGREGE, servi par `stateIn(WhileSubscribed(5 s))` : au depilement de
 * l'ecran de verrouillage, sa valeur EN CACHE — calculee avant, donc avec `isPanicDecoy = false`
 * — est resservie le temps que le `combine` reemette.
 *
 * C'est le mecanisme que l'audit H18 (v1.26.1) a corrige pour l'icone du Coffre, en derivant
 * `isPanicDecoy` directement du verrou, `Eagerly`. Le correctif n'avait ete pose que sur ce
 * champ ; ce bandeau etait reste sur le flux agrege, et il est le plus grave des deux : l'icone
 * revele qu'un coffre existe, celui-ci revele qu'un appel a l'aide vient de partir — a
 * quelqu'un qui tient le telephone par la contrainte. Releve le 2026-09-17.
 */
@Composable
private fun BandeauJeVaisBien(visible: Boolean, onConfirmer: () -> Unit) {
    if (!visible) return
    var confirmationOuverte by remember { mutableStateOf(false) }
    IAmOkBanner(onClick = { confirmationOuverte = true })
    HorizontalDivider()
    if (!confirmationOuverte) return
    RelayDialog(
        onDismissRequest = { confirmationOuverte = false },
        title = stringResource(R.string.emergency_i_am_ok_confirm_title),
        content = {
            Text(
                text = stringResource(R.string.emergency_i_am_ok_confirm_body),
                style = RelayText.MessageBody,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.emergency_i_am_ok_chip_action),
                onClick = {
                    confirmationOuverte = false
                    onConfirmer()
                },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.action_cancel),
                onClick = { confirmationOuverte = false },
            )
        },
    )
}

/**
 * Soft "set me as default" banner rendered above the list when the app is not yet the system
 * default SMS handler. Information nudge — not a failure — so we paint it in the brand-blue
 * family (`primaryContainer` + `primary`) instead of error red. The lighter container alpha
 * keeps the line visible without competing with the conversation list below.
 *
 * Feedback round 7 — the original SMS Tech card, restored: a tinted rounded card with
 * the text on the left and a filled button on the right, in one row. It replaced the
 * flat text-button row (v1.27.12) that had replaced the card.
 *
 * Narrow-screen guard (v1.27.12, kept): the filled button used to take its intrinsic
 * width first and crush the text to a 7-character column on 360dp screens at font
 * scale 1.33. The button now uses compact horizontal padding, and the text caps at
 * 2 lines with an ellipsis — it can never collapse into a sliver. (A literal single
 * line does not fit at 14sp next to the filled button on a phone — the original
 * wrapped to two lines too — so the text reads in full on two lines at default
 * scale and ellipsizes instead of crushing at large scales.)
 */
@Composable
private fun DefaultAppBanner(onSetDefault: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.primaryContainer.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                tint = cs.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = stringResource(R.string.error_not_default_app),
                style = MaterialTheme.typography.bodyMedium,
                color = cs.onPrimaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.size(8.dp))
            FilledTonalButton(
                onClick = onSetDefault,
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = cs.primary,
                    contentColor = cs.onPrimary,
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            ) {
                Text(
                    stringResource(R.string.settings_set_default),
                    fontWeight = FontWeight.Normal,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableConversationRow(
    conversation: com.filestech.sms.domain.model.Conversation,
    subtitleNumber: String? = null,
    showAvatars: Boolean,
    previewLines: Int,
    /**
     * Contact photo from the system contacts (`PHOTO_THUMBNAIL_URI`), resolved by the
     * ViewModel. The per-conversation custom avatar still wins inside [ConversationRow].
     */
    contactPhotoUri: String? = null,
    onOpenThread: () -> Unit,
    onDelete: () -> Unit,
    onBlock: () -> Unit,
    onMoveToVault: (() -> Unit)? = null,
    /**
     * v1.13.1 — callback "entrer en mode sélection multiple" exposé comme item
     * dans l'ActionsSheet legacy long-press. Non-null hors PanicDecoy. Restaure
     * le pattern "appui long → menu" attendu en v1.12, sans perdre le multi.
     */
    onSelectMultiple: (() -> Unit)? = null,
    /**
     * v1.26.1 (audit F1) — épingler / archiver / sourdine. `null` en mode leurre : l'agresseur
     * ne doit pas pouvoir réorganiser ni faire taire les conversations de la victime.
     */
    rowActions: ConversationRowActions? = null,
    /** v1.13.0 — visuel sélectionné (background tinted + check icon). */
    selected: Boolean = false,
    /** v1.13.0 — désactive le swipe et le menu legacy en mode sélection. */
    selectionMode: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    var pendingDelete by remember { mutableStateOf(false) }
    var pendingBlock by remember { mutableStateOf(false) }
    var actionsSheetOpen by remember { mutableStateOf(false) }
    // v1.28.3 — nommer le groupe depuis l'appui long.
    var renameOpen by remember { mutableStateOf(false) }
    // v1.23.2 — verrou one-shot du swipe gauche. `confirmValueChange` N'EST PAS un callback
    // « une fois à la validation » : foundation l'invoque à CHAQUE frame de drag une fois passé
    // la moitié de la distance vers l'ancre voisine (AnchoredDragScope.dragTo → updateIfNeeded),
    // puis encore pendant le fling (SnapLayoutInfoProvider.calculateSnapOffset) et pendant
    // l'animation de retour (anchoredDrag). Sans verrou, un seul swipe empilait N destinations
    // Thread(id) identiques : chaque retour en dépilait une et réaffichait le même écran, d'où
    // le « le retour ne marche qu'au bout de 3-4 essais ». Le verrou est réarmé dès que la row
    // est revenue au repos (cf. LaunchedEffect ci-dessous).
    var swipeOpenConsumed by remember { mutableStateOf(false) }
    val dismissState = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> {
                    // Swipe right → ask for confirmation, do not dismiss yet.
                    // Idempotent : le multi-appel décrit ci-dessus est sans effet ici.
                    pendingDelete = true
                    false
                }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> {
                    // Swipe left → open thread (= reply). Snap back.
                    if (!swipeOpenConsumed) {
                        swipeOpenConsumed = true
                        onOpenThread()
                    }
                    false
                }
                androidx.compose.material3.SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f },
    )
    // v1.23.2 — réarmement du verrou : `currentValue` ne bouge jamais (on veto toujours), donc
    // on observe `targetValue`, qui redevient Settled quand l'offset est revenu à zéro.
    androidx.compose.runtime.LaunchedEffect(dismissState.targetValue) {
        if (dismissState.targetValue == androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
            swipeOpenConsumed = false
        }
    }

    androidx.compose.material3.SwipeToDismissBox(
        state = dismissState,
        // v1.13.0 — swipe désactivé en mode sélection (cohérent avec absence
        // d'ActionsSheet sur long-press et avec le pattern Gmail / Messages).
        enableDismissFromStartToEnd = !selectionMode,
        enableDismissFromEndToStart = !selectionMode,
        backgroundContent = {
            // v1.2.3 audit U6: use the brand/error tokens so swipe backgrounds adapt to the
            // current theme. Hardcoded 0xFFC62828 / 0xFF1565C0 broke on Dark Tech.
            val dir = dismissState.dismissDirection
            val (bg, icon, align) = when (dir) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd ->
                    Triple(com.filestech.sms.ui.theme.BrandDanger, Icons.Outlined.Delete, Alignment.CenterStart)
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart ->
                    Triple(cs.primary, Icons.AutoMirrored.Outlined.Reply, Alignment.CenterEnd)
                else -> Triple(cs.surface, Icons.Outlined.Delete, Alignment.Center)
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(bg)
                    .padding(horizontal = 24.dp),
                contentAlignment = align,
            ) {
                if (dir != androidx.compose.material3.SwipeToDismissBoxValue.Settled) {
                    // Phase 8 — l'icône prend le premier plan du fond balayé : blanc sur le
                    // rouge destructif (4.96:1), `onPrimary` sur le bleu (blanc sur le bleu
                    // éclairci du thème sombre ne donnerait que 2.57:1).
                    val iconTint = if (dir ==
                        androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
                    ) {
                        cs.onPrimary
                    } else {
                        androidx.compose.ui.graphics.Color.White
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                    )
                }
            }
        },
    ) {
        // v1.13.0 — background `primaryContainer.copy(alpha=.35f)` quand
        // sélectionné. Subtil mais lisible WCAG AA face au `onSurface` du
        // texte (le `primaryContainer` est déjà un ton clair sur thème clair
        // et un ton sombre sur thème sombre ; α=0.35 préserve la lisibilité).
        val rowBg = if (selected) cs.primaryContainer.copy(alpha = 0.35f) else cs.surface
        androidx.compose.foundation.layout.Box(modifier = Modifier.background(rowBg)) {
            ConversationRow(
                conversation = conversation,
                subtitleNumber = subtitleNumber,
                onClick = onOpenThread,
                showAvatars = showAvatars,
                previewLines = previewLines,
                contactPhotoUri = contactPhotoUri,
                // v1.13.1 — long-press routing :
                //  - en mode sélection multiple → toggle de l'item (pattern Gmail).
                //  - sinon → ActionsSheet legacy (Block/Delete/Move to vault +
                //    "Sélectionner plusieurs" si onSelectMultiple non-null).
                // Haptic pulse dans les deux cas (Material guideline).
                onLongClick = {
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                    if (selectionMode && onSelectMultiple != null) onSelectMultiple()
                    else actionsSheetOpen = true
                },
            )
            // v1.13.0 — check icon en overlay quand sélectionné. Placé en bas-
            // droite pour ne pas masquer l'avatar ; tinté `primary` pour lisibilité
            // sur le background sélectionné.
            if (selected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(cs.primary),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Check,
                        contentDescription = stringResource(R.string.selected),
                        tint = cs.onPrimary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }

    if (actionsSheetOpen) {
        ConversationActionsSheet(
            onDismiss = { actionsSheetOpen = false },
            onBlockRequested = {
                actionsSheetOpen = false
                pendingBlock = true
            },
            onDeleteRequested = {
                actionsSheetOpen = false
                pendingDelete = true
            },
            // v1.11.0 — null en PanicDecoy (le parent ne passe pas le callback)
            // → l'item de menu "Déplacer vers le coffre" n'apparaît pas.
            onMoveToVaultRequested = onMoveToVault?.let { handler ->
                {
                    actionsSheetOpen = false
                    handler()
                }
            },
            // v1.13.1 — item "Sélectionner plusieurs" qui entre en mode sélection
            // multi. null en PanicDecoy (le sélection-mode TopAppBar est aussi
            // masquée en decoy, cohérence cross-écran).
            onSelectMultipleRequested = onSelectMultiple?.let { handler ->
                {
                    actionsSheetOpen = false
                    handler()
                }
            },
            // v1.28.3 — renommer, sur un groupe seulement, et hors mode leurre comme les autres.
            onRenameRequested = rowActions?.takeIf { conversation.isGroup }?.let {
                {
                    actionsSheetOpen = false
                    renameOpen = true
                }
            },
            // v1.26.1 (audit F1) — état courant + gestes. `null` en mode leurre, via le parent.
            pinned = conversation.pinned,
            archived = conversation.archived,
            muted = conversation.muted,
            onTogglePinRequested = rowActions?.let { a ->
                {
                    actionsSheetOpen = false
                    a.onTogglePin()
                }
            },
            onToggleArchiveRequested = rowActions?.let { a ->
                {
                    actionsSheetOpen = false
                    a.onToggleArchive()
                }
            },
            onToggleMuteRequested = rowActions?.let { a ->
                {
                    actionsSheetOpen = false
                    a.onToggleMute()
                }
            },
        )
    }

    if (renameOpen) {
        com.filestech.sms.ui.components.RenameGroupDialog(
            current = conversation.customName,
            onDismiss = { renameOpen = false },
            onConfirm = { nom ->
                rowActions?.onRename?.invoke(nom)
                renameOpen = false
            },
        )
    }

    if (pendingDelete) {
        RelayDialog(
            onDismissRequest = { pendingDelete = false },
            title = stringResource(R.string.conversations_delete_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.conversations_delete_confirm_body),
                    style = RelayText.MessageBody,
                    color = cs.onSurface,
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = {
                        pendingDelete = false
                        onDelete()
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingDelete = false },
                )
            },
        )
    }

    if (pendingBlock) {
        RelayDialog(
            onDismissRequest = { pendingBlock = false },
            title = stringResource(R.string.conversation_block_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.conversation_block_confirm_body),
                    style = RelayText.MessageBody,
                    color = cs.onSurface,
                )
            },
            confirmButton = {
                androidx.compose.material3.FilledTonalButton(
                    onClick = {
                        pendingBlock = false
                        onBlock()
                    },
                    // v1.25.3 — orange « blocage ». Volontairement PAS le rouge destructif : ce
                    // geste se défait, contrairement à la suppression.
                    //
                    // 🔴 v1.27.4 — LE LIBELLÉ N'EST PLUS ÉCRIT EN DUR, IL EST CALCULÉ.
                    //
                    // Il était `Color.White`, « parce que le KDoc de BrandBlocked affirmait
                    // 4,87:1 ». Ce chiffre était FAUX : blanc sur #E65100 donne **3,79:1**, sous le
                    // seuil AA de 4,5:1 — et un libellé de bouton en 14sp medium est bien du texte
                    // normal. Le noir donne **5,54:1**. Voir [onBrandContainer], qui explique
                    // pourquoi passer au noir *en dur* aurait cassé le jumeau rouge.
                    colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                        containerColor = com.filestech.sms.ui.theme.BrandBlocked,
                        contentColor = com.filestech.sms.ui.theme.onBrandContainer(
                            com.filestech.sms.ui.theme.BrandBlocked,
                        ),
                    ),
                ) { Text(stringResource(R.string.action_block)) }
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { pendingBlock = false },
                )
            },
        )
    }

    // Snap back to settled whenever the dialog closes or after a left-swipe-to-reply.
    androidx.compose.runtime.LaunchedEffect(pendingDelete, pendingBlock, dismissState.currentValue) {
        if (!pendingDelete && !pendingBlock &&
            dismissState.currentValue != androidx.compose.material3.SwipeToDismissBoxValue.Settled
        ) {
            dismissState.reset()
        }
    }
}

/**
 * v1.26.1 (audit F1) — les trois gestes d'organisation d'une conversation, portés ensemble.
 *
 * Regroupés dans un seul objet nullable plutôt qu'en trois paramètres nullables indépendants :
 * ils partagent exactement la même condition d'existence (ni mode leurre, ni mode sélection),
 * donc trois `if` séparés auraient répété la même décision — et fait franchir à l'écran le seuil
 * de complexité cyclomatique du projet.
 */
@androidx.compose.runtime.Stable
class ConversationRowActions(
    val onTogglePin: () -> Unit,
    val onToggleArchive: () -> Unit,
    val onToggleMute: () -> Unit,
    /** v1.28.3 — nom choisi (brut) d'un groupe ; normalisé plus bas. */
    val onRename: (String?) -> Unit,
)

/**
 * Bottom-sheet menu shown on long-press of a conversation row. Surfaces the actions that were
 * previously buried in nested dialogs (Block) alongside the destructive one (Delete). Each
 * choice routes back through the parent's confirm flow so destructive intents still go through
 * a 2-step confirmation.
 */
@Composable
private fun ConversationActionsSheet(
    onDismiss: () -> Unit,
    onBlockRequested: () -> Unit,
    onDeleteRequested: () -> Unit,
    onMoveToVaultRequested: (() -> Unit)? = null,
    onSelectMultipleRequested: (() -> Unit)? = null,
    /** v1.28.3 — « Renommer le groupe », `null` hors groupe ou en mode leurre. */
    onRenameRequested: (() -> Unit)? = null,
    // v1.26.1 (audit F1) — état courant pour choisir le libellé, et gestes correspondants.
    // Les six chaînes existaient déjà, traduites FR+EN, sans aucun référent.
    pinned: Boolean = false,
    archived: Boolean = false,
    muted: Boolean = false,
    onTogglePinRequested: (() -> Unit)? = null,
    onToggleArchiveRequested: (() -> Unit)? = null,
    onToggleMuteRequested: (() -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    RelaySheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.conversation_actions_title),
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
            // v1.11.0 — Trou #2 Vault polish : action "Déplacer vers le coffre"
            // exposée dans le menu long-press. Visible UNIQUEMENT hors PanicDecoy
            // (le caller passe `null` en decoy pour ne pas révéler l'existence du
            // coffre à un agresseur — défense en profondeur sur le guard VaultManager
            // qui refuse aussi côté domain).
            if (onMoveToVaultRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.vault_move_in)) },
                    modifier = Modifier.clickable(onClick = onMoveToVaultRequested),
                )
            }
            if (onRenameRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.action_rename_group)) },
                    modifier = Modifier.clickable(onClick = onRenameRequested),
                )
            }
            // v1.26.1 (audit F1) — épingler / archiver / sourdine. Placés avant les gestes
            // marquants (bloquer, supprimer) : ce sont des actions réversibles et fréquentes.
            // Les callbacks sont nullables sur le même modèle que le coffre : `null` en mode
            // leurre, pour ne pas offrir de prise sur l'organisation des conversations.
            if (onTogglePinRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.PushPin, contentDescription = null)
                    },
                    headlineContent = {
                        Text(
                            stringResource(
                                if (pinned) R.string.action_unpin else R.string.action_pin,
                            ),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onTogglePinRequested),
                )
            }
            if (onToggleArchiveRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.Inventory2, contentDescription = null)
                    },
                    headlineContent = {
                        Text(
                            stringResource(
                                if (archived) R.string.action_unarchive else R.string.action_archive,
                            ),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onToggleArchiveRequested),
                )
            }
            if (onToggleMuteRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = {
                        Icon(Icons.Outlined.NotificationsOff, contentDescription = null)
                    },
                    headlineContent = {
                        Text(
                            stringResource(
                                if (muted) R.string.action_unmute else R.string.action_mute,
                            ),
                        )
                    },
                    modifier = Modifier.clickable(onClick = onToggleMuteRequested),
                )
            }
            // v1.13.1 — entrée mode sélection multiple via long-press menu.
            if (onSelectMultipleRequested != null) {
                androidx.compose.material3.ListItem(
                    leadingContent = { Icon(Icons.Outlined.Check, contentDescription = null) },
                    headlineContent = { Text(stringResource(R.string.bulk_select_multiple)) },
                    modifier = Modifier.clickable(onClick = onSelectMultipleRequested),
                )
            }
            // v1.25.3 — « Bloquer » en rouge, comme « Supprimer » juste en dessous et comme le
            // dialogue de confirmation du fil. Le blocage ne détruit plus rien, mais il reste un
            // geste marquant : il coupe la réception et sort la conversation du flux courant.
            androidx.compose.material3.ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Block,
                        contentDescription = null,
                        tint = com.filestech.sms.ui.theme.BrandBlocked,
                    )
                },
                headlineContent = {
                    Text(
                        text = stringResource(R.string.action_block),
                        // Phase 8 — l'orange [BrandBlocked] ne donnait que 3.79:1 sur fond
                        // clair pour du texte ; voir [com.filestech.sms.ui.theme
                        // .blockedTextColor]. L'icône garde [BrandBlocked] (3.79:1,
                        // au-dessus du seuil 3:1 non textuel).
                        color = com.filestech.sms.ui.theme.blockedTextColor(
                            MaterialTheme.colorScheme,
                        ),
                    )
                },
                modifier = Modifier.clickable(onClick = onBlockRequested),
            )
            androidx.compose.material3.ListItem(
                leadingContent = {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = cs.error,
                    )
                },
                headlineContent = {
                    Text(stringResource(R.string.action_delete), color = cs.error)
                },
                modifier = Modifier.clickable(onClick = onDeleteRequested),
            )
        }
    }
}

@Composable
private fun ImportingPlaceholder(count: Int) {
    val cs = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.CircularProgressIndicator(
            color = cs.primary,
            strokeWidth = 3.dp,
        )
        // Round 4 — the column already centres its children, but a Text that wraps
        // grows to full width and its lines stay left-aligned by default — that is
        // why "Reading your existing SMS…" sat left. Full width + centred lines.
        Text(
            text = stringResource(R.string.conversations_importing_title),
            style = RelayText.ConvoName,
            color = cs.onSurface,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
        )
        Text(
            text = if (count > 0) {
                stringResource(R.string.conversations_importing_count, count)
            } else {
                stringResource(R.string.conversations_importing_body)
            },
            style = RelayText.Preview,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

/**
 * Empty state of the conversations list, on the shared [RelayEmptyState]: a centred glyph,
 * one headline, one body line, and a primary "new message" action — only on the plain empty
 * list. A filtered search or the archived view shows just the headline.
 */
@Composable
private fun ConversationsEmptyState(archived: Boolean, filtered: Boolean, onCompose: () -> Unit) {
    val emblemIcon = when {
        filtered -> Icons.Outlined.SearchOff
        archived -> Icons.Outlined.Inventory2
        else -> Icons.Outlined.ChatBubbleOutline
    }
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        RelayEmptyState(
            icon = emblemIcon,
            headline = when {
                filtered -> stringResource(R.string.conversations_no_match)
                archived -> stringResource(R.string.conversations_archived_empty)
                else -> stringResource(R.string.conversations_empty_title)
            },
            body = if (!archived && !filtered) {
                stringResource(R.string.conversations_empty_body)
            } else {
                null
            },
            buttonText = if (!archived && !filtered) {
                stringResource(R.string.action_new_message)
            } else {
                null
            },
            onButtonClick = if (!archived && !filtered) onCompose else null,
        )
    }
}

/**
 * Sort-picker entry inside the overflow menu. Renders a leading check mark on the currently
 * active mode so the user sees the current state at a glance — no separate dialog, no extra tap.
 */
@Composable
private fun SortMenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    // v1.2.3 audit U22: Semantics expose the radio-button role + selected state so TalkBack
    // reads "Date, sélectionné" instead of just "Date". U14: short haptic when the user
    // changes the sort mode (Material guideline for state-change feedback).
    DropdownMenuItem(
        leadingIcon = {
            if (selected) {
                Icon(Icons.Outlined.Check, contentDescription = null)
            } else {
                Spacer(Modifier.size(24.dp))
            }
        },
        text = { Text(label) },
        onClick = {
            haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
            onClick()
        },
        modifier = Modifier.semantics {
            this.selected = selected
            role = Role.RadioButton
            contentDescription = label
        },
    )
}

/**
 * v1.25.3 — les deux dialogues qui n'existent que pour une conversation bloquée : suppression
 * définitive (corbeille rouge de la ligne) et choix Débloquer / Supprimer (appui long).
 *
 * Extraits de [ConversationsScreen] : leur ajout y faisait franchir le seuil de complexité
 * cyclomatique, et ils forment de toute façon une unité cohérente.
 */
@Composable
private fun BlockedConversationDialogs(
    pendingDelete: com.filestech.sms.domain.model.Conversation?,
    actionsFor: com.filestech.sms.domain.model.Conversation?,
    onDismissDelete: () -> Unit,
    onDismissActions: () -> Unit,
    onConfirmDelete: (Long) -> Unit,
    onUnblock: (com.filestech.sms.domain.model.Conversation) -> Unit,
    onRequestDelete: (com.filestech.sms.domain.model.Conversation) -> Unit,
) {
    // Confirmation obligatoire : c'est le seul geste de cet écran qui efface réellement des
    // messages, de Room ET du fournisseur système.
    pendingDelete?.let { conv ->
        RelayDialog(
            onDismissRequest = onDismissDelete,
            title = stringResource(R.string.conversations_delete_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.conversations_delete_confirm_body),
                    style = RelayText.MessageBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { onConfirmDelete(conv.id) },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismissDelete,
                )
            },
        )
    }

    // « Bloquer » n'aurait aucun sens ici, la conversation l'est déjà : on propose le geste
    // réparateur (débloquer) et le geste assumé (supprimer).
    actionsFor?.let { conv ->
        RelayDialog(
            onDismissRequest = onDismissActions,
            title = stringResource(R.string.conversation_actions_title),
            content = {
                Text(
                    text = stringResource(R.string.conversation_blocked_label),
                    style = RelayText.MessageBody,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_unblock),
                    onClick = { onUnblock(conv) },
                )
            },
            dismissButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.action_delete),
                    onClick = { onRequestDelete(conv) },
                )
            },
        )
    }
}
