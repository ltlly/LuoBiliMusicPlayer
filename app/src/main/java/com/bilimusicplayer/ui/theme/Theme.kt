package com.bilimusicplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 深色主题配色方案
 * 参考 Apple Music / Spotify 的纯黑沉浸式设计
 * 主色：天蓝色 #66CCFF
 */
private val DarkColorScheme = darkColorScheme(
    // 主色系 - 天蓝色
    primary = Color(0xFF66CCFF),           // 天蓝色 - 主品牌色
    onPrimary = Color(0xFF00293D),         // 深蓝 - 主色上的文字
    primaryContainer = Color(0xFF004D6B),  // 深蓝容器
    onPrimaryContainer = Color(0xFFC2E8FF), // 浅蓝 - 容器上的文字

    // 次要色系 - 低饱和蓝灰（用于辅助信息）
    secondary = Color(0xFF9AC7DE),         // 灰蓝
    onSecondary = Color(0xFF0E2F3F),
    secondaryContainer = Color(0xFF2A4453), // 深灰蓝容器
    onSecondaryContainer = Color(0xFFBAE6FA),

    // 第三色系 - 青绿色（用于成功状态）
    tertiary = Color(0xFF5FD4A8),          // 青绿色
    onTertiary = Color(0xFF003828),
    tertiaryContainer = Color(0xFF00513A),
    onTertiaryContainer = Color(0xFFB8F5D9),

    // 错误色系
    error = Color(0xFFFF6B6B),             // 柔和的红色
    onError = Color(0xFF530D0D),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),

    // 背景和表面 - 纯黑设计（省电、沉浸式）
    background = Color(0xFF000000),        // 纯黑背景
    onBackground = Color(0xFFF5F5F7),      // 接近白色的文字
    surface = Color(0xFF000000),           // 纯黑表面（与背景一致，层次靠表面变体区分）
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C1C1E),    // 深灰表面变体（卡片、迷你播放器）
    onSurfaceVariant = Color(0xFFA8A8AD),  // 次要文字

    // 轮廓
    outline = Color(0xFF636366),           // 边框颜色
    outlineVariant = Color(0xFF2C2C2E),    // 边框变体

    // 其他
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFF5F5F7),
    inverseOnSurface = Color(0xFF1C1C1E),
    inversePrimary = Color(0xFF0077CC),
)

/**
 * 浅色主题配色方案
 * 参考 Apple Music 浅色模式的干净米白设计
 * 主色：深蓝 #0077CC
 */
private val LightColorScheme = lightColorScheme(
    // 主色系 - 深蓝
    primary = Color(0xFF0077CC),           // 深蓝 - 主品牌色
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6EBFA),  // 浅蓝容器
    onPrimaryContainer = Color(0xFF00293D),

    // 次要色系 - 低饱和蓝灰
    secondary = Color(0xFF4A6B80),         // 灰蓝
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EEF5),
    onSecondaryContainer = Color(0xFF0E2F3F),

    // 第三色系 - 青绿色
    tertiary = Color(0xFF00856B),          // 青绿色
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD5F0E6),
    onTertiaryContainer = Color(0xFF003828),

    // 错误色系
    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFF8C1D18),

    // 背景和表面 - 米白设计
    background = Color(0xFFFAFAFA),        // 米白背景
    onBackground = Color(0xFF1C1C1E),      // 深色文字
    surface = Color(0xFFFAFAFA),           // 米白表面
    onSurface = Color(0xFF1C1C1E),
    surfaceVariant = Color(0xFFEFEFF0),    // 浅灰表面变体
    onSurfaceVariant = Color(0xFF5A5A5E),  // 次要文字

    // 轮廓
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFD8D8DC),

    // 其他
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2C2C2E),
    inverseOnSurface = Color(0xFFF5F5F7),
    inversePrimary = Color(0xFF66CCFF),
)

/**
 * 字体排版
 * 使用系统默认字体，通过 FontWeight 建立清晰的视觉层次：
 * - 标题类：Bold / SemiBold（歌名、页面标题）
 * - 正文类：Normal（列表、说明文字）
 * - 标签类：Medium（按钮、时间戳等辅助信息）
 */
private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
)

@Composable
fun BiliMusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
