package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class VisibleProjectsForAgentFilterTest {

    private fun project(id: String) = AgentProject(
        id = id,
        userId = "u1",
        connectorId = "c1",
        name = id,
        workspacePath = "/work/$id",
        pinned = false,
        pinnedAt = null,
        activeSessionCount = 0,
        lastActivityAt = null,
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
    )

    private fun session(id: String, projectId: String) = AgentSession(
        id = id,
        connectorId = "c1",
        projectId = projectId,
        deviceName = "Device",
        title = id,
        summary = "",
        cwd = null,
        workspaceLabel = "",
        runtime = "zcode",
        runtimeLabel = "zcode",
        status = SessionStatus.Idle,
        statusLabel = "",
        updatedAtLabel = "now",
        metaLabel = "",
        pinned = false,
        archived = false,
        unread = false,
        lastReadSeq = 0,
        takeover = false,
        connectorOnline = true,
        live = false,
        sortKey = "",
        updatedSeq = 1,
        runtimeId = "zcode",
        runtimeType = "zcode",
        runtimeName = "zcode",
    )

    @Test
    fun `without a filter every project stays visible`() {
        val projects = listOf(project("p1"), project("p2"))

        assertEquals(
            projects,
            visibleProjectsForAgentFilter(projects, sessionsByProject = emptyMap(), agentFilterActive = false),
        )
    }

    @Test
    fun `a filter hides projects without any leftover session`() {
        val projects = listOf(project("p1"), project("p2"), project("p3"))
        val sessionsByProject = mapOf("p2" to listOf(session("s1", "p2")))

        val visible = visibleProjectsForAgentFilter(projects, sessionsByProject, agentFilterActive = true)

        // p1 与 p3 在过滤后没有会话，整块都是空内容。
        assertEquals(listOf("p2"), visible.map { it.id })
    }

    @Test
    fun `a filter keeps empty session lists out`() {
        val projects = listOf(project("p1"), project("p2"))
        val sessionsByProject = mapOf("p1" to emptyList<AgentSession>())

        assertEquals(
            emptyList<AgentProject>(),
            visibleProjectsForAgentFilter(projects, sessionsByProject, agentFilterActive = true),
        )
    }

    @Test
    fun `no projects stays empty`() {
        assertEquals(
            emptyList<AgentProject>(),
            visibleProjectsForAgentFilter(emptyList(), emptyMap(), agentFilterActive = true),
        )
    }
}
