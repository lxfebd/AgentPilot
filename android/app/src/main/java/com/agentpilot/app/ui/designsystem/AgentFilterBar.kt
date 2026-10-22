package com.agentpilot.app.ui.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.model.runtimeTypeLabel
import com.agentpilot.app.ui.screens.home.SessionAgentIcon

/**
 * Agent 来源分类过滤栏：手机首页列表顶部与平板侧边栏顶部共用。
 * 展示当前接入过的全部 agent（含“所有 Agent”），点击切换只看该 agent 的会话，
 * 再点已选中的项取消过滤。
 */
@Composable
internal fun AgentFilterBar(
    runtimes: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entries = remember(runtimes, selected) {
        val base = runtimes.sortedBy(String::runtimeTypeLabel)
        if (selected != null && selected !in base) {
            (base + selected).sortedBy(String::runtimeTypeLabel)
        } else {
            base
        }
    }
    if (entries.isEmpty() && selected == null) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("agent-filter-all") {
            AgentFilterChip(
                label = stringResource(R.string.home_project_filter_all_agents),
                selected = selected == null,
                icon = null,
                onClick = { onSelect(null) },
            )
        }
        items(entries, key = { "agent-filter-$it" }) { runtime ->
            AgentFilterChip(
                label = runtime.runtimeTypeLabel(),
                selected = selected == runtime,
                icon = { tint, size ->
                    SessionAgentIcon(
                        runtime = runtime,
                        runtimeType = runtime,
                        runtimeId = runtime,
                        tint = tint,
                        modifier = Modifier.size(size),
                    )
                },
                onClick = { onSelect(if (selected == runtime) null else runtime) },
            )
        }
    }
}

@Composable
private fun AgentFilterChip(
    label: String,
    selected: Boolean,
    icon: (@Composable (Color, Dp) -> Unit)?,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val haptic = LocalHapticFeedback.current
    val isSelected = selected
    val background = if (isSelected) colors.primaryAction else colors.secondaryActionSurface
    val foreground = if (isSelected) colors.onPrimaryAction else colors.inkSoft
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            // 选中态必须暴露给无障碍与自动化：语义必须放在最外层才会把
            // 内部 icon/label 子节点合并掉，读屏与 UI 测试才能读到当前筛选
            // 项（selected），而不是一串互不相干的纯文本节点。
            .semantics(mergeDescendants = true) { this.selected = isSelected }
            .background(background)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                icon(foreground, 16.dp)
            }
        }
        Text(
            text = label,
            color = foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
