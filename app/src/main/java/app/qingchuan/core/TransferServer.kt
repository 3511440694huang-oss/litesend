package app.qingchuan.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 接收端：把局域网里的 HTTP 请求翻译成「会话 + 落盘」。
 *
 * 一次 offer 就是一个会话，sid 贯穿 upload/complete/cancel。
 * 所有等待都带超时——设备可能在传输中途锁屏、切 Wi-Fi、直接断电。
 */
class TransferServer(
    private val device: () -> DeviceInfo,
    private val sink: () -> StorageSink,
    private val onOffer: (IncomingOffer) -> Boolean,
    private val onProgress: (sessionId: String, file: IncomingFile, received: Long) -> Unit,
    private val onFileDone: (sessionId: String, file: StoredFile) -> Unit,
    private val onSessionEnd: (sessionId: String, ok: Boolean, message: String) -> Unit,
    private val log: (String) -> Unit = {},
) {

    private class Session(
        val id: String,
        val peer: Peer,
        val files: List<IncomingFile>,
        val tokens: Map<String, String>,
    ) {
        val lock = Any()
        val done: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val busy: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        val handles = ConcurrentHashMap<String, WriteHandle>()

        @Volatile
        var sink: StorageSink? = null

        @Volatile
        var lastActive: Long = System.currentTimeMillis()

        @Volatile
        var closed: Boolean = false

        fun file(fid: String): IncomingFile? = files.firstOrNull { it.id == fid }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val random = SecureRandom()

    @Volatile
    private var server: HttpServer? = null

    @Volatile
    private var offerPool: ThreadPoolExecutor? = null

    @Volatile
    private var actualPort: Int = 0

    @Volatile
    private var started: Boolean = false

    /** 成功返回实际端口；端口被占等任何失败都返回 null，交给上层换端口重试。 */
    fun start(requestedPort: Int): Int? {
        if (started) return actualPort
        val http = HttpServer(requestedPort, log)
        http.setHandler { request -> route(request) }
        if (!http.start()) {
            log("TransferServer 启动失败：端口 $requestedPort 不可用")
            return null
        }
        return try {
            // offer 的确认回调可能弹窗阻塞很久，单独给一个小池，最多同时挂 8 个等待。
            offerPool = ThreadPoolExecutor(
                0,
                MAX_OFFER_WAITS,
                30L,
                TimeUnit.SECONDS,
                SynchronousQueue<Runnable>(),
                NetThreads.factory("qc-offer"),
            )
            server = http
            actualPort = http.boundPort()
            started = true
            log("TransferServer 已启动，端口 $actualPort")
            actualPort
        } catch (t: Throwable) {
            log("TransferServer 启动异常：${t.message}")
            offerPool?.shutdownNow()
            offerPool = null
            http.stop()
            null
        }
    }

    /** 幂等；会为未完成的会话补一次 onSessionEnd(false,"stopped")，否则界面会永远停在「接收中」。 */
    fun stop() {
        if (!started) return
        started = false
        server?.stop()
        server = null
        for ((sid, session) in sessions) {
            abortAll(session)
            session.closed = true
            log("会话 $sid 随服务停止而中止")
            fireSessionEnd(sid, false, "stopped")
        }
        sessions.clear()
        offerPool?.shutdownNow()
        offerPool = null
        actualPort = 0
    }

    // ---------------------------------------------------------------- 路由

    private fun route(req: HttpRequest): HttpResponse {
        sweep()
        val path = if (req.path.length > 1) req.path.trimEnd('/') else req.path
        return when (path) {
            "/api/v1/info" ->
                if (req.method == "GET") handleInfo() else methodNotAllowed("GET")

            "/api/v1/ping" ->
                if (req.method == "GET") handlePing() else methodNotAllowed("GET")

            "/api/v1/offer" ->
                if (req.method == "POST") handleOffer(req) else methodNotAllowed("POST")

            "/api/v1/upload" ->
                if (req.method == "PUT") handleUpload(req) else methodNotAllowed("PUT")

            "/api/v1/complete" ->
                if (req.method == "POST") handleComplete(req) else methodNotAllowed("POST")

            "/api/v1/cancel" ->
                if (req.method == "POST") handleCancel(req) else methodNotAllowed("POST")

            else -> HttpResponse.error(404, "not_found", "未知路径：$path")
        }
    }

    private fun methodNotAllowed(expected: String): HttpResponse =
        HttpResponse.error(405, "bad_method", "该接口只接受 $expected")

    private fun handleInfo(): HttpResponse {
        val info = device()
        // 广播/展示用的 port 可能来自设置，但 /info 必须报真实监听端口，否则对端连不回来。
        val port = if (actualPort > 0) actualPort else info.port
        return HttpResponse.json(
            200,
            J.obj(
                "app" to J.of("qingchuan"),
                "alias" to J.of(info.alias),
                "did" to J.of(info.did),
                "port" to J.of(port),
                "os" to J.of(info.os),
                "version" to J.of(info.version),
                "proto" to J.of(info.proto),
            ),
        )
    }

    private fun handlePing(): HttpResponse = HttpResponse.json(200, J.obj("ok" to J.of(true)))

    // ---------------------------------------------------------------- offer

    private fun handleOffer(req: HttpRequest): HttpResponse {
        val json = readJson(req)
            ?: return HttpResponse.error(400, "bad_request", "请求体不是合法 JSON")
        if (sessions.size >= MAX_SESSIONS) {
            log("并发会话已满，拒绝 offer")
            return declined("busy")
        }
        val sender = json.obj("sender")
        val alias = sender?.str("alias")?.trim().orEmpty().ifBlank { "未知设备" }
        val declaredPort = sender?.int("port") ?: 0
        val peer = Peer(
            alias = alias,
            did = sender?.str("did").orEmpty(),
            host = req.remoteHost,
            port = if (declaredPort in 1..65535) declaredPort else DEFAULT_HTTP_PORT,
            os = sender?.str("os") ?: "Android",
            version = sender?.str("version") ?: "1.0",
        )

        val rawFiles = json.array("files")
        if (rawFiles.size !in 1..MAX_FILES) {
            return limit("文件数量必须在 1..$MAX_FILES 之间（收到 ${rawFiles.size}）")
        }
        val files = ArrayList<IncomingFile>(rawFiles.size)
        val ids = HashSet<String>()
        var total = 0L
        for (raw in rawFiles) {
            val file = IncomingFile.fromJson(raw)
                ?: return HttpResponse.error(400, "bad_request", "文件条目缺少 id 或 name")
            if (file.size < 0) return limit("文件大小非法：${file.size}")
            if (file.size > MAX_FILE_BYTES) return limit("单个文件不得超过 8GiB")
            if (!ids.add(file.id)) {
                return HttpResponse.error(400, "bad_request", "文件 id 重复：${file.id}")
            }
            total += file.size
            if (total > MAX_TOTAL_BYTES) return limit("总大小不得超过 16GiB")
            files.add(file)
        }

        var sid = newSessionId()
        while (sessions.containsKey(sid)) sid = newSessionId()
        val tokens = LinkedHashMap<String, String>()
        for (file in files) tokens[file.id] = randomHex(16)
        val offer = IncomingOffer(sid, peer, files)

        val executor = offerPool
        if (executor == null) return declined("busy")

        // onOffer 是 UI 确认，可能阻塞几十秒。放到独立线程里跑，handler 线程只等带超时的闩锁，
        // 这样等待期间 16 个连接线程里最多被占 4 个（会话上限），其它请求照常处理。
        val latch = CountDownLatch(1)
        val accepted = AtomicBoolean(false)
        try {
            executor.execute {
                val ok = try {
                    onOffer(offer)
                } catch (t: Throwable) {
                    log("onOffer 回调异常：${t.message}")
                    false
                }
                accepted.set(ok)
                latch.countDown()
            }
        } catch (_: RejectedExecutionException) {
            return declined("busy")
        }
        val decided = try {
            latch.await(OFFER_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!decided) {
            log("offer $sid 等待确认超过 ${OFFER_TIMEOUT_MS / 1000}s")
            return declined("timeout")
        }
        if (!accepted.get()) {
            log("offer $sid 被用户拒绝")
            return declined("declined")
        }

        sessions[sid] = Session(sid, peer, files, tokens)
        log("接受会话 $sid：${peer.alias}（${peer.addr}），${files.size} 个文件 / $total 字节")
        return HttpResponse.json(
            200,
            J.obj(
                "accepted" to J.of(true),
                "sid" to J.of(sid),
                "files" to J.arr(
                    files.map { file ->
                        J.obj("id" to J.of(file.id), "token" to J.of(tokens[file.id] ?: ""))
                    },
                ),
            ),
        )
    }

    private fun declined(reason: String): HttpResponse =
        HttpResponse.json(
            200,
            J.obj("accepted" to J.of(false), "reason" to J.of(reason)),
        )

    private fun limit(message: String): HttpResponse =
        HttpResponse.error(400, "limit", message)

    // ---------------------------------------------------------------- upload

    private fun handleUpload(req: HttpRequest): HttpResponse {
        val sid = req.query["sid"]
        val fid = req.query["fid"]
        if (sid.isNullOrEmpty() || fid.isNullOrEmpty()) {
            return HttpResponse.error(404, "no_session", "缺少 sid 或 fid")
        }
        val session = sessions[sid]
            ?: return HttpResponse.error(404, "no_session", "会话不存在或已结束")
        val file = session.file(fid)
            ?: return HttpResponse.error(404, "no_session", "文件不属于该会话")
        val expectedToken = session.tokens[fid]
            ?: return HttpResponse.error(403, "bad_token", "该文件没有上传令牌")
        val token = req.headers["x-token"]
        if (token == null || token != expectedToken) {
            return HttpResponse.error(403, "bad_token", "上传令牌不匹配")
        }
        if (req.contentLength < 0 || req.contentLength != file.size) {
            return HttpResponse.error(409, "bad_size", "Content-Length 必须等于声明的 ${file.size} 字节")
        }
        if (session.done.contains(fid)) {
            return HttpResponse.error(409, "dup_file", "该文件已经上传完成")
        }
        if (!session.busy.add(fid)) {
            return HttpResponse.error(409, "busy", "该文件正在上传")
        }

        var storage: StorageSink? = null
        var handle: WriteHandle? = null
        var received = 0L
        try {
            val store = sinkFor(session)
            storage = store
            // id 必须全局唯一：上层拿 StoredFile.id 当历史记录主键（Index 按 id 去重），
            // 而 offer 里的 fid 只在一次会话内唯一（发送端固定用 f0/f1…），直接透传会互相覆盖。
            val h = store.begin("${session.id}_${file.id}", file.name, file.mime, file.size)
            handle = h
            session.handles[fid] = h
            session.lastActive = System.currentTimeMillis()

            val buffer = ByteArray(CHUNK)
            var sinceReport = 0L
            while (received < file.size) {
                val want = minOf(CHUNK.toLong(), file.size - received).toInt()
                val n = req.body.read(buffer, 0, want)
                if (n < 0) break
                h.out.write(buffer, 0, n)
                received += n
                sinceReport += n
                if (sinceReport >= CHUNK) {
                    sinceReport = 0
                    fireProgress(sid, file, received)
                }
            }
            if (received != file.size) {
                throw IOException("接收字节数不足：$received/${file.size}")
            }
            val stored = store.commit(h, received)
            if (session.handles[fid] === h) session.handles.remove(fid)
            session.done.add(fid)
            session.lastActive = System.currentTimeMillis()
            fireProgress(sid, file, received)
            fireFileDone(sid, stored)
            log("会话 $sid 收到 ${stored.name}（$received 字节）")
            return HttpResponse.json(
                200,
                J.obj(
                    "ok" to J.of(true),
                    "name" to J.of(stored.name),
                    "saved" to J.of(received),
                ),
            )
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
            log("会话 $sid 写入失败：${t.message}")
            val h = handle
            val store = storage
            if (h != null) {
                if (session.handles[fid] === h) session.handles.remove(fid)
                if (store != null) runCatching { store.abort(h) }
            }
            return HttpResponse.error(500, "io", "写入失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            session.busy.remove(fid)
            session.lastActive = System.currentTimeMillis()
        }
    }

    // ---------------------------------------------------------------- complete / cancel

    private fun handleComplete(req: HttpRequest): HttpResponse {
        val json = readJson(req)
            ?: return HttpResponse.error(400, "bad_request", "请求体不是合法 JSON")
        val sid = json.str("sid").orEmpty()
        if (sid.isBlank()) return HttpResponse.error(400, "bad_request", "缺少 sid")
        val session = sessions.remove(sid)
            ?: return HttpResponse.error(404, "no_session", "会话不存在或已结束")
        abortAll(session)
        session.closed = true
        log("会话 $sid 完成")
        fireSessionEnd(sid, true, "ok")
        return HttpResponse.json(200, J.obj("ok" to J.of(true)))
    }

    private fun handleCancel(req: HttpRequest): HttpResponse {
        val json = readJson(req)
            ?: return HttpResponse.error(400, "bad_request", "请求体不是合法 JSON")
        val sid = json.str("sid").orEmpty()
        if (sid.isBlank()) return HttpResponse.error(400, "bad_request", "缺少 sid")
        val reason = json.str("reason")?.takeIf { it.isNotBlank() } ?: "cancelled"
        val session = sessions.remove(sid)
            ?: return HttpResponse.error(404, "no_session", "会话不存在或已结束")
        abortAll(session)
        session.closed = true
        log("会话 $sid 被取消：$reason")
        fireSessionEnd(sid, false, reason)
        return HttpResponse.json(200, J.obj("ok" to J.of(true)))
    }

    // ---------------------------------------------------------------- 内部工具

    private fun sinkFor(session: Session): StorageSink {
        session.sink?.let { return it }
        synchronized(session.lock) {
            session.sink?.let { return it }
            val created = sink()
            session.sink = created
            return created
        }
    }

    private fun abortAll(session: Session) {
        val store = session.sink
        for ((fid, handle) in session.handles) {
            if (store != null) runCatching { store.abort(handle) }
            session.handles.remove(fid)
        }
        session.busy.clear()
    }

    /** 每次请求顺手扫一遍过期会话：不额外起线程，也不依赖客户端一定发 cancel。 */
    private fun sweep() {
        val now = System.currentTimeMillis()
        for ((sid, session) in sessions) {
            if (now - session.lastActive <= SESSION_IDLE_MS) continue
            if (sessions[sid] !== session) continue
            sessions.remove(sid)
            abortAll(session)
            session.closed = true
            log("会话 $sid 闲置超时，已清理")
            fireSessionEnd(sid, false, "timeout")
        }
    }

    private fun readJson(req: HttpRequest): J? {
        val length = req.contentLength
        if (length < 0 || length > MAX_JSON_BODY) return null
        val bytes = readBody(req.body, length)
        if (bytes.isEmpty()) return null
        val parsed = J.parse(String(bytes, Charsets.UTF_8))
        return if (parsed.isNull) null else parsed
    }

    private fun readBody(input: InputStream, limit: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        var total = 0L
        while (total < limit) {
            val want = minOf(buffer.size.toLong(), limit - total).toInt()
            val n = try {
                input.read(buffer, 0, want)
            } catch (_: IOException) {
                -1
            }
            if (n < 0) break
            out.write(buffer, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun newSessionId(): String = "s_" + randomHex(4)

    private fun randomHex(bytes: Int): String {
        val data = ByteArray(bytes)
        random.nextBytes(data)
        val sb = StringBuilder(bytes * 2)
        for (b in data) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private fun fireProgress(sid: String, file: IncomingFile, received: Long) {
        runCatching { onProgress(sid, file, received) }
    }

    private fun fireFileDone(sid: String, stored: StoredFile) {
        runCatching { onFileDone(sid, stored) }
    }

    private fun fireSessionEnd(sid: String, ok: Boolean, message: String) {
        runCatching { onSessionEnd(sid, ok, message) }
    }

    private companion object {
        const val CHUNK = 64 * 1024
        const val MAX_FILES = 200
        const val MAX_FILE_BYTES = 8L * 1024 * 1024 * 1024
        const val MAX_TOTAL_BYTES = 16L * 1024 * 1024 * 1024
        const val MAX_JSON_BODY = 1024L * 1024
        const val MAX_SESSIONS = 4
        const val MAX_OFFER_WAITS = 8
        const val OFFER_TIMEOUT_MS = 90_000L
        const val SESSION_IDLE_MS = 10 * 60_000L
        const val DEFAULT_HTTP_PORT = 53318
        const val HEX = "0123456789abcdef"
    }
}
