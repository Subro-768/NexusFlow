package com.resumabletransfer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.resumabletransfer.app.server.EmbeddedTransferServer
import com.resumabletransfer.app.ui.ReceiverScreen
import com.resumabletransfer.app.ui.ResumableTransferTheme
import com.resumabletransfer.app.ui.SettingsScreen
import com.resumabletransfer.app.ui.TransferHistoryScreen
import com.resumabletransfer.app.ui.TransferScreen
import com.resumabletransfer.app.ui.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScreenNav(val title: String) {
    TRANSFER("Send File"),
    RECEIVER("Receive File"),
    HISTORY("Transfer History"),
    SETTINGS("Settings")
}

class MainActivity : ComponentActivity() {

    private lateinit var transferManager: TransferManager
    private var embeddedServer: EmbeddedTransferServer? = null
    private var nsdHelper: NsdHelper? = null

    private var serverIp by mutableStateOf("127.0.0.1")
    private var serverPort by mutableStateOf("8000")
    private var isConnected by mutableStateOf(false)
    private var connectionStatusText by mutableStateOf("")

    private var selectedFileName by mutableStateOf<String?>(null)
    private var selectedFileSize by mutableStateOf<Long?>(null)
    private var selectedFileUri by mutableStateOf<Uri?>(null)

    private var isReceiverRunning by mutableStateOf(false)
    private var receiverIps by mutableStateOf<List<String>>(listOf("127.0.0.1"))
    private var recentTargetIps by mutableStateOf<List<String>>(emptyList())
    private var deviceName by mutableStateOf("")
    private var discoveredPeers by mutableStateOf<List<PeerDevice>>(emptyList())
    private var isDiscovering by mutableStateOf(false)

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (e: Exception) {
                // Ignore if not grantable
            }
            handleSelectedFile(uri)
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission needed for background transfers", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transferManager = TransferManager.getInstance(applicationContext)
        TransferForegroundService.transferManagerInstance = transferManager

        embeddedServer = EmbeddedTransferServer(applicationContext, 8000)
        receiverIps = embeddedServer?.getLocalIpAddresses() ?: listOf("127.0.0.1")

        // Initialize NSD helper for device discovery
        nsdHelper = NsdHelper(applicationContext)

        // Restore saved server settings
        serverIp = transferManager.getSavedServerIp()
        serverPort = transferManager.getSavedServerPort()
        recentTargetIps = transferManager.getRecentIps()
        deviceName = transferManager.getDeviceName()

        // Start peer discovery immediately (sender view)
        startPeerDiscovery()

        // Fresh cold start: reset active file selection and transfer state to IDLE
        selectedFileName = null
        selectedFileSize = null
        selectedFileUri = null
        transferManager.clearSavedSession()

        checkNotificationPermission()

        setContent {
            ResumableTransferTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                var currentScreen by remember { mutableStateOf(ScreenNav.TRANSFER) }
                val progress by transferManager.progressState.collectAsState()
                val incomingTransfer by (embeddedServer?.incomingState ?: MutableStateFlow(null)).collectAsState()

                BackHandler(enabled = drawerState.isOpen || currentScreen != ScreenNav.TRANSFER) {
                    if (drawerState.isOpen) {
                        scope.launch { drawerState.close() }
                    } else {
                        currentScreen = ScreenNav.TRANSFER
                    }
                }

                ModalNavigationDrawer(
                    drawerState = drawerState,
                    drawerContent = {
                        ModalDrawerSheet(
                            modifier = Modifier.width(300.dp),
                            drawerShape = RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                        ) {
                            Spacer(Modifier.height(24.dp))
                            Row(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                androidx.compose.foundation.Image(
                                    painter = androidx.compose.ui.res.painterResource(id = R.drawable.ic_nexus_flow),
                                    contentDescription = "Nexus Flow Logo",
                                    modifier = Modifier.size(38.dp)
                                )
                                Column {
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(SpanStyle(color = com.resumabletransfer.app.ui.SoloraCyan, fontWeight = FontWeight.Black)) {
                                                append("NEXUS ")
                                            }
                                            withStyle(SpanStyle(color = com.resumabletransfer.app.ui.SoloraEnergyGreen, fontWeight = FontWeight.Black)) {
                                                append("FLOW")
                                            }
                                        },
                                        fontSize = 18.sp,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        "SMART FILE TRANSFER",
                                        fontSize = 10.sp,
                                        color = com.resumabletransfer.app.ui.SoloraTextSecondary,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 1.sp
                                    )
                                }
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Upload, contentDescription = null) },
                                label = { Text("Sender Mode", fontWeight = FontWeight.Medium) },
                                selected = currentScreen == ScreenNav.TRANSFER,
                                onClick = {
                                    currentScreen = ScreenNav.TRANSFER
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Download, contentDescription = null) },
                                label = { Text("Receiver Hub (P2P)", fontWeight = FontWeight.Medium) },
                                selected = currentScreen == ScreenNav.RECEIVER,
                                onClick = {
                                    currentScreen = ScreenNav.RECEIVER
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.History, contentDescription = null) },
                                label = { Text("Transfer History", fontWeight = FontWeight.Medium) },
                                selected = currentScreen == ScreenNav.HISTORY,
                                onClick = {
                                    currentScreen = ScreenNav.HISTORY
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            )

                            NavigationDrawerItem(
                                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                                label = { Text("Settings", fontWeight = FontWeight.Medium) },
                                selected = currentScreen == ScreenNav.SETTINGS,
                                onClick = {
                                    currentScreen = ScreenNav.SETTINGS
                                    scope.launch { drawerState.close() }
                                },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                shape = RoundedCornerShape(8.dp)
                            )

                            Spacer(Modifier.weight(1f))

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text("Connected Server", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("http://$serverIp:$serverPort", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                ) {
                    AnimatedContent(
                        targetState = currentScreen,
                        transitionSpec = {
                            fadeIn() togetherWith fadeOut()
                        },
                        label = "ScreenTransition"
                    ) { screen ->
                        when (screen) {
                            ScreenNav.TRANSFER -> {
                                val displayName = progress.filename.ifEmpty { selectedFileName ?: "" }
                                val displaySize = if (progress.totalBytes > 0) progress.totalBytes else (selectedFileSize ?: 0L)

                                TransferScreen(
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    serverIp = serverIp,
                                    onServerIpChange = {
                                        serverIp = it
                                        transferManager.setSavedServerIp(it)
                                    },
                                    serverPort = serverPort,
                                    onServerPortChange = {
                                        serverPort = it
                                        transferManager.setSavedServerPort(it)
                                    },
                                    recentIps = recentTargetIps,
                                    onSelectRecentIp = { ip ->
                                        serverIp = ip
                                        transferManager.setSavedServerIp(ip)
                                        testConnection()
                                    },
                                    discoveredPeers = discoveredPeers,
                                    isDiscovering = isDiscovering,
                                    onSelectPeer = { peer ->
                                        serverIp = peer.host
                                        serverPort = peer.port.toString()
                                        transferManager.setSavedServerIp(peer.host)
                                        transferManager.setSavedServerPort(peer.port.toString())
                                        testConnection()
                                    },
                                    onTestConnection = { testConnection() },
                                    connectionStatusText = connectionStatusText,
                                    isConnected = isConnected,
                                    selectedFileName = if (displayName.isNotEmpty()) displayName else null,
                                    selectedFileSize = if (displaySize > 0) displaySize else null,
                                    selectedFileUri = selectedFileUri,
                                    onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    progress = progress,
                                    onStartTransfer = { startTransfer() },
                                    onPauseTransfer = { pauseTransfer() },
                                    onResumeTransfer = { resumeTransfer() },
                                    onCancelTransfer = { cancelTransfer() },
                                    onNewTransfer = { handleNewTransfer() }
                                )
                            }
                            ScreenNav.RECEIVER -> {
                                ReceiverScreen(
                                    isServerRunning = isReceiverRunning,
                                    localIps = receiverIps,
                                    incomingState = incomingTransfer,
                                    onToggleServer = { shouldRun ->
                                        if (shouldRun) {
                                            val started = embeddedServer?.start() ?: false
                                            isReceiverRunning = started
                                            if (started) {
                                                receiverIps = embeddedServer?.getLocalIpAddresses() ?: listOf("127.0.0.1")
                                                // Register NSD service so senders can discover this device by name
                                                val name = deviceName.ifBlank { transferManager.getDeviceName() }
                                                nsdHelper?.registerService(name, 8000)
                                                Toast.makeText(this@MainActivity, "Receiver Started — Broadcasting as \"$name\"", Toast.LENGTH_SHORT).show()
                                            } else {
                                                Toast.makeText(this@MainActivity, "Failed to start server (port in use?)", Toast.LENGTH_LONG).show()
                                            }
                                        } else {
                                            embeddedServer?.stop()
                                            nsdHelper?.unregisterService()
                                            isReceiverRunning = false
                                            Toast.makeText(this@MainActivity, "Receiver Server Stopped", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    onRefreshIp = {
                                        receiverIps = embeddedServer?.getLocalIpAddresses() ?: listOf("127.0.0.1")
                                        Toast.makeText(this@MainActivity, "IP Refreshed: ${receiverIps.firstOrNull() ?: "127.0.0.1"}", Toast.LENGTH_SHORT).show()
                                    },
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    onOpenDownloads = { openReceivedDownloadsFolder() },
                                    deviceName = deviceName,
                                    onDeviceNameChange = { newName ->
                                        deviceName = newName
                                        transferManager.setDeviceName(newName)
                                    }
                                )
                            }
                            ScreenNav.HISTORY -> {
                                TransferHistoryScreen(
                                    serverUrl = "http://${serverIp.trim()}:${serverPort.trim()}",
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    onResumeSession = { session ->
                                        loadAndResumeHistorySession(session)
                                        currentScreen = ScreenNav.TRANSFER
                                    }
                                )
                            }
                            ScreenNav.SETTINGS -> {
                                SettingsScreen(
                                    transferManager = transferManager,
                                    onMenuClick = { scope.launch { drawerState.open() } }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private fun openReceivedDownloadsFolder() {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val nexusDir = File(downloads, "NexusFlow")
                setDataAndType(Uri.parse(nexusDir.absolutePath), "*/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Files saved in Downloads/NexusFlow", Toast.LENGTH_LONG).show()
        }
    }

    private fun handleNewTransfer() {
        selectedFileName = null
        selectedFileSize = null
        selectedFileUri = null
        transferManager.clearSavedSession()
    }

    private fun handleSelectedFile(uri: Uri) {
        selectedFileUri = uri
        val cursor = contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = it.getColumnIndex(OpenableColumns.SIZE)

                val name = if (nameIndex != -1) it.getString(nameIndex) else "unknown_file"
                val size = if (sizeIndex != -1) it.getLong(sizeIndex) else 0L

                selectedFileName = name
                selectedFileSize = size

                transferManager.saveFileSelection(uri, name, size)
            }
        }
    }

    private fun loadAndResumeHistorySession(session: TransferApiClient.TransferSession) {
        serverIp = serverIp.trim()
        serverPort = serverPort.trim()
        val url = "http://$serverIp:$serverPort"

        val uri = selectedFileUri ?: transferManager.getSavedFileUri()
        if (uri == null) {
            Toast.makeText(this, "Please pick '${session.filename}' to resume bytes", Toast.LENGTH_LONG).show()
            filePickerLauncher.launch(arrayOf("*/*"))
            return
        }

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.startTransfer(
            serverUrl = url,
            uri = uri,
            filename = session.filename,
            fileSize = session.totalSize,
            existingTransferId = session.transferId
        )
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun testConnection() {
        lifecycleScope.launch {
            connectionStatusText = "Testing..."
            val url = "http://${serverIp.trim()}:${serverPort.trim()}"
            val client = TransferApiClient(url)
            val result = withContext(Dispatchers.IO) {
                client.checkHealth()
            }
            result.onSuccess { info ->
                isConnected = true
                connectionStatusText = "Connected (${info.service})"
                // Save to recent IP history
                val ip = serverIp.trim()
                if (ip.isNotEmpty() && ip != "127.0.0.1") {
                    transferManager.saveRecentIp(ip)
                    recentTargetIps = transferManager.getRecentIps()
                }
            }.onFailure { err ->
                isConnected = false
                val reason = err.message ?: "Connection failed"
                if (reason.contains("127.0.0.1") || reason.contains("localhost")) {
                    connectionStatusText = "Run: adb reverse tcp:8000 tcp:8000"
                } else {
                    connectionStatusText = "Connection Failed"
                }
            }
        }
    }

    private fun startTransfer() {
        val uri = selectedFileUri ?: transferManager.getSavedFileUri() ?: return
        val name = selectedFileName ?: transferManager.getSavedFileName() ?: "file"
        val size = selectedFileSize ?: transferManager.getSavedFileSize() ?: return
        val url = "http://${serverIp.trim()}:${serverPort.trim()}"

        // Save to recent IP history on transfer start
        val ip = serverIp.trim()
        if (ip.isNotEmpty() && ip != "127.0.0.1") {
            transferManager.saveRecentIp(ip)
            recentTargetIps = transferManager.getRecentIps()
        }

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.startTransfer(
            serverUrl = url,
            uri = uri,
            filename = name,
            fileSize = size
        )
    }

    private fun pauseTransfer() {
        transferManager.pauseTransfer()
    }

    private fun resumeTransfer() {
        val uri = selectedFileUri ?: transferManager.getSavedFileUri()
        if (uri == null) {
            Toast.makeText(this, "Please select the file from device to resume", Toast.LENGTH_SHORT).show()
            filePickerLauncher.launch(arrayOf("*/*"))
            return
        }
        val name = selectedFileName ?: transferManager.getSavedFileName() ?: "file"
        val size = selectedFileSize ?: transferManager.getSavedFileSize() ?: return
        val url = "http://${serverIp.trim()}:${serverPort.trim()}"

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.resumeTransfer(
            serverUrl = url,
            uri = uri,
            filename = name,
            fileSize = size
        )
    }

    private fun cancelTransfer() {
        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_CANCEL
        }
        startService(serviceIntent)
        transferManager.cancelTransfer()
    }

    private fun startPeerDiscovery() {
        val ownIps = embeddedServer?.getLocalIpAddresses() ?: listOf("127.0.0.1")
        isDiscovering = true
        val helper = nsdHelper ?: return
        helper.startDiscovery(ownIps)
        // Collect discovered peers into state
        lifecycleScope.launch {
            helper.peers.collect { peers ->
                discoveredPeers = peers
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        embeddedServer?.stop()
        nsdHelper?.destroy()
    }
}
