package app.qingchuan.core

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 进程级引擎：把「发现 + 接收服务 + 发送 + 历史」串起来，向 UI 暴露一组 StateFlow。
 * 单例是刻意的——前台服务与 Activity 共享同一个引擎，避免两套服务器抢端口。
 */
object Engine {

    private const val TAG = "QcEngine"
    private const val OFFER_TIMEOUT_MS = 85_000L

    private lateinit var appContext: Context
    private lateinit var prefs: Prefs
    private lateinit var index: Index
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var server: TransferServer? = null
    private var discovery: Discovery? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var pruneJob: Job? = null
    private var contactStore: ContactStore? = null
    private val contactsFallback = MutableStateFlow<List<Contact>>(emptyList())

    private var decision: CompletableDeferred<Boolean>? = null

    @Volatile
    private var sendCancelled = false

    @Volatile
    private var initialized = false

    private val peerMap = ConcurrentHashMap<String, Peer>()
    private val sessionPeer = ConcurrentHashMap<String, Peer>()
    private val sessionFiles = ConcurrentHashMap<String, List<IncomingFile>>()
    private val receiveDone = ConcurrentHashMap<String, Long>()

    private val _peers = MutableStateFlow<List<Peer>>(emptyList())
    val peers: StateFlow<List<Peer>> = _peers.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    private val _localPort = MutableStateFlow(0)
    val localPort: StateFlow<Int> = _localPort.asStateFlow()

    private val _localIp = MutableStateFlow<String?>(null)
    val localIp: StateFlow<String?> = _localIp.asStateFlow()

    private val _pendingOffer = MutableStateFlow<IncomingOffer?>(null)
    val pendingOffer: StateFlow<IncomingOffer?> = _pendingOffer.asStateFlow()

    private val _recvTask = MutableStateFlow<ReceiveTask?>(null)
    val recvTask: StateFlow<ReceiveTask?> = _recvTask.asStateFlow()

    private val _sendTask = MutableStateFlow<SendTask?>(null)
    val sendTask: StateFlow<SendTask?> = _sendTask.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private val _storagePath = MutableStateFlow("")

    /** 接收目录的展示路径，随存储模式变化。 */
    val storagePath: StateFlow<String> = _storagePath.asStateFlow()

    fun init(context: Context, prefs: Prefs) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            appContext = context.applicationContext
            this.prefs = prefs
            index = Index(appContext)
            contactStore = ContactStore(appContext)
            _history.value = index.all()
            refreshStoragePath()
            initialized = true
        }
    }

    val prefsRef: Prefs get() = prefs

    /** 常用对象（手动连过并保存下来的地址）。 */
    val contacts: StateFlow<List<Contact>> get() = contactStore?.contacts ?: contactsFallback

    fun saveContact(name: String, host: String, port: Int): Contact? =
        contactStore?.let { runCatching { it.add(name, host, port) }.getOrNull() }

    fun removeContact(id: String) {
        contactStore?.remove(id)
    }

    fun renameContact(id: String, name: String) {
        contactStore?.rename(id, name)
    }

    /** 点「常用对象」：用存下来的 host:port 直接连，成功后交给上层选文件。 */
    fun connectContact(contact: Contact, onResult: (ManualResult) -> Unit) {
        connectManual("${contact.host}:${contact.port}") { result ->
            if (result.peer != null) contactStore?.touch(contact.id)
            onResult(result)
        }
    }

    fun historyIndex(): Index = index

    @Synchronized
    fun start(): Boolean {
        if (!initialized) return false
        if (server != null) return true
        val requested = prefs.port.value
        var bound: Int? = null
        var instance: TransferServer? = null
        for (p in requested..(requested + 12)) {
            val s = buildServer()
            val actual = s.start(p)
            if (actual != null) {
                instance = s
                bound = actual
                break
            }
        }
        if (instance == null || bound == null) {
            log("服务启动失败：端口 $requested 及后续 12 个均被占用")
            _notice.value = "无法启动接收服务，请换一个端口"
            return false
        }
        server = instance
        _localPort.value = bound
        acquireMulticastLock()
        val d = Discovery(
            device = { prefs.info(_localPort.value) },
            onPeer = { peer ->
                peerMap[peer.did] = peer
                publishPeers()
            },
            onPeerLost = { did ->
                peerMap.remove(did)
                publishPeers()
            },
            log = ::log,
        )
        discovery = d
        d.start()
        _localIp.value = localIp()
        _running.value = true
        startPruneLoop()
        log("引擎已启动，端口 $bound")
        return true
    }

    @Synchronized
    fun stop() {
        runCatching { discovery?.stop() }
        runCatching { server?.stop() }
        discovery = null
        server = null
        pruneJob?.cancel()
        pruneJob = null
        runCatching {
            multicastLock?.let { if (it.isHeld) it.release() }
        }
        multicastLock = null
        peerMap.clear()
        _peers.value = emptyList()
        _running.value = false
        _pendingOffer.value = null
        decision?.complete(false)
        decision = null
    }

    fun restart() {
        if (!_running.value) return
        stop()
        start()
    }

    fun refreshStoragePath() {
        _storagePath.value = Storage.displayPath(appContext, prefs.storageMode.value)
    }

    fun localAddress(): String {
        val ip = _localIp.value ?: "-"
        val port = _localPort.value
        return if (port > 0) "$ip:$port" else ip
    }

    fun consumeNotice(): String? {
        val n = _notice.value
        _notice.value = null
        return n
    }

    fun showNotice(text: String) {
        _notice.value = text
    }

    // ---------------------------------------------------------------- 接收侧

    private fun buildServer(): TransferServer = TransferServer(
        device = { prefs.info(_localPort.value) },
        sink = { Storage.sink(appContext, prefs.storageMode.value) },
        onOffer = { offer -> handleOffer(offer) },
        onProgress = { sid, file, received -> handleProgress(sid, file, received) },
        onFileDone = { sid, stored -> handleFileDone(sid, stored) },
        onSessionEnd = { sid, ok, message -> handleSessionEnd(sid, ok, message) },
        log = ::log,
    )

    private fun handleOffer(offer: IncomingOffer): Boolean {
        if (offer.files.isEmpty()) return false
        sessionPeer[offer.sessionId] = offer.peer
        sessionFiles[offer.sessionId] = offer.files
        receiveDone[offer.sessionId] = 0L
        if (!prefs.askBeforeReceive.value) {
            _recvTask.value = ReceiveTask(
                sessionId = offer.sessionId,
                peer = offer.peer,
                fileNames = offer.files.map { it.name },
                totalBytes = offer.totalSize,
                currentName = offer.files.first().name,
            )
            return true
        }
        val gate = CompletableDeferred<Boolean>()
        decision = gate
        _pendingOffer.value = offer
        val accepted = runBlocking { withTimeoutOrNull(OFFER_TIMEOUT_MS) { gate.await() } } ?: false
        _pendingOffer.value = null
        synchronized(this) { decision = null }
        if (!accepted) {
            sessionPeer.remove(offer.sessionId)
            sessionFiles.remove(offer.sessionId)
            receiveDone.remove(offer.sessionId)
            return false
        }
        _recvTask.value = ReceiveTask(
            sessionId = offer.sessionId,
            peer = offer.peer,
            fileNames = offer.files.map { it.name },
            totalBytes = offer.totalSize,
            currentName = offer.files.first().name,
        )
        return true
    }

    fun decideOffer(accept: Boolean) {
        decision?.complete(accept)
    }

    private fun handleProgress(sid: String, file: IncomingFile, received: Long) {
        val task = _recvTask.value ?: return
        if (task.sessionId != sid) return
        val done = receiveDone[sid] ?: 0L
        val files = sessionFiles[sid] ?: task.fileNames.map { IncomingFile(it, it, 0, "") }
        val idx = files.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
        _recvTask.value = task.copy(
            receivedBytes = (done + received).coerceAtMost(task.totalBytes),
            currentName = file.name,
            currentIndex = idx,
        )
    }

    private fun handleFileDone(sid: String, stored: StoredFile) {
        receiveDone[sid] = (receiveDone[sid] ?: 0L) + stored.size
        val peer = sessionPeer[sid]
        val entry = HistoryEntry(
            id = stored.id.ifBlank { UUID.randomUUID().toString() },
            name = stored.name,
            mime = stored.mime,
            size = stored.size,
            uri = stored.uri,
            time = stored.time,
            peer = peer?.alias.orEmpty(),
            direction = Direction.IN,
        )
        index.add(entry)
        _history.value = index.all()
    }

    private fun handleSessionEnd(sid: String, ok: Boolean, message: String) {
        val task = _recvTask.value
        if (task != null && task.sessionId == sid) {
            _recvTask.value = task.copy(done = true, failed = !ok, message = message)
            scope.launch {
                delay(2600)
                if (_recvTask.value?.sessionId == sid && _recvTask.value?.done == true) {
                    _recvTask.value = null
                }
            }
            if (ok) {
                _notice.value = "已收到 ${task.fileNames.size} 个文件"
            } else {
                _notice.value = "接收中断：$message"
            }
        }
        sessionPeer.remove(sid)
        sessionFiles.remove(sid)
        receiveDone.remove(sid)
    }

    // ---------------------------------------------------------------- 发送侧

    fun send(peer: Peer, files: List<OutgoingFile>) {
        if (files.isEmpty()) return
        val phase = _sendTask.value?.phase
        if (phase == SendPhase.OFFERING || phase == SendPhase.SENDING) {
            _notice.value = "已有传输进行中"
            return
        }
        sendCancelled = false
        val total = files.sumOf { it.size }
        _sendTask.value = SendTask(
            peer = peer,
            fileNames = files.map { it.name },
            totalBytes = total,
            currentName = files.first().name,
            phase = SendPhase.OFFERING,
        )
        scope.launch {
            var lastTime = System.currentTimeMillis()
            var lastBytes = 0L
            var speed = 0L
            val sender = Sender(log = ::log, self = { runCatching { prefs.info(_localPort.value) }.getOrNull() })
            val outcome = sender.send(
                peer = peer,
                files = files,
                onProgress = { idx, _, sentTotal ->
                    val now = System.currentTimeMillis()
                    if (now - lastTime >= 400) {
                        val dt = now - lastTime
                        if (dt > 0) speed = ((sentTotal - lastBytes) * 1000 / dt).coerceAtLeast(0)
                        lastTime = now
                        lastBytes = sentTotal
                    }
                    _sendTask.value = _sendTask.value?.copy(
                        sentBytes = sentTotal,
                        currentIndex = idx,
                        currentName = files.getOrNull(idx)?.name.orEmpty(),
                        bytesPerSecond = speed,
                        phase = SendPhase.SENDING,
                    )
                },
                onPhase = { p, msg -> _sendTask.value = _sendTask.value?.copy(phase = p, message = msg) },
                isCancelled = { sendCancelled },
            )
            if (outcome.phase == SendPhase.DONE) {
                val entries = files.map { f ->
                    HistoryEntry(
                        id = UUID.randomUUID().toString(),
                        name = f.name,
                        mime = f.mime,
                        size = f.size,
                        uri = f.uri.orEmpty(),
                        time = System.currentTimeMillis(),
                        peer = peer.alias,
                        direction = Direction.OUT,
                    )
                }
                index.addAll(entries)
                _history.value = index.all()
                _notice.value = "已发送 ${files.size} 个文件给 ${peer.alias}"
            } else if (outcome.phase != SendPhase.CANCELLED) {
                _notice.value = outcome.message.ifBlank { "发送失败" }
            }
            _sendTask.value = _sendTask.value?.copy(
                phase = outcome.phase,
                sentBytes = outcome.sentBytes,
                message = outcome.message,
            )
            delay(2600)
            if (_sendTask.value?.phase !in listOf(SendPhase.OFFERING, SendPhase.SENDING)) {
                _sendTask.value = null
            }
        }
    }

    fun cancelSend() {
        sendCancelled = true
    }

    fun dismissSend() {
        if (_sendTask.value?.phase != SendPhase.SENDING && _sendTask.value?.phase != SendPhase.OFFERING) {
            _sendTask.value = null
        }
    }

    // ---------------------------------------------------------------- 对端

    /** 手动连接的解析结果：host + 可选端口。 */
    data class ManualAddress(val host: String, val port: Int?)

    data class ManualResult(val peer: Peer?, val message: String)

    /** 把用户随手输入的地址洗干净：支持 http://、末尾斜杠、IP:端口、[IPv6]:端口。 */
    fun parseAddress(raw: String): ManualAddress? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        s = s.removePrefix("http://").removePrefix("https://").removePrefix("HTTP://")
        s = s.trimEnd('/').trim()
        if (s.isEmpty()) return null
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end <= 0) return null
            val host = s.substring(1, end)
            val port = s.substring(end + 1).removePrefix(":").trim().toIntOrNull()
            return ManualAddress(host, port)
        }
        val idx = s.lastIndexOf(':')
        if (idx > 0) {
            val port = s.substring(idx + 1).trim().toIntOrNull()
            if (port != null && port in 1..65535) return ManualAddress(s.substring(0, idx).trim(), port)
        }
        return ManualAddress(s, null)
    }

    /**
     * 手动连接：只填 IP 时自动在 53318..53322 上试一遍 —— 端口被占或对方改过端口都能连上。
     * 失败会带上具体原因（超时/拒绝/不可达），比一句「连接失败」有用得多。
     */
    fun connectManual(raw: String, onResult: (ManualResult) -> Unit) {
        val parsed = parseAddress(raw)
        if (parsed == null || parsed.host.isBlank()) {
            onResult(ManualResult(null, "地址格式不对，只填 IP 就行，例如 192.168.1.8"))
            return
        }
        scope.launch {
            val d = discovery ?: Discovery(
                device = { prefs.info(_localPort.value) },
                onPeer = { },
                onPeerLost = { },
                log = ::log,
            )
            val ports = parsed.port?.let { listOf(it) } ?: listOf(53318, 53319, 53320, 53321, 53322)
            val timeout = if (parsed.port != null) 3000 else 1200
            var lastError = "连接超时"
            for (p in ports) {
                var err = ""
                val peer = d.probe(parsed.host, p, timeout) { msg -> err = msg }
                if (peer != null) {
                    peerMap[peer.did] = peer
                    publishPeers()
                    withContextMain { onResult(ManualResult(peer, "已连上 ${peer.alias}")) }
                    return@launch
                }
                if (err.isNotBlank()) lastError = err
                if (parsed.port != null) break
            }
            val hint = when {
                lastError.contains("拒绝") -> "对方端口没人监听，可能没开轻传，或地址填错了"
                lastError.contains("超时") || lastError.contains("不可达") ->
                    "连不上 $lastError：确认两台设备在同一个 Wi-Fi、不是访客网络，且路由器没开 AP 隔离"
                else -> "连不上（$lastError）"
            }
            withContextMain { onResult(ManualResult(null, hint)) }
        }
    }

    /** 诊断页数据：发现通道状态 + 本机网络信息。 */
    fun discoveryStats(): Map<String, Any> = discovery?.stats() ?: emptyMap()

    fun gatewayAddress(): String? = runCatching {
        val wm = appContext.getSystemService(android.net.wifi.WifiManager::class.java) ?: return null
        val ip = wm.dhcpInfo?.gateway ?: 0
        if (ip == 0) null else String.format(
            java.util.Locale.US, "%d.%d.%d.%d",
            ip and 0xff, (ip shr 8) and 0xff, (ip shr 16) and 0xff, (ip shr 24) and 0xff,
        )
    }.getOrNull()

    fun probe(host: String, port: Int, onResult: (Peer?) -> Unit) {
        scope.launch {
            val d = discovery ?: Discovery(
                device = { prefs.info(_localPort.value) },
                onPeer = { },
                onPeerLost = { },
                log = ::log,
            )
            val peer = d.probe(host, port)
            if (peer != null) {
                peerMap[peer.did] = peer
                publishPeers()
            }
            withContextMain { onResult(peer) }
        }
    }

    fun forgetPeer(did: String) {
        peerMap.remove(did)
        publishPeers()
    }

    private fun publishPeers() {
        val now = System.currentTimeMillis()
        _peers.value = peerMap.values
            .filter { now - it.lastSeen < 20_000 }
            .sortedBy { it.alias.lowercase() }
    }

    // ---------------------------------------------------------------- 历史

    fun deleteEntry(entry: HistoryEntry) {
        scope.launch {
            if (entry.direction == Direction.IN) Storage.delete(appContext, entry.uri)
            index.remove(entry.id)
            _history.value = index.all()
            _notice.value = "已删除 ${entry.name}"
        }
    }

    fun clearHistory(deleteFiles: Boolean) {
        scope.launch {
            if (deleteFiles) {
                index.all().filter { it.direction == Direction.IN }.forEach { Storage.delete(appContext, it.uri) }
            }
            index.clear()
            _history.value = index.all()
        }
    }

    // ---------------------------------------------------------------- 杂项

    private fun acquireMulticastLock() {
        runCatching {
            val wm = appContext.getSystemService(WifiManager::class.java) ?: return
            val lock = wm.createMulticastLock("qingchuan-multicast")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
        }.onFailure { log("MulticastLock 获取失败：${it.message}") }
    }

    private fun startPruneLoop() {
        pruneJob?.cancel()
        pruneJob = scope.launch {
            while (_running.value) {
                delay(5000)
                publishPeers()
                _localIp.value = localIp()
            }
        }
    }

    /** 取一个站点本地（局域网）IPv4，用于界面展示；拿不到返回 null。 */
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    private fun withContextMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main.immediate) { block() }
    }

    private fun log(message: String) {
        Log.d(TAG, message)
    }
}
