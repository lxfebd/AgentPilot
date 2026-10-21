package com.agentpilot.app.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OptimisticTimelineReconcileTest {

    private fun optimistic(
        id: String,
        status: String = "running",
        orderSeq: Int,
    ) = TimelineMessage(
        id = id,
        sourceItemId = id,
        author = MessageAuthor.User,
        text = "sent from the phone",
        status = status,
        orderSeq = orderSeq,
        clientMessageId = id,
        optimistic = true,
    )

    private fun real(
        id: String,
        orderSeq: Int,
        clientMessageId: String? = null,
    ) = TimelineMessage(
        id = id,
        sourceItemId = id,
        author = MessageAuthor.User,
        text = "sent from the phone",
        status = "done",
        orderSeq = orderSeq,
        clientMessageId = clientMessageId,
    )

    @Test
    fun `echoed client message id retires the placeholder`() {
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(real("server-1", orderSeq = 50, clientMessageId = "opt_1")),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            storedMessages = emptyList(),
            orderingItems = emptyList(),
        )

        assertTrue(messages.pending.isEmpty())
        assertTrue(messages.messages.none { it.optimistic })
        assertEquals(listOf("server-1"), messages.messages.map { it.id })
    }

    @Test
    fun `a newer real user message retires an unmatched placeholder`() {
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(real("server-1", orderSeq = 50)),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            storedMessages = emptyList(),
            orderingItems = emptyList(),
        )

        assertTrue(messages.pending.isEmpty())
        assertEquals(listOf("server-1"), messages.messages.map { it.id })
    }

    @Test
    fun `an older real user message keeps the placeholder pending`() {
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(real("server-1", orderSeq = 5)),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            storedMessages = emptyList(),
            orderingItems = emptyList(),
        )

        assertEquals(listOf("opt_1"), messages.pending.map { it.id })
        assertEquals(listOf("server-1", "opt_1"), messages.messages.map { it.id })
    }

    @Test
    fun `an agent message never retires a placeholder`() {
        val agent = TimelineMessage(
            id = "server-agent",
            sourceItemId = "server-agent",
            author = MessageAuthor.Agent,
            text = "answer",
            orderSeq = 50,
        )
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(agent),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            storedMessages = emptyList(),
            orderingItems = emptyList(),
        )

        assertEquals(listOf("opt_1"), messages.pending.map { it.id })
    }

    @Test
    fun `ordering map order decides retirement`() {
        val ordering = listOf(TimelineOrderingItem(id = "server-1", orderSeq = 90, revision = 1, updatedSeq = 1))
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(real("server-1", orderSeq = 1)),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            storedMessages = emptyList(),
            orderingItems = ordering,
        )

        assertTrue(messages.pending.isEmpty())
    }

    @Test
    fun `retired placeholder carries its attachment preview onto the real message`() {
        val preview = TimelineAttachment(
            fileId = "file-1",
            name = "photo.png",
            mediaType = "image/png",
            size = 12,
            localPreviewUri = "content://preview/1",
        )
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(
                real("server-1", orderSeq = 50).copy(attachments = listOf(preview.copy(localPreviewUri = null))),
            ),
            currentMessages = listOf(optimistic("opt_1", orderSeq = 10).copy(attachments = listOf(preview))),
            storedMessages = emptyList(),
            orderingItems = emptyList(),
        )

        val carried = messages.messages.single { it.id == "server-1" }
        assertEquals("content://preview/1", carried.attachments.single().localPreviewUri)
        assertTrue(messages.pending.isEmpty())
    }

    @Test
    fun `stored placeholder is retired without the current list`() {
        val messages = mergeOptimisticTimelineMessages(
            realMessages = listOf(real("server-1", orderSeq = 50)),
            currentMessages = emptyList(),
            storedMessages = listOf(optimistic("opt_1", orderSeq = 10)),
            orderingItems = emptyList(),
        )

        assertTrue(messages.pending.isEmpty())
        assertEquals(listOf("server-1"), messages.messages.map { it.id })
    }
}
