package app.qingchuan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qingchuan.R
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.Peer
import app.qingchuan.core.ReceiveTask
import app.qingchuan.core.SendPhase
import app.qingchuan.core.Contact
import app.qingchuan.core.SendTask
import app.qingchuan.core.formatSize
import app.qingchuan.core.formatSpeed
import app.qingchuan.ui.components.Avatar
import app.qingchuan.ui.components.FileThumb
import app.qingchuan.ui.components.Mascot
import app.qingchuan.ui.components.QcGroup
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.components.SectionLabel
import app.qingchuan.ui.components.StatusPill
import app.qingchuan.ui.components.ThinProgress
import app.qingchuan.ui.theme.QcTheme

/**
 * 首页（极简版）：只保留「我是谁 / 设备列表 / 最近文件」三块。
 * 上一版的渐变主卡、贴士卡、猫娘探头都去掉了 —— 信息密度降下来才显得干净。
 */
@Composable
fun HomeScreen(
    alias: String,
    address: String,
    running: Boolean,
    peers: List<Peer>,
    sendTask: SendTask?,
    recvTask: ReceiveTask?,
    recent: List<HistoryEntry>,
    contacts: List<Contact>,
    onCopyAddress: () -> Unit,
    onManualAdd: () -> Unit,
    onSendTo: (Peer) -> Unit,
    onContact: (Contact) -> Unit,
    onOpenContacts: () -> Unit,
    onCancelSend: () -> Unit,
    onCancelReceive: () -> Unit,
    onOpenEntry: (HistoryEntry) -> Unit,
    onSeeAll: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item { Header(alias, running, address, onCopyAddress) }

        if (sendTask != null && (sendTask.phase == SendPhase.SENDING || sendTask.phase == SendPhase.OFFERING)) {
            item {
                Spacer(Modifier.height(14.dp))
                TransferRow(
                    title = "发给 ${sendTask.peer.alias}" +
                        if (sendTask.phase == SendPhase.OFFERING) "（等对方确认）" else "",
                    subtitle = "${formatSize(sendTask.sentBytes)} / ${formatSize(sendTask.totalBytes)}" +
                        if (sendTask.bytesPerSecond > 0) " · ${formatSpeed(sendTask.bytesPerSecond)}" else "",
                    progress = sendTask.progress,
                    onCancel = onCancelSend,
                )
            }
        }
        if (recvTask != null && !recvTask.done) {
            item {
                Spacer(Modifier.height(14.dp))
                TransferRow(
                    title = "接收 ${recvTask.peer.alias} 的文件",
                    subtitle = "${formatSize(recvTask.receivedBytes)} / ${formatSize(recvTask.totalBytes)} · ${recvTask.currentName}",
                    progress = recvTask.progress,
                    onCancel = onCancelReceive,
                )
            }
        }

        if (contacts.isNotEmpty()) {
            item { SectionLabel("常用对象", trailing = "管理", onTrailing = onOpenContacts) }
            item {
                QcGroup(Modifier.fillMaxWidth()) {
                    contacts.take(4).forEachIndexed { index, contact ->
                        ContactRow(contact = contact, onClick = { onContact(contact) })
                        if (index != contacts.take(4).lastIndex) RowSeparator(startInset = 74.dp)
                    }
                }
            }
        }

        item { SectionLabel("附近设备", trailing = if (peers.isEmpty()) "手动添加" else "刷新", onTrailing = onManualAdd) }

        if (peers.isEmpty()) {
            item { EmptyDevices(onManualAdd) }
        } else {
            item {
                QcGroup(Modifier.fillMaxWidth()) {
                    peers.forEachIndexed { index, peer ->
                        DeviceRow(peer = peer, onClick = { onSendTo(peer) })
                        if (index != peers.lastIndex) RowSeparator(startInset = 74.dp)
                    }
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionLabel("最近文件", trailing = "全部", onTrailing = onSeeAll) }
            item { RecentRow(recent, onOpenEntry) }
        }
    }
}

@Composable
private fun Header(alias: String, running: Boolean, address: String, onCopy: () -> Unit) {
    Column(Modifier.padding(top = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Mascot(size = 38.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "轻传",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.weight(1f))
            StatusPill(text = if (running) "在线" else "已停止", active = running)
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = address,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = QcTheme.extra.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "复制",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onCopy)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
        Text(
            text = alias,
            style = MaterialTheme.typography.bodySmall,
            color = QcTheme.extra.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DeviceRow(peer: Peer, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(peer.alias, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                peer.alias,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(peer.host)
                    if (peer.latencyMs >= 0) append(" · ").append(peer.latencyMs).append("ms")
                    if (peer.os.isNotBlank()) append(" · ").append(peer.os)
                },
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "发送",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(contact.name, size = 42.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${contact.host}:${contact.port}",
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
                maxLines = 1,
            )
        }
        Text(
            "连接",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(CircleShape).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun EmptyDevices(onManualAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        QcGroup(Modifier.fillMaxWidth(), onClick = onManualAdd) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Mascot(size = 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "还没发现设备",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "两台手机连同一个 Wi-Fi；找不到就手动输入 IP",
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textSecondary,
                    )
                }
                Icon(
                    painter = painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = QcTheme.extra.textTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun TransferRow(title: String, subtitle: String, progress: Float, onCancel: () -> Unit) {
    QcGroup(Modifier.fillMaxWidth().padding(top = 14.dp), padding = PaddingValues(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = QcTheme.extra.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                "${(progress * 100).toInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "取消",
                style = MaterialTheme.typography.labelMedium,
                color = QcTheme.extra.textSecondary,
                modifier = Modifier.clip(CircleShape).clickable(onClick = onCancel).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        ThinProgress(progress = progress)
    }
}

@Composable
private fun RecentRow(recent: List<HistoryEntry>, onOpen: (HistoryEntry) -> Unit) {
    val items = recent.take(4)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { entry ->
            Box(Modifier.weight(1f).aspectRatio(1f)) {
                FileThumb(
                    entry = entry,
                    modifier = Modifier.fillMaxSize(),
                    corner = 14.dp,
                    onClick = { onOpen(entry) },
                )
                if (!entry.isImage) {
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black.copy(alpha = 0.4f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    ) {
                        Text(
                            app.qingchuan.ui.components.extensionOf(entry.name).ifBlank { "文件" },
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                        )
                    }
                }
            }
        }
        repeat(4 - items.size) {
            Box(
                Modifier
                    .weight(1f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(QcTheme.extra.cardFill),
            )
        }
    }
}
