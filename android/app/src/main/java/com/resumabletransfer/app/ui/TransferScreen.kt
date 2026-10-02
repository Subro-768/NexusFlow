package com.resumabletransfer.app.ui

import androidx.compose.ui.text.style.*
import androidx.compose.ui.text.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextAlign
import androidx.compose.ui.text.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumabletransfer.app.TransferProgress
import com.resumabletransfer.app.TransferStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferScreen(
    onMenuClick: () -> Unit,
    serverIp: String,
    onServerIpChange: (String) -> Unit,
    serverPort: String,
    onServerPortChange: (String) -> Unit,
    recentIps: List<String> = emptyList(),
    onSelectRecentIp: (String) -> Unit = {},
    discoveredPeers: List<com.resumabletransfer.app.PeerDevice> = emptyList(),
    onSelectPeer: (com.resumabletransfer.app.PeerDevice) -> Unit = {},
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
    onNewTransfer: () -> Unit
) {
    val scrollState = rememberScrollState()
    val context = LocalContext.current

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Scaffold(
        containerColor = SoloraBgDark,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Image(
                            painter = painterResource(id = com.resumabletransfer.app.R.drawable.ic_nexus_flow),
                            contentDescription = "Nexus Flow Logo",
                            modifier = Modifier.size(34.dp)
                        )
                        Column {
                            Text(
                                text = buildAnnotatedString {
                                    withStyle(SpanStyle(color = SoloraCyan, fontWeight = FontWeight.Black)) {
                                        append("NEXUS ")
                                    }
                                    withStyle(SpanStyle(color = SoloraEnergyGreen, fontWeight = FontWeight.Black)) {
                                        append("FLOW")
                                    }
                                },
                                fontSize = 16.sp,
                                letterSpacing = 1.5.sp
                            )
                            Text(
                                "SMART FILE TRANSFER",
                                fontSize = 9.sp,
                                color = SoloraTextSecondary,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onMenuClick) {
                        Icon(Icons.Default.Menu, contentDescription = "Open navigation menu", tint = SoloraTextPrimary)
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNewTransfer,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SoloraSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "New Transfer", tint = SoloraNeonLime, modifier = Modifier.size(20.dp))
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
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Solora Main Radial Progress Meter Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
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
                            "DATA FLOW",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = SoloraTextSecondary
                        )
                        SoloraStatusBadge(progress.status, pulseAlpha)
                    }

                    // Large Radial Progress Meter
                    Box(
                        modifier = Modifier
                            .size(190.dp)
                            .padding(8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val progressColor = when (progress.status) {
                            TransferStatus.COMPLETED -> SoloraEnergyGreen
                            TransferStatus.INTERRUPTED, TransferStatus.FAILED -> SoloraAlertRed
                            TransferStatus.PAUSED -> SoloraSolarAmber
                            else -> SoloraNeonLime
                        }

                        // Background track circle
                        CircularProgressIndicator(
                            progress = { 1f },
                            modifier = Modifier.fillMaxSize(),
                            color = SoloraSurfaceElevated,
                            strokeWidth = 14.dp,
                            strokeCap = StrokeCap.Round
                        )

                        // Active Progress Arc
                        CircularProgressIndicator(
                            progress = { progress.progressFraction },
                            modifier = Modifier.fillMaxSize(),
                            color = progressColor,
                            strokeWidth = 14.dp,
                            strokeCap = StrokeCap.Round
                        )

                        // Center Stats
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                "${progress.progressPercent}%",
                                fontSize = 38.sp,
                                fontWeight = FontWeight.Black,
                                color = SoloraTextPrimary
                            )
                            Text(
                                if (progress.speedBytesPerSec > 0 && progress.status == TransferStatus.TRANSFERRING)
                                    formatSpeed(progress.speedBytesPerSec)
                                else if (progress.status == TransferStatus.COMPLETED)
                                    "VERIFIED"
                                else
                                    progress.status.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = progressColor,
                                letterSpacing = 1.sp
                            )
                        }
                    }

                    // Metrics Grid (3 Solora Metric Pills)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Card 1: Transferred Volume
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = SoloraSurfaceElevated
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("VOLUME", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SoloraTextMuted, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatFileSize(progress.transferredBytes),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SoloraTextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    "of ${formatFileSize(progress.totalBytes)}",
                                    fontSize = 10.sp,
                                    color = SoloraTextSecondary,
                                    maxLines = 1
                                )
                            }
                        }

                        // Card 2: Speed
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = SoloraSurfaceElevated
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("RATE", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SoloraTextMuted, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    formatSpeed(progress.speedBytesPerSec),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SoloraNeonLime,
                                    maxLines = 1
                                )
                                Text(
                                    "Real-time",
                                    fontSize = 10.sp,
                                    color = SoloraTextSecondary
                                )
                            }
                        }

                        // Card 3: ETA
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            color = SoloraSurfaceElevated
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text("ESTIMATED", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = SoloraTextMuted, letterSpacing = 1.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (progress.etaSeconds > 0 && progress.status == TransferStatus.TRANSFERRING) formatEta(progress.etaSeconds) else "--",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = SoloraCyan,
                                    maxLines = 1
                                )
                                Text(
                                    "Time Left",
                                    fontSize = 10.sp,
                                    color = SoloraTextSecondary
                                )
                            }
                        }
                    }

                    // SHA-256 Verified Banner
                    if (progress.status == TransferStatus.COMPLETED) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
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
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SoloraEnergyGreen, modifier = Modifier.size(22.dp))
                                    Column {
                                        Text(
                                            "SHA-256 Verified 100%",
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 13.sp,
                                            color = SoloraEnergyGreen
                                        )
                                        if (progress.calculatedSha256.isNotEmpty()) {
                                            Text(
                                                progress.calculatedSha256,
                                                fontSize = 9.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = SoloraTextSecondary,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                if (progress.calculatedSha256.isNotEmpty()) {
                                    IconButton(
                                        onClick = {
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("SHA-256", progress.calculatedSha256))
                                            Toast.makeText(context, "Hash copied to clipboard", Toast.LENGTH_SHORT).show()
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(Icons.Default.ContentCopy, contentDescription = "Copy Hash", tint = SoloraEnergyGreen, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }

                    // Control Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        when (progress.status) {
                            TransferStatus.IDLE, TransferStatus.CANCELLED -> {
                                Button(
                                    onClick = onStartTransfer,
                                    enabled = selectedFileUri != null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = SoloraNeonLime,
                                        contentColor = SoloraBgDark,
                                        disabledContainerColor = SoloraSurfaceElevated,
                                        disabledContentColor = SoloraTextMuted
                                    )
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("START TRANSFER", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                }
                            }
                            TransferStatus.TRANSFERRING -> {
                                Button(
                                    onClick = onPauseTransfer,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SoloraSolarAmber, contentColor = SoloraBgDark),
                                    shape = RoundedCornerShape(14.dp)
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("PAUSE", fontWeight = FontWeight.Black)
                                }
                                OutlinedButton(
                                    onClick = onCancelTransfer,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, SoloraBorder)
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("CANCEL", color = SoloraTextSecondary, fontWeight = FontWeight.Bold)
                                }
                            }
                            TransferStatus.PAUSED, TransferStatus.INTERRUPTED -> {
                                Button(
                                    onClick = onResumeTransfer,
                                    modifier = Modifier
                                        .weight(1.4f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SoloraNeonLime, contentColor = SoloraBgDark)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("RESUME (${progress.progressPercent}%)", fontWeight = FontWeight.Black)
                                }
                                OutlinedButton(
                                    onClick = onNewTransfer,
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    border = BorderStroke(1.dp, SoloraBorder)
                                ) {
                                    Text("RESET", color = SoloraTextSecondary, fontWeight = FontWeight.Bold)
                                }
                            }
                            TransferStatus.COMPLETED, TransferStatus.FAILED -> {
                                Button(
                                    onClick = onNewTransfer,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(50.dp),
                                    shape = RoundedCornerShape(14.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = SoloraNeonLime, contentColor = SoloraBgDark)
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("NEW TRANSFER", fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                }
                            }
                            else -> {}
                        }
                    }
                }
            }

            // 2. Source File Selection Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                color = SoloraSurfaceCard,
                border = BorderStroke(1.dp, SoloraBorder)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            Icons.Default.FolderOpen,
                            contentDescription = null,
                            tint = SoloraCyan,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            "PAYLOAD SOURCE",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
                            color = SoloraTextSecondary
                        )
                    }

                    if (selectedFileName != null && selectedFileSize != null) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
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
                                        maxLines = 1
                                    )
                                    Text(
                                        formatFileSize(selectedFileSize),
                                        color = SoloraTextSecondary,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = onPickFile,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, if (selectedFileName == null) SoloraNeonLime else SoloraBorder)
                    ) {
                        Icon(Icons.Default.UploadFile, contentDescription = null, tint = if (selectedFileName == null) SoloraNeonLime else SoloraTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (selectedFileName == null) "SELECT FILE" else "CHANGE FILE",
                            color = if (selectedFileName == null) SoloraNeonLime else SoloraTextSecondary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            letterSpacing = 1.sp
                        )
                    }
                }
            }

            // 3. Destination Server Card
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Default.Lan,
                                contentDescription = null,
                                tint = SoloraCyan,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                "TARGET ENDPOINT (DEVICE B)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp,
                                color = SoloraTextSecondary
                            )
                        }

                        if (connectionStatusText.isNotEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(20.dp),
                                color = if (isConnected) SoloraEnergyGreen.copy(alpha = 0.15f) else SoloraAlertRed.copy(alpha = 0.15f),
                                border = BorderStroke(1.dp, if (isConnected) SoloraEnergyGreen.copy(alpha = 0.4f) else SoloraAlertRed.copy(alpha = 0.4f))
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
                                            .background(if (isConnected) SoloraEnergyGreen else SoloraAlertRed)
                                    )
                                    Text(
                                        text = connectionStatusText.uppercase(),
                                        color = if (isConnected) SoloraEnergyGreen else SoloraAlertRed,
                                        fontWeight = FontWeight.Black,
                                        fontSize = 10.sp,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = serverIp,
                            onValueChange = onServerIpChange,
                            label = { Text("DEVICE IP", fontSize = 10.sp, letterSpacing = 1.sp) },
                            placeholder = { Text("192.168.x.x / 127.0.0.1") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
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
                            label = { Text("PORT", fontSize = 10.sp, letterSpacing = 1.sp) },
                            placeholder = { Text("8000") },
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SoloraNeonLime,
                                unfocusedBorderColor = SoloraBorder,
                                focusedLabelColor = SoloraNeonLime
                            )
                        )
                    }

                    OutlinedButton(
                        onClick = onTestConnection,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        border = BorderStroke(1.dp, SoloraBorder)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, tint = SoloraTextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PING SERVER", color = SoloraTextSecondary, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 1.sp)
                    }
                }
            }

            // ── Nearby Devices Discovery Panel (PRIMARY - Scrollable) ──
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = SoloraSurfaceElevated,
                border = BorderStroke(1.dp, SoloraCyan.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                "NEARBY DEVICES (Tap to select)",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = SoloraCyan
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            IconButton(
                                onClick = { /* refresh nearby */ },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh nearby", tint = SoloraCyan, modifier = Modifier.size(16.dp))
                            }
                            IconButton(
                                onClick = { /* scan QR */ },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = "Scan QR", tint = SoloraNeonLime, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    if (discoveredPeers.isEmpty()) {
                        Text(
                            if (isDiscovering)
                                "Looking for devices with receiving enabled on this Wi-Fi..."
                            else
                                "No devices found. Make sure the target device has 'Enable Receiving' turned on.",
                            fontSize = 10.sp,
                            color = SoloraTextMuted,
                            lineHeight = 14.sp
                        )
                    } else {
                        val peerScrollState = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(peerScrollState),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            discoveredPeers.forEach { peer ->
                                val isSelected = peer.host == serverIp
                                OutlinedButton(
                                    onClick = { onSelectPeer(peer) },
                                    modifier = Modifier
                                        .widthIn(min = 140.dp, max = 180.dp)
                                        .height(48.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        containerColor = if (isSelected) SoloraEnergyGreen.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surface,
                                        contentColor = if (isSelected) SoloraEnergyGreen else SoloraTextPrimary
                                    )
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            if (isSelected) Icons.Default.CheckCircle else Icons.Default.PhoneAndroid,
                                            contentDescription = null,
                                            tint = if (isSelected) SoloraEnergyGreen else SoloraCyan,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(
                                                peer.name,
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isSelected) SoloraEnergyGreen else SoloraTextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "Port ${peer.port}",
                                                fontSize = 8.sp,
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

            // 4. Live Stream Log Console
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
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
                            "TRANSMISSION LOG",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.5.sp,
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
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF070A0E),
                        border = BorderStroke(1.dp, Color(0xFF161E2A))
                    ) {
                        Text(
                            text = if (progress.logMessage.isNotEmpty()) "> ${progress.logMessage}" else "> System ready.",
                            modifier = Modifier.padding(12.dp),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = SoloraEnergyGreen
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SoloraStatusBadge(status: TransferStatus, pulseAlpha: Float) {
    val (bgColor, textColor, text) = when (status) {
        TransferStatus.IDLE -> Triple(SoloraSurfaceElevated, SoloraTextMuted, "STANDBY")
        TransferStatus.CONNECTING -> Triple(SoloraCyan.copy(alpha = 0.2f), SoloraCyan, "CONNECTING...")
        TransferStatus.READY -> Triple(SoloraEnergyGreen.copy(alpha = 0.2f), SoloraEnergyGreen, "READY")
        TransferStatus.TRANSFERRING -> Triple(SoloraNeonLime.copy(alpha = 0.2f), SoloraNeonLime, "STREAMING")
        TransferStatus.PAUSED -> Triple(SoloraSolarAmber.copy(alpha = 0.2f), SoloraSolarAmber, "PAUSED")
        TransferStatus.INTERRUPTED -> Triple(SoloraAlertRed.copy(alpha = 0.2f), SoloraAlertRed, "INTERRUPTED")
        TransferStatus.COMPLETED -> Triple(SoloraEnergyGreen.copy(alpha = 0.2f), SoloraEnergyGreen, "COMPLETED")
        TransferStatus.FAILED -> Triple(SoloraAlertRed.copy(alpha = 0.2f), SoloraAlertRed, "FAILED")
        TransferStatus.CANCELLED -> Triple(SoloraSurfaceElevated, SoloraTextMuted, "CANCELLED")
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = bgColor,
        border = BorderStroke(1.dp, textColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (status == TransferStatus.TRANSFERRING) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(textColor.copy(alpha = pulseAlpha))
                )
            }
            Text(
                text = text,
                color = textColor,
                fontWeight = FontWeight.Black,
                fontSize = 10.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

fun formatFileSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
    val value = bytes / Math.pow(1024.0, digitGroups.toDouble())
    return "%.2f %s".format(value, units[digitGroups])
}

fun formatSpeed(bytesPerSec: Long): String {
    if (bytesPerSec <= 0) return "0 KB/s"
    val mbPerSec = bytesPerSec / (1024.0 * 1024.0)
    return if (mbPerSec >= 1.0) {
        "%.1f MB/s".format(mbPerSec)
    } else {
        "%.0f KB/s".format(bytesPerSec / 1024.0)
    }
}

fun formatEta(seconds: Long): String {
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
        else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
    }
}
