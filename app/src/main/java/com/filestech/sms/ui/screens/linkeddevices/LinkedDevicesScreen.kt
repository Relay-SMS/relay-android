package com.filestech.sms.ui.screens.linkeddevices

import android.Manifest
import android.os.SystemClock
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Tablet
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.filestech.sms.R
import com.filestech.sms.sync.DeviceKind
import com.filestech.sms.sync.QrPairing
import com.filestech.sms.system.settings.ouvrirLaFicheDeLApplication
import com.filestech.sms.ui.components.RelayDestructiveButton
import com.filestech.sms.ui.components.RelayDialog
import com.filestech.sms.ui.components.RelayEmptyState
import com.filestech.sms.ui.components.RelayHint
import com.filestech.sms.ui.components.RelayPrimaryButton
import com.filestech.sms.ui.components.RelaySectionLabel
import com.filestech.sms.ui.components.RelaySettingRow
import com.filestech.sms.ui.components.RelaySettingsGroup
import com.filestech.sms.ui.components.RelaySwitchRow
import com.filestech.sms.ui.components.RelayTextButton
import com.filestech.sms.ui.components.SmsTechSnackbarHost
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GLUE — the Linked devices screen: the server toggle, the linked
 * devices, the QR scanner. It reuses the app's theme and settings
 * components (the third glue point in SYNC_PROTOCOL.md); all protocol,
 * crypto, transport and storage work lives in the standalone `sync`
 * package.
 *
 * Seca-style grouped Settings like the rest of the app: a normal top
 * bar WITH the title, no big headline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkedDevicesScreen(
    onBack: () -> Unit,
    viewModel: LinkedDevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var scannerOpen by rememberSaveable { mutableStateOf(false) }
    var deviceToRemove by remember { mutableStateOf<LinkedDeviceUi?>(null) }
    val snackbarHost = remember { SnackbarHostState() }
    val linkedFmt = stringResource(R.string.linked_devices_linked)
    val removedFmt = stringResource(R.string.linked_devices_removed)

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is LinkedDevicesViewModel.Event.Linked ->
                    snackbarHost.showSnackbar(linkedFmt.format(event.name))
                is LinkedDevicesViewModel.Event.Removed ->
                    snackbarHost.showSnackbar(removedFmt.format(event.name))
            }
        }
    }

    if (scannerOpen) {
        QrScannerScreen(
            scanError = state.scanError,
            scannerEnabled = state.pendingInvite == null,
            snackbarHost = snackbarHost,
            onQrScanned = viewModel::onQrScanned,
            onClose = {
                viewModel.clearScanError()
                scannerOpen = false
            },
        )
    } else {
        LinkedDevicesList(
            state = state,
            snackbarHost = snackbarHost,
            onBack = onBack,
            onOpenScanner = { scannerOpen = true },
            onToggleServer = viewModel::setServerEnabled,
            onRemove = { deviceToRemove = it },
        )
    }

    // The confirm dialog renders above the scanner: the code is still
    // in frame behind it, and dismissing returns to scanning.
    val pendingInvite = state.pendingInvite
    if (pendingInvite != null) {
        val kindLabel = kindLabel(pendingInvite.kind)
        RelayDialog(
            onDismissRequest = viewModel::dismissInvite,
            title = stringResource(R.string.linked_devices_confirm_title),
            content = {
                Text(
                    stringResource(
                        R.string.linked_devices_confirm_body,
                        pendingInvite.displayName,
                        kindLabel,
                    ),
                )
            },
            confirmButton = {
                RelayTextButton(
                    text = stringResource(R.string.linked_devices_confirm_action),
                    confirm = true,
                    onClick = {
                        viewModel.confirmLink()
                        scannerOpen = false
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = viewModel::dismissInvite,
                )
            },
        )
    }

    val toRemove = deviceToRemove
    if (toRemove != null) {
        RelayDialog(
            onDismissRequest = { deviceToRemove = null },
            title = stringResource(
                R.string.linked_devices_remove_title,
                toRemove.device.displayName,
            ),
            content = { Text(stringResource(R.string.linked_devices_remove_body)) },
            confirmButton = {
                RelayDestructiveButton(
                    text = stringResource(R.string.linked_devices_remove_action),
                    onClick = {
                        viewModel.removeDevice(
                            toRemove.device.deviceId,
                            toRemove.device.displayName,
                        )
                        deviceToRemove = null
                    },
                )
            },
            dismissButton = {
                RelayTextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = { deviceToRemove = null },
                )
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LinkedDevicesList(
    state: LinkedDevicesState,
    snackbarHost: SnackbarHostState,
    onBack: () -> Unit,
    onOpenScanner: () -> Unit,
    onToggleServer: (Boolean) -> Unit,
    onRemove: (LinkedDeviceUi) -> Unit,
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.linked_devices_title)) },
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
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
        ) {
            RelaySectionLabel(stringResource(R.string.linked_devices_section_server))
            RelaySettingsGroup {
                item {
                    RelaySwitchRow(
                        icon = Icons.Outlined.Wifi,
                        title = stringResource(R.string.linked_devices_server_title),
                        checked = state.serverEnabled,
                        onCheckedChange = onToggleServer,
                    )
                }
            }
            RelayHint(stringResource(R.string.linked_devices_server_hint))

            RelaySectionLabel(stringResource(R.string.linked_devices_section_devices))
            // Keyed on the device list, as the Settings screen keys its
            // conditional groups: without it the group's content lambda
            // is reused and a freshly linked (or removed) row never
            // appears until the next unrelated recomposition.
            key(state.devices) {
                RelaySettingsGroup {
                    item(onClick = onOpenScanner) {
                        RelaySettingRow(
                            icon = Icons.Outlined.QrCodeScanner,
                            title = stringResource(R.string.linked_devices_link_action),
                            chevron = true,
                        )
                    }
                    state.devices.forEach { ui ->
                        item {
                            RelaySettingRow(
                                icon = kindIcon(ui.device.kind),
                                title = ui.device.displayName,
                                subtitle = deviceSubtitle(ui),
                                trailing = {
                                    IconButton(onClick = { onRemove(ui) }) {
                                        Icon(
                                            painterResource(R.drawable.ic_trash),
                                            contentDescription = stringResource(
                                                R.string.linked_devices_remove_content_desc,
                                            ),
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
            if (state.devices.isEmpty()) {
                RelayEmptyState(
                    icon = Icons.Outlined.Devices,
                    headline = stringResource(R.string.linked_devices_empty_title),
                    body = stringResource(R.string.linked_devices_empty_body),
                    buttonText = stringResource(R.string.linked_devices_link_action),
                    onButtonClick = onOpenScanner,
                    modifier = Modifier.padding(top = 32.dp),
                )
            }
            Spacer(Modifier.size(32.dp))
        }
    }
}

@Composable
private fun kindLabel(kind: DeviceKind): String = when (kind) {
    DeviceKind.DESKTOP -> stringResource(R.string.linked_devices_kind_desktop)
    DeviceKind.PHONE -> stringResource(R.string.linked_devices_kind_phone)
    DeviceKind.TABLET -> stringResource(R.string.linked_devices_kind_tablet)
}

private fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.DESKTOP -> Icons.Outlined.Computer
    DeviceKind.PHONE -> Icons.Outlined.PhoneAndroid
    DeviceKind.TABLET -> Icons.Outlined.Tablet
}

@Composable
private fun deviceSubtitle(ui: LinkedDeviceUi): String {
    val context = LocalContext.current
    val linkedDate = remember(ui.device.linkedAt) {
        android.text.format.DateFormat.getMediumDateFormat(context)
            .format(Date(ui.device.linkedAt))
    }
    val status = if (ui.connected) {
        stringResource(R.string.linked_devices_connected)
    } else {
        stringResource(R.string.linked_devices_linked_on, linkedDate)
    }
    return "${kindLabel(ui.device.kind)} · $status"
}

@Composable
private fun scanErrorText(failure: QrPairing.Failure): String = when (failure) {
    QrPairing.Failure.NotAPairingCode -> stringResource(R.string.linked_devices_scan_invalid)
    QrPairing.Failure.UnsupportedVersion -> stringResource(R.string.linked_devices_scan_unsupported)
    is QrPairing.Failure.InvalidField -> stringResource(R.string.linked_devices_scan_damaged)
}

/**
 * The QR scanner: a full-screen black view with the camera preview, a
 * centred frame to aim with, and the hint (or the scan error) at the
 * bottom. The camera permission is requested here, at the moment it is
 * needed — never earlier.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
private fun QrScannerScreen(
    scanError: QrPairing.Failure?,
    scannerEnabled: Boolean,
    snackbarHost: SnackbarHostState,
    onQrScanned: (String) -> Unit,
    onClose: () -> Unit,
) {
    val camera = rememberPermissionCamera()
    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.linked_devices_scan_title),
                        color = Color.White,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Black),
            )
        },
        snackbarHost = { SmsTechSnackbarHost(snackbarHost) },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color.Black),
            contentAlignment = Alignment.Center,
        ) {
            if (camera.granted) {
                CameraQrPreview(
                    enabled = scannerEnabled,
                    onQrText = onQrScanned,
                    modifier = Modifier.fillMaxSize(),
                )
                // The aim frame: a bordered square in the middle of the
                // preview. It is a guide only — ZXing reads the whole
                // frame, so a code slightly off-centre still scans.
                Box(
                    modifier = Modifier
                        .size(SCAN_FRAME_SIZE_DP.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(24.dp)),
                )
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 32.dp, end = 32.dp, bottom = 48.dp),
                ) {
                    if (scanError != null) {
                        Text(
                            text = scanErrorText(scanError),
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.size(12.dp))
                    }
                    Text(
                        text = stringResource(R.string.linked_devices_scan_hint),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.linked_devices_scan_camera_needed),
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.size(24.dp))
                    RelayPrimaryButton(
                        text = stringResource(R.string.linked_devices_scan_allow_camera),
                        onClick = camera.request,
                    )
                }
            }
        }
    }
}

/** The CAMERA permission, requested at scan time (accompanist, as elsewhere in the app). */
@Stable
private class CameraPermission(
    val granted: Boolean,
    val request: () -> Unit,
)

/**
 * Mirrors [com.filestech.sms.ui.components.rememberPermissionLocalisation]:
 * after two denials Android answers instantly without showing a dialog,
 * so a denial that arrives faster than a human could tap it means the
 * permission can only be granted from the app's system page — open it
 * instead of leaving a dead button.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun rememberPermissionCamera(): CameraPermission {
    val context = LocalContext.current
    val requestStarted = remember { mutableLongStateOf(0L) }
    val state = rememberPermissionState(
        permission = Manifest.permission.CAMERA,
        onPermissionResult = { granted ->
            val instant = SystemClock.elapsedRealtime() - requestStarted.longValue <
                SEUIL_SANS_DIALOGUE_MS
            if (!granted && instant) {
                ouvrirLaFicheDeLApplication(context)
            }
        },
    )
    return CameraPermission(
        granted = state.status == PermissionStatus.Granted,
        request = {
            requestStarted.longValue = SystemClock.elapsedRealtime()
            state.launchPermissionRequest()
        },
    )
}

/**
 * CameraX preview + ZXing decoding. The preview binds to the activity
 * lifecycle, the analyzer runs on its own thread, and both are torn
 * down when the scanner leaves composition — the camera must not keep
 * running (and draining the battery) after the user closes the
 * scanner. Decoding pauses while [enabled] is false (a code is already
 * waiting for the user's confirm), and resumes when it is dismissed.
 */
@Composable
private fun CameraQrPreview(
    enabled: Boolean,
    onQrText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    val enabledState = rememberUpdatedState(enabled)
    val onQrTextState = rememberUpdatedState(onQrText)
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var bindFailed by remember { mutableStateOf(false) }
    val disposed = remember { AtomicBoolean(false) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            cameraProvider?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener(
            {
                if (disposed.get()) return@addListener
                val provider = runCatching { providerFuture.get() }.getOrNull()
                if (provider == null) {
                    bindFailed = true
                    return@addListener
                }
                cameraProvider = provider
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(
                            analysisExecutor,
                            QrCodeAnalyzer(
                                isEnabled = { enabledState.value },
                                onQrText = { text -> onQrTextState.value(text) },
                            ),
                        )
                    }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    bindFailed = true
                }
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        if (bindFailed) {
            Text(
                text = stringResource(R.string.linked_devices_scan_camera_error),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 32.dp),
            )
        }
    }
}

/** Y-plane bytes plus their dimensions after a rotation. */
internal data class RotatedYuv(
    val data: ByteArray,
    val width: Int,
    val height: Int,
)

/**
 * Rotates compacted Y-plane bytes 90 degrees counter-clockwise.
 * Returns the rotated bytes with swapped dimensions (new width is the
 * old height, new height is the old width). Pure function, no Android
 * APIs — the regression test drives it directly.
 *
 * This exists because ZXing's `PlanarYUVLuminanceSource` does not
 * support `rotateCounterClockwise()`: it inherits the base method,
 * which unconditionally throws `UnsupportedOperationException` — and
 * calling it on a portrait frame killed the app on the first frame
 * (2026-11-13). Never rotate via the source; rotate the bytes.
 */
internal fun rotateYuv90CounterClockwise(
    data: ByteArray,
    width: Int,
    height: Int,
): RotatedYuv {
    val newWidth = height
    val rotated = ByteArray(data.size)
    for (y in 0 until height) {
        for (x in 0 until width) {
            // Source pixel (x, y) lands at (y, width - 1 - x).
            rotated[(width - 1 - x) * newWidth + y] = data[y * width + x]
        }
    }
    return RotatedYuv(rotated, newWidth, width)
}

/**
 * Decodes QR codes from the camera's YUV frames with ZXing core —
 * pure Java, on-device, no Play Services (ML Kit is forbidden here:
 * the phone has no Play Services, and the app claims no tracking).
 *
 * Only the Y (luminance) plane is read, and only QR codes are looked
 * for: the pairing code is the only thing this scanner is for.
 * Frames are rotated back to upright from
 * `imageInfo.rotationDegrees` before decoding, so the code scans in
 * any orientation. The rotation is done on the Y bytes themselves
 * ([rotateYuv90CounterClockwise]): ZXing's `PlanarYUVLuminanceSource`
 * does NOT support `rotateCounterClockwise()` — it inherits the base
 * method, which unconditionally throws — so the source must be built
 * from already-upright bytes.
 */
private class QrCodeAnalyzer(
    private val isEnabled: () -> Boolean,
    private val onQrText: (String) -> Unit,
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE)))
    }

    override fun analyze(image: ImageProxy) {
        try {
            if (isEnabled()) {
                // A bad frame must never kill the app: the analyzer
                // runs on a background executor thread, where an
                // uncaught exception kills the whole process. A
                // failed frame is just skipped — the next frame
                // follows in milliseconds.
                runCatching { decode(image) }.getOrNull()?.let(onQrText)
            }
        } finally {
            // Every frame must be closed, decoded or not — a leaked
            // frame stalls the pipeline.
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        val plane = image.planes[0]
        val width = image.width
        val height = image.height
        val rowStride = plane.rowStride
        val buffer = plane.buffer
        if (buffer.remaining() < rowStride * height) return null
        val yuv = ByteArray(buffer.remaining())
        buffer.get(yuv)
        // The Y plane's rows may be padded (rowStride > width): compact
        // them before handing the bytes to ZXing.
        var data = if (rowStride == width) {
            yuv
        } else {
            val compact = ByteArray(width * height)
            for (y in 0 until height) {
                yuv.copyInto(compact, y * width, y * rowStride, y * rowStride + width)
            }
            compact
        }
        var frameWidth = width
        var frameHeight = height
        // Rotate the Y bytes back to upright BEFORE building the
        // source: ZXing's PlanarYUVLuminanceSource throws on
        // rotateCounterClockwise(), so the source must be built from
        // already-upright bytes.
        repeat(image.imageInfo.rotationDegrees / 90) {
            val rotated = rotateYuv90CounterClockwise(data, frameWidth, frameHeight)
            data = rotated.data
            frameWidth = rotated.width
            frameHeight = rotated.height
        }
        val source = PlanarYUVLuminanceSource(
            data,
            frameWidth,
            frameHeight,
            0,
            0,
            frameWidth,
            frameHeight,
            false,
        )
        return runCatching {
            reader.decode(BinaryBitmap(HybridBinarizer(source))).text
        }.getOrNull()
    }
}

/** Side of the square aim frame in the scanner, in dp. */
private const val SCAN_FRAME_SIZE_DP = 264

/**
 * A dialog shown and refused by a human takes far longer than this —
 * see [com.filestech.sms.ui.components.rememberPermissionLocalisation]
 * for why the threshold exists.
 */
private const val SEUIL_SANS_DIALOGUE_MS = 400L
