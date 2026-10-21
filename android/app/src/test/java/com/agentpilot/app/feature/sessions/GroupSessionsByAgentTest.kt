package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class GroupSessionsByAgentTest {

    private fun session(
        id: String,
        runtime: String,
        sortAt: String? = null,
        pinned: Boolean = false,
        running: Boolean = false,
    ) = AgentSession(
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
        status = if (running) SessionStatus.Running else SessionStatus.Idle,
        statusLabel = "",
        updatedAtLabel = "now",
        metaLabel = "",
        pinned = pinned,
        archived = false,
        unread = false,
        lastReadSeq = 0,
        takeover = false,
        connectorOnline = true,
        live = false,
        sortKey = sortAt.orEmpty(),
        updatedSeq = 1,
        runtimeId = runtime,
        runtimeType = runtime,
        runtimeName = runtime,
        sortAt = sortAt,
    )

    @Test
    fun `groups every agent with its own sessions`() {
        val sessions = listOf(
            session("a", "zcode", "2026-01-01T00:00:00Z"),
            session("b", "claude", "2026-01-02T00:00:00Z"),
            session("c", "zcode", "2026-01-03T00:00:00Z"),
        )

        val groups = groupSessionsByAgent(sessions)

        assertEquals(setOf("zcode", "claude"), groups.map { it.runtime }.toSet())
        assertEquals(listOf("c", "a"), groups.first { it.runtime == "zcode" }.sessions.map { it.id })
        assertEquals(listOf("b"), groups.first { it.runtime == "claude" }.sessions.map { it.id })
    }

    @Test
    fun `most active agent group comes first`() {
        val sessions = listOf(
            session("stale-zcode", "zcode", "2026-01-01T00:00:00Z"),
            session("fresh-claude", "claude", "2026-01-09T00:00:00Z"),
        )

        assertEquals(listOf("claude", "zcode"), groupSessionsByAgent(sessions).map { it.runtime })
    }

    @Test
    fun `running session outranks idle ones inside its group`() {
        val sessions = listOf(
            session("idle", "dsh", "2026-01-09T00:00:00Z"),
            session("busy", "dsh", "2026-01-01T00:00:00Z", running = true),
        )

        assertEquals(listOf("busy", "idle"), groupSessionsByAgent(sessions).single().sessions.map { it.id })
    }

    @Test
    fun `single agent yields a single group`() {
        val sessions = listOf(session("a", "codex"), session("b", "codex"))

        val groups = groupSessionsByAgent(sessions)

        assertEquals(1, groups.size)
        assertEquals("codex", groups.single().runtime)
        // 无活动时间时按共享比较器的 id 顺序（降序）排列，与列表其它分区一致。
        assertEquals(listOf("b", "a"), groups.single().sessions.map { it.id })
    }

    @Test
    fun `no sessions yields no groups`() {
        assertEquals(emptyList<AgentSessionGroup>(), groupSessionsByAgent(emptyList()))
    }

    @Test
    fun `groups are ordered by label when activity ties`() {
        val sessions = listOf(
            session("a", "zcode", "2026-01-01T00:00:00Z"),
            session("b", "claude", "2026-01-01T00:00:00Z"),
        )

        assertEquals(listOf("claude", "zcode"), groupSessionsByAgent(sessions).map { it.runtime })
    }
}
