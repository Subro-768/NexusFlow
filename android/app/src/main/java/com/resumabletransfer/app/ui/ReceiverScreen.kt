package com.resumabletransfer.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
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
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.resumabletransfer.app.R
import com.resumabletransfer.app.server.IncomingTransferState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class ReceivedFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val lastModified: Long
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    isServerRunning: Boolean,
    localIps: List<String>,
    incomingState: IncomingTransferState?,
    onToggleServer: (Boolean) -> Unit,
    onRefreshIp: () -> Unit,
    onMenuClick: () -> Unit,
    onOpenDownloads: () -> Unit,
    deviceName: String = "",
    onDeviceNameChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val primaryIp = localIps.firstOrNull { !it.startsWith("127.") } ?: localIps.firstOrNull() ?: "127.0.0.1"

    val qrBitmap = remember(primaryIp, isServerRunning) {
        if (isServerRunning) generateQrCode(primaryIp, 512) else null
    }

    var receivedFiles by remember { mutableStateOf(listOf<ReceivedFileInfo>()) }

    fun refreshFileList() {
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val nexusDir = File(downloads, "NexusFlow")
        val dir = if (nexusDir.exists()) nexusDir else context.filesDir
        val files = dir.listFiles()?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            ?.map {
                ReceivedFileInfo(
                    file = it,
                    name = it.name,
                    size = it.length(),
                    lastModified = it.lastModified()
                )
            } ?: emptyList()
        receivedFiles = files
    }

    LaunchedEffect(incomingState?.status) {
        refreshFileList()
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
                        Image(
                            painter = painterResource(id = R.drawable.ic_nexus_flow),
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
                                "RECEIVER HUB",
                                fontSize = 9.sp,
                                color = SoloraSolarAmber,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.5.sp
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
                        onClick = onRefreshIp,
                        modifier = Modifier
                            .padding(end = 4.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SoloraSurfaceElevated)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh IP", tint = SoloraCyan, modifier = Modifier.size(20.dp))
                    }
                    IconButton(
                        onClick = onOpenDownloads,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SoloraSurfaceElevated)
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Open Folder", tint = SoloraEnergyGreen, modifier = Modifier.size(20.dp))
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
                                        "Broadcasting as:",
                                        fontSize = 9.sp,
                                        color = SoloraTextSecondary,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        deviceName.ifBlank { "This Device" },
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = SoloraEnergyGreen
                                    )
                                }
                                // Secondary: small IP badge
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = SoloraSurfaceElevated
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        IconButton(
                                            onClick = { onRefreshIp() },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = "Refresh", tint = SoloraCyan, modifier = Modifier.size(14.dp))
                                        }
                                        Text(
                                            "$primaryIp:8000",
                                            fontSize = 9.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = SoloraTextMuted
                                        )
                                    }
                                }
                            }
                        }

                        Text(
                            "Other devices on the same Wi-Fi can now discover and send files to you.",
                            fontSize = 11.sp,
                            color = SoloraTextSecondary,
                            lineHeight = 16.sp
                        )
                    } else {
                        Text(
                            "Set your device name above, then enable receiving. Nearby senders will see your name instead of your IP address.",
                            fontSize = 11.sp,
                            color = SoloraTextMuted,
                            lineHeight = 16.sp
                        )
                    }
                }
            }


            // 2. QR Code Pairing Card
            if (isServerRunning && qrBitmap != null) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = SoloraSurfaceCard,
                    border = BorderStroke(1.dp, SoloraBorder),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Text(
                            "QUICK PAIRING IP",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = SoloraCyan,
                            letterSpacing = 1.sp
                        )

                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = androidx.compose.ui.graphics.Color.White,
                            modifier = Modifier
                                .size(170.dp)
                                .padding(4.dp)
                        ) {
                            Image(
                                bitmap = qrBitmap.asImageBitmap(),
                                contentDescription = "Connection QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        Text(
                            primaryIp,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = SoloraNeonLime
                        )
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
                                onClick = { refreshFileList() },
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(SoloraSurfaceElevated)
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = "Refresh files", tint = SoloraCyan, modifier = Modifier.size(16.dp))
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

                    if (receivedFiles.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 18.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No files received yet.\nStart receiver and send files from another device.",
                                color = SoloraTextSecondary,
                                fontSize = 12.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 18.sp
                            )
                        }
                    } else {
                        val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault()) }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            receivedFiles.forEach { item ->
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
                                                    Icons.Default.InsertDriveFile,
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
                                            modifier = Modifier.height(34.dp)
                                        ) {
                                            Text("OPEN", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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

fun generateQrCode(text: String, size: Int): Bitmap? {
    return try {
        val hints = mapOf(EncodeHintType.MARGIN to 1)
        val bitMatrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (bitMatrix.get(x, y)) AndroidColor.BLACK else AndroidColor.WHITE)
            }
        }
        bitmap
    } catch (e: Exception) {
        null
    }
}
