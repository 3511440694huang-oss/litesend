package app.qingchuan.ui.viewer

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.qingchuan.core.Direction
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.ImageStore
import app.qingchuan.core.formatSize
import app.qingchuan.ui.theme.QcColors
import app.qingchuan.ui.theme.QcTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * 全屏图片查看器。
 *
 * 为什么自己跑指针循环而不用 detectTransformGestures：那个检测器越过 touchSlop 后会无条件消费事件，
 * 图片盖在 pager 上时横向滑动会被它吃掉，永远翻不了页。这里只在「双指」或「已放大后的拖动」时消费，
 * 其余手势原样留给 pager。固定黑底同理：看图不跟随浅色主题，主色仍取 QcTheme。
 */
private val PillShape = RoundedCornerShape(999.dp)
private const val MIN_SCALE = 1f
private const val MAX_SCALE = 6f
private const val ZOOMED_THRESHOLD = 1.02f
private const val DOUBLE_TAP_SCALE = 2.5f

@Composable
fun ImageViewerScreen(
    entries: List<HistoryEntry>,
    startIndex: Int,
    onClose: () -> Unit,
    onShare: (HistoryEntry) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
    onOpenExternal: (HistoryEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        // 契约不保证非空；直接回退，避免 coerceIn 在 lastIndex = -1 上抛异常
        LaunchedEffect(Unit) { onClose() }
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val pagerState = rememberPagerState(initialPage = startIndex.coerceIn(0, entries.lastIndex)) { entries.size }
    var chrome by remember { mutableStateOf(true) }
    var zoomed by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<HistoryEntry?>(null) }
    // 翻页后必须复位，否则离开放大页后 pager 会一直保持「禁止滑动」
    LaunchedEffect(pagerState.currentPage) { zoomed = false }
    BackHandler(onBack = onClose)

    val current = entries.getOrNull(pagerState.currentPage)
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), userScrollEnabled = !zoomed) { page ->
            val entry = entries.getOrNull(page) ?: return@HorizontalPager
            ZoomableImage(
                entry = entry,
                current = page == pagerState.currentPage,
                onTap = { chrome = !chrome },
                onZoomChange = { zoomed = it },
            )
        }

        if (current != null) {
            // 顶/底栏同时淡出并带一点位移，比硬切换更像原生看图器
            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { -it / 3 },
                modifier = Modifier.align(Alignment.TopCenter),
            ) { TopBar(current, pagerState.currentPage, entries.size, onClose) }

            AnimatedVisibility(
                visible = chrome,
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it / 3 },
                exit = fadeOut(tween(140)) + slideOutVertically(tween(140)) { it / 3 },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                BottomBar(current, { onShare(current) }, { onOpenExternal(current) }, { pendingDelete = current })
            }
        }
    }

    // 二次确认放在查看器里；真正删文件、改列表、关页面仍由父级决定
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除这个文件？") },
            text = { Text("「${target.name}」会从本机存储中移除，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    onDelete(target)
                }) { Text("删除", color = QcColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("取消", color = Color.White.copy(alpha = 0.7f))
                }
            },
            containerColor = QcColors.NightGroupDeep,
            titleContentColor = Color.White,
            textContentColor = Color.White.copy(alpha = 0.72f),
        )
    }
}

@Composable
private fun TopBar(entry: HistoryEntry, page: Int, total: Int, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.62f), Color.Transparent)))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "关闭预览", Modifier.size(22.dp), Color.White) }
        Text(
            text = entry.name, modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            color = Color.White, style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text("${page + 1} / $total", color = Color.White.copy(alpha = 0.6f), style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
private fun BottomBar(
    entry: HistoryEntry, onShare: () -> Unit,
    onOpenExternal: () -> Unit, onDeleteRequest: () -> Unit,
) {
    val time = remember(entry.time) { formatTime(entry.time) }
    val source = when {
        entry.direction == Direction.IN -> if (entry.peer.isBlank()) "未知设备" else "来自 ${entry.peer}"
        entry.peer.isBlank() -> "本机发出"
        else -> "发给 ${entry.peer}"
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
            .padding(start = 18.dp, end = 18.dp, top = 44.dp, bottom = 14.dp),
    ) {
        Text(
            text = "${formatSize(entry.size)} · $source", color = Color.White.copy(alpha = 0.88f),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        Text(time, color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PillButton(Icons.Filled.Share, "分享", onShare, Modifier.weight(1f), accent = true)
            PillButton(
                icon = Icons.AutoMirrored.Filled.ExitToApp, label = "其它应用",
                onClick = onOpenExternal, modifier = Modifier.weight(1f),
            )
            PillButton(Icons.Filled.Delete, "删除", onDeleteRequest, Modifier.weight(1f), danger = true)
        }
    }
}

@Composable
private fun PillButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
    danger: Boolean = false,
) {
    val extra = QcTheme.extra
    val content = when {
        // 品牌渐变偏亮，统一用深墨绿字保证对比度（深浅主题下都成立）
        accent -> Color(0xFF3A1520)
        danger -> QcColors.Error
        else -> Color.White
    }
    val fill = if (accent) Modifier.background(androidx.compose.ui.graphics.SolidColor(Color(0xFFFF5B6E))) else Modifier.background(Color.White.copy(alpha = 0.12f))
    Row(
        modifier = modifier.clip(PillShape).then(fill).clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp), content)
        Spacer(Modifier.width(6.dp))
        Text(
            text = label, color = content, style = MaterialTheme.typography.labelLarge,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

// ---- 单页：加载 + 缩放 ----

private sealed interface LoadState {
    data object Loading : LoadState
    data object Failed : LoadState
    data class Ready(val bitmap: Bitmap) : LoadState
}

@Composable
private fun ZoomableImage(
    entry: HistoryEntry,
    current: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var attempt by remember(entry.id) { mutableStateOf(0) }
    var state by remember(entry.id) { mutableStateOf<LoadState>(LoadState.Loading) }

    LaunchedEffect(entry.id, attempt) {
        state = LoadState.Loading
        val bitmap = ImageStore.full(context, entry.uri)
        state = if (bitmap == null) LoadState.Failed else LoadState.Ready(bitmap)
    }

    Box(Modifier.fillMaxSize()) {
        when (val loaded = state) {
            LoadState.Loading -> CircularProgressIndicator(
                Modifier.align(Alignment.Center).size(22.dp), Color.White.copy(alpha = 0.7f), 1.5.dp,
            )

            LoadState.Failed -> Column(
                Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Warning, null, Modifier.size(36.dp), Color.White.copy(alpha = 0.45f))
                Spacer(Modifier.height(10.dp))
                Text("无法预览这张图片", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
                // 解码失败常常只是瞬时 OOM，给一次重试比让用户退出去重开更省事
                TextButton(onClick = { attempt++ }) {
                    Text("重试", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                }
            }

            is LoadState.Ready -> ZoomableBitmap(
                loaded.bitmap, entry.name, current, onTap, onZoomChange,
            )
        }
    }
}

@Composable
private fun ZoomableBitmap(
    bitmap: Bitmap, name: String,
    current: Boolean, onTap: () -> Unit, onZoomChange: (Boolean) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val zoomAnim = remember { Animatable(0f) }
    val fade = remember { Animatable(0f) }
    val latestTap by rememberUpdatedState(onTap)
    val latestZoom by rememberUpdatedState(onZoomChange)

    LaunchedEffect(Unit) { fade.animateTo(1f, tween(240)) }
    // 翻走时复位，下次回来应该是 1x 居中
    LaunchedEffect(current) {
        if (!current) {
            scale = MIN_SCALE
            offset = Offset.Zero
        }
    }
    // 只有当前页有权决定 pager 能不能滑
    LaunchedEffect(scale > ZOOMED_THRESHOLD, current) {
        if (current) latestZoom(scale > ZOOMED_THRESHOLD)
    }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = name,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.Medium,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
                alpha = fade.value
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { latestTap() },
                    onDoubleTap = { at ->
                        val target = if (scale > ZOOMED_THRESHOLD) MIN_SCALE else DOUBLE_TAP_SCALE
                        val focus = Offset(at.x - box.width / 2f, at.y - box.height / 2f)
                        val fromScale = scale
                        val fromOffset = offset
                        val toOffset = if (target <= MIN_SCALE) Offset.Zero else {
                            clampOffset(focus * (1f - target), target, box, bitmap)
                        }
                        // 以点击点为锚点放大，而不是永远放大中心
                        scope.launch {
                            zoomAnim.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) {
                                scale = fromScale + (target - fromScale) * value
                                offset = lerp(fromOffset, toOffset, value)
                            }
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) continue
                        // 未放大时的单指滑动必须留给 pager，否则永远翻不了页
                        if (pressed < 2 && scale <= ZOOMED_THRESHOLD) continue
                        val zoomChange = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoomChange == 1f && panChange == Offset.Zero) continue
                        if (zoomAnim.isRunning) scope.launch { zoomAnim.stop() }
                        val next = (scale * zoomChange).coerceIn(MIN_SCALE, MAX_SCALE)
                        // 以双指中点为锚点缩放：中点下的像素保持不动
                        val anchor = event.calculateCentroid(useCurrent = true) - Offset(box.width / 2f, box.height / 2f)
                        offset = clampOffset(anchor - (anchor - offset) * (next / scale) + panChange, next, box, bitmap)
                        scale = next
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                }
            },
    )
}

/** 平移量钳制在「图片边缘不离开屏幕」范围内；缩回 1x 时上下左右都为 0，自然回到居中。 */
private fun clampOffset(raw: Offset, scale: Float, box: IntSize, bitmap: Bitmap): Offset {
    if (box.width <= 0 || box.height <= 0 || bitmap.height <= 0) return Offset.Zero
    val boxRatio = box.width.toFloat() / box.height
    val bitmapRatio = bitmap.width.toFloat() / bitmap.height
    val shownWidth = if (bitmapRatio > boxRatio) box.width.toFloat() else box.height * bitmapRatio
    val maxX = ((shownWidth * scale - box.width) / 2f).coerceAtLeast(0f)
    val maxY = ((shownWidth / bitmapRatio * scale - box.height) / 2f).coerceAtLeast(0f)
    return Offset(raw.x.coerceIn(-maxX, maxX), raw.y.coerceIn(-maxY, maxY))
}

private fun formatTime(time: Long): String {
    if (time <= 0L) return "时间未知"
    return runCatching {
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(time))
    }.getOrDefault("")
}
