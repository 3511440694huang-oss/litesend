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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qingchuan.R
import app.qingchuan.core.StorageMode
import app.qingchuan.core.ThemeMode
import app.qingchuan.ui.components.KVRow
import app.qingchuan.ui.components.Mascot
import app.qingchuan.ui.components.QcGroup
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.components.SectionLabel
import app.qingchuan.ui.theme.QcTheme

/** 设置页（极简版）：分组列表，没有彩色卡片和图标墙。 */
@Composable
fun SettingsScreen(
    alias: String,
    port: Int,
    running: Boolean,
    address: String,
    storagePath: String,
    storageMode: StorageMode,
    allFilesGranted: Boolean,
    background: Boolean,
    askBeforeReceive: Boolean,
    theme: ThemeMode,
    version: String,
    onAlias: (String) -> Unit,
    onPort: (Int) -> Unit,
    onStorageMode: (StorageMode) -> Unit,
    onRequestAllFiles: () -> Unit,
    onBackground: (Boolean) -> Unit,
    onAsk: (Boolean) -> Unit,
    onTheme: (ThemeMode) -> Unit,
    onCopyAddress: () -> Unit,
    onClearHistory: (Boolean) -> Unit,
    onDiagnose: () -> Unit,
) {
    var editAlias by remember { mutableStateOf(false) }
    var editPort by remember { mutableStateOf(false) }
    var clearDialog by remember { mutableStateOf(false) }
    var aboutDialog by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 28.dp),
    ) {
        item {
            Column(Modifier.padding(top = 14.dp)) {
                Text(
                    "设置",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        item {
            SectionLabel("本机")
            QcGroup(Modifier.fillMaxWidth()) {
                KVRow("设备名称", alias, onClick = { editAlias = true })
                RowSeparator()
                KVRow("接收端口", port.toString(), onClick = { editPort = true })
                RowSeparator()
                KVRow(
                    "状态",
                    if (running) "运行中" else "已停止",
                    valueColor = if (running) QcTheme.extra.success else QcTheme.extra.warning,
                )
                RowSeparator()
                KVRow("本机地址", address, onClick = onCopyAddress)
            }
        }

        item {
            SectionLabel("保存位置", trailing = if (storageMode == StorageMode.ROOT && allFilesGranted) "根目录" else "Download")
            QcGroup(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = QcTheme.extra.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        storagePath,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                RowSeparator(startInset = 0.dp)
                StorageRow(
                    title = "Download/轻传",
                    subtitle = "推荐，不需要额外权限",
                    selected = storageMode != StorageMode.ROOT,
                ) { onStorageMode(StorageMode.DOWNLOAD) }
                RowSeparator(startInset = 0.dp)
                StorageRow(
                    title = "手机根目录/轻传",
                    subtitle = if (allFilesGranted) "与 Download 文件夹并排" else "需要「所有文件访问权限」",
                    selected = storageMode == StorageMode.ROOT && allFilesGranted,
                ) { if (allFilesGranted) onStorageMode(StorageMode.ROOT) else onRequestAllFiles() }
            }
        }

        item {
            SectionLabel("接收")
            QcGroup(Modifier.fillMaxWidth()) {
                ToggleRow("后台接收", "退出应用后仍保持在线（会常驻一条通知）", background, onBackground)
                RowSeparator()
                ToggleRow("接收前确认", "每次收到文件先问一句", askBeforeReceive, onAsk)
            }
        }

        item {
            SectionLabel("外观")
            QcGroup(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChip("跟随系统", theme == ThemeMode.SYSTEM, Modifier.weight(1f)) { onTheme(ThemeMode.SYSTEM) }
                    ThemeChip("浅色", theme == ThemeMode.LIGHT, Modifier.weight(1f)) { onTheme(ThemeMode.LIGHT) }
                    ThemeChip("深色", theme == ThemeMode.DARK, Modifier.weight(1f)) { onTheme(ThemeMode.DARK) }
                }
            }
        }

        item {
            SectionLabel("其它")
            QcGroup(Modifier.fillMaxWidth()) {
                KVRow("网络诊断", "连不上时看这里", onClick = onDiagnose)
                RowSeparator()
                KVRow("清空传输记录", "", onClick = { clearDialog = true })
                RowSeparator()
                KVRow("关于轻传", "v$version", onClick = { aboutDialog = true })
            }
        }

        item {
            Row(Modifier.fillMaxWidth().padding(top = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Mascot(size = 26.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    "局域网直传 · 不经服务器",
                    style = MaterialTheme.typography.bodySmall,
                    color = QcTheme.extra.textTertiary,
                )
            }
        }
    }

    if (editAlias) {
        InputDialog("设备名称", alias, false, "同一 Wi-Fi 下的设备会看到这个名字", { editAlias = false }) {
            onAlias(it); editAlias = false
        }
    }
    if (editPort) {
        InputDialog("接收端口", port.toString(), true, "1024 - 65535，保存后重启服务", { editPort = false }) {
            it.toIntOrNull()?.let(onPort); editPort = false
        }
    }
    if (aboutDialog) {
        AlertDialog(
            onDismissRequest = { aboutDialog = false },
            title = { Text("关于轻传") },
            text = {
                Column {
                    Text("轻传 v$version", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "局域网点对点传文件，无账号、无云端、无中转。\n协议 v1 · TCP 53318 · UDP 224.0.0.167:53318",
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textSecondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "APK 里没有自建 native 库，32 位机型也能装。",
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textTertiary,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { aboutDialog = false }) { Text("好") } },
        )
    }
    if (clearDialog) {
        var deleteFiles by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { clearDialog = false },
            title = { Text("清空传输记录") },
            text = {
                Column {
                    Text("记录清空后不能恢复。", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    ToggleRow("同时删除已接收的文件", "会从接收文件夹里真正删掉", deleteFiles) { deleteFiles = it }
                }
            },
            confirmButton = {
                TextButton(onClick = { onClearHistory(deleteFiles); clearDialog = false }) {
                    Text("清空", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { clearDialog = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChecked(!checked) }.padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
                uncheckedTrackColor = QcTheme.extra.cardFillStrong,
            ),
        )
    }
}

@Composable
private fun StorageRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun ThemeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) MaterialTheme.colorScheme.primary else QcTheme.extra.cardFillStrong)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimary else QcTheme.extra.textSecondary,
        )
    }
}

@Composable
private fun InputDialog(
    title: String,
    initial: String,
    numeric: Boolean,
    hint: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = if (numeric) v.filter { it.isDigit() }.take(5) else v.take(40) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                )
                Spacer(Modifier.height(8.dp))
                Text(hint, style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
