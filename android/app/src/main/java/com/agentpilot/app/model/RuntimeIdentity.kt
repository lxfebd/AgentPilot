package com.agentpilot.app.model

/**
 * Canonical provider key of one agent, resolved from every identity field a
 * session, device runtime or filter entry can carry.
 *
 * A session exposes `runtime` (legacy type), `runtimeType` (Runtime Control 2.0
 * type), `runtimeId` (instance) and `runtimeName` (instance title). Any of them
 * can be the only populated one depending on which contract produced the row,
 * so the agent icon must never depend on a single field: a ZCode session that
 * arrives with only `runtimeId = "rti_zcode_primary"` must still render the
 * ZCode mark instead of the generic fallback.
 */
enum class AgentRuntimeKey {
    Codex,
    Claude,
    DeepSeek,
    ZCode,
    Unknown,
    ;

    val displayName: String
        get() = when (this) {
            Codex -> "Codex"
            Claude -> "Claude Code"
            DeepSeek -> "DeepSeek Harness"
            ZCode -> "ZCode"
            Unknown -> "Agent"
        }
}

private val RUNTIME_KEY_BY_TOKEN: Map<String, AgentRuntimeKey> = mapOf(
    "codex" to AgentRuntimeKey.Codex,
    "claudecode" to AgentRuntimeKey.Claude,
    "claude" to AgentRuntimeKey.Claude,
    "deepseekharness" to AgentRuntimeKey.DeepSeek,
    "deepseek" to AgentRuntimeKey.DeepSeek,
    "dsh" to AgentRuntimeKey.DeepSeek,
    "zcode" to AgentRuntimeKey.ZCode,
)

/**
 * Normalize one identity token: lowercase and drop the separators a provider key
 * never carries (`-`, `_`, whitespace), so `ZCode`, `z_code` and `zcode` agree.
 */
internal fun normalizeRuntimeToken(value: String?): String =
    value.orEmpty().trim().lowercase().filterNot { it.isWhitespace() || it == '_' || it == '-' }

/**
 * Resolve the provider key from any single identity field. Instance ids
 * (`rti_<type>_<name>`) and instance titles (`ZCode work`) still resolve to the
 * provider they belong to, so a named instance never loses its brand mark.
 */
fun resolveAgentRuntimeKey(value: String?): AgentRuntimeKey {
    val token = normalizeRuntimeToken(value)
    if (token.isEmpty()) return AgentRuntimeKey.Unknown
    RUNTIME_KEY_BY_TOKEN[token]?.let { return it }
    // Instance ids are namespaced by their provider: rti_<type>_<name>.
    if (token.startsWith("rti")) {
        val provider = token.removePrefix("rti").split("_").firstOrNull { it.isNotBlank() }
        RUNTIME_KEY_BY_TOKEN[provider.orEmpty()]?.let { return it }
    }
    // A title can name the provider anywhere in it ("my claude box"); prefer the
    // longest known token so "deepseekharness" wins over the "deepseek" prefix.
    return RUNTIME_KEY_BY_TOKEN.entries
        .filter { (known, _) -> token.contains(known) }
        .maxByOrNull { (known, _) -> known.length }
        ?.value
        ?: AgentRuntimeKey.Unknown
}

/**
 * Resolve the provider key of one session. `runtimeType` is the canonical
 * Runtime Control 2.0 field, so it wins when present; every other identity
 * field is a fallback for rows produced by an older contract.
 */
fun AgentSession.agentRuntimeKey(): AgentRuntimeKey {
    resolveAgentRuntimeKey(runtimeType)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    resolveAgentRuntimeKey(runtime)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    resolveAgentRuntimeKey(runtimeId)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    return resolveAgentRuntimeKey(runtimeName)
}

/**
 * Resolve the provider key from the identity fields a device runtime or filter
 * entry carries, in the same precedence the session path uses.
 */
fun resolveAgentRuntimeKey(
    runtime: String?,
    runtimeType: String?,
    runtimeId: String? = null,
    runtimeName: String? = null,
): AgentRuntimeKey {
    resolveAgentRuntimeKey(runtimeType)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    resolveAgentRuntimeKey(runtime)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    resolveAgentRuntimeKey(runtimeId)
        .takeIf { it != AgentRuntimeKey.Unknown }
        ?.let { return it }
    return resolveAgentRuntimeKey(runtimeName)
}

data class RuntimeInstanceLabels(
    val primary: String,
    val secondary: String?,
)

fun String.runtimeTypeLabel(): String {
    return when (resolveAgentRuntimeKey(this)) {
        AgentRuntimeKey.Codex -> "Codex"
        AgentRuntimeKey.Claude -> "Claude Code"
        AgentRuntimeKey.DeepSeek -> "DeepSeek Harness"
        AgentRuntimeKey.ZCode -> "ZCode"
        AgentRuntimeKey.Unknown -> replaceFirstChar { char ->
            if (char.isLowerCase()) char.titlecase() else char.toString()
        }
    }
}

fun runtimeInstanceLabels(name: String?, runtimeType: String): RuntimeInstanceLabels {
    val typeLabel = runtimeType.runtimeTypeLabel()
    val instanceName = name?.trim()
        ?.takeIf(String::isNotBlank)
        ?.takeUnless { it.equals(runtimeType, ignoreCase = true) }
    val primary = instanceName ?: typeLabel
    return RuntimeInstanceLabels(
        primary = primary,
        secondary = typeLabel.takeUnless { it.equals(primary, ignoreCase = true) },
    )
}
