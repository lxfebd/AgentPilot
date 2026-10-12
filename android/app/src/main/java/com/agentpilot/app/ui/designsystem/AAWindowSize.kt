package com.agentpilot.app.ui.designsystem

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/**
 * 平板/桌面大屏适配的窗口尺寸上下文。
 *
 * 手机优先的默认值保证未显式提供时（如单元测试、预览）行为与旧版一致
 * （Compact = 手机窄屏）。断点遵循 Material 3 规范：
 *   - Compact  < 600dp（手机竖屏）
 *   - Medium   600..839dp（小平板 / 大屏手机横屏）
 *   - Expanded >= 840dp（平板 / 桌面）
 */
data class AAWindowSize(
    val widthClass: WindowWidthSizeClass = WindowWidthSizeClass.Compact,
    val isMediumWidth: Boolean = false,
    val isExpandedWidth: Boolean = false,
    val isTablet: Boolean = false,
) {
    companion object {
        /** 手机默认值：不提供上下文时使用。 */
        val Phone: AAWindowSize = AAWindowSize(
            widthClass = WindowWidthSizeClass.Compact,
            isMediumWidth = false,
            isExpandedWidth = false,
            isTablet = false,
        )
    }
}

/** 当前窗口尺寸上下文；默认手机形态，大屏由 [ProvideAAWindowSize] 提供。 */
val LocalAAWindowSize = staticCompositionLocalOf { AAWindowSize.Phone }

/**
 * 在应用根部计算一次窗口尺寸并注入 CompositionLocal。
 * 平板（宽度 >= 600dp 中屏 / >= 840dp 大屏）会得到 [AAWindowSize.isTablet] = true，
 * 屏幕上的布局可以据此在列表 + 详情分栏与单列之间切换。
 */
@Composable
fun ProvideAAWindowSize(content: @Composable () -> Unit) {
    val configuration = LocalConfiguration.current
    val windowSize = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val width = configuration.screenWidthDp
        val widthClass = when {
            width >= 840 -> WindowWidthSizeClass.Expanded
            width >= 600 -> WindowWidthSizeClass.Medium
            else -> WindowWidthSizeClass.Compact
        }
        AAWindowSize(
            widthClass = widthClass,
            isMediumWidth = widthClass == WindowWidthSizeClass.Medium,
            isExpandedWidth = widthClass == WindowWidthSizeClass.Expanded,
            isTablet = widthClass != WindowWidthSizeClass.Compact,
        )
    }
    CompositionLocalProvider(LocalAAWindowSize provides windowSize) {
        content()
    }
}