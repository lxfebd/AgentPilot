package com.agentpilot.app.ui.designsystem

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.agentpilot.app.R

/**
 * RikkaHub 式字体资产：代码/等宽场景统一使用 JetBrains Mono。
 * 通过 AAFont.Mono 替换 FontFamily.Monospace，保证全端一致的等宽观感。
 */
object AAFont {
    /** JetBrains Mono（来自 RikkaHub 字体资产），等宽代码字体。 */
    val Mono: FontFamily = FontFamily(
        Font(R.font.jetbrains_mono, FontWeight.Normal),
        Font(R.font.jetbrains_mono, FontWeight.Medium),
        Font(R.font.jetbrains_mono, FontWeight.SemiBold),
        Font(R.font.jetbrains_mono, FontWeight.Bold),
    )
}
