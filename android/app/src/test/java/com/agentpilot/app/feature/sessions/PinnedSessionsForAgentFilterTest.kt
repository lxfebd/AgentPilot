package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinnedSessionsForAgentFilterTest {

    private fun session(
        id: String,
        runtime: String,
        pinned: Boolean,
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
        status = SessionStatus.Idle,
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
        sortKey = "",
        updatedSeq = 1,
        runtimeId = runtime,
        runtimeType = runtime,
        runtimeName = runtime,
    )

    @Test
    fun `only pinned sessions are returned`() {
        val sessions = listOf(
            session("a", "zcode", pinned = true),
            session("b", "zcode", pinned = false),
            session("c", "claude", pinned = true),
        )

        assertEquals(
            listOf("a", "c"),
            pinnedSessionsForAgentFilter(sessions).map { it.id },
        )
    }

    @Test
    fun `a pinned session of another agent survives the filter`() {
        val sessions = listOf(
            session("pinned-claude", "claude", pinned = true),
            session("zcode-one", "zcode", pinned = false),
        )

        // 过滤 zcode 时，置顶的 claude 会话仍要在置顶区可见，否则置顶失去意义。
        assertEquals(
            listOf("pinned-claude"),
            pinnedSessionsForAgentFilter(sessions).map { it.id },
        )
    }

    @Test
    fun `no pinned sessions yields nothing`() {
        val sessions = listOf(session("a", "zcode", pinned = false))

        assertTrue(pinnedSessionsForAgentFilter(sessions).isEmpty())
    }

    @Test
    fun `note is unnecessary without a filter`() {
        val pinned = listOf(session("a", "claude", pinned = true))

        assertFalse(pinnedSectionNeedsAgentNote(pinned, selectedAgentRuntime = null))
    }

    @Test
    fun `note is unnecessary when nothing is pinned`() {
        assertFalse(pinnedSectionNeedsAgentNote(emptyList(), selectedAgentRuntime = "zcode"))
    }

    @Test
    fun `note appears when a pinned session belongs to another agent`() {
        val pinned = listOf(
            session("a", "zcode", pinned = true),
            session("b", "claude", pinned = true),
        )

        assertTrue(pinnedSectionNeedsAgentNote(pinned, selectedAgentRuntime = "zcode"))
    }

    @Test
    fun `no note when every pinned session matches the filter`() {
        val pinned = listOf(
            session("a", "zcode", pinned = true),
            session("b", "zcode", pinned = true),
        )

        assertFalse(pinnedSectionNeedsAgentNote(pinned, selectedAgentRuntime = "zcode"))
    }
}
