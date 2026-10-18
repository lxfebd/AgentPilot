package com.agentpilot.app.ui.screens.sessiondetail

import com.agentpilot.app.feature.sessiondetail.MessageAuthor
import com.agentpilot.app.feature.sessiondetail.TimelineMessage
import com.agentpilot.app.feature.sessiondetail.TimelineMessageKind
import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineRuntimeDividersTest {

    private fun message(
        id: String,
        runtime: String? = "dsh",
        kind: TimelineMessageKind = TimelineMessageKind.Text,
        author: MessageAuthor = MessageAuthor.Agent,
    ) = TimelineMessage(
        id = id,
        sourceItemId = id,
        author = author,
        text = "content $id",
        kind = kind,
        sourceRuntime = runtime,
    )

    private fun single(id: String, runtime: String? = "dsh") =
        TimelineRenderItem.Single(message(id, runtime))

    @Test
    fun `single source labels the segment at its newest end`() {
        val items = listOf(
            single("a", "dsh"),
            single("b", "dsh"),
            single("c", "dsh"),
        )
        // 单来源会话：只贴一次来源标签，位置在段末（视觉底部、最新消息）上方
        assertEquals(mapOf("c" to "dsh"), computeRuntimeDividers(items))
    }

    @Test
    fun `source switch places divider at each segment end`() {
        val items = listOf(
            single("a", "dsh"),
            single("b", "zcode"),
            single("c", "zcode"),
            single("d", "dsh"),
        )
        val dividers = computeRuntimeDividers(items)
        // 三个段：[a]=dsh、[b,c]=zcode、[d]=dsh，分隔条各贴段末（最新一条）
        assertEquals(mapOf("a" to "dsh", "c" to "zcode", "d" to "dsh"), dividers)
    }

    @Test
    fun `blank source segment produces no divider`() {
        val items = listOf(
            single("a", "dsh"),
            single("b", null),
            single("c", "zcode"),
        )
        val dividers = computeRuntimeDividers(items)
        assertEquals(mapOf("a" to "dsh", "c" to "zcode"), dividers)
    }

    @Test
    fun `tool run group source comes from its first message`() {
        val toolRun = TimelineRenderItem.ToolRun(
            listOf(
                message("t1", "zcode", TimelineMessageKind.Command, MessageAuthor.Tool),
                message("t2", null, TimelineMessageKind.Command, MessageAuthor.Tool),
            ),
        )
        val items = listOf(single("a", "dsh"), toolRun)
        val dividers = computeRuntimeDividers(items)
        assertEquals(mapOf("a" to "dsh", toolRun.key to "zcode"), dividers)
    }

    @Test
    fun `adjacent blank items do not create phantom source`() {
        val items = listOf(
            single("a", null),
            single("b", null),
            single("c", "zcode"),
        )
        val dividers = computeRuntimeDividers(items)
        assertEquals(mapOf("c" to "zcode"), dividers)
    }
}