package app.qingchuan.core

import java.io.InputStream

/** 本机对外广播的身份信息。 */
data class DeviceInfo(
    val alias: String,
    val did: String,
    val port: Int,
    val os: String = "Android",
    val version: String = BuildInfo.VERSION_NAME,
    val proto: Int = PROTO_VERSION,
) {
    fun toJson(): J = J.obj(
        "t" to J.of("announce"),
        "alias" to J.of(alias),
        "did" to J.of(did),
        "port" to J.of(port),
        "os" to J.of(os),
        "version" to J.of(version),
        "proto" to J.of(proto),
    )

    companion object {
        const val PROTO_VERSION = 1

        fun fromJson(j: J): DeviceInfo? {
            val alias = j.str("alias") ?: return null
            val did = j.str("did") ?: return null
            val port = j.int("port") ?: return null
            return DeviceInfo(
                alias = alias,
                did = did,
                port = port,
                os = j.str("os") ?: "Android",
                version = j.str("version") ?: "1.0",
                proto = j.int("proto") ?: PROTO_VERSION,
            )
        }
    }
}

/** 局域网发现到的对端。 */
data class Peer(
    val alias: String,
    val did: String,
    val host: String,
    val port: Int,
    val os: String = "Android",
    val version: String = "1.0",
    val lastSeen: Long = System.currentTimeMillis(),
    val latencyMs: Long = -1,
) {
    val addr: String get() = "$host:$port"

    fun toJson(): J = J.obj(
        "alias" to J.of(alias),
        "did" to J.of(did),
        "host" to J.of(host),
        "port" to J.of(port),
        "os" to J.of(os),
        "version" to J.of(version),
    )

    companion object {
        fun fromJson(j: J, host: String, latencyMs: Long = -1): Peer? {
            val alias = j.str("alias") ?: return null
            val did = j.str("did") ?: return null
            val port = j.int("port") ?: return null
            return Peer(
                alias = alias,
                did = did,
                host = host,
                port = port,
                os = j.str("os") ?: "Android",
                version = j.str("version") ?: "1.0",
                latencyMs = latencyMs,
            )
        }
    }
}

/** 发送方在 offer 里声明的单个文件（还没有 token）。 */
data class IncomingFile(
    val id: String,
    val name: String,
    val size: Long,
    val mime: String,
) {
    fun toJson(): J = J.obj(
        "id" to J.of(id),
        "name" to J.of(name),
        "size" to J.of(size),
        "mime" to J.of(mime),
    )

    companion object {
        fun fromJson(j: J): IncomingFile? {
            val id = j.str("id") ?: return null
            val name = j.str("name") ?: return null
            return IncomingFile(
                id = id,
                name = sanitizeName(name),
                size = j.long("size") ?: 0L,
                mime = j.str("mime") ?: "application/octet-stream",
            )
        }
    }
}

/** 收到的一次传输请求，等待本机用户确认。 */
data class IncomingOffer(
    val sessionId: String,
    val peer: Peer,
    val files: List<IncomingFile>,
) {
    val totalSize: Long get() = files.sumOf { it.size }
}

/** 本地待发送的文件；open() 每次调用返回一个新的输入流。 */
class OutgoingFile(
    val name: String,
    val size: Long,
    val mime: String,
    val open: () -> InputStream,
    /** 用于历史记录与缩略图：content:// 或 file:// 字符串，可为空。 */
    val uri: String? = null,
)

/** 已落盘的文件。 */
data class StoredFile(
    val id: String,
    val name: String,
    val uri: String,
    val size: Long,
    val mime: String,
    val time: Long = System.currentTimeMillis(),
)

enum class Direction { IN, OUT }

/** 历史记录条目（收/发都记）。 */
data class HistoryEntry(
    val id: String,
    val name: String,
    val mime: String,
    val size: Long,
    val uri: String,
    val time: Long,
    val peer: String,
    val direction: Direction,
) {
    val isImage: Boolean get() = mime.startsWith("image/")

    fun toJson(): J = J.obj(
        "id" to J.of(id),
        "name" to J.of(name),
        "mime" to J.of(mime),
        "size" to J.of(size),
        "uri" to J.of(uri),
        "time" to J.of(time),
        "peer" to J.of(peer),
        "dir" to J.of(direction.name.lowercase()),
    )

    companion object {
        fun fromJson(j: J): HistoryEntry? {
            val id = j.str("id") ?: return null
            val uri = j.str("uri") ?: return null
            return HistoryEntry(
                id = id,
                name = j.str("name") ?: "未命名",
                mime = j.str("mime") ?: "application/octet-stream",
                size = j.long("size") ?: 0L,
                uri = uri,
                time = j.long("time") ?: 0L,
                peer = j.str("peer") ?: "",
                direction = if (j.str("dir") == "out") Direction.OUT else Direction.IN,
            )
        }
    }
}

enum class StorageMode { DOWNLOAD, ROOT, APP }

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** 传输会话状态（发送侧）。 */
enum class SendPhase { IDLE, OFFERING, SENDING, DONE, FAILED, CANCELLED, DECLINED }

/** 正在进行的发送任务（UI 只读快照）。 */
data class SendTask(
    val peer: Peer,
    val fileNames: List<String>,
    val totalBytes: Long,
    val sentBytes: Long = 0,
    val currentIndex: Int = 0,
    val currentName: String = "",
    val bytesPerSecond: Long = 0,
    val phase: SendPhase = SendPhase.IDLE,
    val message: String = "",
    val startedAt: Long = System.currentTimeMillis(),
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (sentBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** 正在进行的接收任务（UI 只读快照）。 */
data class ReceiveTask(
    val sessionId: String,
    val peer: Peer,
    val fileNames: List<String>,
    val totalBytes: Long,
    val receivedBytes: Long = 0,
    val currentName: String = "",
    val currentIndex: Int = 0,
    val failed: Boolean = false,
    val done: Boolean = false,
    val message: String = "",
) {
    val progress: Float
        get() = if (totalBytes <= 0) 0f else (receivedBytes.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
}

/** 版本号来自 BuildConfig，但 core 层不引用 android 包，方便单测。 */
object BuildInfo {
    @Volatile
    var VERSION_NAME: String = "1.0.0"
}

/** 去掉路径分隔符与控制字符，避免路径穿越。 */
fun sanitizeName(raw: String): String {
    val cleaned = raw
        .replace('\\', '_')
        .replace('/', '_')
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .trim()
        .trimStart('.')
    val safe = cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    return if (safe.length > 180) safe.take(160) + safe.takeLast(16) else safe
}

/** 人类可读体积。 */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024
    var i = 0
    while (value >= 1024 && i < units.size - 1) {
        value /= 1024
        i++
    }
    return if (value >= 100) "${value.toInt()} ${units[i]}" else String.format("%.1f %s", value, units[i])
}

/** 人类可读速度。 */
fun formatSpeed(bytesPerSecond: Long): String {
    if (bytesPerSecond <= 0) return "—"
    return formatSize(bytesPerSecond) + "/s"
}
