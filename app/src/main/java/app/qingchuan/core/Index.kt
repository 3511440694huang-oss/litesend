package app.qingchuan.core

import android.content.Context
import java.io.File

/**
 * 传输历史（收 + 发）的本地索引。
 * 文件本体在公共目录，这里只存元数据；体积很小，整体读写，最多保留 400 条。
 */
class Index(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val lock = Any()
    private var cache: MutableList<HistoryEntry> = load()

    fun all(): List<HistoryEntry> = synchronized(lock) { cache.toList() }

    fun add(entry: HistoryEntry) {
        synchronized(lock) {
            cache.removeAll { it.id == entry.id }
            cache.add(0, entry)
            if (cache.size > MAX) cache = cache.take(MAX).toMutableList()
            persistLocked()
        }
    }

    fun addAll(entries: List<HistoryEntry>) {
        synchronized(lock) {
            entries.forEach { e ->
                cache.removeAll { it.id == e.id }
                cache.add(0, e)
            }
            if (cache.size > MAX) cache = cache.take(MAX).toMutableList()
            persistLocked()
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            cache.removeAll { it.id == id }
            persistLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            cache.clear()
            persistLocked()
        }
    }

    private fun load(): MutableList<HistoryEntry> {
        if (!file.isFile) return mutableListOf()
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            J.parse(text).array("items").mapNotNull { HistoryEntry.fromJson(it) }.toMutableList()
        }.getOrElse { mutableListOf() }
    }

    private fun persistLocked() {
        runCatching {
            val payload = J.obj("v" to J.of(1), "items" to J.arr(cache.map { it.toJson() }))
            val tmp = File(file.parentFile, "history.json.tmp")
            tmp.writeText(payload.stringify(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        }
    }

    private companion object {
        const val MAX = 400
    }
}
