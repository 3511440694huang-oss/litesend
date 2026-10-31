package app.qingchuan.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

/** 一次发送的终态。phase 只可能是 DONE / DECLINED / FAILED / CANCELLED。 */
data class SendOutcome(
    val phase: SendPhase,
    val sentBytes: Long,
    val message: String,
)

/**
 * 发送端：offer → 逐文件 PUT → complete。
 *
 * 用 HttpURLConnection 而不是自写 socket：上传是定长大流量，
 * 能用 setFixedLengthStreamingMode 直接流式写，也不引入第三方依赖。
 * 注意 HttpURLConnection 没有独立的写超时，写阻塞只会由连接层兜底，
 * 读超时 30s 覆盖「对端不响应」这种最常见的卡死。
 */
class Sender(
    private val log: (String) -> Unit = {},
    /**
     * 可选：把本机身份放进 offer，接收端才能显示真实设备名。
     * 保留默认值，`Sender()` / `Sender(log)` 的契约签名依旧成立。
     */
    private val self: () -> DeviceInfo? = { null },
) {

    private val fallbackDid: String by lazy { UUID.randomUUID().toString() }

    /** 阻塞执行；调用方必须放在 IO 线程/协程里。 */
    fun send(
        peer: Peer,
        files: List<OutgoingFile>,
        onProgress: (index: Int, sentForThisFile: Long, sentTotal: Long) -> Unit,
        onPhase: (SendPhase, String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): SendOutcome {
        if (files.isEmpty()) {
            runCatching { onPhase(SendPhase.FAILED, "没有要发送的文件") }
            return SendOutcome(SendPhase.FAILED, 0, "没有要发送的文件")
        }
        if (peer.host.isBlank() || peer.port !in 1..65535) {
            val message = "对端地址无效：${peer.host}:${peer.port}"
            runCatching { onPhase(SendPhase.FAILED, message) }
            return SendOutcome(SendPhase.FAILED, 0, message)
        }

        val base = "http://${hostForUrl(peer.host)}:${peer.port}"
        val me = runCatching { self() }.getOrNull()

        var sentTotal = 0L
        var lastEmit = 0L

        fun emit(index: Int, sentForFile: Long, force: Boolean) {
            val now = System.currentTimeMillis()
            // 64KB 的粒度已经由调用点保证，这里只做 120ms 节流，避免刷爆 UI。
            if (!force && now - lastEmit < PROGRESS_THROTTLE_MS) return
            lastEmit = now
            runCatching { onProgress(index, sentForFile, sentTotal) }
        }

        fun phase(value: SendPhase, message: String) {
            log("$value：$message")
            runCatching { onPhase(value, message) }
        }

        fun fail(message: String): SendOutcome {
            log("发送失败：$message")
            runCatching { onPhase(SendPhase.FAILED, message) }
            return SendOutcome(SendPhase.FAILED, sentTotal, message)
        }

        // 1. offer
        phase(SendPhase.OFFERING, "正在请求 ${peer.alias} 确认…")
        val offerJson = J.obj(
            "sender" to J.obj(
                "alias" to J.of(me?.alias ?: "轻传"),
                "did" to J.of(me?.did ?: fallbackDid),
                "port" to J.of(me?.port ?: 0),
                "os" to J.of(me?.os ?: "Android"),
                "version" to J.of(me?.version ?: BuildInfo.VERSION_NAME),
            ),
            "files" to J.arr(
                files.mapIndexed { index, file ->
                    J.obj(
                        "id" to J.of("f$index"),
                        "name" to J.of(file.name),
                        "size" to J.of(file.size),
                        "mime" to J.of(file.mime),
                    )
                },
            ),
        )
        val offerResult = try {
            postJson("$base/api/v1/offer", offerJson, CONNECT_TIMEOUT_MS, OFFER_TIMEOUT_MS)
        } catch (t: Throwable) {
            return fail("无法连接 ${peer.addr}：${t.message ?: t.javaClass.simpleName}")
        }
        if (offerResult.code !in 200..299) {
            return fail("对方未能受理请求（HTTP ${offerResult.code}）：${errorText(offerResult)}")
        }
        val reply = J.parse(offerResult.text)
        if (reply.bool("accepted") != true) {
            val reason = reply.str("reason") ?: "declined"
            val message = when (reason) {
                "declined" -> "对方拒绝了本次传输"
                "timeout" -> "等待对方确认超时"
                "busy" -> "对方正在忙，稍后再试"
                else -> "对方未接受：$reason"
            }
            phase(SendPhase.DECLINED, message)
            return SendOutcome(SendPhase.DECLINED, 0, message)
        }
        val sid = reply.str("sid")
        if (sid.isNullOrBlank()) return fail("对方应答缺少会话 id")
        val tokens = HashMap<String, String>()
        for (item in reply.array("files")) {
            val id = item.str("id") ?: continue
            val token = item.str("token") ?: continue
            tokens[id] = token
        }

        // 2. 逐文件上传
        var failure: String? = null
        var cancelled = false
        for (index in files.indices) {
            if (isCancelled()) {
                cancelled = true
                break
            }
            val file = files[index]
            val fid = "f$index"
            val token = tokens[fid]
            if (token == null) {
                failure = "服务端没有接受文件：${file.name}"
                break
            }
            phase(SendPhase.SENDING, "正在发送 ${file.name}")
            var connection: HttpURLConnection? = null
            var source: InputStream? = null
            try {
                val url = buildString(256) {
                    append(base).append("/api/v1/upload?sid=").append(enc(sid))
                    // 文件名同时出现在 query 里：便于对端日志排查，且服务端有 name 兜底。
                    append("&fid=").append(enc(fid)).append("&name=").append(enc(file.name))
                }
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    doOutput = true
                    useCaches = false
                    setRequestProperty("Content-Type", "application/octet-stream")
                    setRequestProperty("X-Token", token)
                    setRequestProperty("Connection", "close")
                    setFixedLengthStreamingMode(file.size)
                }
                connection = conn
                val sink = conn.outputStream
                val input = file.open()
                source = input
                val buffer = ByteArray(CHUNK)
                var remaining = file.size
                var sentFile = 0L
                while (remaining > 0) {
                    if (isCancelled()) {
                        cancelled = true
                        break
                    }
                    val n = input.read(buffer, 0, minOf(CHUNK.toLong(), remaining).toInt())
                    if (n < 0) throw IOException("本地文件比声明的 ${file.size} 字节短")
                    sink.write(buffer, 0, n)
                    remaining -= n
                    sentFile += n
                    sentTotal += n
                    emit(index, sentFile, false)
                }
                if (cancelled) break
                sink.flush()
                // 源文件比声明更长同样算失败：对端会落下一个被截断的文件。
                if (input.read() >= 0) throw IOException("本地文件比声明的 ${file.size} 字节长")
                emit(index, sentFile, true)

                val code = conn.responseCode
                val text = readResponse(conn, code)
                if (code !in 200..299) {
                    val body = J.parse(text)
                    val detail = body.str("message") ?: body.str("error") ?: text.take(160)
                    failure = "上传 ${file.name} 失败（HTTP $code）：$detail"
                }
            } catch (t: Throwable) {
                if (isCancelled()) cancelled = true
                else failure = "上传 ${file.name} 失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                runCatching { source?.close() }
                runCatching { connection?.disconnect() }
            }
            if (cancelled || failure != null) break
        }

        if (cancelled || isCancelled()) {
            cancelRemote(base, sid)
            phase(SendPhase.CANCELLED, "已取消")
            return SendOutcome(SendPhase.CANCELLED, sentTotal, "已取消")
        }
        val error = failure
        if (error != null) {
            // 任意失败都要尽力通知对端清理半截会话，失败也不影响本地结论。
            cancelRemote(base, sid)
            return fail(error)
        }

        // 3. complete
        val completed = try {
            postJson("$base/api/v1/complete", J.obj("sid" to J.of(sid)), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
        } catch (t: Throwable) {
            log("完成通知发送失败：${t.message}")
            null
        }
        if (completed == null || completed.code !in 200..299) {
            // 字节已经全部落盘，只是收尾握手失败；报 FAILED 会诱导用户重发一遍。
            val message = "文件已发送，但完成通知失败"
            log(message)
            phase(SendPhase.DONE, message)
            return SendOutcome(SendPhase.DONE, sentTotal, message)
        }
        phase(SendPhase.DONE, "发送完成")
        log("发送完成：${files.size} 个文件 / $sentTotal 字节 → ${peer.addr}")
        return SendOutcome(SendPhase.DONE, sentTotal, "发送完成")
    }

    // ---------------------------------------------------------------- HTTP 工具

    private class HttpResult(val code: Int, val text: String)

    private fun postJson(url: String, json: J, connectMs: Int, readMs: Int): HttpResult {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = connectMs
            connection.readTimeout = readMs
            connection.doOutput = true
            connection.useCaches = false
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            val body = json.stringify().toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            return HttpResult(code, readResponse(connection, code))
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun cancelRemote(base: String, sid: String?) {
        if (sid.isNullOrBlank()) return
        try {
            val result = postJson(
                "$base/api/v1/cancel",
                J.obj("sid" to J.of(sid), "reason" to J.of("cancelled")),
                CANCEL_TIMEOUT_MS,
                CANCEL_TIMEOUT_MS,
            )
            log("已发送 cancel（sid=$sid，HTTP ${result.code}）")
        } catch (t: Throwable) {
            log("发送 cancel 失败（忽略）：${t.message}")
        }
    }

    private fun readResponse(connection: HttpURLConnection, code: Int): String {
        val stream = if (code in 200..399) connection.inputStream else connection.errorStream
        if (stream == null) return ""
        return stream.use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            var total = 0
            while (total < MAX_RESPONSE_BYTES) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                total += n
            }
            String(out.toByteArray(), Charsets.UTF_8)
        }
    }

    private fun errorText(result: HttpResult): String {
        val body = J.parse(result.text)
        return body.str("message") ?: body.str("error") ?: result.text.take(160)
    }

    /** URLEncoder 会把空格编成 '+'，查询串里正好是空格的合法写法。 */
    private fun enc(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: Throwable) {
        value
    }

    private fun hostForUrl(host: String): String =
        if (host.contains(':') && !host.startsWith("[")) "[$host]" else host

    private companion object {
        const val CHUNK = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 5000
        const val READ_TIMEOUT_MS = 30_000
        const val OFFER_TIMEOUT_MS = 95_000
        const val CANCEL_TIMEOUT_MS = 3000
        const val PROGRESS_THROTTLE_MS = 120L
        const val MAX_RESPONSE_BYTES = 256 * 1024
    }
}
