package app.qingchuan.core

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 一条已解析的 HTTP 请求。
 *
 * body 只覆盖 Content-Length 声明的区间，且 close() 不会关闭底层 socket：
 * 这样 handler 完全无视 body 时，框架仍能安全 drain 并把连接交还给下一个请求。
 */
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: InputStream,
    val contentLength: Long,
    val remoteHost: String,
)

/** 处理结果。本协议响应都很小，整体放在内存里比流式返回更简单，也避免连接生命周期纠缠。 */
class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val contentType: String = "application/json; charset=utf-8",
) {
    companion object {
        fun json(status: Int, j: J): HttpResponse =
            HttpResponse(status, j.stringify().toByteArray(Charsets.UTF_8))

        fun text(status: Int, s: String): HttpResponse =
            HttpResponse(status, s.toByteArray(Charsets.UTF_8), "text/plain; charset=utf-8")

        fun error(status: Int, code: String, message: String): HttpResponse =
            json(status, J.obj("error" to J.of(code), "message" to J.of(message)))
    }
}

/**
 * 极简 HTTP/1.1 服务端。
 *
 * 之所以用「阻塞 socket + 有界线程池」而不是 NIO：局域网里并发只有个位数，
 * 阻塞模型简单到可以证明「stop() 之后线程一定退出」，而 Selector 的收尾很难保证。
 */
class HttpServer(private val port: Int, private val log: (String) -> Unit = {}) {

    @Volatile
    private var handler: ((HttpRequest) -> HttpResponse)? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var pool: ThreadPoolExecutor? = null

    @Volatile
    private var running: Boolean = false

    @Volatile
    private var localPort: Int = port

    private var acceptThread: Thread? = null

    // 记录所有活着的连接，stop() 时逐个关闭：只靠 interrupt 唤不醒阻塞在 read() 的线程。
    private val sockets: MutableSet<Socket> =
        Collections.newSetFromMap(ConcurrentHashMap<Socket, Boolean>())

    private val defaultHandler: (HttpRequest) -> HttpResponse =
        { HttpResponse.error(404, "not_found", "未知路径") }

    fun setHandler(handler: (HttpRequest) -> HttpResponse) {
        this.handler = handler
    }

    /** 绑定失败只返回 false，绝不抛异常：调用方要用它来决定换端口重试。 */
    fun start(): Boolean {
        if (running) return true
        val ss = try {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(port), 32)
                // accept 也带超时：即便 stop() 里的 close() 因平台差异没唤醒阻塞，
                // 线程最多 1s 后也会自己回到循环顶部看到 running=false。
                soTimeout = ACCEPT_TIMEOUT_MS
            }
        } catch (t: Throwable) {
            log("HTTP 端口 $port 绑定失败：${t.message ?: t.javaClass.simpleName}")
            return false
        }
        serverSocket = ss
        localPort = ss.localPort
        val executor = ThreadPoolExecutor(
            0,
            MAX_CONNECTIONS,
            30L,
            TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            NetThreads.factory("qc-http-pool"),
            RejectedExecutionHandler { r, _ ->
                // 满了就拒绝并关连接：宁可让一个客户端失败，也不能无限制地起线程。
                (r as? ConnectionTask)?.closeQuietly()
                log("HTTP 并发连接已达上限 $MAX_CONNECTIONS，拒绝新连接")
            },
        )
        pool = executor
        running = true
        acceptThread = NetThreads.start("qc-http-accept") { acceptLoop(ss) }
        return true
    }

    /** start() 之后的实际端口（port 传 0 时由系统分配）。 */
    fun boundPort(): Int = localPort

    /** 幂等：重复调用不会抛异常，也不会留下线程。 */
    fun stop() {
        val wasRunning = running
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (s in sockets) runCatching { s.close() }
        sockets.clear()
        val executor = pool
        pool = null
        if (executor != null) {
            executor.shutdownNow()
            runCatching { executor.awaitTermination(1500, TimeUnit.MILLISECONDS) }
        }
        acceptThread?.let { runCatching { it.join(1000) } }
        acceptThread = null
        if (wasRunning) log("HTTP 服务已停止（端口 $localPort）")
    }

    // ---------------------------------------------------------------- 连接

    private inner class ConnectionTask(private val socket: Socket) : Runnable {
        override fun run() {
            handleConnection(socket)
        }

        fun closeQuietly() {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val socket = try {
                ss.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (t: Throwable) {
                if (running) log("HTTP accept 失败：${t.message}")
                break
            }
            try {
                socket.soTimeout = IDLE_TIMEOUT_MS
                socket.tcpNoDelay = true
                sockets.add(socket)
                val task = ConnectionTask(socket)
                val executor = pool
                if (executor == null) {
                    task.closeQuietly()
                    break
                }
                try {
                    executor.execute(task)
                } catch (_: RejectedExecutionException) {
                    task.closeQuietly()
                }
            } catch (t: Throwable) {
                runCatching { socket.close() }
                sockets.remove(socket)
            }
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val input = BufferedInputStream(socket.getInputStream(), 8192)
            val output = BufferedOutputStream(socket.getOutputStream(), 8192)
            var served = 0
            while (running && served < MAX_REQUESTS_PER_CONN) {
                // 等下一个请求头时用空闲超时；读 body 时换成更宽松的读超时。
                socket.soTimeout = IDLE_TIMEOUT_MS
                val head = readHead(input) ?: break
                if (head.errorStatus != 0) {
                    writeResponse(
                        output,
                        head.errorStatus,
                        "application/json; charset=utf-8",
                        HttpResponse.error(head.errorStatus, head.errorCode, head.errorMessage).body,
                        keepAlive = false,
                        headOnly = false,
                    )
                    break
                }
                served++
                socket.soTimeout = BODY_TIMEOUT_MS
                val body = BodyStream(input, head.contentLength)
                val request = HttpRequest(
                    method = head.method,
                    path = head.path,
                    query = parseQuery(head.rawQuery),
                    headers = head.headers,
                    body = body,
                    contentLength = head.contentLength,
                    remoteHost = remoteHost(socket),
                )
                val response = try {
                    (handler ?: defaultHandler)(request)
                } catch (t: Throwable) {
                    log("HTTP 处理 ${head.method} ${head.path} 异常：${t.message}")
                    HttpResponse.error(500, "internal", "服务端处理失败")
                }
                socket.soTimeout = BODY_TIMEOUT_MS
                var keepAlive = head.keepAlive && served < MAX_REQUESTS_PER_CONN && running
                // 契约要求响应前必须把未读 body 读掉，否则下一个请求会从半个 body 里解析。
                try {
                    drain(body)
                } catch (_: Throwable) {
                    keepAlive = false
                }
                if (body.unread > 0) keepAlive = false
                writeResponse(
                    output,
                    response.status,
                    response.contentType,
                    response.body,
                    keepAlive,
                    headOnly = head.method == "HEAD",
                )
                if (!keepAlive) break
            }
        } catch (_: SocketTimeoutException) {
            // 空闲超时属于正常收尾。
        } catch (_: SocketException) {
            // 对端关闭或 stop() 主动关 socket。
        } catch (t: Throwable) {
            if (running) log("HTTP 连接异常：${t.message}")
        } finally {
            sockets.remove(socket)
            runCatching { socket.close() }
        }
    }

    private fun remoteHost(socket: Socket): String =
        runCatching { socket.inetAddress?.hostAddress ?: "" }.getOrDefault("")

    // ---------------------------------------------------------------- 请求解析

    private class Head(
        val method: String,
        val path: String,
        val rawQuery: String,
        val headers: Map<String, String>,
        val contentLength: Long,
        val keepAlive: Boolean,
        val errorStatus: Int = 0,
        val errorCode: String = "",
        val errorMessage: String = "",
    ) {
        companion object {
            fun err(status: Int, code: String, message: String): Head =
                Head("", "", "", emptyMap(), 0, false, status, code, message)
        }
    }

    private fun readHead(input: BufferedInputStream): Head? {
        val consumed = IntArray(1)
        // 少数客户端会在请求前多发 CRLF，容忍少量空行。
        var line: String?
        var blanks = 0
        while (true) {
            line = try {
                readLine(input, consumed)
            } catch (_: HeadTooLarge) {
                return Head.err(431, "headers_too_large", "请求头超过 16KB")
            } ?: return null
            if (line.isNotEmpty()) break
            blanks++
            if (blanks > 4) return null
        }
        val parts = line.split(' ').filter { it.isNotEmpty() }
        if (parts.size < 2) return Head.err(400, "bad_request", "请求行格式错误")
        val method = parts[0].uppercase()
        val version = parts.getOrNull(2) ?: "HTTP/1.1"

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val headerLine = try {
                readLine(input, consumed)
            } catch (_: HeadTooLarge) {
                return Head.err(431, "headers_too_large", "请求头超过 16KB")
            } ?: return null
            if (headerLine.isEmpty()) break
            val colon = headerLine.indexOf(':')
            if (colon <= 0) continue
            val key = headerLine.substring(0, colon).trim().lowercase()
            if (key.isEmpty()) continue
            val value = headerLine.substring(colon + 1).trim()
            val old = headers[key]
            headers[key] = if (old == null) value else "$old, $value"
        }

        val target = normalizeTarget(parts[1])
        val q = target.indexOf('?')
        val path = urlDecode(if (q >= 0) target.substring(0, q) else target, plusAsSpace = false)
        val rawQuery = if (q >= 0) target.substring(q + 1) else ""

        val contentLengthHeader = headers["content-length"]
        var contentLength = -1L
        if (contentLengthHeader != null) {
            contentLength = contentLengthHeader.trim().toLongOrNull()
                ?: return Head.err(400, "bad_request", "Content-Length 非法")
            if (contentLength < 0) return Head.err(400, "bad_request", "Content-Length 非法")
        }
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            // 本协议的请求体都是定长的；不实现 chunked 是为了不给读循环留歧义。
            return Head.err(411, "length_required", "不支持分块传输，请提供 Content-Length")
        }

        val connection = headers["connection"]?.lowercase() ?: ""
        val keepAlive = if (version.equals("HTTP/1.0", ignoreCase = true)) {
            connection.contains("keep-alive")
        } else {
            !connection.contains("close")
        }
        return Head(method, path, rawQuery, headers, contentLength, keepAlive)
    }

    /** 读到 \n 为止；计数超过 16KB 直接抛给上层转 431，避免恶意超长头把内存吃光。 */
    private fun readLine(input: InputStream, consumed: IntArray): String? {
        val buffer = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b < 0) {
                if (buffer.size() == 0) return null
                return String(buffer.toByteArray(), Charsets.UTF_8)
            }
            if (b == '\n'.code) {
                val bytes = buffer.toByteArray()
                var length = bytes.size
                if (length > 0 && bytes[length - 1] == '\r'.code.toByte()) length--
                return String(bytes, 0, length, Charsets.UTF_8)
            }
            consumed[0]++
            if (consumed[0] > MAX_HEAD_BYTES) throw HeadTooLarge()
            buffer.write(b)
        }
    }

    private fun normalizeTarget(target: String): String {
        val t = target.trim()
        if (t.startsWith("http://") || t.startsWith("https://")) {
            val start = t.indexOf("://") + 3
            val slash = t.indexOf('/', start)
            return if (slash >= 0) t.substring(slash) else "/"
        }
        return if (t.startsWith("/")) t else "/$t"
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isEmpty()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (part in raw.split('&')) {
            if (part.isEmpty()) continue
            val eq = part.indexOf('=')
            val key = urlDecode(if (eq >= 0) part.substring(0, eq) else part, plusAsSpace = true)
            if (key.isEmpty()) continue
            if (map.containsKey(key)) continue
            map[key] = if (eq >= 0) urlDecode(part.substring(eq + 1), plusAsSpace = true) else ""
        }
        return map
    }

    // ---------------------------------------------------------------- 响应

    private fun drain(body: BodyStream) {
        if (body.unread <= 0) return
        val buffer = ByteArray(16 * 1024)
        var drained = 0L
        while (body.unread > 0) {
            val n = body.read(buffer, 0, minOf(body.unread, buffer.size.toLong()).toInt())
            if (n < 0) return
            drained += n
            // 客户端还在传一个超大 body 而我们已决定不处理：放弃 keep-alive，直接关连接。
            if (drained > MAX_DRAIN_BYTES) return
        }
    }

    private fun writeResponse(
        output: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
        keepAlive: Boolean,
        headOnly: Boolean,
    ) {
        val header = buildString(160) {
            append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            append("Server: qingchuan/1\r\n")
            append("\r\n")
        }
        output.write(header.toByteArray(Charsets.UTF_8))
        if (!headOnly && body.isNotEmpty()) output.write(body)
        output.flush()
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        201 -> "Created"
        204 -> "No Content"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        409 -> "Conflict"
        411 -> "Length Required"
        413 -> "Payload Too Large"
        429 -> "Too Many Requests"
        431 -> "Request Header Fields Too Large"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "Status"
    }

    private class HeadTooLarge : IOException("header too large")

    private companion object {
        const val MAX_CONNECTIONS = 16
        const val MAX_REQUESTS_PER_CONN = 8
        const val MAX_HEAD_BYTES = 16 * 1024
        const val ACCEPT_TIMEOUT_MS = 1000
        const val IDLE_TIMEOUT_MS = 15_000
        const val BODY_TIMEOUT_MS = 30_000
        const val MAX_DRAIN_BYTES = 64L * 1024 * 1024
    }
}

/** 只暴露 Content-Length 声明的区间；close() 故意为空，连接归框架管。 */
private class BodyStream(private val src: InputStream, limit: Long) : InputStream() {

    private var remaining: Long = if (limit > 0) limit else 0L

    val unread: Long get() = remaining

    override fun read(): Int {
        if (remaining <= 0) return -1
        val b = src.read()
        if (b < 0) {
            remaining = 0
            return -1
        }
        remaining--
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (remaining <= 0) return -1
        val n = src.read(b, off, minOf(len.toLong(), remaining).toInt())
        if (n < 0) {
            remaining = 0
            return -1
        }
        remaining -= n
        return n
    }

    override fun available(): Int =
        if (remaining <= 0) 0 else minOf(remaining, src.available().toLong()).toInt()

    override fun close() {
        // 不关 socket：keep-alive 还要继续用同一个连接。
    }
}

/**
 * URL 解码。查询串里 '+' 是空格，路径里 '+' 是普通字符，所以由调用方指定。
 * 手写而不用 URLDecoder：后者会把路径里的 '+' 也变成空格。
 */
fun urlDecode(raw: String, plusAsSpace: Boolean = false): String {
    if (raw.indexOf('%') < 0 && (!plusAsSpace || raw.indexOf('+') < 0)) return raw
    val out = ByteArrayOutputStream(raw.length)
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '%' && i + 2 < raw.length) {
            val hi = Character.digit(raw[i + 1], 16)
            val lo = Character.digit(raw[i + 2], 16)
            if (hi >= 0 && lo >= 0) {
                out.write((hi shl 4) or lo)
                i += 3
                continue
            }
        }
        if (plusAsSpace && c == '+') {
            out.write(' '.code)
            i++
            continue
        }
        val bytes = c.toString().toByteArray(Charsets.UTF_8)
        out.write(bytes, 0, bytes.size)
        i++
    }
    return String(out.toByteArray(), Charsets.UTF_8)
}

/** 统一造 daemon 线程：单测会检查 stop() 后不留线程，daemon 是最后一道保险。 */
internal object NetThreads {

    private val seq = AtomicInteger()

    fun start(name: String, block: () -> Unit): Thread =
        Thread(Runnable { block() }, name).apply {
            isDaemon = true
            start()
        }

    fun factory(prefix: String): ThreadFactory = ThreadFactory { r ->
        Thread(r, "$prefix-${seq.incrementAndGet()}").apply { isDaemon = true }
    }
}
