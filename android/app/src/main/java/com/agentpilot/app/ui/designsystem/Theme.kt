package com.agentpilot.app.ui.designsystem

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.core.view.WindowCompat

object AAColor {
    val Canvas = Color(0xFFFDFCFB)
    val Ink = Color(0xFF0A0A0B)
    val InkSoft = Color(0xFF2B2B2B)
    val Muted = Color(0xFF777777)
    val Faint = Color(0xFFAAA8A2)
    val Border = Color(0xFFE7E5E0)
    val Subtle = Color(0xFFF1F0ED)
    val ToolSurface = Color(0xFFECECEA)
    val Done = Color(0xFFE4E2DD)
    val UserBubble = Color(0xFFF0EFEB)
}

data class AgentPilotColors(
    val isDark: Boolean,
    val canvas: Color,
    val ink: Color,
    val inkSoft: Color,
    val muted: Color,
    val faint: Color,
    val border: Color,
    val subtle: Color,
    val raisedSurface: Color,
    val dialogSurface: Color,
    val onRaisedSurface: Color,
    val primaryAction: Color,
    val onPrimaryAction: Color,
    val secondaryActionSurface: Color,
    val secondaryActionBorder: Color,
    val matchedAccountSurface: Color,
    val errorSurface: Color,
    val errorBorder: Color,
    val errorText: Color,
    val errorIcon: Color,
    val runtimeRunning: Color,
    val runtimeTransitioning: Color,
    val runtimeActive: Color,
    val runtimeInactive: Color,
    val runtimeSwitchCheckedTrack: Color,
    val runtimeSwitchUncheckedTrack: Color,
    val runtimeSwitchCheckedThumb: Color,
    val runtimeSwitchUncheckedThumb: Color,
    val sessionStatusAccent: Color,
    val sessionStatusAccentText: Color,
    val noticeWarning: Color,
    val noticeSuccess: Color,
    val sessionMessageBubble: Color,
    val sessionMessageText: Color,
    val sessionTimelineActivitySurface: Color,
    val sessionCodeSurface: Color,
    val sessionStatusNeutralSurface: Color,
    val sessionStatusNeutralText: Color,
    val appShadow: Color,
)

private val LightAgentPilotColors = AgentPilotColors(
    isDark = false,
    canvas = Color(0xFFFDFCFB),
    ink = Color(0xFF0A0A0B),
    inkSoft = Color(0xFF2B2B2B),
    muted = Color(0xFF777777),
    faint = Color(0xFFAAA8A2),
    border = Color(0xFFE7E5E0),
    subtle = Color(0xFFF1F0ED),
    raisedSurface = Color.White,
    dialogSurface = Color.White,
    onRaisedSurface = Color(0xFF111111),
    primaryAction = Color(0xFF0A0A0B),
    onPrimaryAction = Color.White,
    secondaryActionSurface = Color(0xFFF3F3F3),
    secondaryActionBorder = Color(0xFF0A0A0B),
    matchedAccountSurface = Color(0xFFF6F6F3),
    errorSurface = Color(0xFFFFF4F4),
    errorBorder = Color(0xFFF4C7C7),
    errorText = Color(0xFFB42318),
    errorIcon = Color(0xFFB42318),
    runtimeRunning = Color(0xFF10B981),
    runtimeTransitioning = Color(0xFF3B82F6),
    runtimeActive = Color(0xFFF59E0B),
    runtimeInactive = Color(0x66777777),
    runtimeSwitchCheckedTrack = Color(0xFF171717),
    runtimeSwitchUncheckedTrack = Color(0xFFE5E5E5),
    runtimeSwitchCheckedThumb = Color.White,
    runtimeSwitchUncheckedThumb = Color.White,
    sessionStatusAccent = Color(0xFF18866A),
    sessionStatusAccentText = Color(0xFF146B56),
    noticeWarning = Color(0xFFD97706),
    noticeSuccess = Color(0xFF16A34A),
    sessionMessageBubble = Color(0xFFF1F0ED),
    sessionMessageText = Color(0xFF242522),
    sessionTimelineActivitySurface = Color(0x14F1F0ED),
    sessionCodeSurface = Color(0xB8FFFFFF),
    sessionStatusNeutralSurface = Color(0xFFE4E2DD),
    sessionStatusNeutralText = Color(0xFF6F6E69),
    appShadow = Color(0x22000000),
)

private val DarkAgentPilotColors = AgentPilotColors(
    isDark = true,
    canvas = Color(0xFF09090B),
    ink = Color(0xFFFAFAFA),
    inkSoft = Color(0xFFE5E5E5),
    muted = Color(0xFFA3A3A3),
    faint = Color(0xFF737373),
    border = Color.Transparent,
    subtle = Color(0xFF292929),
    raisedSurface = Color(0xFF1F1F1F),
    dialogSurface = Color(0xFF242424),
    onRaisedSurface = Color(0xFFFAFAFA),
    primaryAction = Color(0xFFFAFAFA),
    onPrimaryAction = Color(0xFF09090B),
    secondaryActionSurface = Color(0xFF424242),
    secondaryActionBorder = Color.Transparent,
    matchedAccountSurface = Color(0xFF191919),
    errorSurface = Color(0xFF2A1214),
    errorBorder = Color(0xFF5F2429),
    errorText = Color(0xFFFCA5A5),
    errorIcon = Color(0xFFF97066),
    runtimeRunning = Color(0xFF10B981),
    runtimeTransitioning = Color(0xFF3B82F6),
    runtimeActive = Color(0xFFF59E0B),
    runtimeInactive = Color(0x66A1A1AA),
    runtimeSwitchCheckedTrack = Color(0xFFE5E5E5),
    runtimeSwitchUncheckedTrack = Color(0x26FFFFFF),
    runtimeSwitchCheckedThumb = Color(0xFF171717),
    runtimeSwitchUncheckedThumb = Color(0xFFFAFAFA),
    sessionStatusAccent = Color(0xFF39A887),
    sessionStatusAccentText = Color(0xFF65C4A6),
    noticeWarning = Color(0xFFF59E0B),
    noticeSuccess = Color(0xFF22C55E),
    sessionMessageBubble = Color(0xFF2A2A2A),
    sessionMessageText = Color(0xFFF4F4F5),
    sessionTimelineActivitySurface = Color(0x1018181B),
    sessionCodeSurface = Color(0xFF111113),
    sessionStatusNeutralSurface = Color(0xFF2D2D2D),
    sessionStatusNeutralText = Color(0xFFD4D4D8),
    appShadow = Color(0x52000000),
)

val LocalAAColors = staticCompositionLocalOf { LightAgentPilotColors }

/** 深色判断统一走 isDark，避免 AMOLED 纯黑下 canvas 值变化导致判断失配。 */
val AgentPilotColors.darkMode: Boolean get() = isDark

/**
 * 把 M3 动态方案（壁纸取色）映射进语义色板。
 *
 * 完整跟随 tonal 体系：背景/文字层级也取自动态方案，容器面用 surfaceContainer
 * 系列（对齐 RikkaHub CustomColors：列表项 surfaceBright、卡片 surfaceContainer、
 * 顶栏 surfaceContainer）。这样整个 App 的底色与层次都跟着壁纸走。
 */
private fun AgentPilotColors.withDynamicScheme(
    scheme: androidx.compose.material3.ColorScheme,
    dark: Boolean,
): AgentPilotColors = copy(
    canvas = scheme.background,
    ink = scheme.onBackground,
    inkSoft = scheme.onSurface,
    muted = scheme.onSurfaceVariant,
    faint = scheme.outline,
    // material3 1.4.0 的 ColorScheme 没有 onSurfaceContainer（1.5.0 Expressive 才有），
    // 容器面文字色用 onSurface / onSurfaceVariant 表达同层级语义。
    onRaisedSurface = if (dark) scheme.onSurface else scheme.onSurfaceVariant,
    primaryAction = scheme.primary,
    onPrimaryAction = scheme.onPrimary,
    secondaryActionSurface = if (dark) scheme.surfaceVariant else scheme.secondaryContainer,
    secondaryActionBorder = if (dark) scheme.outline else scheme.outline.copy(alpha = 0.55f),
    matchedAccountSurface = scheme.surfaceContainerHigh,
    errorSurface = scheme.errorContainer,
    errorBorder = if (dark) scheme.error.copy(alpha = 0.35f) else scheme.errorContainer,
    errorText = scheme.error,
    errorIcon = scheme.error,
    runtimeTransitioning = scheme.tertiary,
    runtimeSwitchCheckedTrack = scheme.primary,
    runtimeSwitchCheckedThumb = scheme.onPrimary,
    sessionStatusAccent = if (dark) scheme.tertiary else scheme.primary,
    sessionStatusAccentText = if (dark) scheme.onTertiaryContainer else scheme.onPrimaryContainer,
    // 中性面跟随 M3 tonal 体系（RikkaHub CustomColors 的做法）：
    // 列表项/卡片用 surfaceContainer，对话框用 surfaceContainerHigh，边框用 outlineVariant。
    raisedSurface = if (dark) scheme.surfaceContainer else scheme.surfaceContainer,
    dialogSurface = if (dark) scheme.surfaceContainerHigh else scheme.surfaceContainerHigh,
    border = if (dark) scheme.outlineVariant else scheme.outlineVariant.copy(alpha = 0.55f),
    subtle = if (dark) scheme.surfaceContainerLow else scheme.surfaceContainerLow,
)

/** AMOLED：纯黑背景 + 近黑表面层（保留 isDark 语义）。 */
private fun AgentPilotColors.withAmoled(): AgentPilotColors = copy(
    canvas = Color.Black,
    subtle = Color(0xFF141414),
    raisedSurface = Color(0xFF101010),
    dialogSurface = Color(0xFF0C0C0C),
    matchedAccountSurface = Color(0xFF101010),
    sessionMessageBubble = Color(0xFF161616),
    sessionTimelineActivitySurface = Color(0x12101010),
)

object AAAppearanceMode {
    const val System = "system"
    const val Light = "light"
    const val Dark = "dark"
}

object AALanguageMode {
    const val System = "system"
    const val English = "en"
    const val SimplifiedChinese = "zh-CN"
}

private val LightAgentPilotColorScheme = lightColorScheme(
    primary = AAColor.Ink,
    onPrimary = Color.White,
    background = AAColor.Canvas,
    onBackground = AAColor.Ink,
    surface = AAColor.Canvas,
    onSurface = AAColor.Ink,
    surfaceVariant = AAColor.Subtle,
    outline = AAColor.Border,
)

private val DarkAgentPilotColorScheme = darkColorScheme(
    primary = DarkAgentPilotColors.ink,
    onPrimary = DarkAgentPilotColors.canvas,
    background = DarkAgentPilotColors.canvas,
    onBackground = DarkAgentPilotColors.ink,
    surface = DarkAgentPilotColors.canvas,
    onSurface = DarkAgentPilotColors.ink,
    surfaceVariant = DarkAgentPilotColors.subtle,
    outline = DarkAgentPilotColors.border,
)

private val AgentPilotTypography = Typography(
    displayLarge = Typography().displayLarge.copy(fontFamily = FontFamily.SansSerif),
    headlineLarge = Typography().headlineLarge.copy(fontFamily = FontFamily.SansSerif),
    titleLarge = Typography().titleLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Typography().titleMedium.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
    ),
    titleSmall = Typography().titleSmall.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = Typography().bodyLarge.copy(fontFamily = FontFamily.SansSerif),
    bodyMedium = Typography().bodyMedium.copy(fontFamily = FontFamily.SansSerif),
    labelLarge = Typography().labelLarge.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
    ),
)

object AAThemeTone {
    const val Dynamic = "dynamic"
    const val Classic = "classic"
}

/** 当前主题偏好的记录，供 MainActivity 持久化与设置页读写。 */
object AAThemePreference {
    const val KEY_APPEARANCE_MODE = "appearance_mode"
    const val KEY_THEME_TONE = "theme_tone"
    const val KEY_AMOLED_DARK = "amoled_dark"
}

@Composable
fun AgentPilotTheme(
    appearanceMode: String = AAAppearanceMode.System,
    themeTone: String = AAThemeTone.Dynamic,
    amoledDark: Boolean = false,
    content: @Composable () -> Unit,
) {
    val systemDarkTheme = isSystemInDarkTheme()
    val darkTheme = when (appearanceMode) {
        AAAppearanceMode.Light -> false
        AAAppearanceMode.Dark -> true
        else -> systemDarkTheme
    }
    // Android 12+ 动态取色：以壁纸为源生成语义色。低版本或用户选经典风格时回退手工配色。
    val context = LocalContext.current
    val dynamicSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val useDynamic = dynamicSupported && themeTone != AAThemeTone.Classic
    val colorScheme = remember(darkTheme, useDynamic) {
        when {
            useDynamic && darkTheme -> dynamicDarkColorScheme(context)
            useDynamic -> dynamicLightColorScheme(context)
            darkTheme -> DarkAgentPilotColorScheme
            else -> LightAgentPilotColorScheme
        }
    }
    var colors = if (darkTheme) DarkAgentPilotColors else LightAgentPilotColors
    // 动态取色映射进语义色板（强调/状态色跟壁纸走，中性基底保持不变）。
    if (useDynamic) {
        colors = colors.withDynamicScheme(colorScheme, dark = darkTheme)
    }
    // AMOLED 纯黑：仅替换背景/表面层，深色判断仍以 isDark 为准。
    if (darkTheme && amoledDark) {
        colors = colors.withAmoled()
    }
    // AMOLED 下 colorScheme 的背景/表面同步压黑（Material 组件层）。
    val colorSchemeConverted = if (darkTheme && amoledDark) {
        colorScheme.copy(
            background = Color.Black,
            surface = Color.Black,
            surfaceContainer = Color(0xFF141414),
            surfaceContainerHigh = Color(0xFF1E1E1E),
            surfaceContainerHighest = Color(0xFF262626),
            surfaceVariant = Color(0xFF171717),
        )
    } else {
        colorScheme
    }
    val view = LocalView.current

    SideEffect {
        val window = (view.context as? android.app.Activity)?.window ?: return@SideEffect
        window.statusBarColor = colors.canvas.toArgb()
        window.navigationBarColor = colors.canvas.toArgb()
        WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !darkTheme
    }

    CompositionLocalProvider(LocalAAColors provides colors) {
        MaterialTheme(
            colorScheme = colorSchemeConverted,
            typography = AgentPilotTypography,
            content = content,
        )
    }
}
