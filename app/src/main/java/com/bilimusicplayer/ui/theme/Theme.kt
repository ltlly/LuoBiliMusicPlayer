package com.bilimusicplayer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * 深色主题配色方案
 * 参考 Spotify、Apple Music 等主流音乐App设计
 * 主色：天蓝色 #66CCFF
 */
private val DarkColorScheme = darkColorScheme(
    // 主色系 - 天蓝色（深色模式使用更亮的版本）
    primary = Color(0xFF66CCFF),           // 天蓝色 - 主品牌色
    onPrimary = Color(0xFF003548),         // 深蓝 - 主色上的文字
    primaryContainer = Color(0xFF004D6B),  // 深蓝容器
    onPrimaryContainer = Color(0xFFB3E5FF), // 浅蓝 - 容器上的文字

    // 次要色系 - 紫色（用于强调和变化）
    secondary = Color(0xFFB794F6),         // 浅紫色
    onSecondary = Color(0xFF2D1B4E),       // 深紫
    secondaryContainer = Color(0xFF3E2A5C), // 深紫容器
    onSecondaryContainer = Color(0xFFE0CFFC), // 浅紫容器文字

    // 第三色系 - 青绿色（用于成功状态）
    tertiary = Color(0xFF5FD4A8),          // 青绿色
    onTertiary = Color(0xFF003828),        // 深绿
    tertiaryContainer = Color(0xFF00513A), // 深绿容器
    onTertiaryContainer = Color(0xFFB8F5D9), // 浅绿容器文字

    // 错误色系
    error = Color(0xFFFF6B6B),             // 柔和的红色
    onError = Color(0xFF530D0D),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFFFDAD6),

    // 背景和表面 - 纯黑设计（参考Spotify）
    background = Color(0xFF000000),        // 纯黑背景（省电、高对比）
    onBackground = Color(0xFFE6E1E5),      // 浅灰文字
    surface = Color(0xFF1C1B1F),           // 深灰表面
    onSurface = Color(0xFFE6E1E5),         // 浅灰文字
    surfaceVariant = Color(0xFF2B2930),    // 表面变体
    onSurfaceVariant = Color(0xFFCAC4D0),  // 表面变体文字

    // 轮廓
    outline = Color(0xFF938F99),           // 边框颜色
    outlineVariant = Color(0xFF49454F),    // 边框变体

    // 其他
    scrim = Color(0xFF000000),             // 遮罩
    inverseSurface = Color(0xFFE6E1E5),
    inverseOnSurface = Color(0xFF1C1B1F),
    inversePrimary = Color(0xFF0091EA),
)

/**
 * 浅色主题配色方案
 * 参考 Apple Music、网易云音乐等设计
 * 主色：天蓝色 #66CCFF
 */
private val LightColorScheme = lightColorScheme(
    // 主色系 - 天蓝色（浅色模式使用稍深的版本）
    primary = Color(0xFF0091EA),           // 深天蓝 - 主品牌色
    onPrimary = Color.White,               // 白色文字
    primaryContainer = Color(0xFFE1F5FE),  // 浅蓝容器
    onPrimaryContainer = Color(0xFF003548), // 深蓝容器文字

    // 次要色系 - 紫色
    secondary = Color(0xFF7C4DFF),         // 紫色
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE7F6), // 浅紫容器
    onSecondaryContainer = Color(0xFF2D1B4E),

    // 第三色系 - 青绿色
    tertiary = Color(0xFF00BFA5),          // 青绿色
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2F1), // 浅绿容器
    onTertiaryContainer = Color(0xFF003828),

    // 错误色系
    error = Color(0xFFD32F2F),             // 标准红色
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFF8C1D18),

    // 背景和表面 - 纯白设计（参考Apple Music）
    background = Color(0xFFFFFBFF),        // 纯白背景
    onBackground = Color(0xFF1C1B1F),      // 深色文字
    surface = Color.White,                 // 白色表面
    onSurface = Color(0xFF1C1B1F),         // 深色文字
    surfaceVariant = Color(0xFFF3EDF7),    // 浅灰表面变体
    onSurfaceVariant = Color(0xFF49454F),  // 深灰文字

    // 轮廓
    outline = Color(0xFF79747E),           // 边框颜色
    outlineVariant = Color(0xFFCAC4D0),    // 边框变体

    // 其他
    scrim = Color(0xFF000000),             // 遮罩
    inverseSurface = Color(0xFF313033),
    inverseOnSurface = Color(0xFFF4EFF4),
    inversePrimary = Color(0xFF66CCFF),
)

@Composable
fun BiliMusicPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
