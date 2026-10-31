package app.qingchuan.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.Storage

/** 分享 / 用其它应用打开 / 复制文本。全部做好异常兜底，失败给中文提示。 */
object Intents {

    fun share(context: Context, entry: HistoryEntry): Boolean {
        val uri = Storage.shareUri(context, entry.uri) ?: return toast(context, "文件已不存在")
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = entry.mime.ifBlank { "application/octet-stream" }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, entry.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, Intent.createChooser(intent, "分享 ${entry.name}"), "没有可用的分享应用")
    }

    fun open(context: Context, entry: HistoryEntry): Boolean {
        val uri = Storage.shareUri(context, entry.uri) ?: return toast(context, "文件已不存在")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, entry.mime.ifBlank { "*/*" })
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return start(context, intent, "没有能打开该文件的应用")
    }

    fun copy(context: Context, text: String, label: String = "已复制") {
        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
        cm?.setPrimaryClip(android.content.ClipData.newPlainText("qingchuan", text))
        toast(context, label)
    }

    fun openUrl(context: Context, url: String) {
        start(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "没有可用的浏览器")
    }

    private fun start(context: Context, intent: Intent, failText: String): Boolean = try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        toast(context, failText)
        false
    } catch (_: Exception) {
        toast(context, failText)
        false
    }

    private fun toast(context: Context, text: String): Boolean {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        return false
    }
}
