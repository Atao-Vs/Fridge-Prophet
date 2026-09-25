package com.fridgeprophet.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = GreenPrimary,
    onPrimary = Color.White,
    primaryContainer = GreenContainer,
    onPrimaryContainer = GreenDark,

    secondary = OrangePrimary,
    onSecondary = Color.White,
    secondaryContainer = OrangeContainer,
    onSecondaryContainer = OrangeDark,

    tertiary = GreenLight,
    onTertiary = Color.White,

    background = NeutralLightBg,
    onBackground = NeutralOnLight,
    surface = NeutralLightSurface,
    onSurface = NeutralOnLight,
    surfaceVariant = NeutralLightVariant,
    onSurfaceVariant = NeutralOnLightVariant,

    error = RedPrimary,
    onError = Color.White,
    errorContainer = RedContainer,
    onErrorContainer = Color(0xFF5C110C),

    outline = OutlineLight,
    outlineVariant = OutlineLight,
)

private val DarkColors = darkColorScheme(
    primary = GreenLight,
    onPrimary = Color(0xFF00301F),
    primaryContainer = GreenDark,
    onPrimaryContainer = GreenContainer,

    secondary = OrangeLight,
    onSecondary = Color(0xFF3D1C00),
    secondaryContainer = OrangeDark,
    onSecondaryContainer = OrangeContainer,

    tertiary = GreenPrimary,
    onTertiary = Color.White,

    background = NeutralDarkBg,
    onBackground = NeutralOnDark,
    surface = NeutralDarkSurface,
    onSurface = NeutralOnDark,
    surfaceVariant = NeutralDarkVariant,
    onSurfaceVariant = NeutralOnDarkVariant,

    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),

    outline = OutlineDark,
    outlineVariant = OutlineDark,
)

/** 语义色：新鲜度、临期状态等。不跟随主题色，保持一致的视觉含义。 */
object SemanticColors {
    val fresh = Color(0xFF3E8E63)
    val normal = Color(0xFF6B8E3E)
    val soon = Color(0xFFE08A2E)
    val expired = Color(0xFFC7463B)
    val neutral = Color(0xFF8A8880)

    /**
     * 已点赞的爱心颜色。
     *
     * 不复用上面的 expired（红）是刻意的：那个红在这套配色里表示「坏了、过期了」，
     * 借给「喜欢」用会让同一个颜色带两种相反的情绪。而且主题色本身是绿色系，
     * 直接拿 primary 当爱心色会变成「绿心」，看着像状态灯不像表态。
     */
    val liked = Color(0xFFE0245E)

    fun forFreshness(freshness: String): Color = when (freshness) {
        "新鲜" -> fresh
        "正常" -> normal
        "尽快食用" -> soon
        "已过期" -> expired
        else -> neutral
    }
}

@Composable
fun FridgeProphetTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            // 这里只负责状态栏图标的明暗：浅色主题配深色图标，深色主题配浅色图标。
            // 状态栏 / 导航栏的底色交给 MainActivity 的 enableEdgeToEdge()——
            // 从 API 35 起 statusBarColor 已废弃，手动设置不再生效。
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
