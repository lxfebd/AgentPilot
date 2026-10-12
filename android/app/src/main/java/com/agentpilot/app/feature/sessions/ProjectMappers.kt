package com.agentpilot.app.feature.sessions

import com.agentpilot.app.api.RemoteProject
import com.agentpilot.app.model.AgentProject

internal fun RemoteProject.toAgentProject(): AgentProject {
    return AgentProject(
        id = id,
        userId = userId,
        connectorId = connectorId,
        name = name,
        workspacePath = workspacePath,
        pinned = pinned,
        pinnedAt = pinnedAt,
        activeSessionCount = activeSessionCount,
        lastActivityAt = lastActivityAt,
        createdAt = createdAt,
        updatedAt = updatedAt,
        manuallyCreated = manuallyCreated,
        sidebarSessionCounts = sidebarSessionCounts,
    )
}
