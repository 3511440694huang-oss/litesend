package app.qingchuan.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.qingchuan.core.ThemeMode

/** Material3 之外的扩展色；界面里不要直接写死颜色。 */
data class QcExtra(
    val textSecondary: Color,
    val textTertiary: Color,
    val cardStroke: Color,
    val cardFill: Color,
    val cardFillStrong: Color,
    val success: Color,
    val warning: Color,
    val brandGradient: List<Color>,
    val heroGradient: List<Color>,
    val glow: Color,
    val scrim: Color,
    val shadow: Color,
    val isDark: Boolean,
)

private val LightScheme = lightColorScheme(
    primary = QcColors.Accent,
    onPrimary = Color.White,
    primaryContainer = QcColors.AccentSoft,
    onPrimaryContainer = QcColors.AccentDeep,
    secondary = QcColors.Online,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4F6EE),
    onSecondaryContainer = Color(0xFF13795A),
    tertiary = QcColors.Warn,
    onTertiary = Color(0xFF3A2600),
    background = QcColors.Paper,
    onBackground = QcColors.Text,
    surface = QcColors.Paper,
    onSurface = QcColors.Text,
    surfaceVariant = QcColors.Group,
    onSurfaceVariant = QcColors.Text2,
    surfaceContainer = QcColors.Group,
    surfaceContainerHigh = QcColors.GroupDeep,
    surfaceContainerHighest = QcColors.GroupDeep,
    outline = QcColors.Line,
    outlineVariant = QcColors.Line,
    error = QcColors.Error,
    onError = Color.White,
    errorContainer = Color(0xFFFFE9EA),
    onErrorContainer = Color(0xFF7A1B1E),
)

private val DarkScheme = darkColorScheme(
    primary = QcColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A1F23),
    onPrimaryContainer = Color(0xFFFFD6DB),
    secondary = QcColors.Online,
    onSecondary = Color(0xFF00251A),
    secondaryContainer = Color(0xFF123328),
    onSecondaryContainer = Color(0xFFB7EEDC),
    tertiary = QcColors.Warn,
    onTertiary = Color(0xFF3A2600),
    background = QcColors.Night,
    onBackground = QcColors.NightText,
    surface = QcColors.Night,
    onSurface = QcColors.NightText,
    surfaceVariant = QcColors.NightGroup,
    onSurfaceVariant = QcColors.NightText2,
    surfaceContainer = QcColors.NightGroup,
    surfaceContainerHigh = QcColors.NightGroupDeep,
    surfaceContainerHighest = QcColors.NightGroupDeep,
    outline = QcColors.NightLine,
    outlineVariant = QcColors.NightLine,
    error = Color(0xFFFF8A8A),
    onError = Color(0xFF3A0E0E),
    errorContainer = Color(0xFF4A1C1C),
    onErrorContainer = Color(0xFFFFD6D6),
)

private val LightExtra = QcExtra(
    textSecondary = QcColors.Text2,
    textTertiary = QcColors.Text3,
    cardStroke = QcColors.Line,
    cardFill = QcColors.Group,
    cardFillStrong = QcColors.GroupDeep,
    success = QcColors.Online,
    warning = QcColors.Warn,
    brandGradient = listOf(QcColors.Accent, QcColors.AccentDeep),
    heroGradient = listOf(QcColors.Accent, QcColors.AccentDeep),
    glow = Color(0x00000000),
    scrim = Color(0xCCFFFFFF),
    shadow = Color.Black,
    isDark = false,
)

private val DarkExtra = QcExtra(
    textSecondary = QcColors.NightText2,
    textTertiary = QcColors.NightText3,
    cardStroke = QcColors.NightLine,
    cardFill = QcColors.NightGroup,
    cardFillStrong = QcColors.NightGroupDeep,
    success = QcColors.Online,
    warning = QcColors.Warn,
    brandGradient = listOf(QcColors.Accent, QcColors.AccentDeep),
    heroGradient = listOf(QcColors.Accent, QcColors.AccentDeep),
    glow = Color(0x00000000),
    scrim = Color(0xCC0E0E10),
    shadow = Color.Black,
    isDark = true,
)

val LocalQcExtra = staticCompositionLocalOf { LightExtra }

object QcTheme {
    val extra: QcExtra
        @Composable get() = LocalQcExtra.current
}

val QcShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun QingchuanTheme(
    mode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (mode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    CompositionLocalProvider(LocalQcExtra provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = QcTypography,
            shapes = QcShapes,
            content = content,
        )
    }
}
