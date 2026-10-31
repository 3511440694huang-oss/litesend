package app.qingchuan.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.LruCache
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * 图片解码 + 两级缓存（内存 LRU / 缩略图磁盘）。
 *
 * 为什么自己写：项目硬约束不新增依赖（Coil/Glide 不能用）；也不用 ImageDecoder——
 * minSdk 24 上拿不到，而且它只吃可随机访问的 File/ByteBuffer，content:// 必须先整段读进内存，更费。
 * 为什么两趟解码：先 inJustDecodeBounds 只读尺寸算出 2 的幂 inSampleSize，再真正解码，
 * 保证 8000px 的原图也只按 maxPx 分配像素，避免堆直接爆掉。
 */
object ImageStore {

    private const val THUMB_DIR = "thumbs"

    /** 超过这个数量就清掉最旧的一半，避免缩略图把 cacheDir 撑爆。 */
    private const val MAX_THUMB_FILES = 300
    private const val JPEG_QUALITY = 88

    /**
     * 上限 min(48MB, maxMemory/6)：只占 1/6 堆，连续翻大图时不会把别的对象挤到 OOM。
     * 故意不在 entryRemoved 里 recycle()：位图可能还在 Compose 的绘制队列里，回收会直接崩。
     */
    private val memory = object : LruCache<String, Bitmap>(memoryBudget()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }

    /** 同一 key 的并发请求排队等第一趟解码，不重复解。 */
    private val gates = ConcurrentHashMap<String, Mutex>()

    suspend fun thumb(context: Context, uriString: String, maxPx: Int = 384): Bitmap? {
        if (uriString.isBlank() || maxPx < 16) return null
        val key = cacheKey(uriString, maxPx)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            decodeOnce(key) {
                readThumb(context, uriString, maxPx) ?: run {
                    val bitmap = decode(context, uriString, maxPx) ?: return@decodeOnce null
                    writeThumb(context, uriString, maxPx, bitmap)
                    bitmap
                }
            }
        }
    }

    suspend fun full(context: Context, uriString: String, maxPx: Int = 2048): Bitmap? {
        if (uriString.isBlank() || maxPx < 16) return null
        val key = cacheKey(uriString, maxPx)
        memory.get(key)?.let { return it }
        return withContext(Dispatchers.IO) { decodeOnce(key) { decode(context, uriString, maxPx) } }
    }

    /** 内存吃紧（或用户切走页面）时由调用方主动释放；磁盘缩略图保留。 */
    fun clearMemory() {
        memory.evictAll()
    }

    // ---- 并发去重 ----

    private suspend fun decodeOnce(key: String, produce: suspend () -> Bitmap?): Bitmap? {
        memory.get(key)?.let { return it }
        val mine = Mutex()
        val gate = gates.putIfAbsent(key, mine) ?: mine
        try {
            return gate.withLock {
                // 等锁期间别人可能已经解完并放进内存
                memory.get(key)?.let { return@withLock it }
                currentCoroutineContext().ensureActive()
                produce()?.also { memory.put(key, it) }
            }
        } finally {
            // 只删自己放进去的那把锁，别人的留给他们自己收
            if (gate === mine) gates.remove(key, mine)
        }
    }

    // ---- 解码 ----

    private suspend fun decode(context: Context, uriString: String, maxPx: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            val stream = Storage.openRead(context, uriString) ?: return null
            stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            memory.evictAll()
            return null
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        currentCoroutineContext().ensureActive()

        return try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxPx)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val stream = Storage.openRead(context, uriString) ?: return null
            val raw = stream.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
            currentCoroutineContext().ensureActive()
            applyExif(raw, readOrientation(context, uriString))
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            // 已经按 maxPx 采样还 OOM，说明堆被别的东西占满：清内存缓存后放弃，让 UI 显示失败态
            memory.evictAll()
            null
        } catch (e: Exception) {
            null
        }
    }

    /** 每边都缩到 <= maxPx 的最小 2 的幂。 */
    private fun sampleSize(width: Int, height: Int, maxPx: Int): Int {
        var sample = 1
        while (width / sample > maxPx || height / sample > maxPx) sample = sample shl 1
        return sample
    }

    private fun readOrientation(context: Context, uriString: String): Int = runCatching {
        val stream = Storage.openRead(context, uriString) ?: return@runCatching ExifInterface.ORIENTATION_NORMAL
        stream.use {
            ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    /** 相机照片普遍只写 EXIF 不转像素，不修的话全屏里是歪的。 */
    private fun applyExif(source: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return source
        }
        val oriented = runCatching {
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull() ?: return source
        // 旋转期间两份像素同时存在，拿到新图后立刻回收原图压峰值
        if (oriented !== source) source.recycle()
        return oriented
    }

    // ---- 缩略图磁盘缓存 ----

    /**
     * 文件名 = hash(uri)+maxPx。必须带 maxPx：同一个 uri 在栅格里要 384、查看器要 2048，
     * 混用会拿到尺寸不对的图。扩展名固定 .jpg（BitmapFactory 按内容嗅探，不靠扩展名）。
     */
    private fun thumbFile(context: Context, uriString: String, maxPx: Int): File {
        val dir = File(context.cacheDir, THUMB_DIR)
        if (!dir.isDirectory) dir.mkdirs()
        return File(dir, "${Storage.uriKey(uriString)}-$maxPx.jpg")
    }

    private fun readThumb(context: Context, uriString: String, maxPx: Int): Bitmap? {
        val file = thumbFile(context, uriString, maxPx)
        if (!file.isFile) return null
        val bitmap = runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
        // 上次写到一半（进程被杀）会留下坏文件，删掉让下次重解
        if (bitmap == null) file.delete()
        return bitmap
    }

    private fun writeThumb(context: Context, uriString: String, maxPx: Int, bitmap: Bitmap) {
        runCatching {
            val file = thumbFile(context, uriString, maxPx)
            val tmp = File(file.absolutePath + ".tmp")
            FileOutputStream(tmp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            pruneThumbs(file.parentFile)
        }
        // 写缓存失败无所谓：下次重新解码即可，绝不影响主流程
    }

    private fun pruneThumbs(dir: File?) {
        val files = dir?.listFiles() ?: return
        if (files.size <= MAX_THUMB_FILES) return
        files.sortedBy { it.lastModified() }
            .take(files.size / 2)
            .forEach { runCatching { it.delete() } }
    }

    private fun cacheKey(uriString: String, maxPx: Int): String = "$uriString#$maxPx"

    private fun memoryBudget(): Int {
        val sixth = Runtime.getRuntime().maxMemory() / 6
        // 下限 6MB：小堆设备上 maxMemory/6 塞不下两三张图，缓存会完全失效
        return minOf(sixth, 48L * 1024 * 1024).coerceAtLeast(6L * 1024 * 1024).toInt()
    }
}
