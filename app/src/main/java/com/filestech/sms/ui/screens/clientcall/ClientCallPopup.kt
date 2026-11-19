package com.filestech.sms.ui.screens.clientcall

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.core.ext.formatPhoneNumberForDisplay
import com.filestech.sms.ui.components.RelayAvatar
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.filestech.sms.ui.theme.BrandSuccessLight

/**
 * Client mode — tablet calls: the incoming-call dropdown, rendered
 * as an AppRoot overlay (like the Safety call ack): it floats over
 * whatever screen is showing, so a ringing call is visible from a
 * thread or Settings, not just the list. Small, like the desktop's
 * mini incoming popup: the caller's avatar + name + "Incoming
 * call", and ONE green button with a chat bubble — tapping it opens
 * the conversation with that number (or starts a new chat) so the
 * user can text them when the phone is out of reach.
 *
 * The popup dismisses itself when the call ends or is answered
 * elsewhere (the engine clears the call state); it hides behind
 * the lock screen (a caller's name is contact info, and the popup
 * is still there after unlock while the call rings). No
 * answer/decline buttons: a linked device cannot answer or end a
 * call, and there is no public API for call audio on one.
 */
@Composable
fun ClientCallPopupOverlay(
    showLock: Boolean,
    onOpenThread: (Long) -> Unit,
    viewModel: ClientCallViewModel = hiltViewModel(),
) {
    val popup by viewModel.popup.collectAsStateWithLifecycle()
    val host = remember { SnackbarHostState() }
    val openFailedMessage = stringResource(R.string.client_call_open_failed)

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ClientCallViewModel.Event.OpenThread -> onOpenThread(event.conversationId)
                ClientCallViewModel.Event.OpenChatFailed -> host.showSnackbar(openFailedMessage)
                // Dial feedback belongs to the thread screen, which
                // owns the dial — this instance never dials.
                is ClientCallViewModel.Event.DialFailed -> Unit
            }
        }
    }

    // The last popup is remembered so the exit animation has
    // something to draw: clearing the call nulls `popup` at once,
    // and without this the card would vanish instead of sliding out
    // (the pinned banner's `lastPinned` pattern).
    var lastPopup by remember { mutableStateOf<ClientCallPopup?>(null) }
    if (popup != null) lastPopup = popup

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = popup != null && !showLock,
            enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding(),
        ) {
            lastPopup?.let {
                ClientCallPopupCard(
                    popup = it,
                    onChat = { viewModel.openChat(it.number) },
                )
            }
        }
        // The failure snackbar sits where every other screen puts
        // its own; the Box stays transparent to touches until one
        // shows (the Safety call ack overlay's pattern).
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            SmsTechSnackbarHost(host)
        }
    }
}

/** The dropdown card: avatar + name + "Incoming call", one green chat button. */
@Composable
private fun ClientCallPopupCard(
    popup: ClientCallPopup,
    onChat: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val title = popup.displayName
        ?: popup.number.takeIf { it.isNotBlank() }?.formatPhoneNumberForDisplay()
        ?: stringResource(R.string.client_call_unknown_number)
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = cs.surfaceContainerHigh,
        tonalElevation = 6.dp,
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RelayAvatar(
                label = title,
                size = 40.dp,
                customUri = popup.avatarUri,
                // A 1:1 caller who is not a contact gets the grey
                // person avatar, like the thread header.
                isUnknownContact = popup.displayName == null && popup.number.isNotBlank(),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.client_call_incoming),
                    style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            // No number (withheld, or the phone lacks the call-log
            // permission): there is nobody to text, so the button
            // stays hidden and the popup is informational only.
            if (popup.number.isNotBlank()) {
                Spacer(Modifier.width(12.dp))
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(BrandSuccessLight)
                        .clickable(onClick = onChat)
                        .semantics { role = Role.Button },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_notification_message),
                        contentDescription = stringResource(R.string.client_call_chat),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}
