package com.agentpilot.app.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.feature.sessions.SessionsState
import com.agentpilot.app.feature.sessions.railSessionOrder
import com.agentpilot.app.feature.sessions.railSessionStatus
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.ui.designsystem.LocalAAColors

/**
 * 平板/大屏空态任务仪表盘：没有打开会话时，右侧内容区直接列出全部会话与进度，
 * 点击任意一行即可进入会话。
 */
@Composable
internal fun HomeTaskDashboard(
    state: SessionsState,
    projectSessionsById: Map<String, List<AgentSession>>,
    selectedAgentRuntime: String?,
    onOpenSession: (AgentSession) -> Unit,
    onPairDevice: () -> Unit,
) {
    val colors = LocalAAColors.current
    val allSessions = remember(projectSessionsById, state.sessions, selectedAgentRuntime) {
        (projectSessionsById.values.flatten() + state.sessions)
            .associateBy { it.id }
            .values
            .filter { !it.archived }
            .filter { selectedAgentRuntime == null || it.runtime == selectedAgentRuntime }
            .sortedBy { railSessionOrder(it) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = stringResource(R.string.rail_dashboard_title),
            color = colors.ink,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp),
        )
        if (state.devices.isEmpty()) {
            Text(
                text = stringResource(R.string.home_pair_device_first),
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        } else if (allSessions.isEmpty()) {
            Text(
                text = stringResource(R.string.rail_dashboard_hint),
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
        allSessions.forEach { session ->
            val status = railSessionStatus(session)
            val projectName = state.projects.firstOrNull { it.id == session.projectId }?.name
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.subtle)
                    .clickable { onOpenSession(session) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(status.color),
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = session.title.ifBlank { session.runtime },
                        color = colors.ink,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!projectName.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = projectName,
                            color = colors.muted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.width(10.dp))
                if (status.labelRes != null) {
                    Text(
                        text = stringResource(status.labelRes),
                        color = status.color,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
