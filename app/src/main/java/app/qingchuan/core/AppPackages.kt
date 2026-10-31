package app.qingchuan.core

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.text.Collator
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 本机一个已安装应用的可发送快照。
 *
 * [sizeBytes] 是 base.apk + 所有 split 的总和，只用于列表展示；真正发出去的长度
 * 由 [AppPackages.prepareForSend] 现算（列表放久了、应用被更新过，缓存数字就不准了）。
 * [apkPath] 是 [ApplicationInfo.sourceDir]，通常所有应用都可读；读不到时发送会被跳过。
 */
data class InstalledApp(
    val packageName: String,
    val label: String,
    val versionName: String,
    val sizeBytes: Long,
    val apkPath: String,
    val isSplit: Boolean,
    val icon: Drawable?,
)

/**
 * 把「本机已安装的应用」变成「可发送的文件」。
 *
 * 两条硬约束：
 * 1) 发送协议会把 Content-Length 写死成 [OutgoingFile.size]，少一个字节对面就判失败，
 *    所以 size 必须等于 open() 真正能读出的字节数（打包场景就是 zip 落盘后的长度）。
 * 2) 有 split 的应用单独发 base.apk 是装不上的（缺 split），必须把 base + splits
 *    一起打成 zip（扩展名 .apks），对面才能凑齐一整套。
 */
@Suppress("DEPRECATION") // getInstalledApplications / getPackageInfo 的旧重载只在 33+ 被标记弃用，这里要兼容 minSdk 24
object AppPackages {

    private const val MIME_APK = "application/vnd.android.package-archive"
    private const val MIME_BUNDLE = "application/octet-stream"
    private const val CACHE_DIR = "apkshare"

    /** split 应用的打包缓存：超过这个时长没再被用到就删掉，避免占着几百兆。 */
    private const val CACHE_MAX_AGE_MS = 6 * 60 * 60 * 1000L

    /** 缓存里最多留几个 .apks；一次多选发送会一次生成多个，所以不能无脑清空。 */
    private const val CACHE_MAX_FILES = 12

    /** 进程被杀留下的 .part 残片，超过这个时长才敢删（可能正有别的打包在写）。 */
    private const val PART_MAX_AGE_MS = 10 * 60 * 1000L

    private const val BUFFER = 256 * 1024

    /**
     * 读取已安装应用；默认只列用户安装的应用（FLAG_SYSTEM 为 0 的那些）。
     *
     * 内部已切到 [Dispatchers.IO]，从主线程调用也不会卡。单个包读取失败只跳过它，
     * 不会让整个列表失败。结果按中文拼音排序。
     */
    suspend fun load(context: Context, includeSystem: Boolean = false): List<InstalledApp> =
        withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val infos = runCatching { pm.getInstalledApplications(PackageManager.GET_META_DATA) }
                .getOrNull()
                .orEmpty()
            val apps = ArrayList<InstalledApp>(infos.size)
            for (info in infos) {
                val app = runCatching { describe(pm, info, includeSystem) }.getOrNull() ?: continue
                apps += app
            }
            apps.sortWith(byPinyin())
            apps
        }

    /**
     * 生成可发送的文件；APK 读不到就返回 null。
     *
     * 有 split 时会把 base + splits 打包进 cacheDir/apkshare（命中缓存则不重复打包），
     * 所以这是阻塞 IO，请在后台线程调用。
     */
    fun prepareForSend(context: Context, app: InstalledApp): OutgoingFile? {
        val base = File(app.apkPath)
        if (!base.isFile || !base.canRead()) return null
        val version = app.versionName.ifBlank { "unknown" }
        // 缓存里只有包名，拿不到 split 的真实路径；这里回查一次 PackageManager，顺便重校验。
        val splits = readableSplits(context, app.packageName).filter { it.absolutePath != base.absolutePath }

        if (splits.isEmpty()) {
            val size = runCatching { base.length() }.getOrDefault(0L)
            if (size <= 0L) return null
            return OutgoingFile(
                name = sanitizeName("${app.label}-$version.apk"),
                size = size,
                mime = MIME_APK,
                open = { FileInputStream(base) },
                uri = Uri.fromFile(base).toString(),
            )
        }

        val zip = buildBundle(context, app, base, splits) ?: return null
        return OutgoingFile(
            name = sanitizeName("${app.label}-$version.apks"),
            size = zip.length(),
            mime = MIME_BUNDLE,
            open = { FileInputStream(zip) },
            uri = Uri.fromFile(zip).toString(),
        )
    }

    // ------------------------------------------------------------------ 列表

    private fun describe(pm: PackageManager, info: ApplicationInfo, includeSystem: Boolean): InstalledApp? {
        val pkg = info.packageName
        if (pkg.isNullOrBlank()) return null
        if (!includeSystem && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) return null
        val apkPath = info.sourceDir
        if (apkPath.isNullOrBlank()) return null
        val splits = declaredSplits(info)
        val label = runCatching { pm.getApplicationLabel(info).toString() }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: pkg
        val version = runCatching { pm.getPackageInfo(pkg, 0).versionName }
            .getOrNull()
            ?.trim()
            .orEmpty()
        return InstalledApp(
            packageName = pkg,
            label = label,
            versionName = version,
            sizeBytes = totalBytes(apkPath, splits),
            apkPath = apkPath,
            isSplit = splits.isNotEmpty(),
            icon = runCatching { info.loadIcon(pm) }.getOrNull(),
        )
    }

    /** ApplicationInfo 里声明的 split 路径（去重、去掉 base 自己、跳过空值）。 */
    private fun declaredSplits(info: ApplicationInfo): List<String> {
        val dirs = runCatching { info.splitSourceDirs }.getOrNull() ?: return emptyList()
        if (dirs.isEmpty()) return emptyList()
        val base = info.sourceDir
        val out = ArrayList<String>(dirs.size)
        val seen = HashSet<String>(dirs.size)
        for (path in dirs) {
            if (path.isNullOrBlank()) continue
            if (path == base) continue
            if (!seen.add(path)) continue
            out += path
        }
        return out
    }

    private fun totalBytes(apkPath: String, splits: List<String>): Long {
        var total = lengthOrZero(apkPath)
        for (path in splits) total += lengthOrZero(path)
        return total
    }

    private fun lengthOrZero(path: String): Long =
        runCatching { File(path).length() }.getOrDefault(0L).coerceAtLeast(0L)

    /**
     * 中文按拼音、英文按字母。Collator 在老 ROM 上个别语言包可能不认拼音，
     * 这时退回忽略大小写的比较，保证顺序稳定、不会因为排序崩掉整个列表。
     */
    private fun byPinyin(): Comparator<InstalledApp> {
        val collator = runCatching { Collator.getInstance(Locale.SIMPLIFIED_CHINESE) }.getOrNull()
            ?: runCatching { Collator.getInstance(Locale.CHINA) }.getOrNull()
        if (collator == null) {
            return Comparator<InstalledApp> { a, b ->
                val r = a.label.compareTo(b.label, ignoreCase = true)
                if (r != 0) r else a.packageName.compareTo(b.packageName)
            }
        }
        collator.strength = Collator.PRIMARY
        return Comparator { a, b ->
            val result = runCatching { collator.compare(a.label, b.label) }.getOrDefault(0)
            if (result != 0) result else a.packageName.compareTo(b.packageName)
        }
    }

    // ------------------------------------------------------------------ 打包

    /** 回查包信息拿 split 路径；读不到的 split 直接丢掉，能发多少发多少。 */
    private fun readableSplits(context: Context, packageName: String): List<File> {
        if (packageName.isBlank()) return emptyList()
        val info = runCatching { context.packageManager.getApplicationInfo(packageName, 0) }.getOrNull()
            ?: return emptyList()
        val out = ArrayList<File>()
        for (path in declaredSplits(info)) {
            val file = File(path)
            val readable = runCatching { file.isFile && file.canRead() }.getOrDefault(false)
            if (readable) out += file
        }
        return out
    }

    private fun buildBundle(context: Context, app: InstalledApp, base: File, splits: List<File>): File? {
        val dir = File(context.cacheDir, CACHE_DIR)
        if (!dir.isDirectory && !dir.mkdirs()) return null
        val target = File(dir, bundleName(app))
        val sources = listOf(base) + splits
        val newest = sources.maxOfOrNull { runCatching { it.lastModified() }.getOrDefault(0L) } ?: 0L
        // 源 APK 没更新过就直接复用上次的包：同一套应用重复发不用再压一遍。
        val reusable = target.isFile && target.length() > 0L && target.lastModified() >= newest
        if (!reusable) {
            val ok = runCatching { writeZip(target, sources) }.isSuccess
            if (!ok) {
                runCatching { target.delete() }
                return null
            }
        }
        if (!target.isFile || target.length() <= 0L) return null
        trimCache(dir, target)
        return target
    }

    /** 缓存文件名带包名哈希：同名同版本的两个应用不能互相覆盖。对外的展示名另算。 */
    private fun bundleName(app: InstalledApp): String {
        val version = app.versionName.ifBlank { "unknown" }
        val tag = Integer.toHexString(app.packageName.hashCode())
        return sanitizeName("${app.label}-$version-$tag.apks")
    }

    /** 先写 .part 再改名：中途失败不会留下一个「看起来能用」的半截 zip。 */
    private fun writeZip(target: File, sources: List<File>) {
        val tmp = File(target.parentFile, target.name + ".part")
        runCatching { tmp.delete() }
        try {
            ZipOutputStream(BufferedOutputStream(tmp.outputStream(), BUFFER)).use { zos ->
                val used = HashSet<String>()
                for (file in sources) {
                    if (!file.isFile || !file.canRead()) continue
                    val entry = ZipEntry(uniqueEntry(file.name, used))
                    runCatching { entry.time = file.lastModified() }
                    zos.putNextEntry(entry)
                    FileInputStream(file).use { input -> input.copyTo(zos, BUFFER) }
                    zos.closeEntry()
                }
            }
            if (target.exists()) runCatching { target.delete() }
            if (!tmp.renameTo(target)) {
                // 极少数机型上 cacheDir 与 .part 落在不同挂载点，rename 失败，退化成复制
                target.delete()
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
        } finally {
            runCatching { if (tmp.exists()) tmp.delete() }
        }
    }

    /** base.apk / split_config.xx.apk 这些名字在同一个包里本该唯一，重了才加序号。 */
    private fun uniqueEntry(rawName: String, used: MutableSet<String>): String {
        val name = rawName.ifBlank { "apk_${used.size}.apk" }
        if (used.add(name)) return name
        val stem = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "")
        var index = 2
        while (true) {
            val candidate = if (ext.isBlank()) "${stem}_$index" else "${stem}_$index.$ext"
            if (used.add(candidate)) return candidate
            index++
        }
    }

    /** 缓存只留最近用过的几个；别的打包任务可能正在等发，所以只清太旧的和超量的。 */
    private fun trimCache(dir: File, keep: File) {
        val files = runCatching { dir.listFiles() }.getOrNull() ?: return
        val now = System.currentTimeMillis()
        val alive = ArrayList<File>()
        for (file in files) {
            if (!file.isFile || file.absolutePath == keep.absolutePath) continue
            val age = now - runCatching { file.lastModified() }.getOrDefault(0L)
            when {
                !file.name.endsWith(".apks") -> if (age > PART_MAX_AGE_MS) runCatching { file.delete() }
                age > CACHE_MAX_AGE_MS -> runCatching { file.delete() }
                else -> alive += file
            }
        }
        if (alive.size <= CACHE_MAX_FILES) return
        alive.sortedBy { runCatching { it.lastModified() }.getOrDefault(0L) }
            .take(alive.size - CACHE_MAX_FILES)
            .forEach { runCatching { it.delete() } }
    }
}
