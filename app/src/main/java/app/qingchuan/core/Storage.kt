package app.qingchuan.core

import android.content.Context
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** 一次落盘的写入句柄。 */
class WriteHandle(
    val id: String,
    val name: String,
    val mime: String,
    val declaredSize: Long,
    val uriString: String,
    val out: OutputStream,
)

/**
 * 接收文件的落盘策略。两种实现：
 *  - [MediaStoreSink]：Android 10+ 写 Download/轻传，无需任何存储权限；
 *  - [FileSink]：直接写真实目录（Android 9 及以下，或已授予「所有文件访问权限」的根目录模式）。
 */
interface StorageSink {
    /** 形如 "Download/轻传" 或 "/storage/emulated/0/轻传"，用于界面展示。 */
    val displayPath: String

    fun begin(id: String, name: String, mime: String, size: Long): WriteHandle

    fun commit(handle: WriteHandle, written: Long): StoredFile

    fun abort(handle: WriteHandle)
}

/** 直接写真实目录。 */
class FileSink(private val dir: File) : StorageSink {

    override val displayPath: String get() = dir.absolutePath

    override fun begin(id: String, name: String, mime: String, size: Long): WriteHandle {
        dir.mkdirs()
        val file = uniqueFile(dir, sanitizeName(name))
        val out = FileOutputStream(file)
        return WriteHandle(id, file.name, mime, size, "file://" + file.absolutePath, out)
    }

    override fun commit(handle: WriteHandle, written: Long): StoredFile {
        handle.out.flush()
        handle.out.close()
        val path = handle.uriString.removePrefix("file://")
        val f = File(path)
        return StoredFile(
            id = handle.id,
            name = f.name,
            uri = handle.uriString,
            size = if (written > 0) written else f.length(),
            mime = handle.mime,
        )
    }

    override fun abort(handle: WriteHandle) {
        runCatching { handle.out.close() }
        runCatching { File(handle.uriString.removePrefix("file://")).delete() }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val base: String = name.substringBeforeLast('.', name)
        val ext: String = if (name.contains('.')) "." + name.substringAfterLast('.') else ""
        var candidate = File(dir, name)
        var i = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($i)$ext")
            i++
        }
        return candidate
    }
}

/** 通过 MediaStore 写公共 Download 目录（Android 10+ 免权限）。 */
class MediaStoreSink(
    private val context: Context,
    private val relativePath: String,
) : StorageSink {

    override val displayPath: String get() = relativePath.trimEnd('/')

    override fun begin(id: String, name: String, mime: String, size: Long): WriteHandle {
        val safe = sanitizeName(name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, safe)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath.trimEnd('/'))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri: Uri = resolver.insert(collection, values)
            ?: throw IllegalStateException("无法在 $displayPath 创建文件")
        val out = resolver.openOutputStream(uri)
            ?: throw IllegalStateException("无法写入 $displayPath")
        return WriteHandle(id, safe, mime, size, uri.toString(), out)
    }

    override fun commit(handle: WriteHandle, written: Long): StoredFile {
        handle.out.flush()
        handle.out.close()
        val uri = Uri.parse(handle.uriString)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            runCatching { context.contentResolver.update(uri, values, null, null) }
        }
        var realName = handle.name
        var realSize = written
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE), null, null, null)
                ?.use { c ->
                    if (c.moveToFirst()) {
                        realName = c.getString(0) ?: handle.name
                        realSize = c.getLong(1)
                    }
                }
        }
        return StoredFile(
            id = handle.id,
            name = realName,
            uri = handle.uriString,
            size = if (realSize > 0) realSize else written,
            mime = handle.mime,
        )
    }

    override fun abort(handle: WriteHandle) {
        runCatching { handle.out.close() }
        runCatching { context.contentResolver.delete(Uri.parse(handle.uriString), null, null) }
    }
}

/** 存储位置与 URI 工具。 */
object Storage {

    const val FOLDER = "轻传"

    fun hasAllFilesAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)
        } else {
            false
        }

    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), FOLDER)

    fun downloadDir(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FOLDER)

    fun appDir(context: Context): File = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)

    /** 把用户选择的模式落到实际可用模式（无「所有文件访问权限」时根目录模式退回 Download）。 */
    fun effectiveMode(mode: StorageMode): StorageMode = when (mode) {
        StorageMode.ROOT -> if (hasAllFilesAccess()) StorageMode.ROOT else StorageMode.DOWNLOAD
        else -> mode
    }

    fun sink(context: Context, mode: StorageMode): StorageSink {
        val effective = effectiveMode(mode)
        return when (effective) {
            StorageMode.ROOT -> FileSink(rootDir())
            StorageMode.APP -> FileSink(appDir(context))
            StorageMode.DOWNLOAD ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStoreSink(context, "Download/$FOLDER/")
                } else {
                    FileSink(downloadDir())
                }
        }
    }

    fun displayPath(context: Context, mode: StorageMode): String = when (effectiveMode(mode)) {
        StorageMode.ROOT -> rootDir().absolutePath
        StorageMode.APP -> appDir(context).absolutePath
        StorageMode.DOWNLOAD -> "Download/$FOLDER"
    }

    fun openRead(context: Context, uriString: String): InputStream? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") FileInputStream(File(uri.path!!)) else context.contentResolver.openInputStream(uri)
    }.getOrNull()

    fun delete(context: Context, uriString: String): Boolean = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") File(uri.path!!).delete() else context.contentResolver.delete(uri, null, null) > 0
    }.getOrDefault(false)

    /** 分享/外部打开用：file:// 走 FileProvider，content:// 原样返回。 */
    fun shareUri(context: Context, uriString: String): Uri? = runCatching {
        val uri = Uri.parse(uriString)
        if (uri.scheme == "file") {
            FileProvider.getUriForFile(context, "app.qingchuan.fileprovider", File(uri.path!!))
        } else {
            uri
        }
    }.getOrNull()

    /** 内容 URI 的稳定主键，用于缩略图缓存文件名。 */
    fun uriKey(uriString: String): String = uriString.hashCode().toUInt().toString(16)
}
