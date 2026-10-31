package app.qingchuan.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream

/** SAF 选中的 Uri → 可发送文件。 */
object Uris {

    /** 长期持有读取权限，历史记录里的「发出」条目才能在重启后继续预览。 */
    fun persist(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /**
     * 读取文件名/大小/类型。
     * 少数文件提供方拿不到 SIZE，此时把内容复制到缓存里换取准确长度 —— 协议要求
     * Content-Length 与 offer 声明一致，长度不准会让接收端直接拒收。
     */
    fun describe(context: Context, uri: Uri): OutgoingFile? {
        var name: String? = null
        var size = -1L
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null, null, null,
            )?.use { c ->
                if (c.moveToFirst()) {
                    if (!c.isNull(0)) name = c.getString(0)
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
        }
        if (size <= 0) {
            size = runCatching {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
            }.getOrDefault(-1L)
        }
        val display = sanitizeName(name ?: "文件_${System.currentTimeMillis()}")
        val mime = context.contentResolver.getType(uri) ?: mimeOfName(display)
        if (size <= 0) {
            val cached = copyToCache(context, uri, display) ?: return null
            return OutgoingFile(display, cached.length(), mime, { FileInputStream(cached) }, uri.toString())
        }
        return OutgoingFile(
            name = display,
            size = size,
            mime = mime,
            open = {
                context.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("无法读取该文件")
            },
            uri = uri.toString(),
        )
    }

    fun mimeOfName(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isBlank()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
    }

    private fun copyToCache(context: Context, uri: Uri, name: String): File? = runCatching {
        val dir = File(context.cacheDir, "outbox").apply { mkdirs() }
        val out = File(dir, "${System.currentTimeMillis()}_$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
        } ?: return null
        out
    }.getOrNull()
}
