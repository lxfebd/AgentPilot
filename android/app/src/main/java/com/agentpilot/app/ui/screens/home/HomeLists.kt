package com.agentpilot.app.ui.screens.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agentpilot.app.R
import com.agentpilot.app.feature.devices.DeviceAgentPreviews
import com.agentpilot.app.feature.sessions.SessionListIndicator
import com.agentpilot.app.feature.sessions.SessionsState
import com.agentpilot.app.feature.sessions.groupSessionsByAgent
import com.agentpilot.app.feature.sessions.listIndicator
import com.agentpilot.app.feature.sessions.pinnedSessions
import com.agentpilot.app.feature.sessions.pinnedSessionsForAgentFilter
import com.agentpilot.app.feature.sessions.pinnedSectionNeedsAgentNote
import com.agentpilot.app.feature.sessions.recentSessions
import com.agentpilot.app.model.AgentDevice
import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.resolveAgentRuntimeKey
import com.agentpilot.app.model.runtimeTypeLabel
import com.agentpilot.app.ui.designsystem.AuthErrorNotice
import com.agentpilot.app.ui.designsystem.AAFont
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.screens.common.AppEmptyState
import com.agentpilot.app.ui.screens.devices.DeviceRow
import com.agentpilot.app.ui.screens.devices.sortedForDevicesPage
import com.composables.icons.lucide.Bot
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Monitor
import com.composables.icons.lucide.Plus
import com.valentinilk.shimmer.shimmer

import com.agentpilot.app.ui.designsystem.AAShapes
@Composable
internal fun HomeList(
    state: SessionsState,
    tab: HomeTab,
    darkMode: Boolean,
    selectedAgentRuntime: String? = null,
    onSelectAgentRuntime: (String?) -> Unit = {},
    onSessionLongPress: (AgentSession, Rect) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    onOpenDevice: (AgentDevice) -> Unit,
    deviceAgentPreviews: DeviceAgentPreviews,
    onCreateSession: () -> Unit,
    onPairDevice: () -> Unit,
    onLoadMore: (HomeTab) -> Unit,
) {
    val devices = remember(state.devices) { state.devices.sortedForDevicesPage() }
    val sessions = remember(state.sessions, state.archivedSessions, tab, selectedAgentRuntime) {
        val source = if (tab == HomeTab.Active) state.sessions else state.archivedSessions
        if (selectedAgentRuntime == null) source else source.filter { it.runtime == selectedAgentRuntime }
    }
    val hasAnySessions = state.sessions.isNotEmpty() || state.archivedSessions.isNotEmpty()
    when {
        state.isLoading && !state.hasLoaded -> HomeLoadingState()
        state.errorMessage != null && !state.hasLoaded -> AuthErrorNotice(
            message = state.errorMessage,
            modifier = Modifier.padding(top = 10.dp),
        )
        tab == HomeTab.Devices && devices.isEmpty() -> AppEmptyState(
            message = stringResource(R.string.home_devices_empty),
            buttonLabel = stringResource(R.string.home_pair_new_device),
            buttonIcon = Lucide.Monitor,
            onButtonClick = onPairDevice,
            contentOffsetY = (-32).dp,
        )
        tab == HomeTab.Devices -> DeviceList(
            devices = devices,
            darkMode = darkMode,
            agentPreviews = deviceAgentPreviews,
            onOpenDevice = onOpenDevice,
        )
        devices.isEmpty() -> AppEmptyState(
            message = stringResource(R.string.home_pair_device_first),
            buttonLabel = stringResource(R.string.home_pair_new_device),
            buttonIcon = Lucide.Monitor,
            onButtonClick = onPairDevice,
            contentOffsetY = (-32).dp,
        )
        sessions.isEmpty() && (tab == HomeTab.Active || !hasAnySessions) -> AppEmptyState(
            message = stringResource(if (tab == HomeTab.Active) R.string.home_no_active_sessions_create else R.string.home_no_archived_sessions_yet),
            buttonLabel = stringResource(R.string.home_create_new_session),
            buttonIcon = Lucide.Plus,
            onButtonClick = onCreateSession,
        )
        sessions.isEmpty() -> EmptyListText(
            stringResource(if (tab == HomeTab.Active) R.string.home_no_active_sessions else R.string.home_no_archived_sessions)
        )
        else -> SessionList(
            sessions = sessions,
            projects = state.projects,
            devices = state.devices,
            hasMore = if (tab == HomeTab.Active) state.activeHasMore else state.archivedHasMore,
            isLoadingMore = if (tab == HomeTab.Active) state.isLoadingMoreActive else state.isLoadingMoreArchived,
            onLoadMore = { onLoadMore(tab) },
            onSessionLongPress = onSessionLongPress,
            onOpenSession = onOpenSession,
            selectedAgentRuntime = selectedAgentRuntime,
            allSessions = if (tab == HomeTab.Active) state.sessions else state.archivedSessions,
            onSelectAgentRuntime = onSelectAgentRuntime,
        )
    }
}

@Composable
internal fun HomeLoadingState() {
    val colors = LocalAAColors.current
    val darkMode = colors.isDark
    val baseColor = if (darkMode) Color(0xFF1E1E22) else Color(0xFFEDEBE6)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .shimmer()
            .padding(top = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        item(key = "loading-label") {
            SkeletonLine(
                modifier = Modifier
                    .padding(horizontal = 24.dp)
                    .width(84.dp)
                    .height(16.dp),
                baseColor = baseColor,
                shape = CircleShape,
            )
        }
        items(6, key = { "loading-session-$it" }) { index ->
            SessionRowSkeleton(
                index = index,
                baseColor = baseColor,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

@Composable
private fun SessionRowSkeleton(
    index: Int,
    baseColor: Color,
    modifier: Modifier = Modifier,
) {
    val titleWidth = listOf(0.78f, 0.62f, 0.84f, 0.70f, 0.58f, 0.76f)[index % 6]
    val summaryWidth = listOf(0.92f, 0.84f, 0.74f, 0.88f, 0.80f, 0.68f)[index % 6]
    val metaWidth = listOf(0.50f, 0.42f, 0.56f, 0.46f, 0.38f, 0.52f)[index % 6]

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(82.dp),
    ) {
        SkeletonLine(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(titleWidth)
                .height(20.dp),
            baseColor = baseColor,
            shape = AAShapes.tinyShape,
        )
        SkeletonLine(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = 34.dp)
                .fillMaxWidth(summaryWidth)
                .height(15.dp),
            baseColor = baseColor,
            shape = RoundedCornerShape(7.dp),
        )
        SkeletonLine(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = 62.dp)
                .fillMaxWidth(metaWidth)
                .height(13.dp),
            baseColor = baseColor,
            shape = RoundedCornerShape(7.dp),
        )
    }
}

@Composable
private fun SkeletonLine(
    modifier: Modifier,
    baseColor: Color,
    shape: androidx.compose.ui.graphics.Shape,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(baseColor),
    )
}

@Composable
private fun SessionList(
    sessions: List<AgentSession>,
    projects: List<AgentProject>,
    devices: List<AgentDevice>,
    hasMore: Boolean,
    isLoadingMore: Boolean,
    onLoadMore: () -> Unit,
    onSessionLongPress: (AgentSession, Rect) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    selectedAgentRuntime: String? = null,
    allSessions: List<AgentSession> = sessions,
    onSelectAgentRuntime: (String?) -> Unit = {},
) {
    var pinnedExpanded by remember { mutableStateOf(true) }
    var recentExpanded by remember { mutableStateOf(true) }
    // 置顶区始终从完整清单取：置顶是用户主动保留的快速入口，
    // 按 agent 过滤时一并过滤掉会让用户失去置顶的意义。
    val pinned = remember(allSessions) { pinnedSessionsForAgentFilter(allSessions) }
    val recent = remember(sessions) { SessionsState(sessions = sessions).recentSessions }
    // 侧边栏按 agent 来源分类：每个 agent 一段，段头带该 agent 的品牌图标。
    // 组内与组间的排序规则见 groupSessionsByAgent。
    val agentGroups = remember(recent) { groupSessionsByAgent(recent) }
    val projectsById = remember(projects) { projects.associateBy { it.id } }
    val devicesById = remember(devices) { devices.associateBy { it.id } }
    val listState = rememberLazyListState()
    val shouldLoadMore by remember(listState, hasMore, isLoadingMore) {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            hasMore && !isLoadingMore && lastVisible >= listState.layoutInfo.totalItemsCount - 4
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (sessions.isEmpty()) {
            item("empty") { EmptyListText(stringResource(R.string.home_no_sessions_yet)) }
        }
        // 过滤态下置顶区被过滤空时，连段头一起隐藏：一个空段头加一行「无置顶会话」
        // 只是噪音，用户此刻要找的是某个 agent 的会话。未过滤时保留段头与空态文案，
        // 因为空段头提示用户「可以置顶会话」。
        if (pinned.isNotEmpty() || selectedAgentRuntime == null) {
            item("pinned-title") {
                HomeListSectionHeader(
                    label = stringResource(R.string.home_pinned),
                    expanded = pinnedExpanded,
                    onClick = { pinnedExpanded = !pinnedExpanded },
                )
            }
        }
        // 过滤态下置顶区仍显示全部置顶会话，其中可能有其它 agent 的会话；
        // 没有这句标注，用户会疑惑为什么过滤后还看到别的来源。
        if (pinnedExpanded &&
            pinned.isNotEmpty() &&
            pinnedSectionNeedsAgentNote(pinned, selectedAgentRuntime)
        ) {
            item("pinned-agent-note") {
                SectionNoteText(stringResource(R.string.home_pinned_agent_note))
            }
        }
        if (pinnedExpanded) {
            if (pinned.isEmpty()) {
                item("pinned-empty") { SectionEmptyText(stringResource(R.string.home_no_pinned_sessions)) }
            } else {
                items(pinned, key = { "pinned-${it.id}" }) { session ->
                    HomeSessionRow(
                        session = session,
                        contextLabel = session.sidebarContextLabel(
                            projectName = projectsById[session.projectId]?.name,
                            deviceName = devicesById[session.connectorId]?.name,
                        ),
                        showDivider = session.id != pinned.lastOrNull()?.id,
                        onClick = { onOpenSession(session) },
                        onLongPress = { bounds -> onSessionLongPress(session, bounds) },
                    )
                }
            }
        }
        item("recent-title") {
            HomeListSectionHeader(
                label = stringResource(R.string.home_recents),
                expanded = recentExpanded,
                onClick = { recentExpanded = !recentExpanded },
            )
        }
        if (recentExpanded) {
            if (recent.isEmpty()) {
                item("recent-empty") { SectionEmptyText(stringResource(R.string.home_no_recent_sessions)) }
            } else {
                val visibleGroups = if (selectedAgentRuntime == null) {
                    agentGroups
                } else {
                    agentGroups.filter { it.runtime == selectedAgentRuntime }
                }
                // 过滤态下只渲染选中的 agent 段，同时给一个「全部」出口，
                // 否则侧边栏里没有任何入口可以取消过滤。
                if (selectedAgentRuntime != null) {
                    item("agent-group-all") {
                        // 「全部」出口点回去后看到的是最近区的全部 agent 会话，
                        // 计数取完整清单（不含置顶，置顶单独分区）。
                        HomeAllAgentsHeader(
                            count = allSessions.count { !it.pinned },
                            onClick = { onSelectAgentRuntime(null) },
                        )
                    }
                }
                visibleGroups.forEach { group ->
                    item("agent-group-${group.runtime}") {
                        HomeAgentGroupHeader(
                            runtime = group.runtime,
                            count = group.sessions.size,
                            selected = selectedAgentRuntime == group.runtime,
                            onClick = {
                                onSelectAgentRuntime(
                                    if (selectedAgentRuntime == group.runtime) null else group.runtime,
                                )
                            },
                        )
                    }
                    items(group.sessions, key = { "agent-${group.runtime}-${it.id}" }) { session ->
                        HomeSessionRow(
                            session = session,
                            contextLabel = session.sidebarContextLabel(
                                projectName = projectsById[session.projectId]?.name,
                                deviceName = devicesById[session.connectorId]?.name,
                            ),
                            showDivider = session.id != group.sessions.lastOrNull()?.id,
                            onClick = { onOpenSession(session) },
                            onLongPress = { bounds -> onSessionLongPress(session, bounds) },
                        )
                    }
                }
            }
        }
        if (isLoadingMore) {
            item("loading-more") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = LocalAAColors.current.muted,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceList(
    devices: List<AgentDevice>,
    darkMode: Boolean,
    agentPreviews: DeviceAgentPreviews,
    onOpenDevice: (AgentDevice) -> Unit,
) {
    val onlineDevices = remember(devices) { devices.filter { it.online } }
    val offlineDevices = remember(devices) { devices.filterNot { it.online } }
    var onlineExpanded by remember(devices) { mutableStateOf(true) }
    var offlineExpanded by remember(devices) { mutableStateOf(true) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = 96.dp),
    ) {
        if (onlineDevices.isNotEmpty()) {
            item("online-title") {
                HomeSectionHeader(
                    label = stringResource(R.string.home_online),
                    expanded = onlineExpanded,
                    onClick = { onlineExpanded = !onlineExpanded },
                )
            }
            if (onlineExpanded) {
                items(onlineDevices, key = { "online-${it.id}" }) { device ->
                    DeviceRow(
                        device = device,
                        agentPreview = agentPreviews.byDeviceId[device.id],
                        darkMode = darkMode,
                        onClick = { onOpenDevice(device) },
                    )
                }
            }
        }
        if (offlineDevices.isNotEmpty()) {
            item("offline-title") {
                HomeSectionHeader(
                    label = stringResource(R.string.home_offline),
                    expanded = offlineExpanded,
                    onClick = { offlineExpanded = !offlineExpanded },
                )
            }
            if (offlineExpanded) {
                items(offlineDevices, key = { "offline-${it.id}" }) { device ->
                    DeviceRow(
                        device = device,
                        agentPreview = null,
                        darkMode = darkMode,
                        onClick = { onOpenDevice(device) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HomeSectionHeader(
    label: String,
    expanded: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(41.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = colors.faint,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
        Icon(
            imageVector = Lucide.ChevronDown,
            contentDescription = null,
            tint = colors.faint,
            modifier = Modifier
                .size(16.dp)
                .graphicsLayer { rotationZ = if (expanded) 0f else -90f },
        )
    }
}

/**
 * 过滤态下的「全部 agent」出口，与 agent 分段头同一条时间线上。
 */
@Composable
private fun HomeAllAgentsHeader(
    count: Int,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    val haptic = LocalHapticFeedback.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Lucide.Bot,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(16.dp),
            )
        }
        Text(
            text = stringResource(R.string.home_project_filter_all_agents),
            color = colors.ink,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
        Text(
            text = "$count",
            color = colors.faint.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AAFont.Mono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

/**
 * 侧边栏的 agent 分类段头：品牌图标 + agent 名称 + 该 agent 的会话数。
 * 图标与列表行、时间线来源分隔条共用同一套身份解析，保证同一个 agent
 * 在任何界面都显示同一个标志。段头同时承担 agent 过滤：点击只看该 agent，
 * 再次点击恢复全部（与原先独立的过滤栏合并为一处）。
 */
@Composable
private fun HomeAgentGroupHeader(
    runtime: String,
    count: Int,
    selected: Boolean = false,
    onClick: () -> Unit = {},
) {
    val colors = LocalAAColors.current
    val haptic = LocalHapticFeedback.current
    val tint = if (selected) colors.ink else colors.faint

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SessionAgentIcon(runtime = runtime, runtimeType = runtime, runtimeId = runtime, tint = tint)
        Text(
            text = runtime.runtimeTypeLabel(),
            color = tint,
            fontSize = 13.2.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
        )
        Text(
            text = "$count",
            color = tint.copy(alpha = 0.7f),
            fontSize = 12.sp,
            fontFamily = AAFont.Mono,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

@Composable
internal fun HomeSessionRow(
    session: AgentSession,
    contextLabel: String,
    showDivider: Boolean = true,
    onClick: () -> Unit,
    onLongPress: (Rect) -> Unit,
) {
    HomeSessionRowShell(
        height = 66.dp,
        showDivider = showDivider,
        onClick = onClick,
        onLongPress = onLongPress,
    ) {
        HomeSessionRowContent(
            session = session,
            contextLabel = contextLabel,
            titleColor = LocalAAColors.current.inkSoft,
            metaColor = LocalAAColors.current.faint,
        )
    }
}

@Composable
internal fun RowScope.HomeSessionRowContent(
    session: AgentSession,
    contextLabel: String,
    titleColor: Color,
    metaColor: Color,
) {
    val indicator = session.listIndicator()
    Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        SessionAgentIcon(session = session)
    }
    Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = session.title.sessionDisplayTitle(),
            color = titleColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 20.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (contextLabel.isNotBlank()) {
            Text(
                text = contextLabel,
                color = metaColor,
                fontSize = 11.2.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    SessionRowTrailing(session = session, indicator = indicator, timeColor = metaColor)
}

internal fun AgentSession.sidebarContextLabel(projectName: String?, deviceName: String?): String {
    val projectLabel = projectName?.takeIf { it.isNotBlank() }
        ?: cwd?.split('/', '\\')?.lastOrNull { it.isNotBlank() }
        ?: workspaceLabel
    val deviceLabel = deviceName?.takeIf { it.isNotBlank() } ?: this.deviceName
    return listOf(deviceLabel, projectLabel).filter { it.isNotBlank() }.joinToString(" · ")
}

@Composable
internal fun SessionRowTrailing(
    session: AgentSession,
    indicator: SessionListIndicator,
    timeColor: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (indicator != SessionListIndicator.None) {
            SessionStatusIndicator(indicator = indicator)
        }
        if (indicator != SessionListIndicator.WaitingApproval) {
            Text(
                text = session.updatedAtLabel.ifBlank { "now" },
                color = timeColor,
                fontSize = 10.8.sp,
                fontFamily = AAFont.Mono,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun SessionStatusIndicator(indicator: SessionListIndicator) {
    val colors = LocalAAColors.current

    when (indicator) {
        SessionListIndicator.WaitingApproval -> {
            val label = stringResource(R.string.home_session_status_waiting_approval)
            Text(
                text = label,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(colors.raisedSurface)
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                color = colors.inkSoft,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = 18.sp,
                maxLines = 1,
            )
        }

        SessionListIndicator.Busy -> SessionBusyIndicator(
            description = stringResource(R.string.home_session_status_running),
        )

        SessionListIndicator.Unread -> SessionUnreadIndicator(
            color = colors.sessionStatusAccent,
            description = stringResource(R.string.home_session_status_unread),
        )

        SessionListIndicator.None -> Unit
    }
}

@Composable
private fun SessionBusyIndicator(description: String) {
    val colors = LocalAAColors.current
    val transition = rememberInfiniteTransition(label = "session-status")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_200, easing = LinearEasing),
        ),
        label = "session-status-rotation",
    )

    Canvas(
        modifier = Modifier
            .size(20.dp)
            .semantics { contentDescription = description }
            .graphicsLayer { rotationZ = rotation },
    ) {
        val strokeWidth = 2.2.dp.toPx()
        drawCircle(
            color = colors.ink.copy(alpha = 0.16f),
            style = Stroke(width = strokeWidth),
        )
        drawArc(
            color = colors.inkSoft.copy(alpha = 0.78f),
            startAngle = -90f,
            sweepAngle = 100f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun SessionUnreadIndicator(
    color: Color,
    description: String,
) {
    Box(
        modifier = Modifier
            .size(18.dp)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(color),
        )
    }
}

@Composable
private fun HomeSessionRowShell(
    height: androidx.compose.ui.unit.Dp,
    showDivider: Boolean = true,
    onClick: () -> Unit,
    onLongPress: (Rect) -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = LocalAAColors.current
    val haptic = LocalHapticFeedback.current
    var bounds by remember { mutableStateOf(Rect.Zero) }

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .onGloballyPositioned { bounds = it.boundsInRoot() }
                .pointerInput(onClick, onLongPress, bounds) {
                    detectTapGestures(
                        onTap = { onClick() },
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress(bounds)
                        },
                    )
                }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
        if (showDivider) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(if (colors.isDark) Color(0xFF27272A) else Color(0xFFE9E8E5)),
            )
        }
    }
}

@Composable
private fun EmptyListText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            color = LocalAAColors.current.faint,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 分区内的一行轻量提示，比空态文案更矮更淡：只解释眼前的内容为何这样排布。 */
@Composable
private fun SectionNoteText(message: String) {
    Text(
        text = message,
        color = LocalAAColors.current.faint.copy(alpha = 0.8f),
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 2.dp, top = 2.dp, bottom = 6.dp),
    )
}

@Composable
private fun SectionEmptyText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(42.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = message,
            color = LocalAAColors.current.faint,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
