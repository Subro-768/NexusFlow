package com.resumabletransfer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.resumabletransfer.app.R
import com.resumabletransfer.app.server.IncomingTransferState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    isServerRunning: Boolean,
    incomingState: IncomingTransferState?,
    onToggleServer: (Boolean) -> Unit,
    onMenuClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    deviceName: String = "",
    onDeviceNameChange: (String) -> Unit = {},
    onPauseIncoming: () -> Unit = {},
    onResumeIncoming: () -> Unit = {},
    onCancelIncoming: () -> Unit = {}
) {
    val context = LocalContext.current

    var receivedFiles by remember { mutableStateOf(listOf<ReceivedFileInfo>()) }

    // The scan does listFiles() + a stat per entry; it belongs on IO, not the
    // main dispatcher. The loading flag keeps the previous "empty" text from
    // flashing while the listing is in flight.
    var filesLoading by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(incomingState?.status) {
        filesLoading = true
        receivedFiles = withContext(Dispatchers.IO) { scanReceivedFiles(context) }
        filesLoading = false
    }

    Scaffold(
        containerColor = SoloraBgDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Logo bitmap removed for consistency with the sender
                        // app bar and the drawer: wordmark only.
                        Column {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(
                                        SpanStyle(color = SoloraCyan, fontWeight = FontWeight.Black)
                                    ) {
                                        append(stringResource(R.string.brand_name_full))
                                    }
                                    withStyle(
                                        SpanStyle(color = SoloraEnergyGreen, fontWeight = FontWeight.Black)
                                    ) {
                                        append(stringResource(R.string.brand_name_accent))
                                    }
                                },
                                fontSize = 16.sp,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                stringResource(R.string.receiver_hub_title),
                                fontSize = 9.sp,
                                color = SoloraSolarAmber,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = stringResource(R.string.cd_open_menu),
                            tint = SoloraTextPrimary
                        )
                    }
                },
                actions = {
                    // The old action row had a "Refresh IP" button. With the
                    // address no longer displayed there is nothing to refresh,
                    // so only the downloads action remains.
                    IconButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(SoloraSurfaceElevated)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = stringResource(R.string.cd_open_downloads),
                            tint = SoloraEnergyGreen,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SoloraBgDark,
                    titleContentColor = SoloraTextPrimary
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(4.dp))

            // 1. Receiver Status & Server Toggle Card
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SoloraSurfaceCard,
                border = BorderStroke(1.dp, if (isServerRunning) SoloraEnergyGreen.copy(alpha = 0.6f) else SoloraBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

                    // Device Name Field (always visible)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                Icons.Default.Devices,
                                contentDescription = null,
                                tint = SoloraCyan,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                "DEVICE NAME",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = SoloraTextSecondary
                            )
                        }
                        OutlinedTextField(
                            value = deviceName,
                            onValueChange = { if (!isServerRunning) onDeviceNameChange(it) },
                            placeholder = { Text("e.g. Subro's Phone", color = SoloraTextMuted, fontSize = 13.sp) },
                            singleLine = true,
                            enabled = !isServerRunning,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SoloraCyan,
                                unfocusedBorderColor = SoloraBorder,
                                focusedLabelColor = SoloraCyan,
                                disabledBorderColor = SoloraBorder.copy(alpha = 0.5f),
                                disabledTextColor = SoloraTextPrimary
                            ),
                            textStyle = androidx.compose.ui.text.TextStyle(
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = SoloraTextPrimary
                            )
                        )
                        if (isServerRunning) {
                            Text(
                                "Name cannot be changed while receiving is active.",
                                fontSize = 9.sp,
                                color = SoloraTextMuted
                            )
                        }
                    }

                    HorizontalDivider(color = SoloraBorder)

                    // Toggle row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(if (isServerRunning) SoloraEnergyGreen else SoloraAlertRed)
                            )
                            Text(
                                if (isServerRunning) "RECEIVING ENABLED" else "RECEIVING DISABLED",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = if (isServerRunning) SoloraEnergyGreen else SoloraTextSecondary
                            )
                        }

                        Switch(
                            checked = isServerRunning,
                            onCheckedChange = onToggleServer,
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SoloraBgDark,
                                checkedTrackColor = SoloraEnergyGreen,
                                uncheckedThumbColor = SoloraTextSecondary,
                                uncheckedTrackColor = SoloraSurfaceElevated
                            )
                        )
                    }

                    if (isServerRunning) {
                        // Broadcasting status pill
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SoloraEnergyGreen.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, SoloraEnergyGreen.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = SoloraEnergyGreen,
                                    modifier = Modifier.size(18.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(R.string.receiver_broadcasting_as),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoloraTextSecondary
                                    )
                                    Text(
                                        deviceName.ifBlank { stringResource(R.string.receiver_this_device) },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SoloraEnergyGreen,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Text(
                            stringResource(R.string.receiver_discovery_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoloraTextSecondary,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            stringResource(R.string.receiver_off_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoloraTextMuted,
                            lineHeight = 16.sp
                        )
                    }
                }
            }

            // 2. Nearby devices -- name only.
            //
            // The IP address is deliberately NOT shown. This screen used to
            // print "192.168.x.x:8000" in a badge, again under a "QUICK PAIRING
            // IP" heading, and again inside a scannable QR bitmap. That leaked
            // the device's address to anyone shoulder-surfing, to screenshots,
            // and to the recents-app thumbnail. Peers are found over mDNS/NSD
            // anyway, so the address is never needed on screen.
            if (isServerRunning) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SoloraSurfaceCard,
                    border = BorderStroke(1.dp, SoloraBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Wifi,
                                contentDescription = null,
                                tint = SoloraCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                stringResource(R.string.receiver_nearby_devices),
                                style = MaterialTheme.typography.labelMedium,
                                color = SoloraCyan
                            )
                        }

                        Text(
                            stringResource(R.string.receiver_nearby_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = SoloraTextSecondary,
                            textAlign = TextAlign.Center,
                            lineHeight = 16.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = SoloraSurfaceElevated,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Icon(
                                    Icons.Default.PhoneAndroid,
                                    contentDescription = null,
                                    tint = SoloraEnergyGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        deviceName.ifBlank { stringResource(R.string.receiver_this_device) },
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = SoloraTextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        stringResource(R.string.receiver_visible_as_name),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = SoloraTextMuted
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 3. Live Incoming Transfer Status
            if (incomingState != null) {
                val percent = if (incomingState.totalSize > 0) {
                    ((incomingState.receivedBytes.toDouble() / incomingState.totalSize.toDouble()) * 100).toInt()
                } else 0

                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SoloraSurfaceCard,
                    border = BorderStroke(1.dp, if (incomingState.status == "COMPLETED") SoloraEnergyGreen else SoloraCyan),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("INCOMING PAYLOAD", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = SoloraTextSecondary)
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (incomingState.status == "COMPLETED") SoloraEnergyGreen.copy(alpha = 0.2f) else SoloraCyan.copy(alpha = 0.2f)
                            ) {
                                Text(
                                    incomingState.status,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (incomingState.status == "COMPLETED") SoloraEnergyGreen else SoloraCyan
                                )
                            }
                        }

                        Text(
                            incomingState.filename,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = SoloraTextPrimary
                        )

                        LinearProgressIndicator(
                            progress = { if (incomingState.totalSize > 0) incomingState.receivedBytes.toFloat() / incomingState.totalSize.toFloat() else 0f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                            color = if (incomingState.status == "COMPLETED") SoloraEnergyGreen else SoloraCyan,
                            trackColor = SoloraSurfaceElevated
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "${formatFileSize(incomingState.receivedBytes)} / ${formatFileSize(incomingState.totalSize)} ($percent%)",
                                fontSize = 12.sp,
                                color = SoloraTextSecondary,
                                fontFamily = FontFamily.Monospace
                            )
                            Text(
                                "${formatFileSize(incomingState.speedBytesPerSec)}/s",
                                fontSize = 12.sp,
                                color = SoloraNeonLime,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Time left, shown only once a rate has actually been
                        // measured -- a countdown that reads "0s" for the first
                        // second is noise, not information.
                        if (incomingState.etaSeconds > 0 && incomingState.status != "PAUSED") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Default.Schedule,
                                    contentDescription = null,
                                    tint = SoloraSolarAmber,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    stringResource(
                                        R.string.receiver_time_left,
                                        formatEta(incomingState.etaSeconds)
                                    ),
                                    fontSize = 11.sp,
                                    color = SoloraSolarAmber,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        // Pause / Resume / Cancel for the transfer that is
                        // arriving right now.
                        //
                        // The Hub was read-only: once a push started, the only
                        // way to stop it was to turn receiving off entirely,
                        // which also dropped the session state. These act on
                        // the live session, so the sender is told to hold
                        // (HTTP 409) or that it is finished (410) instead of
                        // stalling against a socket nobody is reading.
                        val incomingPaused = incomingState.status == "PAUSED"
                        val incomingLive = incomingState.status == "PENDING" ||
                            incomingState.status == "IN_PROGRESS" ||
                            incomingPaused
                        if (incomingLive) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (incomingPaused) onResumeIncoming() else onPauseIncoming()
                                    },
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, SoloraCyan),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoloraCyan),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        if (incomingPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(
                                            if (incomingPaused) R.string.action_resume else R.string.action_pause
                                        ),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                                OutlinedButton(
                                    onClick = onCancelIncoming,
                                    shape = RoundedCornerShape(12.dp),
                                    border = BorderStroke(1.dp, SoloraAlertRed),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SoloraAlertRed),
                                    modifier = Modifier
                                        .weight(1f)
                                        .heightIn(min = 48.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        stringResource(R.string.action_cancel),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            if (incomingPaused) {
                                Text(
                                    stringResource(R.string.receiver_paused_note),
                                    fontSize = 10.sp,
                                    color = SoloraTextMuted,
                                    lineHeight = 15.sp
                                )
                            }
                        }

                        if (incomingState.status == "COMPLETED") {
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = SoloraEnergyGreen.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, SoloraEnergyGreen.copy(alpha = 0.4f)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = SoloraEnergyGreen, modifier = Modifier.size(20.dp))
                                    Column {
                                        Text("SHA-256 Cryptographically Verified", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SoloraEnergyGreen)
                                        Text("Saved to Downloads/NexusFlow", fontSize = 10.sp, color = SoloraTextSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // 4. RECEIVED FILES LIST (Matches Linux)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = SoloraSurfaceCard,
                border = BorderStroke(1.dp, SoloraBorder),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "RECEIVED FILES",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SoloraTextPrimary,
                            letterSpacing = 1.sp
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            IconButton(
                                onClick = {
                                    // Re-scan on IO; a manual refresh must not
                                    // stat every file on the main thread.
                                    filesLoading = true
                                    coroutineScope.launch {
                                        receivedFiles =
                                            withContext(Dispatchers.IO) { scanReceivedFiles(context) }
                                        filesLoading = false
                                    }
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(SoloraSurfaceElevated)
                            ) {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.cd_refresh),
                                    tint = SoloraCyan,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            IconButton(
                                onClick = onOpenDownloads,
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(SoloraSurfaceElevated)
                            ) {
                                Icon(Icons.Default.Folder, contentDescription = "Open Folder", tint = SoloraEnergyGreen, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (receivedFiles.isEmpty() && !filesLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                stringResource(R.string.receiver_no_files),
                                color = SoloraTextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    } else if (receivedFiles.isNotEmpty()) {
                        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()) }

                        // LazyColumn so only the visible rows compose. The previous
                        // Column { receivedFiles.forEach {} } built every row
                        // eagerly, which grows unbounded as files accumulate.
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.heightIn(max = 320.dp)
                        ) {
                            items(
                                items = receivedFiles,
                                key = { it.file.absolutePath }
                            ) { item ->
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = SoloraSurfaceElevated,
                                    border = BorderStroke(1.dp, SoloraBorder),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(14.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            modifier = Modifier.weight(1f),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(SoloraCyan.copy(alpha = 0.15f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    Icons.AutoMirrored.Filled.InsertDriveFile,
                                                    contentDescription = null,
                                                    tint = SoloraCyan,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(
                                                    item.name,
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 13.sp,
                                                    color = SoloraTextPrimary,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    "${formatFileSize(item.size)}  ·  ${dateFormat.format(Date(item.lastModified))}",
                                                    fontSize = 10.sp,
                                                    color = SoloraTextSecondary,
                                                    fontFamily = FontFamily.Monospace
                                                )
                                            }
                                        }

                                        Button(
                                            onClick = {
                                                openFileWithSystemViewer(context, item.file)
                                            },
                                            shape = RoundedCornerShape(8.dp),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = SoloraEnergyGreen.copy(alpha = 0.2f),
                                                contentColor = SoloraEnergyGreen
                                            ),
                                            border = BorderStroke(1.dp, SoloraEnergyGreen),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                            modifier = Modifier.heightIn(min = 48.dp)
                                        ) {
                                            Text(
                                                stringResource(R.string.cd_open),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

@androidx.compose.runtime.Immutable
data class ReceivedFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long
)

/**
 * Lists received files. Kept out of the composable so it can be dispatched to
 * IO: it performs a directory listing plus a `stat` per file.
 */
fun scanReceivedFiles(context: Context): List<ReceivedFileInfo> {
    val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    val nexusDir = File(downloads, "NexusFlow")
    val dir = if (nexusDir.exists()) nexusDir else context.filesDir
    return dir.listFiles()
        ?.filter { it.isFile && !it.name.startsWith(".") }
        ?.sortedByDescending { it.lastModified() }
        ?.map {
            ReceivedFileInfo(
                file = it,
                name = it.name,
                size = it.length(),
                lastModified = it.lastModified()
            )
        }
        ?: emptyList()
}

fun openFileWithSystemViewer(context: Context, file: File) {
    try {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        val ext = file.extension.lowercase(Locale.getDefault())
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, "Open file with..."))
    } catch (e: Exception) {
        Toast.makeText(context, "Could not open file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
