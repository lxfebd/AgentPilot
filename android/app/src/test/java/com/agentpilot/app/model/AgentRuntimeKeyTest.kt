package com.agentpilot.app.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentRuntimeKeyTest {

    @Test
    fun `canonical provider keys resolve`() {
        assertEquals(AgentRuntimeKey.Codex, resolveAgentRuntimeKey("codex"))
        assertEquals(AgentRuntimeKey.Claude, resolveAgentRuntimeKey("claude"))
        assertEquals(AgentRuntimeKey.Claude, resolveAgentRuntimeKey("claudecode"))
        assertEquals(AgentRuntimeKey.DeepSeek, resolveAgentRuntimeKey("dsh"))
        assertEquals(AgentRuntimeKey.DeepSeek, resolveAgentRuntimeKey("deepseek"))
        assertEquals(AgentRuntimeKey.ZCode, resolveAgentRuntimeKey("zcode"))
    }

    @Test
    fun `zcode keeps its own mark under every spelling`() {
        for (token in listOf("zcode", "ZCode", "Z_CODE", "z-code", " zcode ", "ZCODE")) {
            assertEquals(token, AgentRuntimeKey.ZCode, resolveAgentRuntimeKey(token))
        }
    }

    @Test
    fun `zcode never resolves to claude`() {
        for (token in listOf("zcode", "ZCode", "Z_CODE")) {
            assertEquals(
                "zcode must never borrow the Claude mark",
                AgentRuntimeKey.ZCode,
                resolveAgentRuntimeKey(token),
            )
        }
    }

    @Test
    fun `instance ids resolve to their provider`() {
        assertEquals(AgentRuntimeKey.ZCode, resolveAgentRuntimeKey("rti_zcode_primary"))
        assertEquals(AgentRuntimeKey.Claude, resolveAgentRuntimeKey("rti_claude_work"))
        assertEquals(AgentRuntimeKey.Codex, resolveAgentRuntimeKey("rti_codex_one"))
        assertEquals(AgentRuntimeKey.DeepSeek, resolveAgentRuntimeKey("rti_dsh_phone"))
    }

    @Test
    fun `instance titles resolve to their provider`() {
        assertEquals(AgentRuntimeKey.ZCode, resolveAgentRuntimeKey("ZCode work"))
        assertEquals(AgentRuntimeKey.Claude, resolveAgentRuntimeKey("my claude box"))
    }

    @Test
    fun `longest known token wins over a prefix`() {
        assertEquals(AgentRuntimeKey.DeepSeek, resolveAgentRuntimeKey("deepseekharness"))
    }

    @Test
    fun `blank and unknown values stay unknown`() {
        for (token in listOf(null, "", "   ", "acme", "opencode", "acp")) {
            assertEquals("$token", AgentRuntimeKey.Unknown, resolveAgentRuntimeKey(token))
        }
    }

    @Test
    fun `session resolution prefers runtime type then every identity field`() {
        val zcode = session(runtime = "dsh", runtimeType = "zcode", runtimeId = "rti_zcode_a", runtimeName = "ZCode")
        assertEquals(AgentRuntimeKey.ZCode, zcode.agentRuntimeKey())

        // An older contract row may only carry the legacy runtime type.
        assertEquals(AgentRuntimeKey.Claude, session(runtime = "claude", runtimeType = "", runtimeId = "", runtimeName = "").agentRuntimeKey())
        // A row whose type field is blank still resolves through the instance id.
        assertEquals(
            AgentRuntimeKey.ZCode,
            session(runtime = "", runtimeType = "", runtimeId = "rti_zcode_primary", runtimeName = "").agentRuntimeKey(),
        )
        // And through the instance title alone.
        assertEquals(
            AgentRuntimeKey.Codex,
            session(runtime = "", runtimeType = "", runtimeId = "", runtimeName = "Codex work").agentRuntimeKey(),
        )
    }

    @Test
    fun `display names are stable per provider`() {
        assertEquals("Codex", AgentRuntimeKey.Codex.displayName)
        assertEquals("Claude Code", AgentRuntimeKey.Claude.displayName)
        assertEquals("DeepSeek Harness", AgentRuntimeKey.DeepSeek.displayName)
        assertEquals("ZCode", AgentRuntimeKey.ZCode.displayName)
        assertEquals("Agent", AgentRuntimeKey.Unknown.displayName)
    }

    @Test
    fun `runtime type label uses the shared resolver`() {
        assertEquals("Codex", "codex".runtimeTypeLabel())
        assertEquals("Claude Code", "claude".runtimeTypeLabel())
        assertEquals("DeepSeek Harness", "dsh".runtimeTypeLabel())
        assertEquals("ZCode", "zcode".runtimeTypeLabel())
        assertEquals("Acme", "acme".runtimeTypeLabel())
    }

    @Test
    fun `runtime instance labels keep the instance name primary`() {
        val labels = runtimeInstanceLabels("ZCode work", "zcode")
        assertEquals("ZCode work", labels.primary)
        assertEquals("ZCode", labels.secondary)

        val unnamed = runtimeInstanceLabels("zcode", "zcode")
        assertEquals("ZCode", unnamed.primary)
        assertEquals(null, unnamed.secondary)
    }

    private fun session(
        runtime: String,
        runtimeType: String,
        runtimeId: String,
        runtimeName: String,
    ) = AgentSession(
        id = "s1",
        connectorId = "c1",
        projectId = null,
        deviceName = "Device",
        title = "Session",
        summary = "",
        cwd = null,
        workspaceLabel = "",
        runtime = runtime,
        runtimeLabel = runtimeName.ifBlank { runtime },
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
        runtimeId = runtimeId.ifBlank { runtime },
        runtimeType = runtimeType.ifBlank { runtime },
        runtimeName = runtimeName.ifBlank { runtime },
    )
}
