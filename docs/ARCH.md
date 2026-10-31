# 轻传 LiteSend · 架构与接口契约

> 本文件是**唯一的接口契约来源**。所有模块按此实现，不得擅自改签名；确需改动先改本文件。
> 项目根：`/storage/emulated/0/WORK/01-开发项目/litesend`

## 1. 产品

局域网（同一 Wi-Fi 下）点对点传文件，类似 LocalSend 但更轻：无账号、无云端、无中转。
特色：

1. 收/发两端自动发现（UDP 组播），也支持手动输 IP；
2. 收到的文件直接落在手机存储的独立文件夹（默认 `Download/轻传`，可选根目录 `轻传/`）；
3. **应用内图片预览**：缩略图网格 + 全屏查看器（双指缩放/双击放大/左右滑动切换）；
4. 纯 dex，无任何 native 库 → 同一 APK 同时支持 armeabi-v7a（32 位）与 arm64-v8a；
5. 接收需确认（可关），全程局域网 HTTP，明文（`usesCleartextTraffic=true`）。

## 2. 构建环境（已固化）

```bash
cd /storage/emulated/0/WORK/01-开发项目/litesend
bash build.sh :app:compileDebugKotlin    # 只过 Kotlin 编译（最快暴露错误）
bash build.sh :app:testDebugUnitTest     # JVM 单测
bash build.sh :app:assembleRelease       # release（R8 + 已签名）
```
- JDK17 / Gradle 8.11.1 / AGP 8.8.2 / Kotlin 2.0.21 / compileSdk 36 / minSdk 24 / targetSdk 34；
- **不要**在子任务中自行执行 gradle 构建（多人共用一个 build 目录会冲突），主控统一编译。

## 3. 硬约束（违反即返工）

1. `core` 包里除 `Storage.kt` / `Prefs.kt` / `ImageStore.kt` 外**禁止 import android.***；
   传输引擎必须能在 JVM 单测里裸跑（测试里用 `FileSink` + 临时目录）。
2. **不新增任何第三方依赖**（不引 okhttp/okio/gson/kotlinx-serialization/coil）。
   JSON 用 `core/Json.kt` 的 `J`；协程用 kotlinx-coroutines（已在依赖里）。
3. 只用 `minSdk 24` 可用的 API；Android 29+ 专用 API 必须 `Build.VERSION.SDK_INT` 判断。
4. 所有文本 UTF-8、中文；文件名必须经 `sanitizeName()`。
5. 代码注释用中文，只解释「为什么」，不写废话。
6. 任何会阻塞的操作（socket/文件）都要有超时，不能永久阻塞线程。
7. 日志通过构造参数传入的 `log: (String) -> Unit`，不要直接 `Log.d`（JVM 单测会崩）。

## 4. 目录

```
app/src/main/java/app/qingchuan/
  QcApp.kt  MainActivity.kt
  core/  Json.kt Model.kt Prefs.kt Storage.kt Index.kt Engine.kt
         Net.kt TransferServer.kt Discovery.kt Sender.kt ImageStore.kt   ← 待实现
  service/TransferService.kt
  ui/  theme/… components/… HomeScreen.kt FilesScreen.kt SettingsScreen.kt
       viewer/ViewerScreen.kt                                            ← 待实现
app/src/test/java/app/qingchuan/…                                        ← JVM 单测
```

## 5. 现有核心类型（已实现，勿改）

`core/Model.kt`
```kotlin
data class DeviceInfo(alias, did, port, os, version, proto)      // toJson()
data class Peer(alias, did, host, port, os, version, lastSeen, latencyMs)  // addr
data class IncomingFile(id, name, size, mime)
data class IncomingOffer(sessionId, peer, files)                // totalSize
class OutgoingFile(name, size, mime, open: () -> InputStream, uri: String?)
data class StoredFile(id, name, uri, size, mime, time)
data class HistoryEntry(id, name, mime, size, uri, time, peer, direction)
enum class Direction { IN, OUT } / StorageMode / ThemeMode / SendPhase
data class SendTask(...) / ReceiveTask(...)
fun sanitizeName(raw: String): String
fun formatSize(bytes: Long): String ; fun formatSpeed(bps: Long): String
object BuildInfo { var VERSION_NAME }
```
`core/Json.kt`：`sealed class J`，`J.parse(text)`、`j.str("k")`、`j.long("k")`、`j.int`、`j.bool`、`j.array("k")`、`j.obj("k")`、
`J.of(String/Long/Int/Boolean)`、`J.obj("k" to J.of(v), …)`、`J.arr(list)`、`j.stringify()`。

`core/Storage.kt`
```kotlin
class WriteHandle(id, name, mime, declaredSize, uriString, out: OutputStream)
interface StorageSink { val displayPath: String
  fun begin(id: String, name: String, mime: String, size: Long): WriteHandle
  fun commit(handle: WriteHandle, written: Long): StoredFile
  fun abort(handle: WriteHandle) }
class FileSink(dir: File) : StorageSink
class MediaStoreSink(context, relativePath) : StorageSink
object Storage { const FOLDER="轻传"; hasAllFilesAccess(); rootDir(); downloadDir(); appDir(ctx);
  effectiveMode(mode); sink(ctx, mode); displayPath(ctx, mode); openRead(ctx, uri); delete(ctx, uri);
  shareUri(ctx, uri); uriKey(uri) }
```

## 6. 局域网协议 v1

端口：HTTP **TCP 53318**（设置可改；被占用时主控依次尝试 53318→53330，用实际绑定端口广播）。
组播：**UDP 224.0.0.167:53318**（与 LocalSend 的 53317 区分，可共存）。

### 6.1 发现报文（UDP，JSON，<1200 字节）

```json
{"t":"announce","alias":"X","did":"uuid","port":53318,"os":"Android 14","version":"1.0.0","proto":1}
{"t":"discover"}
```
- 启动立即 `announce`，之后每 **3s** 一次（Socket 复用同一个 multicast Socket，`setTimeToLive(1)`）；
- 收到别的设备 `announce` → 若该 did 不在表里，随机 200~800ms 后**单播**回一次 `announce`；已知设备只刷新；
- 收到 `discover` → 250ms 内单播回 `announce`；
- 对端 host 用报文源 IP；`port` 字段缺省时用源端口；
- 忽略 `did == 自己` 的报文；**12s** 未刷新 → `onPeerLost(did)`；
- 需要 `MulticastLock`（Android 侧主控负责），纯 Java 侧只用 `MulticastSocket`。

### 6.2 HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/info` | `{"app":"qingchuan","alias","did","port","os","version","proto":1}` |
| GET | `/api/v1/ping` | `{"ok":true}` |
| POST | `/api/v1/offer` | 见下 |
| PUT | `/api/v1/upload?sid=&fid=` | 原始字节，头 `X-Token` |
| POST | `/api/v1/complete` | `{"sid":"..."}` → `{"ok":true}` |
| POST | `/api/v1/cancel` | `{"sid":"..."}` → `{"ok":true}` |

**offer** 请求：
```json
{"sender":{"alias":"A","did":"x","port":53318},
 "files":[{"id":"f1","name":"a.jpg","size":123,"mime":"image/jpeg"}]}
```
响应（服务端最多等用户确认 **90s**）：
```json
{"accepted":true,"sid":"s_ab12","files":[{"id":"f1","token":"hex32"}]}
{"accepted":false,"reason":"declined|timeout|busy"}
```
限制：文件数 1..200、单文件 ≤8GiB、总计 ≤16GiB，超限 `400 {"error":"limit","message":"..."}`。
同一个 sid 只接受一次 offer；token 每文件独立，缺/错 → `403 {"error":"bad_token"}`。

**upload**：
- 头 `X-Token` 必须匹配；`Content-Length` 必须存在且等于 offer 里的 size，否则 `409 {"error":"bad_size"}`；
- 服务端按 sid 取 sink，`begin()` 拿 `WriteHandle`，流式写 `handle.out`，每 64KB 回一次进度；
- 成功 `{"ok":true,"name":"最终文件名","saved":字节数}`，并回报 `StoredFile`（sink.commit）；
- 写失败 `500 {"error":"io","message":"..."}` 并 `sink.abort()`。

**complete**：`{"ok":true}`，服务端回 `onSessionEnd(sid, true, "ok")` 并清理会话。
**cancel**：服务端 `abort` 未完成的写入、清理会话、`onSessionEnd(sid,false,reason)`。

错误统一：`4xx/5xx` + `{"error":"code","message":"可读中文"}`。
未知路径 `404 {"error":"not_found"}`。

### 6.3 发送流程（客户端）

1. `POST /api/v1/offer`（发送端读超时 **95s**；接收端等用户确认 90s，应用内确认窗口 85s）→ 被拒则 `SendPhase.DECLINED`；
2. 对每个 accepted 文件 `PUT /api/v1/upload`，读本地流 → 写 socket，按 64KB 计进度并节流 120ms；
3. 全部完成 `POST /api/v1/complete` → `SendPhase.DONE`；
4. 任意失败/取消 → `POST /api/v1/cancel` 后返回 `FAILED/CANCELLED`。

## 7. 待实现模块（子任务）

### 7.1 `core/Net.kt` — 极简 HTTP 服务端 + 客户端工具

```kotlin
class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,     // key 小写
    val body: InputStream,                // 长度 = contentLength，必须读完或由框架 drain
    val contentLength: Long,              // 缺失 = -1
    val remoteHost: String,
)
class HttpResponse(val status: Int, val body: ByteArray, val contentType: String = "application/json; charset=utf-8") {
    companion object {
        fun json(status: Int, j: J): HttpResponse
        fun text(status: Int, s: String): HttpResponse
        fun error(status: Int, code: String, message: String): HttpResponse
    }
}

class HttpServer(private val port: Int, private val log: (String) -> Unit = {}) {
    fun setHandler(handler: (HttpRequest) -> HttpResponse)
    fun start(): Boolean          // 绑定成功 true；失败 false（不抛异常）
    fun boundPort(): Int          // start 后有效
    fun stop()                    // 关闭 serverSocket 与所有连接，可重复调用
}
```
要求：HTTP/1.1；支持 keep-alive（同连接最多 8 个请求 / 空闲 15s 关闭）与 `Connection: close`；
每连接一线程（有界池 16）；请求头最大 16KB、超限 431；**必须**在响应前把未读 body drain 掉；
`PUT/POST` 无 body 时 body 为空流；`stop()` 后不得再有线程泄漏（daemon 线程）。

### 7.2 `core/TransferServer.kt` — 接收端

```kotlin
class TransferServer(
    private val device: () -> DeviceInfo,
    private val sink: () -> StorageSink,                                   // 每个会话取一个
    private val onOffer: (IncomingOffer) -> Boolean,                       // 阻塞，UI 确认；超时由服务端兜底
    private val onProgress: (sessionId: String, file: IncomingFile, received: Long) -> Unit,
    private val onFileDone: (sessionId: String, file: StoredFile) -> Unit,
    private val onSessionEnd: (sessionId: String, ok: Boolean, message: String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun start(requestedPort: Int): Int?    // 成功=实际端口，失败=null
    fun stop()
}
```

### 7.3 `core/Discovery.kt` — 发现

```kotlin
class Discovery(
    private val device: () -> DeviceInfo,
    private val onPeer: (Peer) -> Unit,
    private val onPeerLost: (did: String) -> Unit,
    private val log: (String) -> Unit = {},
) {
    fun start()                       // 加入组播 + 启动 announce 循环 + 过期清理
    fun stop()
    fun announceNow()
    fun probe(host: String, port: Int, timeoutMs: Int = 3000): Peer?   // GET /api/v1/info，手动添加设备
}
```
注：UDP 绑定 53318 失败时允许退化（只发不收），并 log。

### 7.4 `core/Sender.kt` — 发送端

```kotlin
data class SendOutcome(val phase: SendPhase, val sentBytes: Long, val message: String)

class Sender(private val log: (String) -> Unit = {}) {
    /** 阻塞执行；调用方放在 Dispatchers.IO。 */
    fun send(
        peer: Peer,
        files: List<OutgoingFile>,
        onProgress: (index: Int, sentForThisFile: Long, sentTotal: Long) -> Unit,
        onPhase: (SendPhase, String) -> Unit,
        isCancelled: () -> Boolean = { false },
    ): SendOutcome
}
```
超时：连接 5s、读 30s、写 60s。进度节流 120ms。取消时尽力发 cancel 并关连接。

### 7.5 单测（必须写，JVM 可跑，`junit:junit:4.13.2` 已就位）

`app/src/test/java/app/qingchuan/`：
- `JsonTest`：转义/嵌套/数字/非法输入；
- `TransferServerTest`：临时目录 `FileSink`，本机 127.0.0.1 起服务，覆盖 info / offer-接受 / offer-拒绝 / offer-超限 /
  upload 正常（二进制逐字节比对）/ bad_token / bad_size / complete / cancel / 404；
- `SenderTest`：`TransferServer` + `Sender` 端到端（含中文文件名、2MB 随机字节）；
- 测试用 `System.getProperty("java.io.tmpdir")` 下建临时目录，tearDown 删除。

### 7.6 `core/ImageStore.kt` + `ui/viewer/ViewerScreen.kt`

见子任务 S2 的独立说明（契约同样以本文件为准）：
```kotlin
object ImageStore {
    suspend fun thumb(context: Context, uriString: String, maxPx: Int = 384): Bitmap?
    suspend fun full(context: Context, uriString: String, maxPx: Int = 2048): Bitmap?
    fun clearMemory()
}

@Composable
fun ImageViewerScreen(
    entries: List<HistoryEntry>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onOpenExternal: (HistoryEntry) -> Unit,
)
```
栅格/查看器统一用 `QcTheme.extra`（`ui/theme/Theme.kt`）取扩展色。
