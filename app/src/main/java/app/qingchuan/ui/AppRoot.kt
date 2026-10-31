package app.qingchuan.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.qingchuan.R
import app.qingchuan.core.Direction
import app.qingchuan.core.AppPackages
import app.qingchuan.core.Contact
import app.qingchuan.core.Engine
import app.qingchuan.core.InstalledApp
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.IncomingOffer
import app.qingchuan.core.OutgoingFile
import app.qingchuan.core.Peer
import app.qingchuan.core.Prefs
import app.qingchuan.core.Storage
import app.qingchuan.core.Uris
import app.qingchuan.core.formatSize
import app.qingchuan.service.TransferService
import app.qingchuan.ui.components.Avatar
import app.qingchuan.ui.components.PillButton
import app.qingchuan.ui.components.PillStyle
import app.qingchuan.ui.components.QcCard
import app.qingchuan.ui.theme.QcTheme
import app.qingchuan.ui.theme.QingchuanTheme
import app.qingchuan.ui.viewer.ImageViewerScreen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(activity: ComponentActivity, prefs: Prefs) {
    val theme by prefs.theme.collectAsStateWithLifecycle()
    val dark = when (theme) {
        app.qingchuan.core.ThemeMode.DARK -> true
        app.qingchuan.core.ThemeMode.LIGHT -> false
        app.qingchuan.core.ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    QingchuanTheme(mode = theme) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
        AppScaffold(activity, prefs)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppScaffold(activity: ComponentActivity, prefs: Prefs) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val alias by prefs.alias.collectAsStateWithLifecycle()
    val port by prefs.port.collectAsStateWithLifecycle()
    val backgroundPref by prefs.background.collectAsStateWithLifecycle()
    val storageMode by prefs.storageMode.collectAsStateWithLifecycle()
    val askBefore by prefs.askBeforeReceive.collectAsStateWithLifecycle()
    val theme by prefs.theme.collectAsStateWithLifecycle()

    val running by Engine.running.collectAsStateWithLifecycle()
    val localPort by Engine.localPort.collectAsStateWithLifecycle()
    val localIp by Engine.localIp.collectAsStateWithLifecycle()
    val peers by Engine.peers.collectAsStateWithLifecycle()
    val pendingOffer by Engine.pendingOffer.collectAsStateWithLifecycle()
    val sendTask by Engine.sendTask.collectAsStateWithLifecycle()
    val recvTask by Engine.recvTask.collectAsStateWithLifecycle()
    val history by Engine.history.collectAsStateWithLifecycle()
    val contacts by Engine.contacts.collectAsStateWithLifecycle()
    val storagePath by Engine.storagePath.collectAsStateWithLifecycle()

    var tab by remember { mutableStateOf(0) }
    var viewer by remember { mutableStateOf<ViewerRequest?>(null) }
    var manualDialog by remember { mutableStateOf(false) }
    var targetDialog by remember { mutableStateOf(false) }
    var allFilesGranted by remember { mutableStateOf(Storage.hasAllFilesAccess()) }
    var showDiag by remember { mutableStateOf(false) }
    var showContacts by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var appList by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var appLoading by remember { mutableStateOf(false) }
    var appSelected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var diagTick by remember { mutableStateOf(0) }

    // 从系统设置返回后刷新「所有文件访问权限」状态
    val lifecycleOwner = LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesGranted = Storage.hasAllFilesAccess()
                if (storageMode == app.qingchuan.core.StorageMode.ROOT) Engine.refreshStoragePath()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 待发送
    var pendingFiles by remember { mutableStateOf<List<OutgoingFile>>(emptyList()) }
    var pendingPeer by remember { mutableStateOf<Peer?>(null) }
    var sendSheet by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(OpenMultipleDocs()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val files = withContext(Dispatchers.IO) {
                uris.mapNotNull { uri ->
                    Uris.persist(context, uri)
                    Uris.describe(context, uri)
                }
            }
            if (files.isEmpty()) {
                snackbar.showSnackbar("选择失败，无法读取这些文件")
                return@launch
            }
            pendingFiles = files
            // 选完文件一定进发送面板：即使附近还没有设备，面板里也能现场手动加 IP，
            // 不能把用户刚选的文件白白丢掉。
            sendSheet = true
        }
    }

    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && backgroundPref) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= 28) {
            val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
            if (!granted) storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    LaunchedEffect(showApps) {
        if (showApps) {
            appLoading = true
            appList = withContext(Dispatchers.IO) { AppPackages.load(context) }
            appLoading = false
        }
    }

    LaunchedEffect(showDiag) {
        while (showDiag) {
            diagTick++
            kotlinx.coroutines.delay(1000)
        }
    }

    LaunchedEffect(Unit) {
        Engine.notice.collect { msg ->
            val text = msg ?: return@collect
            Engine.consumeNotice()
            // 用独立协程弹提示：showSnackbar 会挂起到消失为止，
            // 直接在 collector 里调用会把后续提示全部丢掉。
            scope.launch { snackbar.showSnackbar(text) }
        }
    }

    val address = if (localPort > 0) "${localIp ?: "-"}:$localPort" else "服务未启动"

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavItem("传输", R.drawable.ic_plane, tab == 0) { tab = 0 }
                NavItem("文件", R.drawable.ic_folder, tab == 1) { tab = 1 }
                NavItem("设置", R.drawable.ic_tab_gear, tab == 2) { tab = 2 }
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> HomeScreen(
                    alias = alias,
                    address = address,
                    running = running,
                    peers = peers,
                    sendTask = sendTask,
                    recvTask = recvTask,
                    recent = history.take(4),
                    contacts = contacts,
                    onCopyAddress = { Intents.copy(context, address, "地址已复制") },
                    onManualAdd = { manualDialog = true },
                    onSendTo = { peer -> pendingPeer = peer; picker.launch(Unit) },
                    onContact = { contact ->
                        Engine.connectContact(contact) { result ->
                            if (result.peer != null) {
                                pendingPeer = result.peer
                                picker.launch(Unit)
                            } else {
                                scope.launch { snackbar.showSnackbar(result.message) }
                            }
                        }
                    },
                    onOpenContacts = { showContacts = true },
                    onCancelSend = { Engine.cancelSend() },
                    onCancelReceive = { scope.launch { snackbar.showSnackbar("请等待当前文件接收完成") } },
                    onOpenEntry = { entry ->
                        if (entry.isImage) openViewer(history, entry) { viewer = it } else Intents.open(context, entry)
                    },
                    onSeeAll = { tab = 1 },
                )

                1 -> FilesScreen(
                    entries = history,
                    storagePath = storagePath,
                    running = running,
                    onOpenImage = { entry -> openViewer(history, entry) { viewer = it } },
                    onOpenFile = { entry -> Intents.open(context, entry) },
                    onShare = { entry -> Intents.share(context, entry) },
                    onDelete = { entry -> Engine.deleteEntry(entry) },
                    onCopyPath = { Intents.copy(context, storagePath, "路径已复制") },
                    onPickFiles = { pendingPeer = null; picker.launch(Unit) },
                    onSendApp = { appSelected = emptySet(); showApps = true },
                )

                else -> SettingsScreen(
                    alias = alias,
                    port = port,
                    running = running,
                    address = address,
                    storagePath = storagePath,
                    storageMode = storageMode,
                    allFilesGranted = allFilesGranted,
                    background = backgroundPref,
                    askBeforeReceive = askBefore,
                    theme = theme,
                    version = app.qingchuan.core.BuildInfo.VERSION_NAME,
                    onAlias = { prefs.setAlias(it) },
                    onPort = { prefs.setPort(it); Engine.restart() },
                    onStorageMode = { mode -> prefs.setStorageMode(mode); Engine.refreshStoragePath() },
                    onRequestAllFiles = {
                        allFilesGranted = requestAllFiles(context)
                    },
                    onBackground = { enabled ->
                        prefs.setBackground(enabled)
                        if (enabled) TransferService.start(context) else TransferService.stop(context)
                    },
                    onAsk = { prefs.setAskBeforeReceive(it) },
                    onTheme = { prefs.setTheme(it) },
                    onCopyAddress = { Intents.copy(context, address, "地址已复制") },
                    onClearHistory = { deleteFiles -> Engine.clearHistory(deleteFiles) },
                    onDiagnose = { showDiag = true },
                )
            }
        }
    }

    // ------------------------------------------------------------------ 浮层

    pendingOffer?.let { offer ->
        ReceiveDialog(
            offer = offer,
            storagePath = storagePath,
            onAccept = { Engine.decideOffer(true) },
            onDecline = { Engine.decideOffer(false) },
        )
    }

    if (sendSheet) {
        SendSheet(
            files = pendingFiles,
            peers = peers,
            target = pendingPeer,
            onPickTarget = { targetDialog = true },
            onDismiss = { sendSheet = false; pendingFiles = emptyList(); pendingPeer = null },
            onSend = { peer ->
                sendSheet = false
                Engine.send(peer, pendingFiles)
                pendingFiles = emptyList()
                pendingPeer = null
                tab = 0
            },
        )
    }

    if (targetDialog) {
        TargetDialog(
            peers = peers,
            onDismiss = { targetDialog = false },
            onPick = { peer -> pendingPeer = peer; targetDialog = false },
            onManual = { targetDialog = false; manualDialog = true },
        )
    }

    if (manualDialog) {
        ManualIpDialog(
            onDismiss = { manualDialog = false },
            onConnected = { peer ->
                manualDialog = false
                // 存不存由用户决定：点提示条上的「存为常用对象」才保存
                scope.launch {
                    val result = snackbar.showSnackbar(
                        message = "已连上 ${peer.alias}",
                        actionLabel = "存为常用对象",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) {
                        Engine.saveContact(peer.alias, peer.host, peer.port)
                        snackbar.showSnackbar("已保存，下次点一下就能连")
                    }
                }
            },
        )
    }

    if (showContacts) {
        ContactsScreen(
            contacts = contacts,
            onBack = { showContacts = false },
            onConnect = { contact ->
                showContacts = false
                Engine.connectContact(contact) { result ->
                    if (result.peer != null) {
                        pendingPeer = result.peer
                        picker.launch(Unit)
                    } else {
                        scope.launch { snackbar.showSnackbar(result.message) }
                    }
                }
            },
            onAdd = { name, host, portInput ->
                val host2 = host.trim()
                if (host2.isNotBlank()) {
                    val p = if (portInput > 0) portInput else Prefs.DEFAULT_PORT
                    Engine.saveContact(name.ifBlank { host2 }, host2, p)
                    scope.launch { snackbar.showSnackbar("已保存到常用对象") }
                }
            },
            onRename = { contact, name -> Engine.renameContact(contact.id, name) },
            onRemove = { contact -> Engine.removeContact(contact.id) },
        )
    }

    if (showApps) {
        AppPickerScreen(
            apps = appList,
            loading = appLoading,
            selected = appSelected,
            onToggle = { pkg ->
                appSelected = if (pkg in appSelected) appSelected - pkg else appSelected + pkg
            },
            onClose = { showApps = false },
            onConfirm = {
                val picked = appList.filter { it.packageName in appSelected }
                showApps = false
                scope.launch {
                    val files = withContext(Dispatchers.IO) {
                        picked.mapNotNull { runCatching { AppPackages.prepareForSend(context, it) }.getOrNull() }
                    }
                    if (files.isEmpty()) {
                        snackbar.showSnackbar("提取安装包失败")
                    } else {
                        if (files.size != picked.size) {
                            scope.launch { snackbar.showSnackbar("${picked.size - files.size} 个应用提取失败，已跳过") }
                        }
                        pendingFiles = files
                        pendingPeer = null
                        sendSheet = true
                    }
                }
            },
        )
    }

    if (showDiag) {
        // diagTick 每秒变化一次，remember 的 key 逼着这里重新取一次实时数据
        val stats = remember(diagTick) { Engine.discoveryStats() }
        DiagnosticsScreen(
            address = address,
            port = localPort,
            running = running,
            gateway = Engine.gatewayAddress(),
            packets = (stats["packets"] as? Long) ?: 0L,
            lastPacketAt = (stats["lastPacketAt"] as? Long) ?: 0L,
            peersCount = peers.size,
            discoveryMode = (stats["mode"] as? String) ?: "未启动",
            onBack = { showDiag = false },
            onTest = { showDiag = false; manualDialog = true },
        )
    }

    viewer?.let { request ->
        ImageViewerScreen(
            entries = request.entries,
            startIndex = request.index,
            onClose = { viewer = null },
            onShare = { entry -> Intents.share(context, entry) },
            onDelete = { entry -> Engine.deleteEntry(entry); viewer = null },
            onOpenExternal = { entry -> Intents.open(context, entry) },
        )
    }

    BackHandler(enabled = viewer != null || manualDialog || sendSheet || targetDialog || showDiag || showContacts || showApps) {
        when {
            viewer != null -> viewer = null
            showApps -> showApps = false
            showContacts -> showContacts = false
            showDiag -> showDiag = false
            targetDialog -> targetDialog = false
            sendSheet -> { sendSheet = false; pendingFiles = emptyList(); pendingPeer = null }
            manualDialog -> manualDialog = false
        }
    }
}

private data class ViewerRequest(val entries: List<HistoryEntry>, val index: Int)

private fun openViewer(history: List<HistoryEntry>, entry: HistoryEntry, onOpen: (ViewerRequest) -> Unit) {
    val images = history.filter { it.isImage }
    val idx = images.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
    if (images.isEmpty()) return
    onOpen(ViewerRequest(images, idx))
}

@Composable
private fun RowScope.NavItem(label: String, iconRes: Int, selected: Boolean, onClick: () -> Unit) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = {
            if (iconRes != 0) {
                Icon(
                    painter = painterResource(iconRes),
                    contentDescription = label,
                    modifier = Modifier.size(22.dp),
                )
            } else {
                Icon(Icons.Filled.Settings, contentDescription = label, modifier = Modifier.size(22.dp))
            }
        },
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.primary,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            indicatorColor = Color.Transparent,
            unselectedIconColor = QcTheme.extra.textTertiary,
            unselectedTextColor = QcTheme.extra.textTertiary,
        ),
    )
}

@Composable
private fun ReceiveDialog(offer: IncomingOffer, storagePath: String, onAccept: () -> Unit, onDecline: () -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(offer.peer.alias, size = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("收到传输请求", style = MaterialTheme.typography.titleMedium)
                    Text(
                        offer.peer.alias + " · " + offer.peer.host,
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textTertiary,
                    )
                }
            }
        },
        text = {
            Column {
                Text(
                    "${offer.files.size} 个文件 · ${formatSize(offer.totalSize)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
                offer.files.take(6).forEach { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = QcTheme.extra.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            formatSize(f.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = QcTheme.extra.textTertiary,
                        )
                    }
                }
                if (offer.files.size > 6) {
                    Text("…等 ${offer.files.size} 个文件", style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "将保存到 $storagePath",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAccept) { Text("接收") } },
        dismissButton = { TextButton(onClick = onDecline) { Text("拒绝") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SendSheet(
    files: List<OutgoingFile>,
    peers: List<Peer>,
    target: Peer?,
    onPickTarget: () -> Unit,
    onDismiss: () -> Unit,
    onSend: (Peer) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val chosen = target ?: peers.singleOrNull()
    val total = files.sumOf { it.size }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text(
                "发送 ${files.size} 个文件",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "共 ${formatSize(total)}",
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textTertiary,
            )
            Spacer(Modifier.height(14.dp))
            QcCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                padding = PaddingValues(12.dp),
                onClick = onPickTarget,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (chosen != null) Avatar(chosen.alias, size = 34.dp) else Box(
                        Modifier.size(34.dp).clip(CircleShape).background(QcTheme.extra.cardFillStrong)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (chosen != null) "发送给 ${chosen.alias}" else "选择接收设备",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            if (chosen != null) "${chosen.host}:${chosen.port}" else "点按选择附近设备",
                            style = MaterialTheme.typography.bodySmall,
                            color = QcTheme.extra.textTertiary,
                        )
                    }
                    if (chosen != null) Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            LazyColumn(Modifier.heightIn(max = 220.dp)) {
                items(files) { f ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            painter = painterResource(R.drawable.ic_file),
                            contentDescription = null,
                            tint = QcTheme.extra.textSecondary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            f.name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Text(formatSize(f.size), style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PillButton("取消", onDismiss, style = PillStyle.Outline, modifier = Modifier.weight(1f))
                PillButton(
                    if (chosen == null) "先选设备" else "立即发送",
                    onClick = { chosen?.let(onSend) },
                    enabled = chosen != null,
                    modifier = Modifier.weight(1.4f),
                )
            }
        }
    }
}

@Composable
private fun TargetDialog(peers: List<Peer>, onDismiss: () -> Unit, onPick: (Peer) -> Unit, onManual: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择接收设备") },
        text = {
            if (peers.isEmpty()) {
                Text("附近还没有设备，先手动输入对方 IP。", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(peers, key = { it.did }) { peer ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(1.dp, QcTheme.extra.cardStroke, RoundedCornerShape(14.dp))
                                .padding(10.dp)
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(peer.alias, size = 32.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(peer.alias, style = MaterialTheme.typography.titleSmall)
                                Text("${peer.host}:${peer.port}", style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
                            }
                            TextButton(onClick = { onPick(peer) }) { Text("选择") }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onManual) { Text("手动输入 IP") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun ManualIpDialog(onDismiss: () -> Unit, onConnected: (Peer) -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("手动连接") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(64) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    label = { Text("对方 IP") },
                    placeholder = { Text("192.168.1.8") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "只填 IP 就行，端口会自动找。地址在对方首页上。",
                    style = MaterialTheme.typography.bodySmall,
                    color = QcTheme.extra.textTertiary,
                )
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && text.isNotBlank(),
                onClick = {
                    busy = true
                    error = null
                    Engine.connectManual(text) { result ->
                        busy = false
                        val peer = result.peer
                        if (peer != null) onConnected(peer) else error = result.message
                    }
                },
            ) { Text(if (busy) "连接中…" else "连接") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

/** 打开系统 SAF，并申请持久化读取权限（历史记录里的「发出」条目重启后仍可预览）。 */
private class OpenMultipleDocs : ActivityResultContract<Unit, List<Uri>>() {
    override fun createIntent(context: Context, input: Unit): Intent =
        Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }

    override fun parseResult(resultCode: Int, intent: Intent?): List<Uri> {
        if (resultCode != Activity.RESULT_OK || intent == null) return emptyList()
        val clip = intent.clipData
        if (clip != null) return (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        return intent.data?.let { listOf(it) } ?: emptyList()
    }
}

private fun requestAllFiles(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
        // Android 10 及以下没有「所有文件访问权限」这个概念，根目录模式不可用。
        android.widget.Toast.makeText(
            context,
            "Android 10 及以下会把文件存到 Download/轻传，无需额外授权",
            android.widget.Toast.LENGTH_LONG,
        ).show()
        return false
    }
    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
        data = Uri.parse("package:${context.packageName}")
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        false
    } catch (_: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {
            android.widget.Toast.makeText(context, "无法打开权限设置页", android.widget.Toast.LENGTH_SHORT).show()
        }
        false
    }
}
