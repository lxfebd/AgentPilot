package com.agentpilot.app.ui.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 统一形状体系（Shape scale）。
 *
 * 设计背景：本文件建立之前，UI 层散落着 14 种硬编码圆角
 * （6 / 7 / 8 / 10 / 12 / 13 / 14 / 15 / 16 / 17 / 18 / 20 / 22 / 24 / 26 dp），
 * 相同语义的元素在不同页面使用不同数值，是「设计语言不统一」的根因。
 *
 * 映射原则（从大到小对应语义层级）：
 *  - 容器级（dialog / sheet / 浮层）-> [extraLarge]
 *  - 卡片级（内容块）-> [large] / [medium]
 *  - 控件级（按钮 / 输入框 / 列表项）-> [medium] / [small]
 *  - 标签级（chip / badge / 小方块）-> [extraSmall]
 *  - 极小元素（代码块内联片段）-> [tiny]
 *
 * 新增组件一律引用本文件，不再直接写 RoundedCornerShape(x.dp)。
 * 迁移存量代码时按上表就近归并，不改变原有视觉尺寸所在量级。
 */
object AAShapes {

    /** 极小：代码块内联片段、极小徽标。 */
    val tiny: Dp = 6.dp

    /** 标签级：chip、badge、小方块。 */
    val extraSmall: Dp = 10.dp

    /** 控件级：按钮、输入框、列表项、菜单项。 */
    val small: Dp = 14.dp

    /** 控件级偏大：常用卡片、次级按钮。 */
    val medium: Dp = 20.dp

    /** 卡片级：主要内容卡片。 */
    val large: Dp = 22.dp

    /** 容器级：dialog、sheet、浮层。 */
    val extraLarge: Dp = 26.dp

    /**
     * 分组内相邻项之间的圆角断点。
     *
     * RikkaHub 的 `CardGroup` 用 20dp（外）/ 4dp（内）的圆角组合实现连续分段：
     * 首尾项各带 20dp 圆角，中间项方形，视觉上多行拼成一张卡片。
     * 本值与 [medium] 同源，保证分组外沿与单张卡片一致。
     */
    val groupInner: Dp = 4.dp

    /** 极小圆角形状。 */
    val tinyShape: RoundedCornerShape = RoundedCornerShape(tiny)

    /** 标签级圆角形状。 */
    val extraSmallShape: RoundedCornerShape = RoundedCornerShape(extraSmall)

    /** 控件级圆角形状。 */
    val smallShape: RoundedCornerShape = RoundedCornerShape(small)

    /** 控件级偏大圆角形状。 */
    val mediumShape: RoundedCornerShape = RoundedCornerShape(medium)

    /** 卡片级圆角形状。 */
    val largeShape: RoundedCornerShape = RoundedCornerShape(large)

    /** 容器级圆角形状。 */
    val extraLargeShape: RoundedCornerShape = RoundedCornerShape(extraLarge)

    /** 分组内项圆角形状。 */
    val groupInnerShape: RoundedCornerShape = RoundedCornerShape(groupInner)
}

/**
 * 按组内位置返回圆角形状：首尾用 [AAShapes.medium]，中间用 [AAShapes.groupInner]。
 *
 * 用于实现「连续分段卡片」视觉——这是 RikkaHub `CardGroup` 的核心手法：
 * 多个同组项拼成一张连贯卡片，内部项之间不画分隔线，靠圆角断点表达分组。
 * 规格与 RikkaHub 实测一致：外沿 20dp，内项 4dp。
 * 圆角规格是通用设计实践，本文件不复制任何第三方源码。
 *
 * @param index 当前项在组内的下标（从 0 开始）
 * @param count 组内总项数
 */
@Composable
@ReadOnlyComposable
fun aaGroupItemShape(index: Int, count: Int): RoundedCornerShape =
    when {
        count <= 1 -> AAShapes.mediumShape
        index == 0 && index == count - 1 -> AAShapes.mediumShape
        index == 0 -> RoundedCornerShape(
            topStart = AAShapes.medium,
            topEnd = AAShapes.medium,
            bottomStart = AAShapes.groupInner,
            bottomEnd = AAShapes.groupInner,
        )

        index == count - 1 -> RoundedCornerShape(
            topStart = AAShapes.groupInner,
            topEnd = AAShapes.groupInner,
            bottomStart = AAShapes.medium,
            bottomEnd = AAShapes.medium,
        )

        else -> AAShapes.groupInnerShape
    }
