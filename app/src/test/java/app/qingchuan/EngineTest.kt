package app.qingchuan

import app.qingchuan.core.DeviceInfo
import app.qingchuan.core.Discovery
import app.qingchuan.core.FileSink
import app.qingchuan.core.HttpResponse
import app.qingchuan.core.HttpServer
import app.qingchuan.core.IncomingOffer
import app.qingchuan.core.J
import app.qingchuan.core.OutgoingFile
import app.qingchuan.core.Peer
import app.qingchuan.core.SendOutcome
import app.qingchuan.core.SendPhase
import app.qingchuan.core.Sender
import app.qingchuan.core.StoredFile
import app.qingchuan.core.TransferServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.util.Random
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 传输引擎的 JVM 单测：不打桩、不起 Android，全部走真 socket。
 * 每个用例用独立临时目录，@After 删干净，避免互相看到对方落的文件。
 */
class EngineTest {

    private val tmpRoot: File =
        File(System.getProperty("java.io.tmpdir"), "qc-engine-" + UUID.randomUUID()).apply { mkdirs() }
    private val receiveDir: File = File(tmpRoot, "recv").apply { mkdirs() }

    private val closeables = ArrayList<AutoCloseable>()

    @After
    fun tearDown() {
        for (closeable in closeables) runCatching { closeable.close() }
        closeables.clear()
        deleteRecursively(tmpRoot)
    }

    // ================================================================ JSON

    @Test
    fun jsonRoundTripHandlesEscapesAndNesting() {
        val original = J.obj(
            "name" to J.of("a测试.jpg"),
            "quote" to J.of("他说\"好\""),
            "path" to J.of("C:\\tmp\\x"),
            "multi" to J.of("第一行\n第二行\t制表"),
            "big" to J.of(4294967296L),
            "ok" to J.of(true),
            "list" to J.arr(
                J.of(1),
                J.of("二"),
                J.obj("deep" to J.of(false)),
            ),
            "nil" to J.Null,
        )
        val text = original.stringify()
        assertTrue("双引号必须转义", text.contains("\\\""))
        assertTrue("反斜杠必须转义", text.contains("\\\\"))
        assertTrue("换行必须转义", text.contains("\\n"))
        assertTrue("制表符必须转义", text.contains("\\t"))

        val parsed = J.parse(text)
        assertEquals("a测试.jpg", parsed.str("name"))
        assertEquals("他说\"好\"", parsed.str("quote"))
        assertEquals("C:\\tmp\\x", parsed.str("path"))
        assertEquals("第一行\n第二行\t制表", parsed.str("multi"))
        assertEquals(4294967296L, parsed.long("big"))
        assertEquals(true, parsed.bool("ok"))
        assertEquals(3, parsed.array("list").size)
        assertEquals("二", parsed.array("list")[1].asString)
        assertEquals(false, parsed.array("list")[2].bool("deep"))
        assertTrue(parsed["nil"]?.isNull == true)

        // \u 转义与字符串化后的数字
        assertEquals("中", J.parse("{\"u\":\"\\u4e2d\"}").str("u"))
        assertEquals(42L, J.parse("{\"n\":42}").long("n"))
        assertEquals(7L, J.parse("{\"n\":\"7\"}").long("n"))
    }

    @Test
    fun jsonRejectsInvalidInput() {
        assertTrue(J.parse("").isNull)
        assertTrue(J.parse("   ").isNull)
        assertTrue(J.parse("{bad").isNull)
        assertTrue(J.parse("{\"a\":}").isNull)
        assertTrue(J.parse("[1,2").isNull)
        assertTrue(J.parse("{\"a\":1").isNull)
        assertTrue(J.parse("not json").isNull)
        assertTrue(J.parse("{\"a\":1}").isNull.not())

        val empty = J.parse("{}")
        assertNull(empty.str("missing"))
        assertEquals(0, empty.array("files").size)
    }

    // ================================================================ HttpServer

    @Test(timeout = 60_000)
    fun httpServerParsesQueryHeadersAndKeepsAlive() {
        val server = HttpServer(port = 0)
        server.setHandler { req ->
            HttpResponse.json(
                200,
                J.obj(
                    "method" to J.of(req.method),
                    "path" to J.of(req.path),
                    "a" to J.of(req.query["a"] ?: ""),
                    "b" to J.of(req.query["b"] ?: ""),
                    "token" to J.of(req.headers["x-token"] ?: ""),
                    "length" to J.of(req.contentLength),
                    "body" to J.of(req.body.readBytes().toString(Charsets.UTF_8)),
                ),
            )
        }
        assertTrue(server.start())
        closeables.add(AutoCloseable { server.stop() })

        RawConnection(server.boundPort()).use { connection ->
            connection.send(
                "GET /api/v1/x?a=%E4%B8%AD%E6%96%87&b=2 HTTP/1.1\r\n" +
                    "Host: test\r\nX-Token: AbC\r\n\r\n",
            )
            val first = connection.readResponse()
            assertEquals(200, first.code)
            assertEquals("keep-alive", first.headers["connection"])
            val firstBody = J.parse(first.body)
            assertEquals("GET", firstBody.str("method"))
            assertEquals("/api/v1/x", firstBody.str("path"))
            assertEquals("中文", firstBody.str("a"))
            assertEquals("2", firstBody.str("b"))
            assertEquals("AbC", firstBody.str("token"))
            assertEquals(-1L, firstBody.long("length"))

            // 同一连接上的第二个请求：框架必须已经把请求 1 完整消费掉
            connection.send("POST /api/v1/y HTTP/1.1\r\nHost: test\r\nContent-Length: 5\r\n\r\nhello")
            val second = connection.readResponse()
            assertEquals(200, second.code)
            val secondBody = J.parse(second.body)
            assertEquals("POST", secondBody.str("method"))
            assertEquals("hello", secondBody.str("body"))
            assertEquals(5L, secondBody.long("length"))
        }
    }

    /** handler 完全无视 body 时，框架也必须先 drain，否则下一个请求会从半个 body 开始解析。 */
    @Test(timeout = 60_000)
    fun httpServerDrainsUnreadBodyBeforeNextRequest() {
        val server = HttpServer(port = 0)
        server.setHandler { request ->
            HttpResponse.json(200, J.obj("len" to J.of(request.contentLength)))
        }
        assertTrue(server.start())
        closeables.add(AutoCloseable { server.stop() })

        RawConnection(server.boundPort()).use { connection ->
            connection.send("POST /x HTTP/1.1\r\nHost: t\r\nContent-Length: 11\r\n\r\nhello world")
            val first = connection.readResponse()
            assertEquals(200, first.code)
            assertEquals(11L, J.parse(first.body).long("len"))

            connection.send("GET /y HTTP/1.1\r\nHost: t\r\nConnection: close\r\n\r\n")
            val second = connection.readResponse()
            assertEquals(200, second.code)
        }
    }

    @Test(timeout = 60_000)
    fun httpServerRejectsOversizedHeaders() {
        val server = HttpServer(port = 0)
        server.setHandler { HttpResponse.json(200, J.obj("ok" to J.of(true))) }
        assertTrue(server.start())
        closeables.add(AutoCloseable { server.stop() })

        RawConnection(server.boundPort()).use { connection ->
            val big = "x".repeat(20 * 1024)
            runCatching { connection.send("GET / HTTP/1.1\r\nHost: t\r\nX-Big: $big\r\n\r\n") }
            val response = connection.readResponse()
            assertEquals(431, response.code)
            assertEquals("headers_too_large", J.parse(response.body).str("error"))
        }
    }

    // ================================================================ TransferServer

    @Test(timeout = 60_000)
    fun infoAndPing() {
        val rig = rig()
        val info = get("http://127.0.0.1:${rig.port}/api/v1/info")
        assertEquals(200, info.code)
        val json = info.json
        assertEquals("qingchuan", json.str("app"))
        assertEquals("测试机", json.str("alias"))
        assertEquals("did-server", json.str("did"))
        assertEquals(rig.port.toLong(), json.long("port"))
        assertEquals("Android 14", json.str("os"))
        assertEquals(1L, json.long("proto"))

        val ping = get("http://127.0.0.1:${rig.port}/api/v1/ping")
        assertEquals(200, ping.code)
        assertEquals(true, ping.json.bool("ok"))
    }

    @Test(timeout = 60_000)
    fun unknownPathAndWrongMethod() {
        val rig = rig()
        val missing = get("http://127.0.0.1:${rig.port}/api/v1/nope")
        assertEquals(404, missing.code)
        assertEquals("not_found", missing.json.str("error"))

        val wrong = postJson("http://127.0.0.1:${rig.port}/api/v1/info", J.obj())
        assertEquals(405, wrong.code)
        assertEquals("bad_method", wrong.json.str("error"))
    }

    @Test(timeout = 60_000)
    fun offerAcceptedThenUploadTwoMegabytesAndComplete() {
        val rig = rig()
        val payload = randomBytes(2 * 1024 * 1024)
        val (sid, tokens) = openSession(rig, listOf(OfferFile("f0", "a测试.jpg", payload.size.toLong())))
        val token = tokens["f0"] ?: error("应答里没有 token")
        assertEquals(32, token.length)
        assertEquals(1, rig.offers.size)
        assertEquals(1, rig.offers[0].files.size)
        assertEquals(payload.size.toLong(), rig.offers[0].totalSize)

        val upload = upload(rig, sid, "f0", token, payload)
        assertEquals(200, upload.code)
        val uploadJson = upload.json
        assertEquals(true, uploadJson.bool("ok"))
        assertEquals(payload.size.toLong(), uploadJson.long("saved"))
        assertEquals("a测试.jpg", uploadJson.str("name"))

        // 落盘内容逐字节一致
        val saved = receiveDir.listFiles()?.filter { it.isFile } ?: emptyList()
        assertEquals(1, saved.size)
        assertEquals("a测试.jpg", saved[0].name)
        assertArrayEquals(payload, saved[0].readBytes())

        assertEquals(1, rig.doneFiles.size)
        assertEquals(payload.size.toLong(), rig.doneFiles[0].size)
        assertTrue("进度回调应到过 2MB", rig.progress.contains(payload.size.toLong()))
        // 64KB 粒度：2MB 至少 32 次回调，且单调不减
        assertTrue("进度回调次数过少：${rig.progress.size}", rig.progress.size >= 32)
        assertTrue(
            "进度必须单调不减",
            rig.progress.toList().zipWithNext().all { (a, b) -> b >= a },
        )

        val complete = postJson(
            "http://127.0.0.1:${rig.port}/api/v1/complete",
            J.obj("sid" to J.of(sid)),
        )
        assertEquals(200, complete.code)
        assertEquals(true, complete.json.bool("ok"))
        assertEquals(1, rig.sessionEnds.size)
        assertEquals(true, rig.sessionEnds[0].second)
        assertEquals("ok", rig.sessionEnds[0].third)

        // 会话已清理：同一个 sid 不能再上传
        val again = upload(rig, sid, "f0", token, payload)
        assertEquals(404, again.code)
    }

    /**
     * 上层拿 StoredFile.id 当历史主键（Index 按 id 去重），而发送端的 fid 固定是 f0/f1…
     * 所以两次会话收到同名 fid 时必须给出不同的 id，否则历史记录会互相覆盖。
     */
    @Test(timeout = 60_000)
    fun storedFileIdsAreUniqueAcrossSessions() {
        val rig = rig()
        val payload = randomBytes(1024)
        repeat(2) { round ->
            val (sid, tokens) = openSession(
                rig,
                listOf(OfferFile("f0", "same-name-$round.bin", payload.size.toLong())),
            )
            val token = tokens["f0"] ?: error("应答里没有 token")
            assertEquals(200, upload(rig, sid, "f0", token, payload).code)
            assertEquals(
                200,
                postJson(
                    "http://127.0.0.1:${rig.port}/api/v1/complete",
                    J.obj("sid" to J.of(sid)),
                ).code,
            )
        }
        assertEquals(2, rig.doneFiles.size)
        assertTrue("id 不能为空", rig.doneFiles.all { it.id.isNotBlank() })
        assertEquals("两次会话的 StoredFile.id 不能相同", 2, rig.doneFiles.map { it.id }.toSet().size)
    }

    @Test(timeout = 60_000)
    fun offerDeclined() {
        val rig = rig(accepts = false)
        val response = postJson(
            "http://127.0.0.1:${rig.port}/api/v1/offer",
            offerJson(listOf(OfferFile("f0", "a.txt", 3))),
        )
        assertEquals(200, response.code)
        assertEquals(false, response.json.bool("accepted"))
        assertEquals("declined", response.json.str("reason"))
        assertNull(response.json.str("sid"))
    }

    @Test(timeout = 60_000)
    fun offerTooManyFilesIsLimitError() {
        val rig = rig()
        val files = (0 until 201).map { OfferFile("f$it", "f$it.txt", 1) }
        val response = postJson("http://127.0.0.1:${rig.port}/api/v1/offer", offerJson(files))
        assertEquals(400, response.code)
        assertEquals("limit", response.json.str("error"))
        assertEquals(0, rig.offerCalls.get())
    }

    @Test(timeout = 60_000)
    fun offerTooLargeFileIsLimitError() {
        val rig = rig()
        val huge = 8L * 1024 * 1024 * 1024 + 1
        val response = postJson(
            "http://127.0.0.1:${rig.port}/api/v1/offer",
            offerJson(listOf(OfferFile("f0", "huge.bin", huge))),
        )
        assertEquals(400, response.code)
        assertEquals("limit", response.json.str("error"))
    }

    @Test(timeout = 60_000)
    fun offerTotalSizeLimitIsEnforced() {
        val rig = rig()
        val six = 6L * 1024 * 1024 * 1024
        val files = listOf(
            OfferFile("f0", "a.bin", six),
            OfferFile("f1", "b.bin", six),
            OfferFile("f2", "c.bin", six),
        )
        val response = postJson("http://127.0.0.1:${rig.port}/api/v1/offer", offerJson(files))
        assertEquals(400, response.code)
        assertEquals("limit", response.json.str("error"))
    }

    @Test(timeout = 60_000)
    fun offerWithBrokenJsonIsBadRequest() {
        val rig = rig()
        val response = postJson("http://127.0.0.1:${rig.port}/api/v1/offer", J.parse("{oops"))
        assertEquals(400, response.code)
        assertEquals("bad_request", response.json.str("error"))
    }

    @Test(timeout = 60_000)
    fun uploadWithBadTokenIs403() {
        val rig = rig()
        val (sid, tokens) = openSession(rig, listOf(OfferFile("f0", "a.bin", 16)))
        assertNotNull(tokens["f0"])

        val wrong = upload(rig, sid, "f0", "deadbeefdeadbeefdeadbeefdeadbeef", randomBytes(16))
        assertEquals(403, wrong.code)
        assertEquals("bad_token", wrong.json.str("error"))

        val missing = upload(rig, sid, "f0", null, randomBytes(16))
        assertEquals(403, missing.code)
        assertEquals("bad_token", missing.json.str("error"))

        assertTrue("失败的写入不能留下文件", (receiveDir.listFiles()?.size ?: 0) == 0)
    }

    @Test(timeout = 60_000)
    fun uploadWithWrongLengthIs409() {
        val rig = rig()
        val (sid, tokens) = openSession(rig, listOf(OfferFile("f0", "a.bin", 5)))
        val token = tokens["f0"] ?: error("应答里没有 token")

        val short = upload(rig, sid, "f0", token, randomBytes(3))
        assertEquals(409, short.code)
        assertEquals("bad_size", short.json.str("error"))
        assertTrue((receiveDir.listFiles()?.size ?: 0) == 0)
    }

    /** 客户端半途断开（body 短于 Content-Length）：必须 abort 掉半截文件并回 500。 */
    @Test(timeout = 60_000)
    fun shortUploadAbortsHalfWrittenFile() {
        val rig = rig()
        val (sid, tokens) = openSession(rig, listOf(OfferFile("f0", "short.bin", 10)))
        val token = tokens["f0"] ?: error("应答里没有 token")

        Socket("127.0.0.1", rig.port).use { socket ->
            socket.soTimeout = 5000
            val request = "PUT /api/v1/upload?sid=$sid&fid=f0 HTTP/1.1\r\n" +
                "Host: test\r\nX-Token: $token\r\nContent-Length: 10\r\nConnection: close\r\n\r\n" +
                "abcde"
            socket.getOutputStream().apply {
                write(request.toByteArray(Charsets.UTF_8))
                flush()
            }
            // 半关写方向：服务端读到 EOF，但仍能把 500 写回来
            socket.shutdownOutput()
            val response = String(socket.getInputStream().readBytes(), Charsets.UTF_8)
            assertTrue("应返回 500，实际：$response", response.startsWith("HTTP/1.1 500"))
            assertTrue("应带 io 错误码，实际：$response", response.contains("\"io\""))
        }
        assertEquals(0, rig.doneFiles.size)
        assertTrue("失败后必须删除半截文件", (receiveDir.listFiles()?.size ?: 0) == 0)
    }

    /** onOffer 阻塞等用户确认时，其它请求必须照常处理（否则 16 个 handler 线程会被拖死）。 */
    @Test(timeout = 60_000)
    fun offerWaitDoesNotBlockOtherRequests() {
        val gate = CountDownLatch(1)
        val rig = rig(onOffer = { gate.await(10, TimeUnit.SECONDS) })
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "qc-test-offer").apply { isDaemon = true }
        }
        try {
            val pending = executor.submit<Resp> {
                postJson(
                    "http://127.0.0.1:${rig.port}/api/v1/offer",
                    offerJson(listOf(OfferFile("f0", "a.bin", 1))),
                )
            }
            val deadline = System.currentTimeMillis() + 5000
            while (rig.offerCalls.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)

            val started = System.currentTimeMillis()
            val ping = get("http://127.0.0.1:${rig.port}/api/v1/ping")
            val elapsed = System.currentTimeMillis() - started
            assertEquals(200, ping.code)
            assertTrue("确认等待期间 ping 应立即返回，实际 ${elapsed}ms", elapsed < 1000)

            gate.countDown()
            val offer = pending.get(10, TimeUnit.SECONDS)
            assertEquals(200, offer.code)
            assertEquals(true, offer.json.bool("accepted"))
        } finally {
            gate.countDown()
            executor.shutdownNow()
        }
    }

    @Test(timeout = 60_000)
    fun uploadToUnknownSessionIs404() {
        val rig = rig()
        val response = upload(rig, "s_not_exist", "f0", "whatever", null)
        assertEquals(404, response.code)
        assertEquals("no_session", response.json.str("error"))
    }

    @Test(timeout = 60_000)
    fun cancelAbortsSessionAndNotifies() {
        val rig = rig()
        val (sid, tokens) = openSession(rig, listOf(OfferFile("f0", "a.bin", 1024)))
        val token = tokens["f0"] ?: error("应答里没有 token")

        val cancel = postJson(
            "http://127.0.0.1:${rig.port}/api/v1/cancel",
            J.obj("sid" to J.of(sid), "reason" to J.of("user_cancel")),
        )
        assertEquals(200, cancel.code)
        assertEquals(true, cancel.json.bool("ok"))
        assertEquals(1, rig.sessionEnds.size)
        assertEquals(false, rig.sessionEnds[0].second)
        assertEquals("user_cancel", rig.sessionEnds[0].third)

        // 取消之后旧 token 立刻失效
        val after = upload(rig, sid, "f0", token, randomBytes(1024))
        assertEquals(404, after.code)
        assertTrue((receiveDir.listFiles()?.size ?: 0) == 0)

        // 重复 cancel 是幂等的失败：会话已经不在了
        val again = postJson("http://127.0.0.1:${rig.port}/api/v1/cancel", J.obj("sid" to J.of(sid)))
        assertEquals(404, again.code)
    }

    // ================================================================ Sender 端到端

    @Test(timeout = 120_000)
    fun senderEndToEndWithChineseName() {
        val rig = rig()
        val bigBytes = randomBytes(2 * 1024 * 1024)
        val smallBytes = "轻传 LiteSend 文本".toByteArray(Charsets.UTF_8)
        val bigFile = File(tmpRoot, "src-big.bin").apply { writeBytes(bigBytes) }
        val smallFile = File(tmpRoot, "src-small.txt").apply { writeBytes(smallBytes) }

        val outgoing = listOf(
            OutgoingFile(
                name = "a测试.jpg",
                size = bigBytes.size.toLong(),
                mime = "image/jpeg",
                open = { FileInputStream(bigFile) },
                uri = "file://" + bigFile.absolutePath,
            ),
            OutgoingFile(
                name = "说明 文件.txt",
                size = smallBytes.size.toLong(),
                mime = "text/plain",
                open = { FileInputStream(smallFile) },
            ),
        )
        val progressEvents = CopyOnWriteArrayList<Long>()
        val phases = CopyOnWriteArrayList<SendPhase>()
        val sender = Sender(log = {}, self = { DeviceInfo("发送手机", "did-sender", 53318) })

        val outcome: SendOutcome = sender.send(
            peer = Peer(alias = "接收机", did = "did-server", host = "127.0.0.1", port = rig.port),
            files = outgoing,
            onProgress = { _, _, total -> progressEvents.add(total) },
            onPhase = { phase, _ -> phases.add(phase) },
        )

        assertEquals(SendPhase.DONE, outcome.phase)
        assertEquals(bigBytes.size.toLong() + smallBytes.size, outcome.sentBytes)
        assertTrue(phases.contains(SendPhase.OFFERING))
        assertTrue(phases.contains(SendPhase.SENDING))
        assertTrue(phases.contains(SendPhase.DONE))
        assertEquals(outcome.sentBytes, progressEvents.last())

        assertEquals(2, rig.doneFiles.size)
        val byName = rig.doneFiles.associateBy { it.name }
        assertTrue("中文文件名应原样落盘：${byName.keys}", byName.containsKey("a测试.jpg"))
        assertTrue("带空格的中文名应原样落盘：${byName.keys}", byName.containsKey("说明 文件.txt"))
        assertArrayEquals(bigBytes, File(byName.getValue("a测试.jpg").uri.removePrefix("file://")).readBytes())
        assertArrayEquals(smallBytes, File(byName.getValue("说明 文件.txt").uri.removePrefix("file://")).readBytes())

        assertEquals(1, rig.sessionEnds.size)
        assertEquals(true, rig.sessionEnds[0].second)
        assertEquals("ok", rig.sessionEnds[0].third)
        assertTrue("服务端进度应覆盖 2MB", rig.progress.contains(bigBytes.size.toLong()))
    }

    @Test(timeout = 60_000)
    fun senderReportsDeclined() {
        val rig = rig(accepts = false)
        val file = File(tmpRoot, "x.txt").apply { writeText("hello") }
        val phases = CopyOnWriteArrayList<SendPhase>()
        val sender = Sender()

        val outcome = sender.send(
            peer = Peer(alias = "接收机", did = "did-server", host = "127.0.0.1", port = rig.port),
            files = listOf(OutgoingFile("x.txt", file.length(), "text/plain", { FileInputStream(file) })),
            onProgress = { _, _, _ -> },
            onPhase = { phase, _ -> phases.add(phase) },
        )
        assertEquals(SendPhase.DECLINED, outcome.phase)
        assertEquals(0L, outcome.sentBytes)
        assertTrue(phases.contains(SendPhase.OFFERING))
        assertTrue(phases.contains(SendPhase.DECLINED))
        assertEquals(0, rig.doneFiles.size)
    }

    @Test(timeout = 60_000)
    fun senderHonoursCancellation() {
        val rig = rig()
        val file = File(tmpRoot, "y.bin").apply { writeBytes(randomBytes(64 * 1024)) }
        val sender = Sender()
        val outcome = sender.send(
            peer = Peer(alias = "接收机", did = "did-server", host = "127.0.0.1", port = rig.port),
            files = listOf(OutgoingFile("y.bin", file.length(), "application/octet-stream", { FileInputStream(file) })),
            onProgress = { _, _, _ -> },
            onPhase = { _, _ -> },
            isCancelled = { true },
        )
        assertEquals(SendPhase.CANCELLED, outcome.phase)
        assertEquals(0, rig.doneFiles.size)
        assertTrue("服务端应收到 cancel", rig.sessionEnds.any { !it.second })
    }

    @Test(timeout = 60_000)
    fun senderFailsFastWhenPeerIsDown() {
        val sender = Sender()
        val file = File(tmpRoot, "z.txt").apply { writeText("z") }
        val outcome = sender.send(
            peer = Peer(alias = "空", did = "nobody", host = "127.0.0.1", port = 1),
            files = listOf(OutgoingFile("z.txt", 1, "text/plain", { FileInputStream(file) })),
            onProgress = { _, _, _ -> },
            onPhase = { _, _ -> },
        )
        assertEquals(SendPhase.FAILED, outcome.phase)
        assertTrue(outcome.message.isNotBlank())
    }

    // ================================================================ Discovery.probe

    /** 组播可能因为环境不可用而降级，但 start/stop 绝不允许抛异常或留线程。 */
    @Test(timeout = 60_000)
    fun discoveryStartStopIsClean() {
        val discovery = Discovery(
            device = { DeviceInfo("本机", "did-self-test", 53318) },
            onPeer = { },
            onPeerLost = { },
            log = { },
        )
        discovery.start()
        Thread.sleep(200)
        discovery.announceNow()
        discovery.stop()
        discovery.stop()

        val deadline = System.currentTimeMillis() + 5000
        var alive = engineThreads()
        while (alive.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            alive = engineThreads()
        }
        assertTrue("stop() 之后仍有线程存活：${alive.map { it.name }}", alive.isEmpty())
    }

    @Test(timeout = 60_000)
    fun discoveryProbeAddsPeer() {
        val rig = rig()
        val added = CopyOnWriteArrayList<Peer>()
        val discovery = Discovery(
            device = { DeviceInfo("本机", "did-self", 53318) },
            onPeer = { added.add(it) },
            onPeerLost = { },
        )
        try {
            val peer = discovery.probe("127.0.0.1", rig.port, 3000)
            assertNotNull(peer)
            assertEquals("测试机", peer!!.alias)
            assertEquals("did-server", peer.did)
            assertEquals(rig.port, peer.port)
            assertTrue(peer.latencyMs >= 0)
            assertEquals(1, added.size)

            // 探测一个没人监听的端口：必须超时/拒绝并返回 null，而不是抛异常
            assertNull(discovery.probe("127.0.0.1", 1, 500))
        } finally {
            discovery.stop()
        }
    }

    // ================================================================ 线程

    /** 端口被占时必须安静地返回 null（上层靠它决定换端口），绝不能抛异常。 */
    @Test(timeout = 60_000)
    fun startReturnsNullWhenPortIsBusy() {
        val busy = ServerSocket(0)
        try {
            val server = TransferServer(
                device = { DeviceInfo("测试机", "did-busy", 53318) },
                sink = { FileSink(receiveDir) },
                onOffer = { false },
                onProgress = { _, _, _ -> },
                onFileDone = { _, _ -> },
                onSessionEnd = { _, _, _ -> },
            )
            assertNull(server.start(busy.localPort))
            server.stop()
        } finally {
            runCatching { busy.close() }
        }
    }

    @Test(timeout = 60_000)
    fun stopLeavesNoEngineThreads() {
        val rig = rig()
        get("http://127.0.0.1:${rig.port}/api/v1/ping")
        rig.close()
        closeables.remove(rig)
        // 幂等：重复 stop 不应抛异常
        rig.close()

        val deadline = System.currentTimeMillis() + 5000
        var alive = engineThreads()
        while (alive.isNotEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            alive = engineThreads()
        }
        assertTrue("stop() 之后仍有线程存活：${alive.map { it.name }}", alive.isEmpty())
    }

    // ================================================================ 测试脚手架

    private data class OfferFile(
        val id: String,
        val name: String,
        val size: Long,
        val mime: String = "application/octet-stream",
    )

    private class Resp(val code: Int, val text: String) {
        val json: J get() = J.parse(text)
    }

    private class Rig(
        val server: TransferServer,
        val port: Int,
        val offerCalls: AtomicInteger,
        val offers: CopyOnWriteArrayList<IncomingOffer>,
        val sessionEnds: CopyOnWriteArrayList<Triple<String, Boolean, String>>,
        val doneFiles: CopyOnWriteArrayList<StoredFile>,
        val progress: CopyOnWriteArrayList<Long>,
    ) : AutoCloseable {
        override fun close() {
            server.stop()
        }
    }

    private fun rig(
        accepts: Boolean = true,
        directory: File = receiveDir,
        onOffer: ((IncomingOffer) -> Boolean)? = null,
    ): Rig {
        val calls = AtomicInteger()
        val offers = CopyOnWriteArrayList<IncomingOffer>()
        val ends = CopyOnWriteArrayList<Triple<String, Boolean, String>>()
        val done = CopyOnWriteArrayList<StoredFile>()
        val progress = CopyOnWriteArrayList<Long>()
        val server = TransferServer(
            device = {
                DeviceInfo(
                    alias = "测试机",
                    did = "did-server",
                    port = 53318,
                    os = "Android 14",
                    version = "1.0.0",
                )
            },
            sink = { FileSink(directory) },
            onOffer = { offer ->
                calls.incrementAndGet()
                offers.add(offer)
                onOffer?.invoke(offer) ?: accepts
            },
            onProgress = { _, _, received -> progress.add(received) },
            onFileDone = { _, stored -> done.add(stored) },
            onSessionEnd = { sid, ok, message -> ends.add(Triple(sid, ok, message)) },
            log = { },
        )
        val port = server.start(0) ?: error("TransferServer 未能在端口 0 启动")
        val result = Rig(server, port, calls, offers, ends, done, progress)
        closeables.add(result)
        return result
    }

    private fun offerJson(files: List<OfferFile>): J = J.obj(
        "sender" to J.obj(
            "alias" to J.of("发送端"),
            "did" to J.of("did-sender"),
            "port" to J.of(53318),
            "os" to J.of("Android 14"),
            "version" to J.of("1.0.0"),
        ),
        "files" to J.arr(
            files.map { file ->
                J.obj(
                    "id" to J.of(file.id),
                    "name" to J.of(file.name),
                    "size" to J.of(file.size),
                    "mime" to J.of(file.mime),
                )
            },
        ),
    )

    private fun openSession(rig: Rig, files: List<OfferFile>): Pair<String, Map<String, String>> {
        val response = postJson("http://127.0.0.1:${rig.port}/api/v1/offer", offerJson(files))
        assertEquals(200, response.code)
        val json = response.json
        assertEquals(true, json.bool("accepted"))
        val sid = json.str("sid") ?: error("应答里没有 sid")
        val tokens = HashMap<String, String>()
        for (item in json.array("files")) {
            val id = item.str("id") ?: continue
            val token = item.str("token") ?: continue
            tokens[id] = token
        }
        return sid to tokens
    }

    private fun upload(
        rig: Rig,
        sid: String,
        fid: String,
        token: String?,
        data: ByteArray?,
    ): Resp {
        val name = URLEncoder.encode("a 测试.bin", "UTF-8")
        val url = "http://127.0.0.1:${rig.port}/api/v1/upload?sid=$sid&fid=$fid&name=$name"
        val headers = if (token == null) emptyMap() else mapOf("X-Token" to token)
        return request("PUT", url, data, headers)
    }

    private fun get(url: String): Resp = request("GET", url, null, emptyMap())

    private fun postJson(url: String, body: J): Resp =
        request(
            "POST",
            url,
            body.stringify().toByteArray(Charsets.UTF_8),
            mapOf("Content-Type" to "application/json; charset=utf-8"),
        )

    private fun request(
        method: String,
        url: String,
        body: ByteArray?,
        headers: Map<String, String>,
    ): Resp {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = method
            connection.connectTimeout = 5000
            connection.readTimeout = 20_000
            connection.useCaches = false
            for ((key, value) in headers) connection.setRequestProperty(key, value)
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            return Resp(code, text)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    /** 极简裸连接：用来验证 keep-alive / 请求头 / 431 这些 HttpURLConnection 测不到的细节。 */
    private class RawConnection(port: Int) : AutoCloseable {
        private val socket = Socket("127.0.0.1", port).apply { soTimeout = 5000 }
        private val input: InputStream = socket.getInputStream()
        private val output = socket.getOutputStream()

        class Response(
            val code: Int,
            val headers: Map<String, String>,
            val body: String,
        )

        fun send(text: String) {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.flush()
        }

        fun readResponse(): Response {
            val statusLine = readLine() ?: error("连接没有返回任何响应")
            val code = statusLine.split(' ').getOrNull(1)?.toIntOrNull() ?: error("状态行异常：$statusLine")
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0) {
                    headers[line.substring(0, colon).trim().lowercase()] =
                        line.substring(colon + 1).trim()
                }
            }
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = ByteArray(length)
            var read = 0
            while (read < length) {
                val n = input.read(body, read, length - read)
                if (n < 0) break
                read += n
            }
            return Response(code, headers, String(body, 0, read, Charsets.UTF_8))
        }

        private fun readLine(): String? {
            val out = ByteArrayOutputStream()
            while (true) {
                val b = input.read()
                if (b < 0) {
                    return if (out.size() == 0) null else String(out.toByteArray(), Charsets.UTF_8)
                }
                if (b == '\n'.code) {
                    val bytes = out.toByteArray()
                    val length = if (bytes.isNotEmpty() && bytes[bytes.size - 1] == '\r'.code.toByte()) {
                        bytes.size - 1
                    } else {
                        bytes.size
                    }
                    return String(bytes, 0, length, Charsets.UTF_8)
                }
                out.write(b)
            }
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    private fun engineThreads(): List<Thread> =
        Thread.getAllStackTraces().keys.filter { it.isAlive && it.name.startsWith("qc-") }

    private fun randomBytes(size: Int): ByteArray = ByteArray(size).also { Random().nextBytes(it) }

    private fun deleteRecursively(file: File) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { deleteRecursively(it) }
        }
        runCatching { file.delete() }
    }
}
