package app.qingchuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.qingchuan.core.formatSize
import app.qingchuan.ui.components.KVRow
import app.qingchuan.ui.components.QcGroup
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.components.SectionLabel
import app.qingchuan.ui.theme.QcTheme

/**
 * 网络诊断：把「为什么连不上」拆成几条能自己看懂的判断。
 * 这些数据都是本机真实测量出来的，不猜。
 */
@Composable
fun DiagnosticsScreen(
    address: String,
    port: Int,
    running: Boolean,
    gateway: String?,
    packets: Long,
    lastPacketAt: Long,
    peersCount: Int,
    discoveryMode: String,
    onBack: () -> Unit,
    onTest: () -> Unit,
) {
    val now = System.currentTimeMillis()
    val silentFor = if (lastPacketAt > 0) (now - lastPacketAt) / 1000 else -1
    val receivedBroadcast = lastPacketAt > 0

    LazyColumn(
        // 它是盖在整页之上的浮层，必须自带不透明底色，否则两层页面会叠在一起
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "网络诊断",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "完成",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onBack).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        item {
            SectionLabel("本机")
            QcGroup(Modifier.fillMaxWidth()) {
                KVRow("接收服务", if (running) "运行中" else "未启动", valueColor = if (running) QcTheme.extra.success else QcTheme.extra.warning)
                RowSeparator()
                KVRow("本机地址", address)
                RowSeparator()
                KVRow("端口", port.toString())
                RowSeparator()
                KVRow("网关", gateway ?: "未获取到")
                RowSeparator()
                KVRow("发现通道", discoveryMode)
                RowSeparator()
                KVRow("收到广播", if (receivedBroadcast) "${packets} 条 / ${silentFor}s 前" else "0 条")
                RowSeparator()
                KVRow("附近设备", "${peersCount} 台")
            }
        }

        item {
            SectionLabel("判断")
            QcGroup(Modifier.fillMaxWidth(), padding = PaddingValues(16.dp)) {
                Text(
                    verdict(running, receivedBroadcast, silentFor, peersCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        item {
            SectionLabel("连不上时按顺序排查")
            QcGroup(Modifier.fillMaxWidth()) {
                TipRow("1", "关掉「AP 隔离 / 客户端隔离 / 访客网络」", "路由器管理页里的安全设置；访客 Wi-Fi 默认就互相隔离，两台设备要连同一个主 Wi-Fi。")
                RowSeparator(startInset = 44.dp)
                TipRow("2", "确认两个设备在同一频段", "Wi-Fi 7 的新设备常在 5G/6G 频段、老设备在 2.4G；某些路由器的 6GHz 或「IoT 网络」跟主网络是隔离的。")
                RowSeparator(startInset = 44.dp)
                TipRow("3", "关掉 VPN、加速器和代理", "手机上的 VPN/游戏加速器会把局域网流量也接管，导致同网段不通。")
                RowSeparator(startInset = 44.dp)
                TipRow("4", "试试用热点直连", "其中一台手机开热点、另一台连上，两台设备就处在完全没有隔离的网络里，传输一定可用。")
                RowSeparator(startInset = 44.dp)
                TipRow("5", "手动输入 IP 再试一次", "只填 IP 即可，端口会自动找。")
            }
        }

        item {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onTest)
                    .padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text("输入对方 IP 测试连接", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun TipRow(index: String, title: String, body: String) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(QcTheme.extra.cardFillStrong),
            contentAlignment = Alignment.Center,
        ) {
            Text(index, style = MaterialTheme.typography.labelSmall, color = QcTheme.extra.textSecondary)
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(body, style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textSecondary)
        }
    }
}

private fun verdict(running: Boolean, received: Boolean, silentFor: Long, peers: Int): String = when {
    !running -> "接收服务没起来：先回到首页等状态变成「在线」，或换一个端口。"
    peers > 0 -> "已经发现 $peers 台设备，网络互通正常。如果传输失败，多半是对端没点「接收」。"
    !received -> "一条局域网广播都收不到。最常见的原因是路由器开了 AP 隔离/访客网络，或两台设备在不同网段。"
    silentFor >= 0 && silentFor < 30 -> "能收到广播，但列表里还没有设备：对端可能还没打开轻传，或它的广播被拦住了。"
    else -> "曾经收到过广播，但最近 ${silentFor}s 没有新的：对端可能退出了应用或掉线了。"
}
