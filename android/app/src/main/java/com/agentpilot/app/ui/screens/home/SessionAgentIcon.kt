package com.agentpilot.app.ui.screens.home

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.agentpilot.app.R
import com.agentpilot.app.model.AgentRuntimeKey
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.agentRuntimeKey
import com.agentpilot.app.model.resolveAgentRuntimeKey
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.Lucide

/**
 * Brand mark of the agent that owns a session, a timeline segment or a filter
 * entry. Provider resolution is shared with [resolveAgentRuntimeKey] so every
 * surface picks the same mark: a ZCode session always renders the ZCode mark,
 * whatever identity field carried it.
 */
@Composable
internal fun SessionAgentIcon(
    key: AgentRuntimeKey,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val drawable = when (key) {
        AgentRuntimeKey.Codex -> R.drawable.ic_session_agent_codex
        AgentRuntimeKey.Claude -> R.drawable.ic_session_agent_claude
        AgentRuntimeKey.DeepSeek -> R.drawable.ic_session_agent_deepseek
        AgentRuntimeKey.ZCode -> R.drawable.ic_session_agent_zcode
        AgentRuntimeKey.Unknown -> null
    }
    val label = key.displayName
    val effectiveTint = tint ?: LocalAAColors.current.faint
    if (drawable != null) {
        Icon(
            painter = painterResource(drawable),
            contentDescription = label,
            tint = effectiveTint,
            modifier = modifier.size(16.dp),
        )
    } else {
        Icon(
            imageVector = Lucide.Bot,
            contentDescription = label,
            tint = effectiveTint,
            modifier = modifier.size(16.dp),
        )
    }
}

@Composable
internal fun SessionAgentIcon(
    session: AgentSession,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) = SessionAgentIcon(session.agentRuntimeKey(), modifier, tint)

/** Identity-only overload for device runtimes, filter entries and timeline segments. */
@Composable
internal fun SessionAgentIcon(
    runtime: String?,
    runtimeType: String?,
    runtimeId: String? = null,
    runtimeName: String? = null,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) = SessionAgentIcon(
    key = resolveAgentRuntimeKey(runtime, runtimeType, runtimeId, runtimeName),
    modifier = modifier,
    tint = tint,
)
