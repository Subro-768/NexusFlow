package com.resumabletransfer.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.resumabletransfer.app.server.EmbeddedTransferServer
import com.resumabletransfer.app.ui.ReceiverScreen
import com.resumabletransfer.app.ui.ResumableTransferTheme
import com.resumabletransfer.app.ui.SettingsScreen
import com.resumabletransfer.app.ui.PeerDetailScreen
import com.resumabletransfer.app.ui.TransferHistoryScreen
import com.resumabletransfer.app.ui.TransferScreen
import com.resumabletransfer.app.ui.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class ScreenNav(val title: String) {
    TRANSFER("Send File"),
    RECEIVER("Receive File"),
    HISTORY("Transfer History"),
    SETTINGS("Settings")
}

private const val NEXUS_SCHEME = "nexus://"

/** Name-only pairing form, matching the Linux Hub's QR. See applyPairingUri. */
private const val RECEIVE_SCHEME = "nexus://receive/"

/** How long a scanned name is polled for in the discovery list. */
private const val PAIRING_LOOKUP_MS = 12_000L

/**
 * The pairing payload this device shows as a QR.
 *
 * Name-only, for the same privacy reason as the Linux Hub: the code is
 * photographed, screenshotted and sent over chat, and none of those should leak
 * a LAN address. The sender resolves the name over mDNS.
 */
fun buildPairingPayload(deviceName: String): String =
    RECEIVE_SCHEME + java.net.URLEncoder.encode(deviceName.ifBlank { "NexusFlow" }, "UTF-8")
private const val DEFAULT_PORT = 8000

private const val STATE_URI = "state_selected_uri"
private const val STATE_FILENAME = "state_selected_filename"
private const val STATE_FILESIZE = "state_selected_filesize"
private const val STATE_SCREEN = "state_current_screen"
private const val STATE_SHARED_INDEX = "state_shared_index"
private const val STATE_SHARED_URIS = "state_shared_uris"

class MainActivity : ComponentActivity() {

    private lateinit var transferManager: TransferManager
    private var embeddedServer: EmbeddedTransferServer? = null
    private var nsdHelper: NsdHelper? = null

    /**
     * Current top-level destination.
     *
     * Hoisted out of setContent because the Sharesheet handler needs to navigate
     * to the Send screen from outside the composition -- an incoming share can
     * arrive while the user is on the Receiver or History screen.
     */
    private var currentScreen by mutableStateOf(ScreenNav.TRANSFER)

    private var serverIp by mutableStateOf("127.0.0.1")
    private var serverPort by mutableStateOf("8000")
    private var isConnected by mutableStateOf(false)
    private var connectionStatusText by mutableStateOf("")

    private var selectedFileName by mutableStateOf<String?>(null)
    private var selectedFileSize by mutableStateOf<Long?>(null)
    private var selectedFileUri by mutableStateOf<Uri?>(null)

    private var isReceiverRunning by mutableStateOf(false)
    private var deviceName by mutableStateOf("")
    private var discoveredPeers by mutableStateOf<List<PeerDevice>>(emptyList())
    private var isDiscovering by mutableStateOf(false)

    /** Subnet-scan progress in percent, or -1 when not scanning. */
    private var scanningProgress by mutableStateOf(-1)

    /** Name of the peer currently selected as target, for history grouping. */
    private var selectedPeerName by mutableStateOf("")

    /** Non-null while the History screen is drilled into one device. */
    private var selectedPeerHistory by mutableStateOf<PeerHistory?>(null)

    /**
     * Files received from the Android Sharesheet, queued for sending.
     *
     * Held in state so the UI can list them with name/size/type and let the user
     * drop items before starting. [sharedQueueIndex] tracks which one the
     * progress card is currently showing; the queue itself survives rotation via
     * rememberSaveable in the composable that renders it.
     */
    private var sharedFiles by mutableStateOf<List<SharedFile>>(emptyList())
    private var sharedQueueIndex by mutableStateOf(0)

    /**
     * Feedback channel. Every `Toast.makeText` call in this Activity (there
     * were ten) is replaced by a snackbar so messages are anchored to the app,
     * survive a configuration change, and can carry an action. Toasts raised
     * from inside a composable cannot do any of that.
     */
    private val snackbarHostState = SnackbarHostState()

    private fun notifyUser(message: String) {
        lifecycleScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }

    /**
     * Opens the camera to scan a peer's pairing code.
     *
     * The button used to open a text box and ask for `nexus://host:port`, which
     * works but is not what "Scan QR code" implies -- and the Linux Receiver
     * draws that exact string as a QR, so the user had to transcribe it by hand.
     * This launches the real scanner; the typed dialog is still reachable as a
     * fallback for a peer whose camera is broken or whose code is emailed.
     */
    private fun launchQrScanner() {
        qrScanLauncher.launch(ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt(getString(R.string.qr_scan_prompt))
            setBeepEnabled(false)
            setOrientationLocked(false)
            // The payload is short; a short timeout stops the camera staring at
            // the lens for anyone who opened it by mistake.
            setTimeout(30_000L)
        })
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents.isNullOrBlank()) {
            // Cancelled or timed out. Nothing to say: the button did nothing
            // visible, so a snackbar here would be noise.
            return@registerForActivityResult
        }
        applyPairingUri(result.contents)
    }

    /**
     * Handles a scanned or pasted `nexus://` pairing payload by pre-filling the
     * target host and port. The QR button used to render as an enabled icon with
     * an empty body while the desktop build encoded this scheme, so scanning a
     * peer's code previously did nothing at all.
     */
    private fun applyPairingUri(raw: String) {
        val uri = raw.trim()
        val host: String?
        val port: Int?
        when {
            // `nexus://receive/<name>` is what the Linux Hub draws and now what
            // this app draws too. It carries no address on purpose: a photo of the
            // QR should not hand over the machine's LAN IP. The name is resolved
            // through the mDNS peer list instead.
            uri.startsWith(RECEIVE_SCHEME) -> {
                val wanted = runCatching {
                    java.net.URLDecoder.decode(
                        uri.removePrefix(RECEIVE_SCHEME).substringBefore('?').trim('/'),
                        "UTF-8"
                    )
                }.getOrElse { uri.removePrefix(RECEIVE_SCHEME).substringBefore('?').trim('/') }
                // Name matching is tolerant: discovery reports whatever the peer
                // advertised, which need not match the QR byte for byte on case
                // or surrounding space.
                fun findPeer() = discoveredPeers.firstOrNull {
                    it.name.trim().equals(wanted.trim(), ignoreCase = true)
                }
                val peer = findPeer()
                if (peer != null) {
                    host = peer.host
                    port = peer.port
                } else {
                    // Discovery has not seen it yet, and it usually appears a
                    // second or two later. Rescanning and giving up meant a user
                    // who scanned slightly early had to scan again, so poll for a
                    // bounded while instead -- and still never guess an address.
                    notifyUser(getString(R.string.msg_pairing_searching, wanted))
                    rescanPeers()
                    lifecycleScope.launch {
                        val deadline = System.currentTimeMillis() + PAIRING_LOOKUP_MS
                        while (System.currentTimeMillis() < deadline) {
                            delay(500)
                            val found = findPeer() ?: continue
                            serverIp = found.host
                            found.port.let { serverPort = it.toString() }
                            transferManager.setSavedServerIp(found.host)
                            transferManager.setSavedServerPort(serverPort)
                            notifyUser(
                                getString(R.string.msg_pairing_connected, found.host, serverPort)
                            )
                            testConnection()
                            return@launch
                        }
                        notifyUser(getString(R.string.msg_pairing_name_not_found, wanted))
                    }
                    return
                }
            }
            uri.startsWith(NEXUS_SCHEME) -> {
                val authority = uri.removePrefix(NEXUS_SCHEME)
                    .substringBefore('?')
                    .trim('/')
                val hostPart = authority.substringBefore(':')
                val portPart = authority.substringAfter(':', "")
                host = hostPart.takeIf { it.isNotBlank() }
                port = portPart.toIntOrNull() ?: DEFAULT_PORT
            }
            uri.startsWith("http://") || uri.startsWith("https://") -> {
                val withoutScheme = uri.substringAfter("://")
                host = withoutScheme.substringBefore('/').substringBefore(':').takeIf { it.isNotBlank() }
                port = withoutScheme.substringAfter(':', "").substringBefore('/').toIntOrNull() ?: DEFAULT_PORT
            }
            else -> {
                host = null
                port = null
            }
        }
        if (host == null) {
            notifyUser(getString(R.string.msg_pairing_unrecognised))
            return
        }
        serverIp = host
        if (port != null) serverPort = port.toString()
        transferManager.setSavedServerIp(serverIp)
        transferManager.setSavedServerPort(serverPort)
        notifyUser(getString(R.string.msg_pairing_connected, serverIp, serverPort))
        testConnection()
    }

    /**
     * Manual entry for a pairing code.
     *
     * Kept as the fallback for the camera scanner: a peer whose camera is broken,
     * or a code that arrived by email. Accepts a pasted or typed
     * `nexus://host:port` payload -- the exact string the desktop Receiver screen
     * puts in its QR.
     */
    private fun showPairingCodeDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "nexus://192.168.1.5:8000"
            setSingleLine()
        }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.msg_qr_scanned)
            .setMessage(R.string.pairing_dialog_message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                applyPairingUri(input.text.toString())
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

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
            notifyUser(getString(R.string.msg_notification_permission))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        transferManager = TransferManager.getInstance(applicationContext)
        TransferForegroundService.transferManagerInstance = transferManager

        embeddedServer = EmbeddedTransferServer(applicationContext, 8000)

        // Initialize NSD helper for device discovery
        nsdHelper = NsdHelper(applicationContext)

        // Restore saved server settings
        serverIp = transferManager.getSavedServerIp()
        serverPort = transferManager.getSavedServerPort()
        deviceName = transferManager.getDeviceName()

        // Start peer discovery immediately (sender view)
        startPeerDiscovery()

        // Only on a genuine cold start. onCreate also runs on every configuration
        // change (rotation, theme switch, multi-window resize), and
        // clearSavedSession() cancels the running transfer job and wipes
        // KEY_URI/KEY_FILENAME/KEY_FILESIZE. Without this guard, rotating the
        // device mid-transfer destroyed the transfer and lost the file
        // reference -- in a resumable transfer app.
        if (savedInstanceState == null) {
            // A cold start used to wipe the session outright, which is exactly
            // the case the task cares about: kill the app at 40%, reopen it, and
            // the transfer should still be at 40%. So try to restore first and
            // only fall back to a clean slate when there is genuinely nothing in
            // flight (fresh install, or the previous transfer finished).
            val restored = transferManager.restoreSession()
            if (restored) {
                selectedFileUri = transferManager.getSavedFileUri()
                selectedFileName = transferManager.getSavedFileName()
                selectedFileSize = transferManager.getSavedFileSize()
            } else {
                selectedFileName = null
                selectedFileSize = null
                selectedFileUri = null
                transferManager.clearSavedSession()
            }

            // Same reasoning for the queue. savedInstanceState only covers a
            // configuration change, so a killed process used to lose the whole
            // batch. Entries whose staged copy the system reclaimed are dropped
            // rather than offered: a queue row pointing at a file that cannot be
            // read is worse than a shorter honest queue.
            val (persistedUris, persistedIndex) = transferManager.loadQueue()
            persistedUris.forEach { raw ->
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
                if (SharedFileStager.stagedFileFor(applicationContext, uri) != null) {
                    sharedFiles = sharedFiles + SharedFileStager.describeExisting(
                        applicationContext, uri
                    )
                }
            }
            if (sharedFiles.isNotEmpty()) {
                sharedQueueIndex = persistedIndex.coerceIn(0, sharedFiles.lastIndex)
            }
        } else {
            selectedFileUri = savedInstanceState.getString(STATE_URI)?.let(Uri::parse)
            selectedFileName = savedInstanceState.getString(STATE_FILENAME)
            selectedFileSize = savedInstanceState.getLong(STATE_FILESIZE, 0L).takeIf { it > 0L }
            currentScreen = savedInstanceState.getString(STATE_SCREEN)
                ?.let { runCatching { ScreenNav.valueOf(it) }.getOrNull() }
                ?: ScreenNav.TRANSFER
            sharedQueueIndex = savedInstanceState.getInt(STATE_SHARED_INDEX, 0)
            // Re-derive the queue from the staged files rather than trusting saved
            // metadata: a staged file may have been reclaimed from the cache, in
            // which case it must not be offered for sending again.
            savedInstanceState.getStringArrayList(STATE_SHARED_URIS)?.forEach { raw ->
                val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return@forEach
                if (SharedFileStager.stagedFileFor(applicationContext, uri) != null) {
                    sharedFiles = sharedFiles + SharedFileStager.describeExisting(
                        applicationContext, uri
                    )
                }
            }
        }

        checkNotificationPermission()

        // Process a share only on a genuine cold start.
        //
        // onCreate also runs on every configuration change, and Android replays
        // getIntent() there -- so calling this unconditionally re-staged the
        // shared bytes on each rotation and stacked duplicate copies in the
        // cache. On a recreate the queue is restored from savedInstanceState
        // above instead, which re-attaches to the existing staged copies via
        // stagedFileFor() rather than copying again.
        //
        // When the app was already running the intent arrives through
        // onNewIntent(), which calls this directly.
        if (savedInstanceState == null) {
            handleShareIntent(intent)
        }

        setContent {
            ResumableTransferTheme {
                val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
                val scope = rememberCoroutineScope()
                // currentScreen is hoisted to the Activity (see the field above) so
                // the Sharesheet handler can navigate without touching composition
                // state.
                // collectAsStateWithLifecycle stops collecting when the Activity
                // is not STARTED. With plain collectAsState the progress flow kept
                // driving recompositions for a backgrounded app.
                val progress by transferManager.progressState.collectAsStateWithLifecycle()
                val incomingTransfer by (embeddedServer?.incomingState
                    ?: remember { MutableStateFlow(null) }).collectAsStateWithLifecycle()

                // When a shared file finishes successfully, move the queue on to
                // the next one. Keyed on the status so it fires once per
                // transition, not on every recomposition.
                LaunchedEffect(progress.status) {
                    maybeAdvanceSharedQueue(progress.status)
                }

                // Persist the queue whenever its contents or position change,
                // from whichever of the seven mutation sites did it. Watching the
                // two state values here means no new queue code path can forget
                // to save -- the bug this replaces was a queue that survived a
                // rotation but not a process kill.
                LaunchedEffect(sharedFiles, sharedQueueIndex) {
                    if (sharedFiles.isEmpty()) {
                        transferManager.clearQueue()
                    } else {
                        transferManager.saveQueue(
                            sharedFiles.map { it.uri.toString() },
                            sharedQueueIndex
                        )
                    }
                }

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
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                Column {
                                    Text(
                                        text = buildAnnotatedString {
                                            withStyle(
                                                SpanStyle(
                                                    color = com.resumabletransfer.app.ui.SoloraCyan,
                                                    fontWeight = FontWeight.Black
                                                )
                                            ) {
                                                append(stringResource(R.string.brand_name_full))
                                            }
                                            withStyle(
                                                SpanStyle(
                                                    color = com.resumabletransfer.app.ui.SoloraEnergyGreen,
                                                    fontWeight = FontWeight.Black
                                                )
                                            ) {
                                                append(stringResource(R.string.brand_name_accent))
                                            }
                                        },
                                        fontSize = 18.sp,
                                        letterSpacing = 1.sp
                                    )
                                    Text(
                                        stringResource(R.string.brand_tagline),
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

                            // The "Connected Server" footer used to print the raw
                            // endpoint URL, which put the LAN address of the
                            // device this app was pointed at directly on screen.
                            // Peers are already selected from the nearby-devices
                            // list, so the block was redundant as well as a
                            // disclosure. Removed on request.
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
                                    discoveredPeers = discoveredPeers,
                                    isDiscovering = isDiscovering,
                                    onSelectPeer = { peer ->
                                        serverIp = peer.host
                                        serverPort = peer.port.toString()
                                        selectedPeerName = peer.name
                                        transferManager.setSavedServerIp(peer.host)
                                        transferManager.setSavedServerPort(peer.port.toString())
                                        testConnection()
                                    },
                                    onRefreshPeers = { rescanPeers() },
                                    onScanQr = { launchQrScanner() },
                                    onManualPairingEntry = { showPairingCodeDialog() },
                                    sharedFiles = sharedFiles,
                                    sharedQueueIndex = sharedQueueIndex,
                                    onRemoveShared = { removeSharedFile(it) },
                                    onClearShared = {
                                        clearSharedFiles()
                                        notifyUser(getString(R.string.msg_share_queue_cleared))
                                    },
                                    onSendAllShared = { sendAllSharedFiles() },
                                    selectedFileName = if (displayName.isNotEmpty()) displayName else null,
                                    selectedFileSize = if (displaySize > 0) displaySize else null,
                                    selectedFileUri = selectedFileUri,
                                    onPickFile = { filePickerLauncher.launch(arrayOf("*/*")) },
                                    progress = progress,
                                    onStartTransfer = { startTransfer() },
                                    onPauseTransfer = { pauseTransfer() },
                                    onResumeTransfer = { resumeTransfer() },
                                    onCancelTransfer = { cancelTransfer() },
                                    onNewTransfer = { handleNewTransfer() },
                                    snackbarHostState = snackbarHostState
                                )
                            }
                            ScreenNav.RECEIVER -> {
                                ReceiverScreen(
                                    isServerRunning = isReceiverRunning,
                                    incomingState = incomingTransfer,
                                    onToggleServer = { shouldRun ->
                                        if (shouldRun) {
                                            val started = embeddedServer?.start() ?: false
                                            isReceiverRunning = started
                                            if (started) {
                                                // Register NSD so senders discover this device
                                                // by name. The address is never shown on
                                                // screen -- peers resolve it over mDNS.
                                                val name = deviceName.ifBlank { transferManager.getDeviceName() }
                                                embeddedServer?.deviceName = name
                                                nsdHelper?.registerService(name, DEFAULT_PORT)
                                                // Also announce over UDP broadcast. mDNS does
                                                // NOT traverse an Android hotspot, so without
                                                // this a laptop tethered to this phone could
                                                // never discover it. Broadcast is the
                                                // mechanism that works there.
                                                LanDiscovery.startAdvertising(
                                                    applicationContext,
                                                    name,
                                                    DEFAULT_PORT
                                                )
                                                notifyUser(getString(R.string.msg_receiver_started, name))
                                            } else {
                                                notifyUser(getString(R.string.msg_receiver_start_failed))
                                            }
                                        } else {
                                            embeddedServer?.stop()
                                            nsdHelper?.unregisterService()
                                            LanDiscovery.stopAdvertising()
                                            isReceiverRunning = false
                                            notifyUser(getString(R.string.msg_receiver_stopped))
                                        }
                                    },
                                    onMenuClick = { scope.launch { drawerState.open() } },
                                    onOpenDownloads = { openReceivedDownloadsFolder() },
                                    deviceName = deviceName,
                                    onDeviceNameChange = { newName ->
                                        deviceName = newName
                                        transferManager.setDeviceName(newName)
                                    },
                                    // These act on the live session rather than on
                                    // the toggle: pausing stops the bytes while
                                    // keeping the peer addressable, so the sender
                                    // can pick the same offset back up on Resume.
                                    onPauseIncoming = {
                                        val id = incomingTransfer?.transferId
                                        if (id != null && embeddedServer?.pauseIncoming(id) == true) {
                                            notifyUser(getString(R.string.msg_incoming_paused))
                                        }
                                    },
                                    onResumeIncoming = {
                                        val id = incomingTransfer?.transferId
                                        if (id != null && embeddedServer?.resumeIncoming(id) == true) {
                                            notifyUser(getString(R.string.msg_incoming_resumed))
                                        }
                                    },
                                    // Only while the endpoint is actually up:
                                    // a QR for a dead receiver sends the sender
                                    // straight into a connection failure.
                                    // Refreshed by the Hub when the dropdown is
                                    // actually opened, not captured once here:
                                    // remember(isReceiverRunning) froze the list,
                                    // so joining a different Wi-Fi while
                                    // receiving showed the old address.
                                    localAddresses = {
                                        embeddedServer?.getLocalIpAddresses().orEmpty()
                                    },
                                    port = DEFAULT_PORT,
                                    pairingPayload = if (isReceiverRunning) {
                                        buildPairingPayload(
                                            deviceName.ifBlank { transferManager.getDeviceName() }
                                        )
                                    } else {
                                        null
                                    },
                                    onCancelIncoming = {
                                        val id = incomingTransfer?.transferId
                                        if (id != null && embeddedServer?.cancelIncoming(id) == true) {
                                            notifyUser(getString(R.string.msg_incoming_cancelled))
                                        }
                                    }
                                )
                            }
                            ScreenNav.HISTORY -> {
                                // A peer is open when the user has drilled into it;
                                // otherwise show the device list.
                                val openPeer = selectedPeerHistory
                                if (openPeer != null) {
                                    PeerDetailScreen(
                                        peer = openPeer,
                                        onBack = { selectedPeerHistory = null },
                                        onResumeSession = { entry ->
                                            resumeHistoryEntry(entry)
                                            selectedPeerHistory = null
                                            currentScreen = ScreenNav.TRANSFER
                                        }
                                    )
                                } else {
                                    TransferHistoryScreen(
                                        onMenuClick = { scope.launch { drawerState.open() } },
                                        onOpenPeer = { selectedPeerHistory = it },
                                        onResumeSession = { entry ->
                                            resumeHistoryEntry(entry)
                                            currentScreen = ScreenNav.TRANSFER
                                        }
                                    )
                                }
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
            notifyUser(getString(R.string.msg_files_saved_in))
        }
    }

    private fun handleNewTransfer() {
        // A shared batch belongs to the transfer that started it; starting a new
        // transfer by hand abandons the queue rather than leaving files staged
        // with no way to send them.
        if (sharedFiles.isNotEmpty()) clearSharedFiles()
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

    /**
     * Resumes an incomplete send from the history list.
     *
     * Now takes a [HistoryEntry] rather than a server session, because history is
     * read from the device-local store and carries the peer it belongs to — so
     * the target is restored from the entry rather than whatever peer happens
     * to be selected right now.
     */
    private fun resumeHistoryEntry(entry: HistoryEntry) {
        serverIp = serverIp.trim()
        serverPort = serverPort.trim()
        val url = "http://$serverIp:$serverPort"

        val uri = selectedFileUri ?: transferManager.getSavedFileUri()
        if (uri == null) {
            notifyUser(getString(R.string.msg_pick_to_resume_history, entry.filename))
            filePickerLauncher.launch(arrayOf("*/*"))
            return
        }

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.resumeTransfer(
            serverUrl = url,
            uri = uri,
            filename = entry.filename,
            fileSize = entry.totalBytes,
            peerDisplayName = entry.peerName.ifBlank { selectedPeerName }
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
                connectionStatusText = getString(R.string.msg_connected, info.service)
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

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.startTransfer(
            serverUrl = url,
            uri = uri,
            filename = name,
            fileSize = size,
            peerDisplayName = selectedPeerName
        )
    }

    private fun pauseTransfer() {
        transferManager.pauseTransfer()
    }

    private fun resumeTransfer() {
        val uri = selectedFileUri ?: transferManager.getSavedFileUri()
        if (uri == null) {
            notifyUser(getString(R.string.msg_select_file_to_resume))
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
            fileSize = size,
            peerDisplayName = selectedPeerName
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
        val helper = nsdHelper ?: run {
            isDiscovering = false
            return
        }
        isDiscovering = true
        helper.startDiscovery(ownIps)

        // UDP broadcast scanning, in parallel with mDNS. Broadcast is what
        // works on an Android hotspot (mDNS multicast is not forwarded), so it
        // is started unconditionally rather than only when NSD succeeds.
        // Results from both are merged so a peer visible either way appears.
        LanDiscovery.onPeersChanged = { lanPeers ->
            val fromLan = lanPeers.map {
                PeerDevice(it.name, it.host, it.port)
            }
            lifecycleScope.launch {
                val fromMdns = discoveredPeers
                discoveredPeers = (fromMdns + fromLan)
                    .distinctBy { "${it.name}|${it.host}|${it.port}" }
                    .sortedBy { it.name }
                if (discoveredPeers.isNotEmpty()) isDiscovering = false
            }
        }
        LanDiscovery.startScanning(applicationContext)

        // NsdHelper.peers is a StateFlow, so collect() replays the current value.
        // When discovery stops (listener torn down) the empty list arrives here
        // and isDiscovering must be cleared -- previously it was only ever set
        // to true, so the panel said "Looking for devices..." indefinitely.
        lifecycleScope.launch {
            helper.peers.collect { peers ->
                val fromLan = LanDiscovery.getPeers().map {
                    PeerDevice(it.name, it.host, it.port)
                }
                discoveredPeers = (peers + fromLan)
                    .distinctBy { "${it.name}|${it.host}|${it.port}" }
                    .sortedBy { it.name }
                isDiscovering = false
            }
        }

        // Also start TCP subnet scan for hotspots that drop both mDNS and UDP broadcast
        startSubnetScan()
    }

    /** Refresh button: query all discovery mechanisms immediately. */
    private fun rescanPeers() {
        LanDiscovery.scanNow()
        nsdHelper?.startDiscovery(
            embeddedServer?.getLocalIpAddresses() ?: listOf("127.0.0.1")
        )
        isDiscovering = true
        startSubnetScan()
    }

    private fun startSubnetScan() {
        SubnetScanner.onFound = { peer ->
            val device = PeerDevice(peer.deviceName, peer.ip, peer.port)
            lifecycleScope.launch {
                if (discoveredPeers.none { it.host == peer.ip }) {
                    discoveredPeers = (discoveredPeers + device)
                        .distinctBy { "${it.name}|${it.host}|${it.port}" }
                        .sortedBy { it.name }
                }
                isDiscovering = false
            }
        }
        SubnetScanner.onProgress = { pct ->
            lifecycleScope.launch { scanningProgress = pct }
        }
        SubnetScanner.onDone = {
            lifecycleScope.launch { scanningProgress = -1 }
        }
        SubnetScanner.start()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        selectedFileUri?.let { outState.putString(STATE_URI, it.toString()) }
        outState.putString(STATE_FILENAME, selectedFileName)
        outState.putLong(STATE_FILESIZE, selectedFileSize ?: 0L)
        outState.putString(STATE_SCREEN, currentScreen.name)
        outState.putInt(STATE_SHARED_INDEX, sharedQueueIndex)
        outState.putStringArrayList(
            STATE_SHARED_URIS,
            ArrayList(sharedFiles.map { it.uri.toString() })
        )
    }

    // ── Android Sharesheet ────────────────────────────────────────────────

    /**
     * Called when a share arrives while the app is already running.
     *
     * `singleTask` in the manifest routes the share to the existing instance
     * rather than creating a second one, which is what stops a share from
     * killing an in-flight transfer. `setIntent` keeps `getIntent()` in sync so
     * a later configuration change does not replay a stale intent.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    /**
     * Stages the files from a share intent and opens the Send screen with them
     * queued.
     *
     * Runs on IO because staging copies the bytes; a large video shared from the
     * Gallery would otherwise block the main thread long enough to trigger an
     * ANR. Failures are reported per file rather than aborting the whole share.
     */
    private fun handleShareIntent(intent: Intent?) {
        if (!ShareIntentParser.isShare(intent)) return

        val uris = ShareIntentParser.extractUris(intent)
        if (uris.isEmpty()) {
            // A plain text share, or a provider that exposed nothing usable.
            notifyUser(getString(R.string.msg_share_no_files))
            return
        }

        val mime = ShareIntentParser.extractMime(intent)
        ShareIntentParser.tryTakePersistable(contentResolver, intent)

        lifecycleScope.launch {
            val staged = withContext(Dispatchers.IO) {
                SharedFileStager.stageAll(applicationContext, uris, mime)
            }

            val usable = staged.filter { it.isUsable }
            val failed = staged.filterNot { it.isUsable }

            if (usable.isEmpty()) {
                notifyUser(getString(R.string.msg_share_all_failed))
                return@launch
            }

            sharedFiles = usable
            sharedQueueIndex = 0

            // Land on the Send screen with the first file already selected, so
            // the existing transfer workflow takes over from here.
            currentScreen = ScreenNav.TRANSFER
            handleSelectedFileFor(usable.first())

            when {
                failed.isEmpty() ->
                    notifyUser(getString(R.string.msg_share_received, usable.size))
                else ->
                    notifyUser(
                        getString(
                            R.string.msg_share_partial,
                            usable.size,
                            failed.size
                        )
                    )
            }

            SharedFileStager.trimToBudget(applicationContext)
        }
    }

    /** Drops a queued shared file and advances to the next one. */
    private fun removeSharedFile(file: SharedFile) {
        val remaining = sharedFiles.filterNot { it.uri == file.uri }
        file.stagedFile?.delete()
        sharedFiles = remaining
        if (remaining.isEmpty()) {
            sharedQueueIndex = 0
            // Also clear the transfer session. The progress card falls back to
            // TransferProgress.filename for display, and the persisted URI still
            // points at the file just deleted, so nulling selectedFileName alone
            // left a phantom entry in PAYLOAD SOURCE that could no longer be sent.
            transferManager.clearSavedSession()
            selectedFileUri = null
            selectedFileName = null
            selectedFileSize = null
        } else {
            sharedQueueIndex = sharedQueueIndex.coerceIn(0, remaining.size - 1)
            handleSelectedFileFor(remaining[sharedQueueIndex])
        }
    }

    /** Clears the whole share queue and its staged copies. */
    private fun clearSharedFiles() {
        sharedFiles.forEach { it.stagedFile?.delete() }
        sharedFiles = emptyList()
        sharedQueueIndex = 0
        SharedFileStager.cleanupStale(applicationContext)
    }

    /**
     * Sends the queued shared files one after another to the selected peer.
     *
     * Deliberately reuses the existing single-file transfer path rather than
     * adding a second mechanism: each file goes through the same chunking,
     * resumability, progress and SHA-256 verification as a manually picked file.
     * The next file is started only once the previous one reaches a terminal
     * state, so the foreground service is never asked to run two transfers at
     * once.
     *
     * Nothing is sent until the user picks a peer, matching the manual flow.
     */
    private fun sendAllSharedFiles() {
        val pending = sharedFiles
        if (pending.isEmpty()) return

        val host = serverIp.trim()
        if (host.isEmpty() || host == "127.0.0.1" && !isConnected) {
            notifyUser(getString(R.string.msg_share_pick_device_first))
            return
        }
        val url = "http://$host:${serverPort.trim()}"
        sharedQueueIndex = 0
        startSharedNext(url, pending)
    }

    /**
     * Starts the queued file at [sharedQueueIndex], or clears the queue when the
     * last one has finished.
     */
    private fun startSharedNext(url: String, queue: List<SharedFile>) {
        val index = sharedQueueIndex
        val file = queue.getOrNull(index)
        if (file == null || file.stagedFile == null || !file.stagedFile!!.exists()) {
            // Nothing usable left; drop the finished entries and stop.
            finishSharedQueue()
            return
        }

        selectedFileUri = Uri.fromFile(file.stagedFile)
        selectedFileName = file.name
        selectedFileSize = file.sizeBytes

        val serviceIntent = Intent(this, TransferForegroundService::class.java).apply {
            action = TransferForegroundService.ACTION_START
        }
        ContextCompat.startForegroundService(this, serviceIntent)

        transferManager.startTransfer(
            serverUrl = url,
            uri = requireNotNull(selectedFileUri),
            filename = file.name,
            fileSize = file.sizeBytes,
            peerDisplayName = selectedPeerName
        )
    }

    /**
     * Removes the file that just finished and starts the next one, if any.
     *
     * The next transfer is kicked off here rather than only re-selecting it, so
     * a shared batch actually sends every file. Falls back to the queue staying
     * staged for manual sending if the peer is somehow no longer set.
     */
    private fun finishSharedQueue() {
        val done = sharedFiles.getOrNull(sharedQueueIndex)
        done?.stagedFile?.delete()
        sharedFiles = sharedFiles.filterIndexed { i, _ -> i != sharedQueueIndex }

        if (sharedFiles.isEmpty()) {
            sharedQueueIndex = 0
            SharedFileStager.cleanupStale(applicationContext)
            notifyUser(getString(R.string.msg_share_queue_done))
            return
        }

        sharedQueueIndex = sharedQueueIndex.coerceAtMost(sharedFiles.size - 1)
        val host = serverIp.trim()
        val canContinue = host.isNotEmpty() && (host != "127.0.0.1" || isConnected)
        if (canContinue) {
            startSharedNext("http://$host:${serverPort.trim()}", sharedFiles)
        } else {
            // No usable target: surface the next file so the user can pick one.
            handleSelectedFileFor(sharedFiles[sharedQueueIndex])
        }
    }

    /**
     * Advances the shared queue when a transfer it started reaches a terminal
     * state. A no-op when the current file did not come from a share, so the
     * manual single-file flow is unaffected.
     */
    private fun maybeAdvanceSharedQueue(status: TransferStatus) {
        if (sharedFiles.isEmpty()) return
        if (status !in setOf(
                TransferStatus.COMPLETED,
                TransferStatus.FAILED,
                TransferStatus.CANCELLED
            )
        ) return
        // Only auto-advance on success: a failure should stop and let the user
        // resume or cancel deliberately rather than silently moving on.
        if (status != TransferStatus.COMPLETED) return
        val current = selectedFileUri ?: return
        val expected = sharedFiles.getOrNull(sharedQueueIndex)?.stagedFile ?: return
        if (Uri.fromFile(expected) != current) return
        finishSharedQueue()
    }

    /** Selects a staged shared file through the normal file-selection path. */
    private fun handleSelectedFileFor(file: SharedFile) {
        val staged = file.stagedFile
        if (staged == null || !staged.exists()) {
            removeSharedFile(file)
            return
        }
        selectedFileUri = Uri.fromFile(staged)
        selectedFileName = file.name
        selectedFileSize = file.sizeBytes
        transferManager.saveFileSelection(Uri.fromFile(staged), file.name, file.sizeBytes)
    }

    override fun onDestroy() {
        super.onDestroy()
        LanDiscovery.stopScanning()
        LanDiscovery.stopAdvertising()
        SubnetScanner.stop()
        embeddedServer?.stop()
        nsdHelper?.destroy()
    }
}
