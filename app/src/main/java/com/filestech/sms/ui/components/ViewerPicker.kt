package com.filestech.sms.ui.components

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.filestech.sms.R
import com.filestech.sms.data.local.datastore.ViewerChoiceStore
import com.filestech.sms.data.local.datastore.ViewerKind
import com.filestech.sms.domain.model.Attachment
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Round 33 — Relay's own viewer picker with remember-my-choice.
 *
 * The system resolver was a dead end: with a plain ACTION_VIEW
 * intent it shows the most-recently-used handler on top with Just
 * once / Always and the rest under "Use a different app" — and
 * tapping a non-top app opens it once only, with no Always offered
 * for it on that tap. In theory the next tap promotes it to the
 * top, where Always becomes available; on Kristin's phone Gallery
 * never gets promoted no matter how many times she opens with it
 * (the resolver ranks by overall usage and Photos wins). Relay
 * cannot control that ranking or offer Always for a non-top app —
 * so Relay remembers the choice itself, in its own settings
 * ([ViewerChoiceStore]).
 *
 * Opening a media attachment (image, video, audio — the same path,
 * a remembered choice per type) goes through
 * [rememberAttachmentOpener]: no remembered viewer (or the
 * remembered app is gone) shows this picker — every handler with
 * its icon + name, tap to select, then Just once (opens without
 * remembering) or Always (remembers the package and opens with
 * it). A remembered viewer opens directly with `setPackage`, no
 * picker. One handler opens directly too — a picker with one app
 * is no choice. No handler at all still tries the plain intent —
 * an empty query can mean the query was blind, and a tap must
 * never silently die. Anything that is not image/video/audio keeps
 * the plain system intent.
 */
internal data class ViewerApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

/** What opening an attachment resolves to, once the handlers are known. */
internal sealed interface ViewerOpenDecision {
    /** Open directly — with this package, or the plain intent when `null`. */
    data class Open(val packageName: String?) : ViewerOpenDecision

    /** Show Relay's picker with these apps. */
    data class Pick(val apps: List<ViewerApp>) : ViewerOpenDecision

    /**
     * The query found no handler. The caller still tries the
     * plain intent (fail open): an empty query can also mean the
     * query itself was blind, not that no viewer exists.
     */
    data object NoHandler : ViewerOpenDecision
}

/**
 * The open decision for [remembered] (the stored "Always" package,
 * `null` when none) against the [apps] that handle this attachment:
 * no handlers → nothing; one handler → open it directly; a
 * remembered handler that is still installed → open it directly;
 * otherwise the picker. A remembered app that was uninstalled is
 * simply not among the handlers, so the picker shows again.
 */
internal fun resolveViewerOpen(
    remembered: String?,
    apps: List<ViewerApp>,
): ViewerOpenDecision {
    if (apps.isEmpty()) return ViewerOpenDecision.NoHandler
    if (apps.size == 1) return ViewerOpenDecision.Open(apps.first().packageName)
    if (remembered != null && apps.any { it.packageName == remembered }) {
        return ViewerOpenDecision.Open(remembered)
    }
    return ViewerOpenDecision.Pick(apps)
}

/**
 * Every app that handles ACTION_VIEW for ([uri], [mimeType]),
 * Relay itself excluded (it must never offer itself as its own
 * viewer), deduped by package and sorted by name. Empty when the
 * query fails — the caller fails open to the plain intent.
 */
internal fun queryViewerApps(context: Context, uri: Uri, mimeType: String): List<ViewerApp> {
    val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(uri, mimeType) }
    val resolveInfos = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
    }.getOrNull() ?: return emptyList()
    val pm = context.packageManager
    return resolveInfos
        .mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            if (packageName == context.packageName) return@mapNotNull null
            ViewerApp(
                packageName = packageName,
                label = runCatching { info.loadLabel(pm).toString() }.getOrNull() ?: packageName,
                icon = runCatching { info.loadIcon(pm) }.getOrNull(),
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase() }
}

/** The user-visible name of the app owning [packageName], or the package itself when unknown. */
internal fun appLabelFor(context: Context, packageName: String): String {
    return runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getApplicationInfo(
                packageName,
                PackageManager.ApplicationInfoFlags.of(0),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(packageName, 0)
        }
        context.packageManager.getApplicationLabel(info).toString()
    }.getOrNull() ?: packageName
}

/**
 * The Hilt entry point for [ViewerChoiceStore], for composables
 * that cannot take it as a constructor parameter (the attachment
 * opener is a `remember` helper, not a ViewModel) — the same
 * pattern as `ClientSyncEngineEntryPoint`.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ViewerChoiceStoreEntryPoint {
    fun viewerChoiceStore(): ViewerChoiceStore
}

/** An attachment waiting on the picker: what it is, its kind, and the apps that can open it. */
private data class PendingViewerPick(
    val attachment: Attachment,
    val kind: ViewerKind,
    val apps: List<ViewerApp>,
)

/**
 * The attachment opener for a screen: call it where the screen
 * opens attachments (thread bubbles, the gallery, the details
 * panel) and use the returned lambda in place of a bare
 * [ouvrirPieceJointe] call. The picker dialog renders from here
 * when an open needs it — the call site does nothing more.
 */
@Composable
internal fun rememberAttachmentOpener(): (Attachment) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember(context) {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ViewerChoiceStoreEntryPoint::class.java,
        ).viewerChoiceStore()
    }
    var pending by remember { mutableStateOf<PendingViewerPick?>(null) }
    pending?.let { pick ->
        ViewerPickerDialog(
            apps = pick.apps,
            onPickOnce = {
                pending = null
                ouvrirPieceJointe(context, pick.attachment)
            },
            onPickAlways = { app ->
                pending = null
                scope.launch { store.remember(pick.kind, app.packageName) }
                ouvrirPieceJointe(context, pick.attachment, packageName = app.packageName)
            },
            onDismiss = { pending = null },
        )
    }
    return remember(context, scope, store) {
        val opener: (Attachment) -> Unit = { attachment ->
            scope.launch {
                openAttachment(context, store, attachment) { pick -> pending = pick }
            }
        }
        opener
    }
}

/**
 * One open: non-media types go straight to the plain intent; media
 * types resolve the remembered viewer against the current
 * handlers, opening directly or raising the picker through
 * [onPick]. With no handler the plain intent is tried anyway —
 * a tap must never silently die.
 */
private suspend fun openAttachment(
    context: Context,
    store: ViewerChoiceStore,
    attachment: Attachment,
    onPick: (PendingViewerPick) -> Unit,
) {
    val targetUri = attachment.toShareableUri(context)
    if (targetUri == null) {
        Timber.w("AttachmentOpener: cannot resolve URI for %s", attachment.localUri)
        return
    }
    val kind = ViewerKind.fromMimeType(attachment.mimeType)
    if (kind == null) {
        ouvrirPieceJointe(context, attachment)
        return
    }
    val apps = withContext(Dispatchers.Default) {
        queryViewerApps(context, targetUri, attachment.mimeType)
    }
    val remembered = store.flow.first().forKind(kind)
    when (val decision = resolveViewerOpen(remembered, apps)) {
        is ViewerOpenDecision.Open ->
            ouvrirPieceJointe(context, attachment, packageName = decision.packageName)
        is ViewerOpenDecision.Pick -> onPick(PendingViewerPick(attachment, kind, decision.apps))
        ViewerOpenDecision.NoHandler -> {
            // Fail open: an empty query does not prove no viewer
            // exists (a missing <queries> entry blinded it in
            // round 33) — and the plain intent is not blocked by
            // package visibility, so it still reaches a handler
            // when one is installed. If truly none is, the
            // runCatching inside ouvrirPieceJointe logs and
            // returns. A tap must never silently die.
            Timber.w(
                "AttachmentOpener: no viewer for %s — trying the plain intent",
                attachment.mimeType,
            )
            ouvrirPieceJointe(context, attachment)
        }
    }
}

/**
 * The picker: every handler as a radio row (icon + name), then
 * Just once / Always — the system's mental model. Just once opens
 * without remembering; Always remembers the package and opens
 * with it. Dismissing (back, outside tap) opens nothing.
 */
@Composable
private fun ViewerPickerDialog(
    apps: List<ViewerApp>,
    onPickOnce: (ViewerApp) -> Unit,
    onPickAlways: (ViewerApp) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<ViewerApp?>(null) }
    RelayDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.viewer_picker_title),
        content = {
            apps.forEach { app ->
                ViewerPickerRow(
                    app = app,
                    selected = selected == app,
                    onClick = { selected = app },
                )
            }
        },
        confirmButton = {
            RelayTextButton(
                text = stringResource(R.string.viewer_always),
                enabled = selected != null,
                onClick = { selected?.let(onPickAlways) },
                confirm = true,
            )
        },
        dismissButton = {
            RelayTextButton(
                text = stringResource(R.string.viewer_just_once),
                enabled = selected != null,
                onClick = { selected?.let(onPickOnce) },
            )
        },
    )
}

/** One handler row: the app icon + name, the whole row tappable (radio role). */
@Composable
private fun ViewerPickerRow(
    app: ViewerApp,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val bitmap = remember(app.icon) {
        app.icon?.let {
            runCatching { it.toBitmap().asImageBitmap() }.getOrNull()
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
            )
        } else {
            // No icon (an app without one): a blank slot keeps the
            // row aligned with the rows that have one.
            Spacer(Modifier.size(40.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyLarge,
            color = cs.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The row owns the tap (selectable above); the button itself
        // is display-only, so TalkBack announces one radio, not two.
        RadioButton(selected = selected, onClick = null)
    }
}
