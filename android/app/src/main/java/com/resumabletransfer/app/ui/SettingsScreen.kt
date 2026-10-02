package com.resumabletransfer.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumabletransfer.app.R
import com.resumabletransfer.app.TransferManager
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    transferManager: TransferManager,
    onMenuClick: () -> Unit
) {
    val scrollState = rememberScrollState()
    // rememberSaveable so the typed values survive rotation. rememberSaveable
    // also requires the values to be Bundle-saveable, hence the primitives.
    var chunkSizeKb by rememberSaveable { mutableStateOf(transferManager.getChunkSizeKb()) }
    var throttleDelayMs by rememberSaveable { mutableStateOf(transferManager.getThrottleDelayMs()) }
    // A real SnackbarHostState instead of a boolean: the old version popped in
    // with no enter/exit animation and vanished on navigation.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NexusTopBar(
                subtitle = stringResource(R.string.settings_title),
                onMenuClick = onMenuClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Chunk Size Card
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
                        Icon(Icons.Default.Memory, contentDescription = null, tint = SoloraNeonLime, modifier = Modifier.size(20.dp))
                        Text("STREAM CHUNK SIZE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = SoloraTextSecondary)
                    }
                    Text(
                        "Controls the byte payload buffer size per HTTP transaction slice.",
                        fontSize = 12.sp,
                        color = SoloraTextSecondary
                    )

                    val chunkOptions = listOf(256, 512, 1024, 2048, 4096)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        chunkOptions.forEach { sizeKb ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (chunkSizeKb == sizeKb),
                                    onClick = {
                                        chunkSizeKb = sizeKb
                                        transferManager.setChunkSizeKb(sizeKb)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = SoloraNeonLime, unselectedColor = SoloraBorder)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (sizeKb >= 1024) "${sizeKb / 1024} MB" else "$sizeKb KB",
                                    fontWeight = if (chunkSizeKb == sizeKb) FontWeight.Bold else FontWeight.Normal,
                                    color = SoloraTextPrimary
                                )
                                if (sizeKb == 1024) {
                                    Spacer(Modifier.width(8.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = SoloraNeonLime.copy(alpha = 0.15f)
                                    ) {
                                        Text("DEFAULT", modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 9.sp, fontWeight = FontWeight.Black, color = SoloraNeonLime, letterSpacing = 1.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Demo Simulation Throttle
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
                        Icon(Icons.Default.Speed, contentDescription = null, tint = SoloraCyan, modifier = Modifier.size(20.dp))
                        Text("DEMO THROTTLE SIMULATOR", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = SoloraTextSecondary)
                    }
                    Text(
                        "Adds simulated micro-delays between chunks for effortless hackathon demonstration of live interruptions & resumes.",
                        fontSize = 12.sp,
                        color = SoloraTextSecondary
                    )

                    val delayOptions = listOf(
                        0 to "Max Bandwidth (0ms delay)",
                        150 to "Demo Mode (150ms / chunk)",
                        400 to "Slow Presentation (400ms / chunk)"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        delayOptions.forEach { (delayVal, label) ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = (throttleDelayMs == delayVal),
                                    onClick = {
                                        throttleDelayMs = delayVal
                                        transferManager.setThrottleDelayMs(delayVal)
                                    },
                                    colors = RadioButtonDefaults.colors(selectedColor = SoloraCyan, unselectedColor = SoloraBorder)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    label,
                                    fontWeight = if (throttleDelayMs == delayVal) FontWeight.Bold else FontWeight.Normal,
                                    color = SoloraTextPrimary
                                )
                            }
                        }
                    }
                }
            }

            // Session Reset Card
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
                    Text("PURGE SESSION CACHE", fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = SoloraTextSecondary)
                    Text(
                        "Clears stored session IDs, previous file references, and cache.",
                        fontSize = 12.sp,
                        color = SoloraTextSecondary
                    )
                    OutlinedButton(
                        onClick = {
                            transferManager.clearSavedSession()
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.settings_session_cleared)
                                )
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SoloraAlertRed),
                        border = BorderStroke(1.dp, SoloraAlertRed.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("PURGE CACHE", fontWeight = FontWeight.Bold, fontSize = 11.sp, letterSpacing = 1.sp)
                    }
                }
            }
        }
    }
}
