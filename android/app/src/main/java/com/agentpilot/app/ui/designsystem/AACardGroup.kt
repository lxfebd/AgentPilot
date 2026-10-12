package com.agentpilot.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 分段卡片容器（segmented card group）。
 *
 * 设计意图：把「一个卡片 + 若干 Divider + 若干行」改成「一张连贯卡片」——
 * 组内各项圆角首尾为 [AAShapes.medium]、中间为直角，靠形状断点表达分组，
 * 组内不再绘制分隔线。这是 RikkaHub `CardGroup` 采用的视觉手法，
 * 本文件按通用设计实践独立实现，不含第三方源码。
 *
 * 用法：
 * ```
 * AACardGroup {
 *     AACardGroupItem(title = "账号", icon = Lucide.User) { /* trailing */ }
 *     AACardGroupItem(title = "外观", icon = Lucide.Palette) { /* trailing */ }
 * }
 * ```
 *
 * @param shape 整体圆角，默认 [AAShapes.mediumShape]
 * @param content 组内容，建议只放 [AACardGroupItem]
 */
@Composable
fun AACardGroup(
    modifier: Modifier = Modifier,
    shape: Shape = AAShapes.mediumShape,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalAAColors.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background ?: aaCardGroupBackground(colors))
            .border(0.8.dp, aaCardGroupBorder(colors), shape),
        content = content,
    )
}

/**
 * 分段卡片内的单行。整行为点击目标（[onClick] 非空时）。
 *
 * @param title 主标题
 * @param subtitle 副标题，可空
 * @param icon 左侧图标，可空
 * @param enabled 禁用态（整体降透明度并忽略点击）
 * @param showDivider 是否在该行下方绘制细分隔线。默认 false——分段卡片靠圆角断点分组，
 *   仅当同一组内需要更强的行间分隔时才开启。
 * @param trailing 行尾内容（开关、按钮、文本等）
 * @param onClick 点击回调，空则不可点击
 */
@Composable
fun AACardGroupItem(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    showDivider: Boolean = false,
    contentPadding: Dp = 14.dp,
    trailing: @Composable (RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalAAColors.current
    val alpha = if (enabled) 1f else 0.5f

    Column {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 60.dp)
                .then(
                    if (onClick != null) {
                        Modifier.clickable(enabled = enabled, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(
                    start = contentPadding,
                    end = contentPadding,
                    top = 12.dp,
                    bottom = 12.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.ink.copy(alpha = alpha),
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    color = colors.ink.copy(alpha = alpha),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 21.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        color = colors.muted.copy(alpha = alpha),
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        lineHeight = 16.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing?.invoke(this)
        }
        if (showDivider) {
            // 分段接缝：画布色细缝贯通卡片，让组内各项读成独立分段。
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(aaCardGroupSeam(colors)),
            )
        }
    }
}

/**
 * 分段卡片内的表单型行（无图标，副标题在上、标题在下）。
 * 用于输入框、下拉选择等需要纵向排列标签与值的场景。
 */
@Composable
fun AACardGroupFormItem(
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = colors.muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 15.sp,
            )
            Text(
                text = supporting.orEmpty(),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal,
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing?.invoke(this)
    }
}

/** 分段卡片背景色：浅色取画布层（动态浅色方案的 background），深色取抬升面，弱化与画布的边界。 */
@Composable
internal fun aaCardGroupBackground(colors: AgentPilotColors): Color =
    if (colors.isDark) colors.raisedSurface else colors.canvas

/** 分段卡片描边色。 */
@Composable
internal fun aaCardGroupBorder(colors: AgentPilotColors): Color =
    if (colors.isDark) colors.border else Color(0xFFE7E5E0)

/** 分段接缝颜色：深色取画布色（缝即背景），浅色取页面背景（raisedSurface）。 */
@Composable
fun aaCardGroupSeam(colors: AgentPilotColors): Color =
    if (colors.isDark) colors.canvas else colors.raisedSurface
