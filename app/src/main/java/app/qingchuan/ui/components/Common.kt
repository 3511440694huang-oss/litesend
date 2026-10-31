package app.qingchuan.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.qingchuan.R
import app.qingchuan.core.HistoryEntry
import app.qingchuan.core.ImageStore
import app.qingchuan.core.formatSize
import app.qingchuan.ui.theme.QcTheme
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** 分组卡片：一块浅灰圆角，不加阴影不加描边，靠留白分层。 */
@Composable
fun QcGroup(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    fill: Color = QcTheme.extra.cardFill,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    padding: PaddingValues = PaddingValues(0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    var base = modifier.clip(shape).background(fill)
    if (onClick != null) base = base.clickable(onClick = onClick)
    Column(base.padding(padding), content = content)
}

/** 兼容旧调用名，等同于 QcGroup。 */
@Composable
fun QcCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    shape: RoundedCornerShape = RoundedCornerShape(18.dp),
    fill: Color = QcTheme.extra.cardFill,
    elevation: Dp = 0.dp,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) = QcGroup(modifier = modifier, onClick = onClick, fill = fill, shape = shape, padding = padding, content = content)

/** 分组内的一条分隔线，左侧留出文字起始位置。 */
@Composable
fun RowSeparator(startInset: Dp = 16.dp) {
    HorizontalDivider(
        modifier = Modifier.padding(start = startInset),
        thickness = 0.7.dp,
        color = QcTheme.extra.cardStroke,
    )
}

/** 小标题：浅灰、字重中等，比正文更小。 */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = QcTheme.extra.textSecondary,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(enabled = onTrailing != null) { onTrailing?.invoke() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** 旧的 SectionHeader 别名。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: String? = null,
    onTrailing: (() -> Unit)? = null,
    subtitle: String? = null,
) {
    if (subtitle == null) {
        SectionLabel(title, modifier, trailing, onTrailing)
    } else {
        Row(modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 22.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelMedium, color = QcTheme.extra.textSecondary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = QcTheme.extra.textTertiary)
            }
            if (trailing != null) {
                Text(
                    trailing,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(CircleShape).clickable(enabled = onTrailing != null) { onTrailing?.invoke() }.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

enum class PillStyle { Primary, Soft, Outline, Glass, Plain }

/** 按钮：只有「主色实心」和「无底文字」两种，不做花哨样式。 */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    style: PillStyle = PillStyle.Primary,
    enabled: Boolean = true,
) {
    val extra = QcTheme.extra
    val bg = when {
        !enabled -> extra.cardFillStrong
        style == PillStyle.Primary -> MaterialTheme.colorScheme.primary
        style == PillStyle.Soft -> MaterialTheme.colorScheme.primaryContainer
        style == PillStyle.Outline -> Color.Transparent
        style == PillStyle.Glass -> extra.cardFillStrong
        else -> Color.Transparent
    }
    val fg = when {
        !enabled -> extra.textTertiary
        style == PillStyle.Primary -> MaterialTheme.colorScheme.onPrimary
        style == PillStyle.Soft -> MaterialTheme.colorScheme.onPrimaryContainer
        style == PillStyle.Plain -> MaterialTheme.colorScheme.primary
        style == PillStyle.Glass -> extra.textSecondary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1)
    }
}

/** 状态点 + 文案。 */
@Composable
fun StatusPill(text: String, active: Boolean, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(if (active) QcTheme.extra.success else QcTheme.extra.textTertiary),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = if (active) QcTheme.extra.success else QcTheme.extra.textSecondary,
        )
    }
}

@Composable
fun ThinProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = QcTheme.extra.cardStroke,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(track),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(color),
        )
    }
}

private val avatarColors = listOf(
    Color(0xFF34C08A), Color(0xFF6C8BFA), Color(0xFFF0A33B), Color(0xFFFF5B6E), Color(0xFF3EAED6),
)

/** 设备首字头像（纯色圆角方块，不用渐变）。 */
@Composable
fun Avatar(name: String, size: Dp = 42.dp) {
    val color = avatarColors[(name.hashCode() and Int.MAX_VALUE) % avatarColors.size]
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 猫娘吉祥物（圆形头像）。 */
@Composable
fun Mascot(size: Dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.mascot),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier.size(size),
    )
}

@Composable
fun FileThumb(
    entry: HistoryEntry,
    modifier: Modifier = Modifier,
    corner: Dp = 14.dp,
    onClick: (() -> Unit)? = null,
) {
    val extra = QcTheme.extra
    val bitmap = rememberThumb(entry)
    var box = modifier.clip(RoundedCornerShape(corner)).background(extra.cardFillStrong)
    if (onClick != null) box = box.clickable(onClick = onClick)
    Box(box, contentAlignment = Alignment.Center) {
        when {
            bitmap != null -> Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = entry.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            else -> FileGlyph(entry.mime, Modifier.size(24.dp))
        }
    }
}

@Composable
fun FileGlyph(mime: String, modifier: Modifier = Modifier) {
    val icon = if (mime.startsWith("image/") || mime.startsWith("video/")) R.drawable.ic_image else R.drawable.ic_file
    Icon(
        painter = painterResource(icon),
        contentDescription = null,
        tint = QcTheme.extra.textSecondary,
        modifier = modifier,
    )
}

@Composable
fun rememberThumb(entry: HistoryEntry, maxPx: Int = 360): Bitmap? {
    val context = LocalContext.current
    val state by produceState<Bitmap?>(initialValue = null, entry.uri, maxPx) {
        value = ImageStore.thumb(context, entry.uri, maxPx)
    }
    return state
}

/** 空状态：吉祥物 + 一句话。 */
@Composable
fun EmptyState(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Mascot(size = 76.dp)
        Spacer(Modifier.height(12.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(4.dp))
        Text(
            subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = QcTheme.extra.textSecondary,
            modifier = Modifier.padding(horizontal = 28.dp),
        )
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

/** 键值行（设置用）。 */
@Composable
fun KVRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = QcTheme.extra.textSecondary,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = valueColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1.1f).padding(start = 12.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        if (onClick != null) {
            Spacer(Modifier.width(6.dp))
            Icon(
                painter = painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = QcTheme.extra.textTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

fun relativeTime(millis: Long): String {
    if (millis <= 0) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val fmt = SimpleDateFormat("HH:mm", Locale.CHINA)
    val sameYear = now.get(Calendar.YEAR) == then.get(Calendar.YEAR)
    val dayDiff = run {
        val a = now.clone() as Calendar
        val b = then.clone() as Calendar
        a.set(Calendar.HOUR_OF_DAY, 0); a.set(Calendar.MINUTE, 0); a.set(Calendar.SECOND, 0); a.set(Calendar.MILLISECOND, 0)
        b.set(Calendar.HOUR_OF_DAY, 0); b.set(Calendar.MINUTE, 0); b.set(Calendar.SECOND, 0); b.set(Calendar.MILLISECOND, 0)
        ((a.timeInMillis - b.timeInMillis) / 86_400_000L).toInt()
    }
    return when {
        dayDiff == 0 -> "今天 " + fmt.format(Date(millis))
        dayDiff == 1 -> "昨天 " + fmt.format(Date(millis))
        sameYear -> SimpleDateFormat("M月d日", Locale.CHINA).format(Date(millis))
        else -> SimpleDateFormat("yyyy年M月d日", Locale.CHINA).format(Date(millis))
    }
}

fun extensionOf(name: String): String = name.substringAfterLast('.', "").uppercase(Locale.ROOT).take(4)
