package app.qingchuan.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.qingchuan.MainActivity
import app.qingchuan.QcApp
import app.qingchuan.R
import app.qingchuan.core.Engine
import app.qingchuan.core.IncomingOffer
import app.qingchuan.core.ReceiveTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 前台服务：让接收服务在应用退到后台后仍然在线。
 * 通知本身也是 Android 对后台常驻的硬性要求，顺带把「正在接收 xx%」显示在通知栏。
 */
class TransferService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 先 startForeground 再起引擎：startForegroundService 有 5s 硬性期限，
        // 万一引擎初始化遇到异常情况，也不能因为「没及时挂通知」被系统判 ANR。
        startForeground(NOTIF_ID, buildNotification("正在等待接收", "正在启动接收服务…"))
        Engine.init(applicationContext, (application as QcApp).prefs)
        Engine.start()
        observe()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Engine.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // 这里刻意不调用 Engine.stop()：关掉「后台接收」时应用可能仍在前台，
        // 引擎由 MainActivity.onStop 按前后台状态统一收尾，避免竞态把服务停了。
        scope.cancel()
        super.onDestroy()
    }

    private fun observe() {
        scope.launch {
            combine(
                Engine.running,
                Engine.localPort,
                Engine.recvTask,
                Engine.pendingOffer,
            ) { running, port, recv, offer -> Quad(running, port, recv, offer) }
                .collect { q ->
                    val (title, text) = describe(q.running, q.port, q.recv, q.offer)
                    getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID, buildNotification(title, text))
                }
        }
    }

    private data class Quad(
        val running: Boolean,
        val port: Int,
        val recv: ReceiveTask?,
        val offer: IncomingOffer?,
    )

    private fun describe(running: Boolean, port: Int, recv: ReceiveTask?, offer: IncomingOffer?): Pair<String, String> {
        offer?.let { return "收到传输请求" to "${it.peer.alias} 想发送 ${it.files.size} 个文件" }
        recv?.let {
            if (!it.done) {
                val pct = (it.progress * 100).toInt()
                return "正在接收 $pct%" to it.currentName
            }
            return (if (it.failed) "接收已中断" else "接收完成") to it.message
        }
        if (!running) return "接收服务已停止" to "打开轻传重新开始"
        return "轻传正在等待接收" to statusText(port)
    }

    private fun statusText(port: Int = Engine.localPort.value): String {
        val ip = Engine.localIp.value
        return when {
            ip != null && port > 0 -> "$ip:$port"
            port > 0 -> "端口 $port"
            else -> "点按返回应用"
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_service_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_service_desc)
            setShowBadge(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "qc_service"
        private const val NOTIF_ID = 4301

        fun start(context: Context) {
            val intent = Intent(context, TransferService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TransferService::class.java))
        }
    }
}
