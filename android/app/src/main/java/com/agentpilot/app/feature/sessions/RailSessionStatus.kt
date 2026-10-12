package com.agentpilot.app.feature.sessions

import androidx.compose.ui.graphics.Color
import com.agentpilot.app.R
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus

// 固定语义色：不随主题变化，保证不同进度状态一眼可辨
internal val RailStatusRunning = Color(0xFF3FB950) // 进行中 · 绿
internal val RailStatusWaiting = Color(0xFFE3B341) // 等待回答/排队/审批 · 琥珀
internal val RailStatusBlocked = Color(0xFFF0883E) // 已中断 · 橙
internal val RailStatusError = Color(0xFFF85149) // 出错 · 红
internal val RailStatusUnread = Color(0xFF58A6FF) // 有新回复 · 蓝
internal val RailStatusDone = Color(0xFF8B949E) // 已结束 · 灰
internal val RailStatusOffline = Color(0xFF6E7681) // 离线 · 暗灰

internal data class RailSessionStatus(
    val labelRes: Int?,
    val color: Color,
    val priority: Int,
    // 需要人处理：等待回答/审批/出错/中断/有新回复
    val attention: Boolean = false,
)

internal fun railSessionStatus(session: AgentSession): RailSessionStatus =
    when {
        !session.connectorOnline -> RailSessionStatus(R.string.rail_status_offline, RailStatusOffline, 9)
        session.status == SessionStatus.Running -> RailSessionStatus(R.string.rail_status_running, RailStatusRunning, 0)
        session.status == SessionStatus.Error -> RailSessionStatus(R.string.rail_status_error, RailStatusError, 1, attention = true)
        session.status == SessionStatus.Blocked -> RailSessionStatus(R.string.rail_status_blocked, RailStatusBlocked, 1, attention = true)
        session.status == SessionStatus.WaitingApproval -> RailSessionStatus(R.string.home_session_status_waiting_approval, RailStatusWaiting, 2, attention = true)
        session.status == SessionStatus.Waiting -> RailSessionStatus(R.string.rail_status_waiting, RailStatusWaiting, 3, attention = true)
        session.status == SessionStatus.Pending -> RailSessionStatus(R.string.rail_status_pending, RailStatusWaiting, 4)
        session.status == SessionStatus.Idle && session.unread -> RailSessionStatus(null, RailStatusUnread, 4, attention = true)
        session.status == SessionStatus.Stopping -> RailSessionStatus(R.string.rail_status_stopping, RailStatusDone, 7)
        session.status == SessionStatus.Idle -> RailSessionStatus(R.string.rail_status_idle, RailStatusDone, 8)
        else -> RailSessionStatus(null, RailStatusDone, 10)
    }

// 项目内排序：置顶最前，其次需要人处理的，再其次进行中，其余保持服务端顺序
internal fun railSessionOrder(session: AgentSession): Int =
    when {
        session.pinned -> 0
        railSessionStatus(session).attention -> 1
        session.status == SessionStatus.Running -> 2
        else -> 3
    }
