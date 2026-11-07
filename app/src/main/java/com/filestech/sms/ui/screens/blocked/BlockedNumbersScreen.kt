package com.filestech.sms.ui.screens.blocked

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.filestech.sms.R
import com.filestech.sms.domain.model.BlockedNumber
import com.filestech.sms.domain.repository.BlockedNumberRepository
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.RelayTextField
import com.filestech.sms.ui.theme.RelayText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BlockedNumbersViewModel @Inject constructor(
    private val repo: BlockedNumberRepository,
) : ViewModel() {
    val state: StateFlow<List<BlockedNumber>> = repo.observe()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    fun add(number: String) = viewModelScope.launch { repo.block(number) }
    fun remove(rawNumber: String) = viewModelScope.launch { repo.unblock(rawNumber) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockedNumbersScreen(onBack: () -> Unit, viewModel: BlockedNumbersViewModel = hiltViewModel()) {
    val rows by viewModel.state.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }
    var newNumber by remember { mutableStateOf("") }

    /** v1.25.3 (audit M21) — numéro en attente de confirmation de déblocage. */
    var confirmUnblockFor by remember { mutableStateOf<String?>(null) }

    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.blocked_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            // The accessible name lives on the BUTTON, not on the decorative icon: without
            // it TalkBack would announce a nameless button on the only way to add a number.
            // The icon stays unnamed so its label is never announced twice.
            FloatingActionButton(
                onClick = { showDialog = true },
                modifier = Modifier
                    .size(56.dp)
                    .semantics { contentDescription = stringResource(R.string.blocked_add) },
                shape = CircleShape,
                containerColor = cs.primary,
                contentColor = cs.onPrimary,
            ) {
                Icon(
                    Icons.Outlined.Add,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
    ) { padding ->
        if (rows.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.Center,
            ) {
                RelayEmptyState(
                    icon = Icons.Outlined.Block,
                    headline = stringResource(R.string.blocked_empty),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.id }) { item ->
                    BlockedRow(
                        number = item.rawNumber,
                        label = item.label,
                        onUnblock = { confirmUnblockFor = item.rawNumber },
                    )
                }
            }
        }
    }

    confirmUnblockFor?.let { number ->
        RelayDialog(
            onDismissRequest = { confirmUnblockFor = null },
            title = stringResource(R.string.blocked_unblock_confirm_title),
            content = {
                Text(
                    text = stringResource(R.string.blocked_unblock_confirm_body, number),
                    style = RelayText.Preview,
                    color = cs.onSurfaceVariant,
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_unblock),
                    onClick = {
                        confirmUnblockFor = null
                        viewModel.remove(number)
                    },
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { confirmUnblockFor = null },
                )
            },
        )
    }

    if (showDialog) {
        RelayDialog(
            onDismissRequest = { showDialog = false; newNumber = "" },
            title = stringResource(R.string.blocked_add),
            content = {
                RelayTextField(
                    value = newNumber,
                    onValueChange = { newNumber = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_block),
                    onClick = { viewModel.add(newNumber); showDialog = false; newNumber = "" },
                    enabled = newNumber.isNotBlank(),
                    confirm = true,
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { showDialog = false; newNumber = "" },
                )
            },
        )
    }
}

/** One blocked number: 72dp row with a block glyph, number + optional label, unblock action. */
@Composable
private fun BlockedRow(
    number: String,
    label: String?,
    onUnblock: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.Block,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = number,
                style = RelayText.ConvoName,
                color = cs.onSurface,
            )
            if (label != null) {
                Text(
                    text = label,
                    style = RelayText.Preview,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        // v1.25.3 (audit M21) — passe par une confirmation : un tap
        // accidentel remettait en circulation un numéro indésirable, sans
        // annulation possible ni trace de ce qui venait de se produire.
        IconButton(onClick = onUnblock) {
            Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_unblock))
        }
    }
}
