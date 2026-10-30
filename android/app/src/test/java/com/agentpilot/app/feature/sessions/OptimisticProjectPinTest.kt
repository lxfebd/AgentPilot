package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentProject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticProjectPinTest {

    private fun project(id: String, pinned: Boolean = false, pinnedAt: String? = null, updatedAt: String = "2026-01-01T00:00:00Z") =
        AgentProject(
            id = id,
            userId = "u1",
            connectorId = "c1",
            name = id,
            workspacePath = "/work/$id",
            pinned = pinned,
            pinnedAt = pinnedAt,
            activeSessionCount = 0,
            lastActivityAt = null,
            createdAt = "2026-01-01T00:00:00Z",
            updatedAt = updatedAt,
        )

    @Test
    fun `pinning optimistically moves the project above unpinned ones and stamps a time`() {
        val state = SessionsState(projects = listOf(project("a"), project("b"), project("c")))

        val next = state.withOptimisticProjectPin("b", pinned = true)

        assertTrue(next.projects.first().id == "b")
        assertTrue(next.projects.first().pinned)
        assertNotNull(next.projects.first().pinnedAt)
        assertEquals(listOf("a", "c"), next.projects.drop(1).map { it.id })
    }

    @Test
    fun `pinning sorts by the newest pin time above older pinned projects`() {
        val state = SessionsState(
            projects = listOf(project("a"), project("b", pinned = true, pinnedAt = "2026-02-01T00:00:00Z"), project("c")),
        )

        val next = state.withOptimisticProjectPin("c", pinned = true)

        // Both are pinned; the freshly stamped pin time (now) outranks the older one.
        assertEquals(listOf("c", "b", "a"), next.projects.map { it.id })
        assertTrue(next.projects.take(2).all { it.pinned })
        assertTrue(!next.projects.last().pinned)
    }

    @Test
    fun `unpinning clears the time and demotes the project`() {
        val state = SessionsState(
            projects = listOf(project("a"), project("b", pinned = true, pinnedAt = "2026-02-01T00:00:00Z"), project("c")),
        )

        val next = state.withOptimisticProjectPin("b", pinned = false)

        assertTrue(next.projects.first().id != "b")
        assertTrue(next.projects.none { it.id == "b" && it.pinned })
        assertNull(next.projects.first { it.id == "b" }.pinnedAt)
    }

    @Test
    fun `unknown project id leaves the state untouched`() {
        val state = SessionsState(projects = listOf(project("a")))
        assertSame(state, state.withOptimisticProjectPin("nope", pinned = true))
    }
}