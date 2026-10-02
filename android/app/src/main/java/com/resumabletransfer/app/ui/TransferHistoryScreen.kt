package com.resumabletransfer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.resumabletransfer.app.R
import com.resumabletransfer.app.TransferApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransferHistoryScreen(
    serverUrl: String,
    onMenuClick: () -> Unit,
    onResumeSession: (TransferApiClient.TransferSession) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var historyList by remember { mutableStateOf<List<TransferApiClient.TransferSession>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    fun loadHistory() {
        isLoading = true
        errorMessage = ""
        coroutineScope.launch(Dispatchers.IO) {
            val client = TransferApiClient(serverUrl)
            val result = client.getTransfersHistory()
            withContext(Dispatchers.Main) {
                isLoading = false
                if (result.isSuccess) {
                    historyList = result.getOrThrow()
                } else {
                    errorMessage = result.exceptionOrNull()?.localizedMessage ?: "Failed to load history"
                }
            }
        }
    }

    LaunchedEffect(serverUrl) {
        loadHistory()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            NexusTopBar(
                subtitle = stringResource(R.string.nav_history),
                onMenuClick = onMenuClick,
                actions = {
                    IconButton(
                        onClick = { loadHistory() },
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            tint = SoloraNeonLime
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = SoloraNeonLime)
            } else if (errorMessage.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = SoloraAlertRed, modifier = Modifier.size(48.dp))
                    Text(errorMessage, color = SoloraAlertRed, fontSize = 13.sp)
                    Button(
                        onClick = { loadHistory() },
                        colors = ButtonDefaults.buttonColors(containerColor = SoloraSurfaceElevated, contentColor = SoloraTextPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("RETRY", fontWeight = FontWeight.Bold)
                    }
                }
            } else if (historyList.isEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.History, contentDescription = null, tint = SoloraTextMuted, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("No past transmission sessions recorded.", color = SoloraTextSecondary, fontSize = 13.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = historyList,
                        key = { it.transferId }
                    ) { item ->
                        val isComplete = (item.status == "COMPLETED")
                        val pct = if (item.totalSize > 0) ((item.receivedBytes * 100) / item.totalSize).toInt() else 0

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isComplete) {
                                        onResumeSession(item)
                                    }
                                },
                            shape = RoundedCornerShape(18.dp),
                            color = SoloraSurfaceCard,
                            border = BorderStroke(1.dp, if (isComplete) SoloraBorder else SoloraSolarAmber.copy(alpha = 0.4f))
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
                                        item.filename,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp,
                                        color = SoloraTextPrimary,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isComplete) SoloraEnergyGreen.copy(alpha = 0.15f) else SoloraSolarAmber.copy(alpha = 0.15f)
                                    ) {
                                        Text(
                                            if (isComplete) "COMPLETED" else "INCOMPLETE ($pct%)",
                                            color = if (isComplete) SoloraEnergyGreen else SoloraSolarAmber,
                                            fontWeight = FontWeight.Black,
                                            fontSize = 10.sp,
                                            letterSpacing = 1.sp,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                LinearProgressIndicator(
                                    progress = { if (item.totalSize > 0) item.receivedBytes.toFloat() / item.totalSize.toFloat() else 0f },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp),
                                    trackColor = SoloraSurfaceElevated,
                                    color = if (isComplete) SoloraEnergyGreen else SoloraSolarAmber
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${formatFileSize(item.receivedBytes)} / ${formatFileSize(item.totalSize)}",
                                        fontSize = 12.sp,
                                        color = SoloraTextSecondary
                                    )

                                    Text(
                                        "ID: ${item.transferId}",
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = SoloraTextMuted
                                    )
                                }

                                if (!item.calculatedSha256.isNullOrBlank()) {
                                    Text(
                                        "SHA-256: ${item.calculatedSha256}",
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = SoloraEnergyGreen
                                    )
                                }

                                if (!isComplete) {
                                    Button(
                                        onClick = { onResumeSession(item) },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = SoloraNeonLime, contentColor = SoloraBgDark)
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("RESUME FROM ${pct}%", fontWeight = FontWeight.Black, fontSize = 12.sp, letterSpacing = 1.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
