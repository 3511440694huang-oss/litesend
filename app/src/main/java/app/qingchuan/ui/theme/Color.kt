package app.qingchuan.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 极简配色：白纸 + 灰阶 + 一个珊瑚红强调色。
 * 上一版颜色太多（奶油底、粉色渐变、薄荷绿、暖阴影）显得杂，
 * 这一版只保留「黑、灰、白 + 一个强调色」，靠留白和字号分层。
 */
object QcColors {
    /** 唯一的强调色：发送按钮、选中态、链接。 */
    val Accent = Color(0xFFFF5B6E)
    val AccentDeep = Color(0xFFE8455A)
    val AccentSoft = Color(0xFFFFE9EC)

    /** 状态点用（在线绿）。 */
    val Online = Color(0xFF34C08A)
    val Warn = Color(0xFFE9A23B)
    val Error = Color(0xFFE5484D)

    val Paper = Color(0xFFFFFFFF)
    val Group = Color(0xFFF6F6F7)
    val GroupDeep = Color(0xFFEFEFF1)
    val Text = Color(0xFF0F0F10)
    val Text2 = Color(0xFF8E8E93)
    val Text3 = Color(0xFFB9B9BF)
    val Line = Color(0xFFEDEDF0)

    val Night = Color(0xFF0E0E10)
    val NightGroup = Color(0xFF1C1C1E)
    val NightGroupDeep = Color(0xFF26262A)
    val NightText = Color(0xFFF2F2F5)
    val NightText2 = Color(0xFF8E8E93)
    val NightText3 = Color(0xFF5C5C61)
    val NightLine = Color(0xFF2A2A2D)
}
