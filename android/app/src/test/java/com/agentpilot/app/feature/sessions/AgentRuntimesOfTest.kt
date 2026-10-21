package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimesOfTest {

    private fun session(id: String, runtime: String) = AgentSession(
        id = id,
        connectorId = "c1",
        projectId = null,
        deviceName = "Device",
        title = id,
        summary = "",
        cwd = null,
        workspaceLabel = "",
        runtime = runtime,
        runtimeLabel = runtime,
        status = SessionStatus.Idle,
        statusLabel = "Idle",
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
        runtimeId = runtime,
        runtimeType = runtime,
        runtimeName = runtime,
    )

    @Test
    fun `collapses several session lists into one ordered runtime set`() {
        val active = listOf(session("a", "zcode"), session("b", "dsh"))
        val projects = listOf(session("c", "zcode"), session("d", "claude"))

        assertEquals(
            listOf("claude", "dsh", "zcode"),
            agentRuntimesOf(active, projects),
        )
    }

    @Test
    fun `drops blank runtimes`() {
        val sessions = listOf(session("a", "dsh"), session("b", ""))

        assertEquals(listOf("dsh"), agentRuntimesOf(sessions))
    }

    @Test
    fun `no sessions yields no runtimes`() {
        assertEquals(emptyList<String>(), agentRuntimesOf())
        assertEquals(emptyList<String>(), agentRuntimesOf(emptyList()))
    }
}
