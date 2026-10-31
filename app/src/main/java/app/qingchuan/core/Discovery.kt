package app.qingchuan.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * 局域网发现（UDP 组播）。
 *
 * 用固定组播地址 + 固定端口，不做网段扫描：同一 Wi-Fi 下组播一发即达，
 * 代价是某些路由器会拦组播，所以还有 probe() 这条手动兜底路径。
 * 所有 socket 都带超时，所有线程都是 daemon，stop() 之后不留活线程。
 */
class Discovery(
    private val device: () -> DeviceInfo,
    private val onPeer: (Peer) -> Unit,
    private val onPeerLost: (did: String) -> Unit,
    private val log: (String) -> Unit = {},
) {

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var socket: MulticastSocket? = null

    @Volatile
    private var groupAddress: InetAddress? = null

    @Volatile
    private var timer: ScheduledExecutorService? = null

    private var recvThread: Thread? = null

    private val peers = ConcurrentHashMap<String, Peer>()

    // 手动探测到的设备不会主动广播，不能被 12s 过期逻辑踢掉。
    private val manual: MutableSet<String> =
        Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private val sendLock = Any()

    @Volatile
    private var lastSendErrorAt: Long = 0

    /** 收到的 UDP 报文计数与最后时间，诊断页用。 */
    @Volatile
    var packetsReceived: Long = 0L
        private set

    @Volatile
    var lastPacketAt: Long = 0L
        private set

    /** multicast / broadcast / send-only，由 start() 实际结果决定。 */
    @Volatile
    var mode: String = "未启动"
        private set

    fun start() {
        if (running) return
        running = true
        val group = try {
            InetAddress.getByName(GROUP_ADDRESS)
        } catch (t: Throwable) {
            log("组播地址解析失败：${t.message}")
            running = false
            return
        }
        groupAddress = group

        val bound = try {
            // MulticastSocket(port) 内部先 setReuseAddress 再 bind，正好是多播所需。
            MulticastSocket(UDP_PORT).apply { timeToLive = 1 }
        } catch (t: Throwable) {
            log("UDP 组播绑定 $UDP_PORT 失败，降级为只发不收：${t.message}")
            mode = "只发不收（端口被占用）"
            try {
                MulticastSocket(0).apply { timeToLive = 1 }
            } catch (t2: Throwable) {
                log("UDP 发送套接字创建失败：${t2.message}")
                running = false
                return
            }
        }
        socket = bound
        runCatching { bound.broadcast = true }
        mode = "组播 + 广播"

        // 加入组播失败不算致命：单播应答（discover / 新设备回执）仍然可用。
        runCatching { joinDefaultGroup(bound, group) }
            .onFailure { log("加入组播组失败，仅能收发单播：${it.message}") }

        announceNow()
        val scheduled = Executors.newScheduledThreadPool(2, NetThreads.factory("qc-disc-timer"))
        timer = scheduled
        runCatching {
            scheduled.scheduleAtFixedRate(
                Runnable { announceNow() },
                ANNOUNCE_INTERVAL_MS,
                ANNOUNCE_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
            scheduled.scheduleAtFixedRate(
                Runnable { expirePeers() },
                EXPIRE_CHECK_MS,
                EXPIRE_CHECK_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        recvThread = NetThreads.start("qc-disc-recv") { receiveLoop(bound) }
        log("UDP 发现已启动（$GROUP_ADDRESS:$UDP_PORT）")
    }

    /** 幂等；重复调用不抛异常。 */
    fun stop() {
        if (!running) return
        running = false
        timer?.shutdownNow()
        timer = null
        val s = socket
        socket = null
        if (s != null) {
            val group = groupAddress
            if (group != null) runCatching { leaveDefaultGroup(s, group) }
            runCatching { s.close() }
        }
        groupAddress = null
        recvThread?.let { runCatching { it.join(1200) } }
        recvThread = null
        peers.clear()
        manual.clear()
        log("UDP 发现已停止")
    }

    fun announceNow() {
        sendAnnounce(null)
    }

    /**
     * 手动添加设备：直接问一次 /api/v1/info。
     * 组播被路由器拦掉时，这是唯一能连上对端的方式，所以必须带超时、失败返回 null。
     */
    fun probe(host: String, port: Int, timeoutMs: Int = 3000, onError: (String) -> Unit = {}): Peer? {
        val cleanHost = host.trim()
        if (cleanHost.isEmpty() || port !in 1..65535) {
            onError("地址不合法")
            return null
        }
        val url = "http://${hostForUrl(cleanHost)}:$port/api/v1/info"
        val started = System.currentTimeMillis()
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs.coerceIn(200, 30_000)
                readTimeout = timeoutMs.coerceIn(200, 30_000)
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                useCaches = false
            }
        } catch (t: Throwable) {
            log("探测 $cleanHost:$port 失败：${t.message}")
            onError(classify(t))
            return null
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                log("探测 $cleanHost:$port 返回 HTTP $code")
                return null
            }
            val text = connection.inputStream.use { readLimited(it, 64 * 1024) }
            val info = DeviceInfo.fromJson(J.parse(text))
            if (info == null) {
                log("探测 $cleanHost:$port 的应答不是合法 DeviceInfo")
                return null
            }
            val latency = System.currentTimeMillis() - started
            val peer = Peer(
                alias = info.alias,
                did = info.did,
                host = cleanHost,
                port = if (info.port in 1..65535) info.port else port,
                os = info.os,
                version = info.version,
                lastSeen = System.currentTimeMillis(),
                latencyMs = latency,
            )
            peers[peer.did] = peer
            manual.add(peer.did)
            firePeer(peer)
            log("手动添加设备 ${peer.alias}（${peer.addr}，${latency}ms）")
            return peer
        } catch (t: Throwable) {
            log("探测 $cleanHost:$port 失败：${t.message ?: t.javaClass.simpleName}")
            onError(classify(t))
            return null
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    // ---------------------------------------------------------------- 收包

    private fun receiveLoop(s: MulticastSocket) {
        runCatching { s.soTimeout = 1000 }
        val buffer = ByteArray(MAX_PACKET)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                s.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running) log("UDP 接收异常：${t.message}")
                break
            }
            packetsReceived++
            lastPacketAt = System.currentTimeMillis()
            try {
                handlePacket(packet)
            } catch (t: Throwable) {
                log("UDP 报文处理失败：${t.message}")
            }
        }
    }

    private fun handlePacket(packet: DatagramPacket) {
        if (packet.length <= 0 || packet.length > MAX_PACKET) return
        val source = packet.address ?: return
        val host = source.hostAddress ?: return
        val json = J.parse(String(packet.data, packet.offset, packet.length, Charsets.UTF_8))
        when (json.str("t")) {
            "announce" -> onAnnounce(json, host, packet.port)
            // 有人在找设备：马上单播回一条，让对方的列表立刻出现我们。
            "discover" -> sendAnnounce(InetSocketAddress(source, packet.port))
            else -> Unit
        }
    }

    private fun onAnnounce(json: J, host: String, sourcePort: Int) {
        val did = json.str("did") ?: return
        if (did == device().did) return
        val declared = json.int("port") ?: 0
        val port = if (declared in 1..65535) declared else sourcePort
        if (port !in 1..65535) return
        val peer = Peer(
            alias = json.str("alias")?.trim().orEmpty().ifBlank { "未知设备" },
            did = did,
            host = host,
            port = port,
            os = json.str("os") ?: "Android",
            version = json.str("version") ?: "1.0",
            lastSeen = System.currentTimeMillis(),
        )
        val previous = peers.put(did, peer)
        manual.remove(did)
        // 每次 announce 都回调：上层（Engine）按 lastSeen 过滤在线列表，
        // 只在「新设备」时回调会让在线设备 20s 后被误判为离线。
        firePeer(peer)
        if (previous == null) {
            // 新设备才回执，且随机错开 200~800ms：否则一堆设备会在同一毫秒互相回包。
            schedule(200L + Random.nextLong(601L)) {
                sendAnnounce(InetSocketAddress(host, sourcePort))
            }
        }
    }

    /** 按本机 /24 推一个子网广播地址；拿不到就返回 null。 */
    private fun subnetBroadcast(): InetAddress? {
        val addr = runCatching {
            java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<java.net.Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress && !it.isLinkLocalAddress }
        }.getOrNull() ?: return null
        val raw = addr.address
        return runCatching {
            InetAddress.getByAddress(byteArrayOf(raw[0], raw[1], raw[2], 0xFF.toByte()))
        }.getOrNull()
    }

    fun stats(): Map<String, Any> = mapOf(
        "mode" to mode,
        "packets" to packetsReceived,
        "lastPacketAt" to lastPacketAt,
        "peers" to peers.size,
    )

    private fun classify(t: Throwable): String = when (t) {
        is SocketTimeoutException -> "连接超时"
        is java.net.ConnectException -> "对方拒绝连接"
        is java.net.NoRouteToHostException -> "网络不可达"
        is java.net.UnknownHostException -> "地址无法解析"
        else -> t.message?.take(40) ?: t.javaClass.simpleName
    }

    private fun expirePeers() {
        if (!running) return
        val now = System.currentTimeMillis()
        for ((did, peer) in peers) {
            if (manual.contains(did)) continue
            if (now - peer.lastSeen <= PEER_TIMEOUT_MS) continue
            if (peers.remove(did) != null) {
                log("设备离线：${peer.alias}（$did）")
                fireLost(did)
            }
        }
    }

    // ---------------------------------------------------------------- 发包

    private fun sendAnnounce(target: InetSocketAddress?) {
        if (!running) return
        val s = socket ?: return
        val group = groupAddress ?: return
        val bytes = try {
            device().toJson().stringify().toByteArray(Charsets.UTF_8)
        } catch (t: Throwable) {
            log("announce 报文生成失败：${t.message}")
            return
        }
        if (bytes.size > MAX_PACKET) {
            log("announce 报文过大（${bytes.size} 字节），已跳过")
            return
        }
        val destination = target ?: InetSocketAddress(group, UDP_PORT)
        try {
            synchronized(sendLock) {
                s.send(DatagramPacket(bytes, bytes.size, destination))
                if (target == null) {
                    // 有些路由器拦组播却放行广播，双通道冗余发一份成本极低。
                    runCatching {
                        s.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(InetAddress.getByName("255.255.255.255"), UDP_PORT)))
                    }
                    subnetBroadcast()?.let { b ->
                        runCatching { s.send(DatagramPacket(bytes, bytes.size, InetSocketAddress(b, UDP_PORT))) }
                    }
                }
            }
        } catch (t: Throwable) {
            // 网络抖动时 3s 一条的失败日志会刷屏，限流到 30s 一次。
            val now = System.currentTimeMillis()
            if (now - lastSendErrorAt > 30_000) {
                lastSendErrorAt = now
                log("UDP 发送 announce 失败：${t.message}")
            }
        }
    }

    private fun schedule(delayMs: Long, block: () -> Unit) {
        val executor = timer ?: return
        runCatching { executor.schedule(Runnable { block() }, delayMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Android 的 MulticastSocket 只有 joinGroup(InetAddress)，没有 JDK 的单参数 SocketAddress 重载；
     * 走默认网卡正好符合「同一 Wi-Fi」的语义，所以单独包一层并压掉 deprecation 警告。
     */
    @Suppress("DEPRECATION")
    private fun joinDefaultGroup(multicast: MulticastSocket, group: InetAddress) {
        multicast.joinGroup(group)
    }

    @Suppress("DEPRECATION")
    private fun leaveDefaultGroup(multicast: MulticastSocket, group: InetAddress) {
        multicast.leaveGroup(group)
    }

    private fun firePeer(peer: Peer) {
        runCatching { onPeer(peer) }
    }

    private fun fireLost(did: String) {
        runCatching { onPeerLost(did) }
    }

    private fun readLimited(input: InputStream, max: Int): String {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        var total = 0
        while (total < max) {
            val n = input.read(buffer)
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }

    /** 形如 fe80::1 的 IPv6 必须加方括号，否则 URL 解析会炸。 */
    private fun hostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private companion object {
        const val GROUP_ADDRESS = "224.0.0.167"
        const val UDP_PORT = 53318
        const val ANNOUNCE_INTERVAL_MS = 3000L
        const val EXPIRE_CHECK_MS = 2000L
        const val PEER_TIMEOUT_MS = 12_000L
        const val MAX_PACKET = 1500
    }
}
