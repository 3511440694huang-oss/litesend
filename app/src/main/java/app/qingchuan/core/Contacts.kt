package app.qingchuan.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.UUID

/**
 * 常用传输对象：手动连过一次并保存下来的地址。
 * 只存最必要的五项，够列表展示和一次直连；其余信息（别名、延迟）连上后由 Peer 现取。
 */
data class Contact(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val lastUsedAt: Long,
) {
    /** 展示用地址。port = 0 表示「交给上层走默认端口」，此时只显示 IP，别给用户看 ":0"。 */
    val addr: String get() = if (port in 1..65535) "$host:$port" else host

    fun toJson(): J = J.obj(
        "id" to J.of(id),
        "name" to J.of(name),
        "host" to J.of(host),
        "port" to J.of(port),
        "lastUsedAt" to J.of(lastUsedAt),
    )

    companion object {
        /** 解析单条；缺 id/host、端口越界都返回 null，由调用方跳过（坏数据不能污染整个列表）。 */
        fun fromJson(j: J): Contact? {
            if (j !is J.O) return null
            val id = j.str("id")?.trim().orEmpty()
            val host = cleanHost(j.str("host").orEmpty())
            if (id.isEmpty() || host.isEmpty()) return null
            val port = j.int("port") ?: return null
            if (port !in 0..65535) return null
            return Contact(
                id = id,
                name = cleanDisplayName(j.str("name"), host),
                host = host,
                port = port,
                lastUsedAt = (j.long("lastUsedAt") ?: 0L).coerceAtLeast(0L),
            )
        }
    }
}

private const val MAX_NAME_CHARS = 24
private const val MAX_CONTACTS = 50

/** 名称清洗：去控制字符（列表是单行，换行会把行撑开）、去首尾空白；空则用 IP 兜底。 */
private fun cleanDisplayName(raw: String?, host: String): String {
    val cleaned = raw.orEmpty()
        .filter { it.code >= 0x20 && it.code != 0x7f }
        .trim()
    return clip(cleaned.ifEmpty { host.trim() }, MAX_NAME_CHARS)
}

/** 按字符截断，但不切开代理对 —— emoji 名字截到一半会变成乱码方块。 */
private fun clip(text: String, max: Int): String {
    if (text.length <= max) return text
    var end = max
    if (Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end)
}

/** 地址清洗：粘贴常带空格/换行，全部去掉；保留大小写（mDNS 名字有意义）。 */
private fun cleanHost(raw: String): String =
    raw.filterNot { it.isWhitespace() || it.code < 0x20 || it.code == 0x7f }.trim()

/**
 * 「常用对象」的本地存储：单文件 JSON，整体读、整体写，最多 50 条。
 *
 * 写盘沿用 [Index] 的做法：先写 .tmp 再 rename，写到一半被杀掉也不会留下半个文件；
 * 任何 IO/解析异常都静默吞掉 —— 这是记录用户便利性的附加数据，绝不能因为它崩主流程。
 *
 * 所有公开方法都在同一把锁里改内存 + 落盘，可以从任意线程调用（引擎的回调线程也直接调）。
 */
class ContactStore(context: Context) {

    private val file = File(context.filesDir, "contacts.json")
    private val tmp = File(context.filesDir, "contacts.json.tmp")
    private val lock = Any()

    private val _contacts = MutableStateFlow(readLocked())

    /** 按最近使用排序，界面直接 collect。 */
    val contacts: StateFlow<List<Contact>> = _contacts.asStateFlow()

    /**
     * 保存一条。同 host:port 已存在时不重复添加，只更新名字与时间并返回那条；
     * 最多保留 [MAX_CONTACTS] 条，超出丢最久没用的。
     */
    fun add(name: String, host: String, port: Int): Contact {
        val hostValue = cleanHost(host)
        val portValue = normalizePort(port)
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = _contacts.value
            val existing = current.firstOrNull { it.host.equals(hostValue, ignoreCase = true) && it.port == portValue }
            if (existing != null) {
                val updated = existing.copy(
                    // 名字留空时保留原名，别把用户改过的名字又冲回 IP
                    name = if (name.isBlank()) existing.name else cleanDisplayName(name, hostValue),
                    lastUsedAt = now,
                )
                commitLocked(ordered(listOf(updated) + current.filterNot { it.id == existing.id }))
                return updated
            }
            val contact = Contact(
                id = UUID.randomUUID().toString(),
                name = cleanDisplayName(name, hostValue),
                host = hostValue,
                port = portValue,
                lastUsedAt = now,
            )
            // 洗完之后没有地址（比如只粘了空格）：返回对象给调用方，但不落盘，
            // 免得列表里多一条「点不动的常用对象」。
            if (hostValue.isEmpty()) return contact
            commitLocked(ordered(listOf(contact) + current))
            return contact
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val current = _contacts.value
            val next = current.filterNot { it.id == id }
            if (next.size == current.size) return
            commitLocked(next)
        }
    }

    /** 改名字；空名字回落到 host（和新增时的规则一致）。时间不动，排序不受影响。 */
    fun rename(id: String, name: String) {
        synchronized(lock) {
            val current = _contacts.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val target = current[index]
            val next = current.toMutableList()
            next[index] = target.copy(name = cleanDisplayName(name, target.host))
            commitLocked(next)
        }
    }

    /** 用过一次就把时间刷新并顶到最前 —— 列表顺序 = 最近使用顺序。 */
    fun touch(id: String) {
        synchronized(lock) {
            val current = _contacts.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val touched = current[index].copy(lastUsedAt = System.currentTimeMillis())
            commitLocked(ordered(listOf(touched) + current.filterNot { it.id == id }))
        }
    }

    fun find(host: String, port: Int): Contact? {
        val hostValue = cleanHost(host)
        val portValue = normalizePort(port)
        return synchronized(lock) {
            _contacts.value.firstOrNull { it.host.equals(hostValue, ignoreCase = true) && it.port == portValue }
        }
    }

    // ---- 下面都要求已持有 lock ----

    private fun commitLocked(next: List<Contact>) {
        _contacts.value = next
        persistLocked(next)
    }

    private fun persistLocked(items: List<Contact>) {
        runCatching {
            val text = J.obj(
                "v" to J.of(1),
                "items" to J.arr(items.map { it.toJson() }),
            ).stringify()
            tmp.writeText(text, Charsets.UTF_8)
            // 先删再 rename：某些文件系统上 rename 到已存在的目标会失败
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                // rename 失败就退回直接覆盖，至少别把新数据留在 .tmp 里等下次被覆盖
                file.writeText(text, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun readLocked(): List<Contact> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val parsed = J.parse(file.readText(Charsets.UTF_8))
            val items = parsed.array("items")
            // 按 host:port 去重（手改过文件也不至于出现两行一样的），保留最近使用的那条
            val dedup = LinkedHashMap<String, Contact>()
            for (item in items) {
                val contact = Contact.fromJson(item) ?: continue
                val key = "${contact.host.lowercase()}|${contact.port}"
                val prev = dedup[key]
                if (prev == null || contact.lastUsedAt >= prev.lastUsedAt) dedup[key] = contact
            }
            ordered(dedup.values.toList())
        }.getOrElse { emptyList() }
    }

    private fun ordered(items: List<Contact>): List<Contact> =
        items.sortedByDescending { it.lastUsedAt }.take(MAX_CONTACTS)

    /** 端口只收 1..65535；其余（含空白、非数字、越界）一律记 0，由上层换成默认端口。 */
    private fun normalizePort(port: Int): Int = if (port in 1..65535) port else 0
}
