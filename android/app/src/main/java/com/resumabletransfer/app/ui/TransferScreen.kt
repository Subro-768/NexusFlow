package com.resumabletransfer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumabletransfer.app.PeerDevice
import com.resumabletransfer.app.R
import com.resumabletransfer.app.TransferProgress
import com.resumabletransfer.app.TransferStatus

/** Minimum accessible touch target per Material 3. */
private val MinTouchTarget = 48.dp

@Composable
fun TransferScreen(
    onMenuClick: () -> Unit,
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    serverPort: String,
    onServerPortChange: (String) -> Unit,
    recentIps: List<String> = emptyList(),
    onSelectRecentIp: (String) -> Unit = {},
    discoveredPeers: List<PeerDevice> = emptyList(),
    onSelectPeer: (PeerDevice) -> Unit = {},
    onRefreshPeers: () -> Unit = {},
    onScanQr: () -> Unit = {},
    isDiscovering: Boolean = false,
    onTestConnection: () -> Unit,
    connectionStatusText: String,
    isConnected: Boolean,
    selectedFileName: String?,
    selectedFileSize: Long?,
    selectedFileUri: Uri?,
    onPickFile: () -> Unit,
    progress: TransferProgress,
    onStartTransfer: () -> Unit,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit,
    onCancelTransfer: () -> Unit,
    onNewTransfer: () -> Unit,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() }
) {
    val scrollState = rememberScrollState()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NexusTopBar(
                subtitle = stringResource(R.string.brand_tagline),
                onMenuClick = onMenuClick,
                actions = {
                    IconButton(
                        onClick = onNewTransfer,
                        modifier = Modifier.size(MinTouchTarget)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_new_transfer),
                            tint = SoloraNeonLime,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ProgressCard(
                progress = progress,
                canStart = selectedFileUri != null,
                onStartTransfer = onStartTransfer,
                onPauseTransfer = onPauseTransfer,
                onResumeTransfer = onResumeTransfer,
                onCancelTransfer = onCancelTransfer,
                onNewTransfer = onNewTransfer
            )

            PayloadSourceCard(
                selectedFileName = selectedFileName,
                selectedFileSize = selectedFileSize,
                onPickFile = onPickFile
            )

            EndpointCard(
                serverIp = serverIp,
                onServerIpChange = onServerIpChange,
                serverPort = serverPort,
                onServerPortChange = onServerPortChange,
                recentIps = recentIps,
                onSelectRecentIp = onSelectRecentIp,
                connectionStatusText = connectionStatusText,
                isConnected = isConnected,
                onTestConnection = onTestConnection
            )

            PeerDiscoveryPanel(
                discoveredPeers = discoveredPeers,
                selectedHost = serverIp,
                isDiscovering = isDiscovering,
                onSelectPeer = onSelectPeer,
                onRefreshPeers = onRefreshPeers,
                onScanQr = onScanQr
            )

            ErrorBanner(progress.errorMessage, progress.status)

            LogCard(progress.logMessage)
        }
    }
}

/** Shared NEXUS FLOW app bar — previously copy-pasted into three screens. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NexusTopBar(
    subtitle: String,
    onMenuClick: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_nexus_flow),
                    contentDescription = stringResource(R.string.cd_logo),
                    modifier = Modifier.size(34.dp)
                )
                Column {
                    Text(
                        text = buildAnnotatedString {
                            withStyle(
                                SpanStyle(
                                    color = SoloraCyan,
                                    fontWeight = FontWeight.Black
                                )
                            ) { append(stringResource(R.string.brand_name_full)) }
                            withStyle(
                                SpanStyle(
                                    color = SoloraEnergyGreen,
                                    fontWeight = FontWeight.Black
                                )
                            ) { append(stringResource(R.string.brand_name_accent)) }
                        },
                        fontSize = 16.sp,
                        letterSpacing = 1.5.sp
                    )
                    Text(
                        subtitle,
                        fontSize = 9.sp,
                        color = SoloraTextSecondary,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 1.sp
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onMenuClick, modifier = Modifier.size(MinTouchTarget)) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = stringResource(R.string.cd_open_menu),
                    tint = SoloraTextPrimary
                )
            }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = SoloraTextPrimary
        )
    )
}

@Composable
private fun ProgressCard(
    progress: TransferProgress,
    canStart: Boolean,
    onStartTransfer: () -> Unit,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit,
    onCancelTransfer: () -> Unit,
    onNewTransfer: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.card_data_flow),
                    style = MaterialTheme.typography.labelMedium,
                    color = SoloraTextSecondary
                )
                SoloraStatusBadge(progress.status)
            }

            ProgressGauge(progress)

            MetricsRow(progress)

            if (progress.status == TransferStatus.COMPLETED) {
                ShaVerifiedBanner(progress.calculatedSha256)
            }

            ControlButtons(
                status = progress.status,
                progressPercent = progress.progressPercent,
                canStart = canStart,
                onStartTransfer = onStartTransfer,
                onPauseTransfer = onPauseTransfer,
                onResumeTransfer = onResumeTransfer,
                onCancelTransfer = onCancelTransfer,
                onNewTransfer = onNewTransfer
            )
        }
    }
}

/**
 * Radial progress meter.
 *
 * Two fixes over the previous implementation:
 *  - the arc is animated, so it sweeps instead of snapping one chunk-width per
 *    server response;
 *  - the track indicator is hidden from accessibility, because TalkBack
 *    otherwise announced two progress bars and the track always reported 100%.
 */
@Composable
private fun ProgressGauge(progress: TransferProgress) {
    val accent = progress.status.accentColor()
    val animatedProgress by animateFloatAsState(
        targetValue = progress.progressFraction.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 420, easing = FastOutSlowInEasing),
        label = "gauge"
    )

    val statusLabel = when (progress.status) {
        TransferStatus.COMPLETED -> stringResource(R.string.status_verified)
        else -> stringResource(progress.status.labelRes())
    }
    val subLabel = if (progress.status == TransferStatus.TRANSFERRING && progress.speedBytesPerSec > 0) {
        formatSpeed(progress.speedBytesPerSec)
    } else {
        statusLabel
    }

    // Resolved here, not inside the semantics block: stringResource is a
    // @Composable call and cannot be made from a semantics lambda.
    val gaugeDescription = "${stringResource(R.string.card_data_flow)} $statusLabel"

    Box(
        modifier = Modifier
            .size(190.dp)
            .semantics {
                contentDescription = gaugeDescription
                progressBarRangeInfo = ProgressBarRangeInfo(animatedProgress, 0f..1f)
                stateDescription = "${progress.progressPercent}%"
            },
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            progress = { 1f },
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics { },
            color = SoloraSurfaceElevated,
            strokeWidth = 14.dp,
            strokeCap = StrokeCap.Round
        )
        CircularProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier.fillMaxSize(),
            color = accent,
            strokeWidth = 14.dp,
            strokeCap = StrokeCap.Round
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "${progress.progressPercent}%",
                style = MaterialTheme.typography.displayLarge,
                color = SoloraTextPrimary
            )
            Text(
                subLabel,
                style = MaterialTheme.typography.labelMedium,
                color = accent
            )
        }
    }
}

@Composable
private fun MetricsRow(progress: TransferProgress) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MetricPill(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.metric_volume),
            value = formatFileSize(progress.transferredBytes),
            caption = stringResource(R.string.value_of_format, formatFileSize(progress.totalBytes)),
            valueColor = SoloraTextPrimary
        )
        MetricPill(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.metric_rate),
            value = formatSpeed(progress.speedBytesPerSec),
            caption = stringResource(R.string.metric_real_time),
            valueColor = SoloraNeonLime
        )
        MetricPill(
            modifier = Modifier.weight(1f),
            label = stringResource(R.string.metric_estimated),
            value = if (progress.etaSeconds > 0 && progress.status == TransferStatus.TRANSFERRING) {
                formatEta(progress.etaSeconds)
            } else {
                stringResource(R.string.value_unknown)
            },
            caption = stringResource(R.string.metric_time_left),
            valueColor = SoloraCyan
        )
    }
}

@Composable
private fun MetricPill(
    modifier: Modifier = Modifier,
    label: String,
    value: String,
    caption: String,
    valueColor: Color
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = SoloraSurfaceElevated
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = SoloraTextMuted
            )
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = valueColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                caption,
                style = LabelTiny,
                color = SoloraTextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ShaVerifiedBanner(sha256: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SoloraEnergyGreen.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, SoloraEnergyGreen.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier
                .padding(12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = SoloraEnergyGreen,
                    modifier = Modifier.size(22.dp)
                )
                Column {
                    Text(
                        stringResource(R.string.sha_verified),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = SoloraEnergyGreen
                    )
                    if (sha256.isNotEmpty()) {
                        Text(
                            sha256,
                            style = LabelTiny,
                            fontFamily = FontFamily.Monospace,
                            color = SoloraTextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            if (sha256.isNotEmpty()) {
                IconButton(
                    onClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", sha256))
                    },
                    modifier = Modifier.size(MinTouchTarget)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_hash),
                        tint = SoloraEnergyGreen,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ControlButtons(
    status: TransferStatus,
    progressPercent: Int,
    canStart: Boolean,
    onStartTransfer: () -> Unit,
    onPauseTransfer: () -> Unit,
    onResumeTransfer: () -> Unit,
    onCancelTransfer: () -> Unit,
    onNewTransfer: () -> Unit
) {
    val buttonShape = RoundedCornerShape(14.dp)
    val limeColors = ButtonDefaults.buttonColors(
        containerColor = SoloraNeonLime,
        contentColor = SoloraTextOnAccent
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        when (status) {
            TransferStatus.IDLE, TransferStatus.CANCELLED -> {
                Button(
                    onClick = onStartTransfer,
                    enabled = canStart,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = buttonShape,
                    colors = limeColors
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_start_transfer), fontWeight = FontWeight.Black)
                }
            }
            TransferStatus.TRANSFERRING -> {
                Button(
                    onClick = onPauseTransfer,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = buttonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoloraSolarAmber,
                        contentColor = SoloraTextOnAccent
                    )
                ) {
                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.btn_pause), fontWeight = FontWeight.Black)
                }
                OutlinedButton(
                    onClick = onCancelTransfer,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp),
                    shape = buttonShape,
                    border = BorderStroke(1.dp, SoloraBorder)
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        stringResource(R.string.btn_cancel),
                        color = SoloraTextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            TransferStatus.PAUSED, TransferStatus.INTERRUPTED -> {
                Button(
                    onClick = onResumeTransfer,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(50.dp),
                    shape = buttonShape,
                    colors = limeColors
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.btn_resume, progressPercent),
                        fontWeight = FontWeight.Black
                    )
                }
                OutlinedButton(
                    onClick = onNewTransfer,
                    modifier = Modifier
                        .weight(0.8f)
                        .height(50.dp),
                    shape = buttonShape,
                    border = BorderStroke(1.dp, SoloraBorder)
                ) {
                    Text(
                        stringResource(R.string.btn_reset),
                        color = SoloraTextSecondary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            TransferStatus.COMPLETED, TransferStatus.FAILED -> {
                Button(
                    onClick = onNewTransfer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = buttonShape,
                    colors = limeColors
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.btn_new_transfer), fontWeight = FontWeight.Black)
                }
            }
            else -> {}
        }
    }
}

@Composable
private fun PayloadSourceCard(
    selectedFileName: String?,
    selectedFileSize: Long?,
    onPickFile: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SectionHeader(
                icon = { tint ->
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                },
                title = stringResource(R.string.card_payload_source),
                tint = SoloraCyan
            )

            if (selectedFileName != null && selectedFileSize != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.medium,
                    color = SoloraSurfaceElevated
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = SoloraNeonLime.copy(alpha = 0.15f),
                            modifier = Modifier.size(42.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                    contentDescription = null,
                                    tint = SoloraNeonLime,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                selectedFileName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = SoloraTextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                formatFileSize(selectedFileSize),
                                style = MaterialTheme.typography.bodySmall,
                                color = SoloraTextSecondary
                            )
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onPickFile,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = MaterialTheme.shapes.small,
                border = BorderStroke(
                    1.dp,
                    if (selectedFileName == null) SoloraNeonLime else SoloraBorder
                )
            ) {
                Icon(
                    Icons.Default.UploadFile,
                    contentDescription = null,
                    tint = if (selectedFileName == null) SoloraNeonLime else SoloraTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        if (selectedFileName == null) R.string.btn_select_file
                        else R.string.btn_change_file
                    ),
                    color = if (selectedFileName == null) SoloraNeonLime else SoloraTextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun EndpointCard(
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    serverPort: String,
    onServerPortChange: (String) -> Unit,
    recentIps: List<String>,
    onSelectRecentIp: (String) -> Unit,
    connectionStatusText: String,
    isConnected: Boolean,
    onTestConnection: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(
                    icon = { tint ->
                        Icon(
                            Icons.Default.Lan,
                            contentDescription = null,
                            tint = tint,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    title = stringResource(R.string.card_target_endpoint),
                    tint = SoloraCyan
                )

                if (connectionStatusText.isNotEmpty()) {
                    ConnectionBadge(connectionStatusText, isConnected)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = serverIp,
                    onValueChange = onServerIpChange,
                    label = { Text(stringResource(R.string.label_device_ip), style = LabelTiny) },
                    placeholder = { Text(stringResource(R.string.hint_device_ip)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(2.2f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoloraNeonLime,
                        unfocusedBorderColor = SoloraBorder,
                        focusedLabelColor = SoloraNeonLime
                    )
                )
                OutlinedTextField(
                    value = serverPort,
                    onValueChange = onServerPortChange,
                    label = { Text(stringResource(R.string.label_port), style = LabelTiny) },
                    placeholder = { Text(stringResource(R.string.hint_port)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SoloraNeonLime,
                        unfocusedBorderColor = SoloraBorder,
                        focusedLabelColor = SoloraNeonLime
                    )
                )
            }

            // Previously the recent-IP list was threaded through MainActivity and
            // persisted to prefs, but never rendered. It is the fastest way back
            // to a known-good host, so it now appears here.
            if (recentIps.isNotEmpty()) {
                Text(
                    stringResource(R.string.recent_targets),
                    style = MaterialTheme.typography.labelSmall,
                    color = SoloraTextMuted
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(recentIps, key = { it }) { ip ->
                        OutlinedButton(
                            onClick = { onSelectRecentIp(ip) },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.heightIn(min = 40.dp)
                        ) {
                            Text(ip, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            OutlinedButton(
                onClick = onTestConnection,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                border = BorderStroke(1.dp, SoloraBorder)
            ) {
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = SoloraTextSecondary,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.btn_ping_server),
                    color = SoloraTextSecondary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(
    icon: @Composable (Color) -> Unit,
    title: String,
    tint: Color
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        icon(tint)
        Text(
            title,
            style = MaterialTheme.typography.labelMedium,
            color = SoloraTextSecondary
        )
    }
}

@Composable
private fun ConnectionBadge(text: String, connected: Boolean) {
    val tint = if (connected) SoloraEnergyGreen else SoloraAlertRed
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = tint.copy(alpha = 0.15f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(tint)
            )
            Text(
                text = text.uppercase(),
                color = tint,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PeerDiscoveryPanel(
    discoveredPeers: List<PeerDevice>,
    selectedHost: String,
    isDiscovering: Boolean,
    onSelectPeer: (PeerDevice) -> Unit,
    onRefreshPeers: () -> Unit,
    onScanQr: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SoloraSurfaceElevated,
        border = BorderStroke(1.dp, SoloraCyan.copy(alpha = 0.3f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        Icons.Default.Wifi,
                        contentDescription = null,
                        tint = SoloraCyan,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        stringResource(R.string.card_nearby_devices),
                        style = MaterialTheme.typography.labelSmall,
                        color = SoloraCyan
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    // These two were rendered as enabled IconButtons with empty
                    // bodies; they now do real work.
                    IconButton(onClick = onRefreshPeers, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh_nearby),
                            tint = SoloraCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onScanQr, modifier = Modifier.size(MinTouchTarget)) {
                        Icon(
                            Icons.Default.QrCodeScanner,
                            contentDescription = stringResource(R.string.cd_scan_qr),
                            tint = SoloraNeonLime,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            if (discoveredPeers.isEmpty()) {
                Text(
                    if (isDiscovering) {
                        stringResource(R.string.nearby_empty_searching)
                    } else {
                        stringResource(R.string.nearby_empty_idle)
                    },
                    style = LabelTiny,
                    color = SoloraTextMuted
                )
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(discoveredPeers, key = { "${it.host}:${it.port}" }) { peer ->
                        val isSelected = peer.host == selectedHost
                        val selectDesc = stringResource(
                            if (isSelected) R.string.peer_selected else R.string.peer_unselected
                        )
                        OutlinedButton(
                            onClick = { onSelectPeer(peer) },
                            modifier = Modifier
                                .widthIn(min = 140.dp, max = 180.dp)
                                .heightIn(min = MinTouchTarget),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = if (isSelected) {
                                    SoloraEnergyGreen.copy(alpha = 0.15f)
                                } else {
                                    MaterialTheme.colorScheme.surface
                                },
                                contentColor = if (isSelected) SoloraEnergyGreen else SoloraTextPrimary
                            )
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    if (isSelected) Icons.Default.CheckCircle
                                    else Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = if (isSelected) SoloraEnergyGreen else SoloraCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        peer.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) SoloraEnergyGreen else SoloraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.peer_port_format, peer.port),
                                        style = LabelTiny,
                                        color = SoloraTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Surfaces [TransferProgress.errorMessage].
 *
 * The field was populated on every failure path in TransferManager but never
 * rendered, so a FAILED transfer showed a red arc and the word "FAILED" with no
 * indication of what went wrong.
 */
@Composable
private fun ErrorBanner(errorMessage: String, status: TransferStatus) {
    if (errorMessage.isBlank()) return
    if (status != TransferStatus.FAILED && status != TransferStatus.INTERRUPTED) return

    val tint = status.accentColor()
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = tint.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Cancel,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(18.dp)
            )
            Column {
                Text(
                    stringResource(R.string.error_headline),
                    style = MaterialTheme.typography.labelMedium,
                    color = tint
                )
                Text(
                    errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SoloraTextPrimary
                )
            }
        }
    }
}

@Composable
private fun LogCard(logMessage: String) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.card_transmission_log),
                    style = MaterialTheme.typography.labelMedium,
                    color = SoloraTextSecondary
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(SoloraEnergyGreen)
                )
            }
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 55.dp, max = 100.dp),
                shape = MaterialTheme.shapes.small,
                color = Color(0xFF070A0E),
                border = BorderStroke(1.dp, Color(0xFF161E2A))
            ) {
                Text(
                    text = if (logMessage.isNotEmpty()) {
                        stringResource(R.string.log_prefix, logMessage)
                    } else {
                        stringResource(R.string.log_ready)
                    },
                    modifier = Modifier.padding(12.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = SoloraEnergyGreen
                )
            }
        }
    }
}

/**
 * Status badge.
 *
 * The pulse animation now lives inside this composable rather than being read
 * in TransferScreen's top-level body. Previously an infinite transition was
 * declared at the root of an ~800-line composable and read there, so all 60
 * frames per second invalidated the entire screen just to blink a 6dp dot.
 */
@Composable
fun SoloraStatusBadge(status: TransferStatus) {
    val accent = status.accentColor()
    val container by animateColorAsState(
        targetValue = accent.copy(alpha = if (status == TransferStatus.IDLE) 1f else 0.2f),
        animationSpec = tween(200),
        label = "badgeBg"
    )

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (status == TransferStatus.IDLE) SoloraSurfaceElevated else container,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (status == TransferStatus.TRANSFERRING) {
                PulseDot(accent)
            }
            Text(
                text = stringResource(status.labelRes()),
                color = if (status == TransferStatus.IDLE) SoloraTextMuted else accent,
                fontWeight = FontWeight.Black,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun PulseDot(color: Color) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = androidx.compose.animation.core.LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(6.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = alpha))
    )
}

/** Accent colour for a transfer status, single source of truth across screens. */
internal fun TransferStatus.accentColor(): Color = when (this) {
    TransferStatus.COMPLETED, TransferStatus.READY -> SoloraEnergyGreen
    TransferStatus.INTERRUPTED, TransferStatus.FAILED -> SoloraAlertRed
    TransferStatus.PAUSED -> SoloraSolarAmber
    TransferStatus.CONNECTING -> SoloraCyan
    TransferStatus.IDLE, TransferStatus.CANCELLED -> SoloraTextMuted
    TransferStatus.TRANSFERRING -> SoloraNeonLime
}

/** Maps a status to its localized badge label. */
internal fun TransferStatus.labelRes(): Int = when (this) {
    TransferStatus.IDLE -> R.string.status_idle
    TransferStatus.CONNECTING -> R.string.status_connecting
    TransferStatus.READY -> R.string.status_ready
    TransferStatus.TRANSFERRING -> R.string.status_transferring
    TransferStatus.PAUSED -> R.string.status_paused
    TransferStatus.INTERRUPTED -> R.string.status_interrupted
    TransferStatus.COMPLETED -> R.string.status_completed
    TransferStatus.FAILED -> R.string.status_failed
    TransferStatus.CANCELLED -> R.string.status_cancelled
}
