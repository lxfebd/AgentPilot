package com.agentpilot.app.feature.sessiondetail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DSH 先发一条瞬态 assistant_activity（例如"压缩中…"），再发最终 message。
 * 契约 timeline-item 的 status 枚举包含 done/failed/cancelled/interrupted ——
 * 只要轮次终结，活动行就该退场。此前只认 done，导致失败轮次（例如账号未登录
 * 的 401）把"压缩中"永久留在时间线上，表现为刷屏不消失。
 */
internal class DshAssistantActivityReconcileTest {

    // 候选行必须是已完结的 message 行（type=message + sourceItemType=assistant_activity），
    // 否则它会在终态检查时被提前跳过、永远进不了候选表。
    private fun activity(id: String, hash: String = "h1", text: String = "压缩中…", status: String = "done") =
        TimelineMessage(
            id = id,
            author = MessageAuthor.Agent,
            text = text,
            status = status,
            sourceRuntime = "dsh",
            sourceItemType = "assistant_activity",
            contentHash = hash,
        )

    private fun message(id: String, status: String, hash: String = "h1", text: String = "Failed to authenticate. API Error: 401") =
        TimelineMessage(
            id = id,
            author = MessageAuthor.Agent,
            text = text,
            status = status,
            sourceRuntime = "dsh",
            sourceItemType = "message",
            contentHash = hash,
        )

    @Test
    fun `a done message retires the preceding activity row`() {
        val result = reconcileDshAssistantActivity(listOf(activity("a1"), message("m1", "done")))
        assertEquals(listOf("m1"), result.map { it.id })
    }

    @Test
    fun `a failed message retires the activity row too`() {
        // 账号未登录时轮次失败：活动行不能继续留在时间线上。
        val result = reconcileDshAssistantActivity(listOf(activity("a1"), message("m1", "failed")))
        assertEquals(listOf("m1"), result.map { it.id })
    }

    @Test
    fun `cancelled and interrupted messages retire the activity row as well`() {
        for (status in listOf("cancelled", "interrupted")) {
            val result = reconcileDshAssistantActivity(listOf(activity("a1"), message("m1", status)))
            assertEquals(listOf("m1"), result.map { it.id })
        }
    }

    @Test
    fun `a still running message keeps the activity row visible`() {
        val result = reconcileDshAssistantActivity(listOf(activity("a1"), message("m1", "running")))
        assertEquals(listOf("a1", "m1"), result.map { it.id })
    }

    @Test
    fun `an unfinished activity row is never a candidate`() {
        // status=running 的活动行进不了候选表，因此即便后来的消息已终结也不能收拢它。
        val result = reconcileDshAssistantActivity(listOf(activity("a1", status = "running"), message("m1", "failed")))
        assertEquals(listOf("a1", "m1"), result.map { it.id })
    }

    @Test
    fun `an activity row is never retired by an unrelated hash`() {
        val result = reconcileDshAssistantActivity(listOf(activity("a1", hash = "h1"), message("m1", "failed", hash = "h2")))
        assertEquals(listOf("a1", "m1"), result.map { it.id })
    }

    @Test
    fun `a user message resets the window so later equal hashes do not cross it`() {
        val user = TimelineMessage(id = "u1", author = MessageAuthor.User, text = "继续", status = "done")
        val result = reconcileDshAssistantActivity(
            listOf(activity("a1"), user, message("m1", "failed")),
        )
        assertTrue(result.any { it.id == "a1" && it.id != "m1" })
        assertEquals(listOf("a1", "u1", "m1"), result.map { it.id })
    }

    @Test
    fun `non-dsh runtimes are left untouched`() {
        val other = TimelineMessage(
            id = "a1",
            author = MessageAuthor.Agent,
            text = "compacting",
            status = "running",
            sourceRuntime = "claude",
            sourceItemType = "assistant_activity",
            contentHash = "h1",
        )
        val result = reconcileDshAssistantActivity(listOf(other, message("m1", "failed")))
        assertEquals(listOf("a1", "m1"), result.map { it.id })
    }
}

