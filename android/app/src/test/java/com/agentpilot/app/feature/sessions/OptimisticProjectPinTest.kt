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

    @Test
    fun `a refresh that lands mid-flight cannot flip the pin back`() {
        val state = SessionsState(projects = listOf(project("a"), project("b")))
        val optimistic = state.withOptimisticProjectPin("b", pinned = true)

        // The server list still carries pinned=false because the PATCH has not settled.
        val stale = optimistic.copy(projects = listOf(project("a"), project("b")), pendingProjectPins = optimistic.pendingProjectPins)
        val refreshed = stale.withPendingProjectPinsApplied()

        assertTrue(refreshed.projects.first().id == "b")
        assertTrue(refreshed.projects.first().pinned)
        assertEquals(optimistic.projects.first().pinnedAt, refreshed.projects.first().pinnedAt)
    }

    @Test
    fun `dashboard snapshot replacement keeps an in-flight pin`() {
        val state = SessionsState(projects = listOf(project("a"), project("b")))
        val optimistic = state.withOptimisticProjectPin("b", pinned = true)

        val loaded = SessionsState(projects = listOf(project("a"), project("b")))
        val replaced = optimistic.replacedByDashboardSnapshot(loaded)

        assertTrue(replaced.projects.first().id == "b")
        assertTrue(replaced.projects.first().pinned)
        assertEquals(setOf("b"), replaced.pendingProjectPins.keys)
    }

    @Test
    fun `confirming a pin applies the server row and stops overriding refreshes`() {
        val state = SessionsState(projects = listOf(project("a"), project("b")))
        val optimistic = state.withOptimisticProjectPin("b", pinned = true)

        val confirmed = optimistic.withConfirmedProjectPin(
            "b",
            project("b", pinned = true, pinnedAt = "2026-03-01T00:00:00Z", updatedAt = "2026-03-01T00:00:00Z"),
        )

        assertTrue(confirmed.pendingProjectPins.isEmpty())
        assertEquals("2026-03-01T00:00:00Z", confirmed.projects.first { it.id == "b" }.pinnedAt)
    }

    @Test
    fun `a refused pin restores the pre-tap row and clears the override`() {
        val state = SessionsState(projects = listOf(project("a"), project("b")))
        val optimistic = state.withOptimisticProjectPin("b", pinned = true)

        val rolledBack = optimistic.withRolledBackProjectPin("b")

        assertTrue(rolledBack.pendingProjectPins.isEmpty())
        assertTrue(rolledBack.projects.none { it.id == "b" && it.pinned })
        // Both rows tie on the sort keys, so only the pin flag has to be restored.
        assertEquals(state.projects.map { it.id }.toSet(), rolledBack.projects.map { it.id }.toSet())
        assertTrue(rolledBack.projects.none { it.pinned })
    }
}