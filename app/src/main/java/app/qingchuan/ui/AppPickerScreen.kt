package app.qingchuan.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import app.qingchuan.core.InstalledApp
import app.qingchuan.core.formatSize
import app.qingchuan.ui.components.Mascot
import app.qingchuan.ui.components.PillButton
import app.qingchuan.ui.components.RowSeparator
import app.qingchuan.ui.theme.QcTheme

/**
 * 选择应用（发安装包）：满屏浮层，自带不透明底色，盖住底下的 tab 页。
 *
 * 只负责「选」：搜索框与选中圈都是本地状态，真正的提取 APK / 打包交给 onConfirm，
 * 因为那一步要读几百兆文件，不能在 UI 线程里做。
 */
@Composable
fun AppPickerScreen(
    apps: List<InstalledApp>,
    loading: Boolean,
    selected: Set<String>,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onClose: () -> Unit,
) {
    val extra = QcTheme.extra
    var query by rememberSaveable { mutableStateOf("") }
    val keyword = query.trim()
    val filtered = remember(apps, keyword) {
        if (keyword.isEmpty()) {
            apps
        } else {
            apps.filter {
                it.label.contains(keyword, ignoreCase = true) ||
                    it.packageName.contains(keyword, ignoreCase = true)
            }
        }
    }
    val listState = rememberLazyListState()
    // 换关键词后回到顶部：否则列表停在上次的位置，看起来像「没搜到」
    LaunchedEffect(keyword) {
        if (listState.firstVisibleItemIndex > 0) runCatching { listState.scrollToItem(0) }
    }

    Column(
        // 它是盖在整页之上的浮层，必须自带不透明底色，否则两层页面会叠在一起
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "选择应用",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f),
            )
            Text(
                "完成",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onClose)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        SearchField(query = query, onChange = { query = it })

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> LoadingApps(Modifier.align(Alignment.Center))
                filtered.isEmpty() -> NoApps(keyword = keyword, modifier = Modifier.align(Alignment.Center))
                else -> AppList(
                    apps = filtered,
                    selected = selected,
                    listState = listState,
                    onToggle = onToggle,
                )
            }
        }

        RowSeparator(startInset = 0.dp)
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "已选 ${selected.size} 个",
                style = MaterialTheme.typography.bodyMedium,
                color = if (selected.isEmpty()) extra.textTertiary else extra.textSecondary,
                modifier = Modifier.weight(1f),
            )
            PillButton(text = "发送", onClick = onConfirm, enabled = selected.isNotEmpty())
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    val extra = QcTheme.extra
    val shape = RoundedCornerShape(12.dp)
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        // 底色用分组灰、形状交给 OutlinedTextField 自己画：焦点边框是主题的珊瑚色
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 20.dp, top = 14.dp)
            .background(extra.cardFill, shape),
        textStyle = MaterialTheme.typography.bodyMedium,
        placeholder = {
            Text(
                "搜索应用",
                style = MaterialTheme.typography.bodyMedium,
                color = extra.textTertiary,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = extra.textTertiary,
                modifier = Modifier.size(18.dp),
            )
        },
        singleLine = true,
        shape = shape,
    )
}

/** 列表做成「一整块分组」：首行圆上角、末行圆下角，中间用发丝线隔开。 */
@Composable
private fun AppList(
    apps: List<InstalledApp>,
    selected: Set<String>,
    listState: LazyListState,
    onToggle: (String) -> Unit,
) {
    val fill = QcTheme.extra.cardFill
    val last = apps.lastIndex
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp),
    ) {
        itemsIndexed(apps, key = { _, app -> app.packageName }) { index, app ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(rowShape(index, last))
                    .background(fill),
            ) {
                AppRow(
                    app = app,
                    checked = app.packageName in selected,
                    onToggle = { onToggle(app.packageName) },
                )
                if (index != last) RowSeparator(startInset = 74.dp)
            }
        }
    }
}

private fun rowShape(index: Int, lastIndex: Int): RoundedCornerShape {
    val corner = 18.dp
    return when {
        lastIndex <= 0 -> RoundedCornerShape(corner)
        index == 0 -> RoundedCornerShape(topStart = corner, topEnd = corner)
        index == lastIndex -> RoundedCornerShape(bottomStart = corner, bottomEnd = corner)
        else -> RoundedCornerShape(0.dp)
    }
}

@Composable
private fun AppRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    val extra = QcTheme.extra
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                app.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${app.packageName} · ${formatSize(app.sizeBytes)}" +
                    if (app.isSplit) " · 分发包（打包成 .apks）" else "",
                style = MaterialTheme.typography.bodySmall,
                color = extra.textSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectionMark(checked)
    }
}

/** 图标按需转 bitmap；取不到（或不是 Drawable）时退回首字母块，不让整行塌掉。 */
@Composable
private fun AppIcon(app: InstalledApp) {
    val shape = RoundedCornerShape(13.dp)
    val bitmap: ImageBitmap? = remember(app.packageName, app.icon) {
        runCatching { app.icon?.toBitmap(96, 96)?.asImageBitmap() }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(48.dp).clip(shape),
        )
    } else {
        Box(
            Modifier.size(48.dp).clip(shape).background(QcTheme.extra.cardFillStrong),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = QcTheme.extra.textSecondary,
            )
        }
    }
}

/** 未选：空心圆描边；选中：珊瑚色实心圆 + 白色对勾。 */
@Composable
private fun SelectionMark(checked: Boolean) {
    val base = Modifier.size(22.dp)
    Box(
        modifier = if (checked) {
            base.background(MaterialTheme.colorScheme.primary, CircleShape)
        } else {
            base.border(1.5.dp, QcTheme.extra.textTertiary, CircleShape)
        },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "已选",
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun LoadingApps(modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(
            modifier = Modifier.size(26.dp),
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 2.5.dp,
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "正在读取应用…",
            style = MaterialTheme.typography.bodySmall,
            color = QcTheme.extra.textSecondary,
        )
    }
}

@Composable
private fun NoApps(keyword: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Mascot(76.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            "没有找到应用",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (keyword.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                "换个关键词试试",
                style = MaterialTheme.typography.bodySmall,
                color = QcTheme.extra.textSecondary,
            )
        }
    }
}
