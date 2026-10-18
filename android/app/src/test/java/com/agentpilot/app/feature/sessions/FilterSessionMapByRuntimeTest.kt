package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterSessionMapByRuntimeTest {
    private fun session(id: String, runtime: String) = AgentSession(
        id = id,
        connectorId = "device-a",
        projectId = "project",
        deviceName = "Laptop",
        title = "Task",
        summary = "",
        cwd = null,
        workspaceLabel = "workspace",
        runtime = runtime,
        runtimeLabel = runtime,
        status = SessionStatus.Idle,
        statusLabel = "",
        updatedAtLabel = "",
        metaLabel = "",
        pinned = false,
        archived = false,
        unread = false,
        lastReadSeq = 0,
        takeover = false,
        connectorOnline = true,
        live = false,
        sortKey = "",
        updatedSeq = 0,
    )

    @Test
    fun nullRuntimeKeepsMapUntouched() {
        val sessions = listOf(session("s1", "dsh"), session("s2", "codex"))
        val map = mapOf("p1" to sessions)
        assertEquals(map, filterSessionMapByRuntime(map, null))
    }

    @Test
    fun runtimeFiltersSessionsPerProject() {
        val sessions = listOf(
            session("s1", "dsh"),
            session("s2", "codex"),
            session("s3", "claude"),
        )
        val map = mapOf(
            "p1" to sessions,
            "p2" to listOf(session("s4", "zcode")),
        )
        val filtered = filterSessionMapByRuntime(map, "codex")
        assertEquals(listOf("s2"), filtered["p1"]?.map { it.id })
        assertTrue(filtered["p2"].orEmpty().isEmpty())
    }

    @Test
    fun unknownRuntimeYieldsEmptyLists() {
        val map = mapOf("p1" to listOf(session("s1", "dsh")))
        val filtered = filterSessionMapByRuntime(map, "codex")
        assertTrue(filtered["p1"].orEmpty().isEmpty())
        assertEquals(setOf("p1"), filtered.keys)
    }
}
