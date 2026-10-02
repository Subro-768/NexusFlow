package com.resumabletransfer.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.resumabletransfer.app.HistoryEntry
import com.resumabletransfer.app.HistoryStore
import com.resumabletransfer.app.PeerHistory
import com.resumabletransfer.app.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Transfer history, grouped by device.
 *
 * Reads from [HistoryStore] rather than `GET /transfers` on the currently
 * selected peer, so the list is cumulative: it shows every transfer this phone
 * has ever made, across all devices, and survives switching targets or being
 * offline. Tapping a peer opens [PeerDetailScreen].
 */
@Composable
fun TransferHistoryScreen(
    onMenuClick: () -> Unit,
    onOpenPeer: (PeerHistory) -> Unit,
    onResumeSession: (HistoryEntry) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var peers by remember { mutableStateOf<List<PeerHistory>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun reload() {
        isLoading = true
        val loaded = withContext(Dispatchers.IO) { HistoryStore.get(context).peers() }
        peers = loaded
        isLoading = false
    }

    LaunchedEffect(Unit) { reload() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            NexusTopBar(
                subtitle = stringResource(R.string.nav_history),
                onMenuClick = onMenuClick,
                actions = {
                    IconButton(
                        onClick = { scope.launch { reload() } },
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
            when {
                isLoading -> CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = SoloraNeonLime
                )

                peers.isEmpty() -> Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.History,
                        contentDescription = null,
                        tint = SoloraTextMuted,
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        stringResource(R.string.history_empty),
                        color = SoloraTextSecondary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                else -> LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(peers, key = { it.key }) { peer ->
                        PeerHistoryRow(peer = peer, onClick = { onOpenPeer(peer) })
                    }
                }
            }
        }
    }
}

/** One device row: name, file count, total size, last activity. */
@Composable
private fun PeerHistoryRow(peer: PeerHistory, onClick: () -> Unit) {
    val sent = peer.entries.count { it.direction == HistoryEntry.DIRECTION_SENT }
    val received = peer.entries.count { it.direction == HistoryEntry.DIRECTION_RECEIVED }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(SoloraNeonLime.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Person,
                    contentDescription = null,
                    tint = SoloraNeonLime,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    peer.displayName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = SoloraTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    buildString {
                        append(stringResource(R.string.history_file_count, peer.fileCount))
                        append("  ·  ")
                        append(formatFileSize(peer.totalBytes))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = SoloraTextSecondary
                )
                if (sent > 0 || received > 0) {
                    Text(
                        buildString {
                            if (sent > 0) append("$sent " + stringResource(R.string.history_sent))
                            if (sent > 0 && received > 0) append("  ·  ")
                            if (received > 0) append("$received " + stringResource(R.string.history_received))
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SoloraTextMuted
                    )
                }
            }

            Text(
                formatRelative(peer.lastActivityMillis),
                style = MaterialTheme.typography.labelSmall,
                color = SoloraTextMuted
            )
        }
    }
}

/**
 * Per-device detail: every file sent to / received from one peer, newest first.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerDetailScreen(
    peer: PeerHistory,
    onBack: () -> Unit,
    onResumeSession: (HistoryEntry) -> Unit
) {
    val dateFormat = remember {
        SimpleDateFormat("yyyy-MM-dd  HH:mm", Locale.getDefault())
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            peer.displayName,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = SoloraTextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            stringResource(
                                R.string.history_peer_summary,
                                peer.fileCount,
                                formatFileSize(peer.totalBytes)
                            ),
                            fontSize = 9.sp,
                            color = SoloraTextSecondary,
                            letterSpacing = 1.sp
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.history_back),
                            tint = SoloraTextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = SoloraTextPrimary
                )
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(peer.entries, key = { it.id + it.timestampMillis }) { entry ->
                HistoryEntryRow(
                    entry = entry,
                    dateFormat = dateFormat,
                    onResume = { onResumeSession(entry) }
                )
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(
    entry: HistoryEntry,
    dateFormat: SimpleDateFormat,
    onResume: () -> Unit
) {
    val isSend = entry.direction == HistoryEntry.DIRECTION_SENT
    val accent = when (entry.status.uppercase()) {
        "COMPLETED" -> SoloraEnergyGreen
        "FAILED" -> SoloraAlertRed
        else -> SoloraSolarAmber
    }
    val animatedFraction by animateFloatAsState(
        targetValue = if (entry.totalBytes > 0) {
            (entry.transferredBytes.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
        } else 0f,
        animationSpec = tween(400),
        label = "entryProgress"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = SoloraSurfaceCard,
        border = BorderStroke(1.dp, SoloraBorder)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        if (isSend) Icons.Default.Upload else Icons.Default.Download,
                        contentDescription = null,
                        tint = if (isSend) SoloraCyan else SoloraEnergyGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        entry.filename,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = SoloraTextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = accent.copy(alpha = 0.15f)
                ) {
                    Text(
                        entry.status.uppercase(),
                        color = accent,
                        fontWeight = FontWeight.Black,
                        fontSize = 9.sp,
                        letterSpacing = 1.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            LinearProgressIndicator(
                progress = { animatedFraction },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp),
                trackColor = SoloraSurfaceElevated,
                color = accent
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${formatFileSize(entry.transferredBytes)} / ${formatFileSize(entry.totalBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SoloraTextSecondary
                )
                Text(
                    dateFormat.format(Date(entry.timestampMillis)),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = SoloraTextMuted
                )
            }

            if (!entry.isComplete && isSend) {
                Button(
                    onClick = onResume,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SoloraNeonLime,
                        contentColor = SoloraTextOnAccent
                    )
                ) {
                    Text(
                        stringResource(R.string.history_resume),
                        fontWeight = FontWeight.Black,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp
                    )
                }
            }
        }
    }
}

private fun formatRelative(millis: Long): String {
    if (millis <= 0L) return "--"
    val diff = System.currentTimeMillis() - millis
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000
    return when {
        minutes < 1 -> "now"
        minutes < 60 -> "${minutes}m"
        hours < 24 -> "${hours}h"
        days < 30 -> "${days}d"
        else -> SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(millis))
    }
}
