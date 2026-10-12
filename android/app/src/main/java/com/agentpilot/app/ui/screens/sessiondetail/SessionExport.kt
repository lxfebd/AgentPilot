package com.agentpilot.app.ui.screens.sessiondetail

import com.agentpilot.app.feature.sessiondetail.MessageAuthor
import com.agentpilot.app.feature.sessiondetail.TimelineAgentCallAction
import com.agentpilot.app.feature.sessiondetail.TimelineMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话导出为 Markdown（2.0.8 纯客户端功能）。
 * 按 orderSeq 排序生成完整会话文本，含作者、命令、输出、文件变更、Agent 调用等。
 */
internal fun buildSessionMarkdown(
    messages: List<TimelineMessage>,
    sessionTitle: String,
    runtimeLabel: String,
    exportedAt: Long = System.currentTimeMillis(),
): String {
    val sb = StringBuilder()
    sb.append("# ").append(sessionTitle.ifBlank { "Agent 会话" }).append("\n\n")
    sb.append("- 导出时间：").append(formatTimestamp(exportedAt)).append("\n")
    if (runtimeLabel.isNotBlank()) {
        sb.append("- Agent：").append(runtimeLabel).append("\n")
    }
    sb.append("- 消息数：").append(messages.size).append("\n\n")
    sb.append("---\n\n")

    val ordered = messages.sortedBy { it.orderSeq }
    ordered.forEach { message ->
        appendMessage(sb, message)
    }
    return sb.toString()
}

private fun formatTimestamp(epochMillis: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(epochMillis))
}

private fun appendMessage(sb: StringBuilder, message: TimelineMessage) {
    val role = when (message.author) {
        MessageAuthor.User -> "我"
        MessageAuthor.Agent -> "Agent"
        else -> "系统"
    }
    val seq = if (message.orderSeq > 0) " #${message.orderSeq}" else ""
    sb.append("## ").append(role).append(seq)
    if (message.status == "running" || message.status == "pending") {
        sb.append("（进行中）")
    }
    if (message.errorMessage != null) {
        sb.append("（出错）")
    }
    sb.append("\n\n")

    // 文本内容
    message.text.trim().takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n\n") }
    if (message.text.isBlank()) {
        message.title.trim().takeIf { it.isNotEmpty() }?.let { sb.append("**").append(it).append("**\n\n") }
        message.subtitle.trim().takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n\n") }
        message.detail.trim().takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n\n") }
        message.body.trim().takeIf { it.isNotEmpty() }?.let { sb.append(it).append("\n\n") }
    }

    // 命令
    if (message.command.isNotBlank()) {
        sb.append("```\n").append(message.command.trimEnd()).append("\n```\n\n")
    }

    // 输入
    if (message.input.isNotBlank()) {
        sb.append("> 输入：").append(message.input.trim()).append("\n\n")
    }

    // 输出
    if (message.output.isNotBlank()) {
        sb.append("输出：\n\n```\n").append(message.output.trimEnd()).append("\n```\n\n")
    }

    // 工具错误
    if (message.toolError.isNotBlank()) {
        sb.append("**工具错误**：\n\n```\n").append(message.toolError.trimEnd()).append("\n```\n\n")
    }

    // 文件变更
    if (message.fileChanges.isNotEmpty()) {
        sb.append("文件变更：\n\n")
        message.fileChanges.forEach { change ->
            val action = when (change.action) {
                "create" -> "新建"
                "delete" -> "删除"
                "modify" -> "修改"
                else -> change.action
            }
            sb.append("- `").append(change.path).append("`（").append(action).append("）\n")
        }
        sb.append("\n")
    }

    // Agent 调用
    message.agentCall?.let { call ->
        val actionLabel = when (call.action) {
            TimelineAgentCallAction.Invoke -> "调用"
            TimelineAgentCallAction.Spawn -> "生成"
            TimelineAgentCallAction.SendInput -> "发送输入"
            TimelineAgentCallAction.Resume -> "继续"
            TimelineAgentCallAction.Wait -> "等待"
            TimelineAgentCallAction.Close -> "关闭"
            else -> "操作"
        }
        sb.append("*Agent ").append(actionLabel).append("：")
            .append(call.description.ifBlank { "—" }).append("*\n\n")
    }

    // 原始内容（未覆盖以上情况的补充）
    if (
        message.text.isBlank() && message.title.isBlank() && message.body.isBlank() &&
        message.command.isBlank() && message.output.isBlank() && message.input.isBlank() &&
        message.toolError.isBlank() && message.rawContent.isNotBlank()
    ) {
        sb.append(message.rawContent.trim()).append("\n\n")
    }

    sb.append("---\n\n")
}
