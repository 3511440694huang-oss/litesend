package app.qingchuan.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.qingchuan.R
import app.qingchuan.core.Direction
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.formatSize
import app.qingchuan.ui.components.FileGlyph
import app.qingchuan.ui.components.FileThumb
import app.qingchuan.ui.components.Mascot
import app.qingchuan.ui.components.QcGroup
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.components.SectionLabel
import app.qingchuan.ui.components.relativeTime
import app.qingchuan.ui.theme.QcTheme

private enum class FileFilter(val label: String) {
    ALL("全部"), IN("收到"), OUT("发出"), IMAGE("图片")
}

/** 文件页（极简版）：一个路径条 + 筛选 + 图片网格 + 文件行。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun FilesScreen(
    entries: List<HistoryEntry>,
    storagePath: String,
    running: Boolean,
    onOpenImage: (HistoryEntry) -> Unit,
    onOpenFile: (HistoryEntry) -> Unit,
    onShare: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onCopyPath: () -> Unit,
    onPickFiles: () -> Unit,
    onSendApp: () -> Unit,
) {
    var filter by remember { mutableStateOf(FileFilter.ALL) }
    val filtered = remember(entries, filter) {
        when (filter) {
            FileFilter.ALL -> entries
            FileFilter.IN -> entries.filter { it.direction == Direction.IN }
            FileFilter.OUT -> entries.filter { it.direction == Direction.OUT }
            FileFilter.IMAGE -> entries.filter { it.isImage }
        }
    }
    val images = filtered.filter { it.isImage }
    val others = filtered.filterNot { it.isImage }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "文件",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Text(
                        "${entries.size} 个文件",
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textSecondary,
                    )
                }
                Text(
                    "发应用",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onSendApp).padding(horizontal = 10.dp, vertical = 6.dp),
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    "发文件",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).clickable(onClick = onPickFiles).padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }

        item {
            QcGroup(Modifier.fillMaxWidth().padding(top = 14.dp), onClick = onCopyPath) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = null,
                        tint = QcTheme.extra.textSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            storagePath,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "接收文件夹 · 点按复制路径",
                            style = MaterialTheme.typography.bodySmall,
                            color = QcTheme.extra.textTertiary,
                        )
                    }
                }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 16.dp, start = 4.dp)) {
                FileFilter.values().forEach { f ->
                    val selected = filter == f
                    Text(
                        f.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onBackground else QcTheme.extra.textTertiary,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.clickable { filter = f },
                    )
                }
            }
        }

        if (filtered.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Mascot(size = 76.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(
                        if (entries.isEmpty()) "还没有文件" else "这个分类下没有文件",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (entries.isEmpty()) "收到或发出的文件都会出现在这里" else "换一个分类看看",
                        style = MaterialTheme.typography.bodySmall,
                        color = QcTheme.extra.textSecondary,
                    )
                }
            }
        }

        if (images.isNotEmpty()) {
            item { SectionLabel("图片", trailing = "点开可全屏预览") }
            items(images.chunked(3)) { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { entry ->
                        var menu by remember(entry.id) { mutableStateOf(false) }
                        Box(Modifier.weight(1f).aspectRatio(1f)) {
                            FileThumb(
                                entry = entry,
                                modifier = Modifier.fillMaxSize().combinedClickable(
                                    onClick = { onOpenImage(entry) },
                                    onLongClick = { menu = true },
                                ),
                                corner = 12.dp,
                            )
                            EntryMenu(menu, { menu = false }, entry, onShare, onDelete, onOpenFile)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        if (others.isNotEmpty()) {
            item { SectionLabel(if (images.isEmpty()) "文件" else "其它文件") }
            item {
                QcGroup(Modifier.fillMaxWidth()) {
                    others.forEachIndexed { index, entry ->
                        FileRow(entry, onOpenFile, onShare, onDelete)
                        if (index != others.lastIndex) RowSeparator(startInset = 76.dp)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    entry: HistoryEntry,
    onOpen: (HistoryEntry) -> Unit,
    onShare: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    var menu by remember(entry.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(entry) }.padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp)).background(QcTheme.extra.cardFillStrong),
            contentAlignment = Alignment.Center,
        ) {
            FileGlyph(entry.mime, Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    append(formatSize(entry.size))
                    append(" · ")
                    append(if (entry.direction == Direction.IN) entry.peer.ifBlank { "未知设备" } else "发给 " + entry.peer.ifBlank { "未知设备" })
                    append(" · ")
                    append(relativeTime(entry.time))
                },
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            Icon(
                Icons.Filled.MoreVert,
                contentDescription = "更多",
                tint = QcTheme.extra.textTertiary,
                modifier = Modifier.size(20.dp).clip(CircleShape).clickable { menu = true }.padding(2.dp),
            )
            EntryMenu(menu, { menu = false }, entry, onShare, onDelete, onOpen)
        }
    }
}

@Composable
private fun EntryMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    entry: HistoryEntry,
    onShare: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onOpen: (HistoryEntry) -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(text = { Text("打开 / 查看") }, onClick = { onDismiss(); onOpen(entry) })
        DropdownMenuItem(
            text = { Text("分享") },
            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
            onClick = { onDismiss(); onShare(entry) },
        )
        DropdownMenuItem(
            text = { Text("删除", color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = { onDismiss(); onDelete(entry) },
        )
    }
}
