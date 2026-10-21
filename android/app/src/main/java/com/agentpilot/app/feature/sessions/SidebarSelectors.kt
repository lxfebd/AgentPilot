package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import com.agentpilot.app.model.runtimeTypeLabel
import java.time.Instant

internal fun timestampMillis(value: String?): Long =
    value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrDefault(0L) } ?: 0L

/**
 * 置顶区要显示的会话：全部置顶会话，**不受 agent 过滤影响**。
 *
 * 置顶是用户主动保留的快速入口，按 agent 过滤时若一并过滤掉，用户就失去了
 * 置顶的意义。与「最近」区天然互斥（`recentSessions` 排除置顶会话），所以
 * 同一会话不会在两个分区重复出现。行内的 agent 图标已经表明它是否属于
 * 当前过滤的来源。
 */
fun pinnedSessionsForAgentFilter(sessions: List<AgentSession>): List<AgentSession> =
    sessions.filter { it.pinned }

/**
 * 置顶区是否需要标注「这些会话不都属于当前过滤的 agent」。
 *
 * 置顶区在过滤态下仍显示全部置顶会话，其中可能包含其它 agent 的会话；
 * 没有这句标注，用户会疑惑为什么过滤后还看到别的来源。无过滤、
 * 或置顶会话全部属于当前 agent 时不需要标注。
 */
fun pinnedSectionNeedsAgentNote(
    pinnedSessions: List<AgentSession>,
    selectedAgentRuntime: String?,
): Boolean {
    if (selectedAgentRuntime == null || pinnedSessions.isEmpty()) return false
    return pinnedSessions.any { it.runtime != selectedAgentRuntime }
}

/**
 * 会话清单里出现过的 agent 来源（runtime 类型），按展示名排序、去重。
 * 手机首页过滤栏、项目模式列表与平板侧边栏共用同一份数据，避免三处各算一遍。
 */
fun agentRuntimesOf(vararg sessionLists: List<AgentSession>): List<String> =
    sessionLists.asSequence()
        .flatten()
        .map(AgentSession::runtime)
        .filter(String::isNotBlank)
        .distinct()
        .sortedBy(String::runtimeTypeLabel)
        .toList()

/**
 * 按 agent 来源分组的会话段。手机侧边栏与平板侧边栏共用同一分组语义：
 * 组内按 [sessionListComparator] 排序（置顶、进行中优先，然后按活动时间），
 * 组与组之间按各自最新一条会话的活动时间排，最活跃的 agent 在最上面。
 */
data class AgentSessionGroup(
    val runtime: String,
    val sessions: List<AgentSession>,
)

/**
 * 侧边栏要展示的项目集合。
 *
 * 未过滤时保留所有项目：空项目仍是新建会话的入口。过滤激活时，没有可见会话的
 * 项目整块都是空内容（项目头 + “无会话”），会把侧边栏填成噪音，因此隐藏。
 */
fun visibleProjectsForAgentFilter(
    projects: List<AgentProject>,
    sessionsByProject: Map<String, List<AgentSession>>,
    agentFilterActive: Boolean,
): List<AgentProject> {
    if (!agentFilterActive) return projects
    return projects.filter { project ->
        sessionsByProject[project.id].orEmpty().isNotEmpty()
    }
}

fun groupSessionsByAgent(
    sessions: List<AgentSession>,
    now: Long = System.currentTimeMillis(),
): List<AgentSessionGroup> {
    val comparator = sessionListComparator(now)
    return sessions
        .groupBy { it.runtime }
        .map { (runtime, group) ->
            AgentSessionGroup(runtime = runtime, sessions = group.sortedWith(comparator))
        }
        .sortedWith(
            compareByDescending<AgentSessionGroup> { group ->
                group.sessions.maxOfOrNull { timestampMillis(it.sortKey) } ?: 0L
            }.thenBy { it.runtime.runtimeTypeLabel() },
        )
}

fun sessionListComparator(now: Long = System.currentTimeMillis()): Comparator<AgentSession> = Comparator { left, right ->
    val pinned = right.pinned.compareTo(left.pinned)
    val leftRunning = left.status == SessionStatus.Running || left.optimisticTopUntil > now
    val rightRunning = right.status == SessionStatus.Running || right.optimisticTopUntil > now
    when {
        pinned != 0 -> pinned
        leftRunning != rightRunning -> if (leftRunning) -1 else 1
        leftRunning -> left.id.compareTo(right.id)
        else -> timestampMillis(right.sortKey).compareTo(timestampMillis(left.sortKey))
            .takeIf { it != 0 } ?: right.id.compareTo(left.id)
    }
}

fun archivedSessionComparator(): Comparator<AgentSession> =
    compareByDescending<AgentSession> { timestampMillis(it.archivedAt ?: it.sortKey) }.thenBy { it.id }

enum class ProjectSessionStatusFilter(val archiveStates: List<Boolean>) {
    Active(listOf(false)),
    Archived(listOf(true)),
    All(listOf(false, true)),
}

data class ProjectSessionLoadKey(val projectId: String, val archived: Boolean)

data class ProjectDeviceAgentFilter(
    val connectorId: String? = null,
    val runtime: String? = null,
) {
    val active: Boolean get() = connectorId != null || runtime != null

    fun matches(session: AgentSession): Boolean =
        (connectorId == null || session.connectorId == connectorId) &&
            (runtime == null || session.runtime == runtime)
}

fun projectSessionMatchesStatus(session: AgentSession, status: ProjectSessionStatusFilter): Boolean = when (status) {
    ProjectSessionStatusFilter.Active -> !session.archived && !session.pinned
    ProjectSessionStatusFilter.Archived -> session.archived
    ProjectSessionStatusFilter.All -> session.archived || !session.pinned
}

fun projectHasVisibleSessions(
    project: AgentProject,
    sessions: Collection<AgentSession>,
    status: ProjectSessionStatusFilter,
    filter: ProjectDeviceAgentFilter = ProjectDeviceAgentFilter(),
): Boolean {
    if (filter.active) {
        if (filter.connectorId != null && project.connectorId != filter.connectorId) return false
        return sessions.any { session ->
            session.projectId == project.id && projectSessionMatchesStatus(session, status) && filter.matches(session)
        }
    }
    return project.manuallyCreated || (project.sidebarSessionCounts?.let { counts ->
        when (status) {
            ProjectSessionStatusFilter.Active -> counts.active > 0
            ProjectSessionStatusFilter.Archived -> counts.archived > 0
            ProjectSessionStatusFilter.All -> counts.active + counts.archived > 0
        }
    } ?: sessions.any { it.projectId == project.id && projectSessionMatchesStatus(it, status) })
}

fun projectHasActiveSessions(project: AgentProject, sessions: Collection<AgentSession>): Boolean =
    projectHasVisibleSessions(project, sessions, ProjectSessionStatusFilter.Active)

/**
 * 按 agent runtime 过滤「项目 id → 会话列表」映射；runtime 为 null 时原样返回。
 * 手机首页列表与平板侧边栏共用同一过滤语义。
 */
fun filterSessionMapByRuntime(
    sessionsByProject: Map<String, List<AgentSession>>,
    runtime: String?,
): Map<String, List<AgentSession>> {
    if (runtime == null) return sessionsByProject
    return sessionsByProject.mapValues { (_, sessions) ->
        sessions.filter { it.runtime == runtime }
    }
}

fun sortProjectsByActivity(projects: List<AgentProject>, sessions: Collection<AgentSession>): List<AgentProject> {
    val activity = sessions.filter { !it.projectId.isNullOrBlank() }.groupBy { it.projectId }
        .mapValues { (_, items) -> items.maxOf { timestampMillis(it.sortKey) } }
    fun empty(project: AgentProject) = project.manuallyCreated && project.lastActivityAt.isNullOrBlank()
        && project.id !in activity && project.activeSessionCount == 0
        && (project.sidebarSessionCounts?.active ?: 0) == 0 && (project.sidebarSessionCounts?.archived ?: 0) == 0
    return projects.sortedWith(
        compareByDescending<AgentProject> { empty(it) }
            .thenByDescending { maxOf(timestampMillis(it.lastActivityAt), activity[it.id] ?: 0L) }
            .thenByDescending { timestampMillis(it.createdAt) }
            .thenBy { it.name }.thenBy { it.id },
    )
}
