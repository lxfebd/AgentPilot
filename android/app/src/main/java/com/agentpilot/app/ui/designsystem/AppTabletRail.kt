package com.agentpilot.app.ui.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.agentpilot.app.R
import com.agentpilot.app.api.AuthMeResponse
import com.agentpilot.app.feature.sessions.RailStatusError
import com.agentpilot.app.feature.sessions.RailStatusRunning
import com.agentpilot.app.feature.sessions.RailStatusWaiting
import com.agentpilot.app.feature.sessions.railSessionOrder
import com.agentpilot.app.feature.sessions.railSessionStatus
import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.SessionStatus
import com.agentpilot.app.navigation.AppDestination
import com.composables.icons.lucide.Archive
import com.composables.icons.lucide.Clock
import com.composables.icons.lucide.ChevronDown
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Folder
import com.composables.icons.lucide.FolderOpen
import com.composables.icons.lucide.Lucide
import kotlinx.coroutines.withTimeoutOrNull
import com.composables.icons.lucide.Pencil
import com.composables.icons.lucide.Pin
import com.composables.icons.lucide.Plus
import com.composables.icons.lucide.Search
import com.composables.icons.lucide.Server
import com.composables.icons.lucide.Settings
import com.composables.icons.lucide.Terminal
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun AppTabletRail(
    currentDestination: AppDestination,
    onNavigate: (AppDestination) -> Unit,
    userId: String,
    role: String,
    onLoadAccount: suspend () -> Result<AuthMeResponse>,
    onOpenSettings: () -> Unit,
    projects: List<AgentProject>,
    projectSessionsById: Map<String, List<AgentSession>>,
    expandedProjectIds: Set<String>,
    onProjectExpandedChange: (String, Boolean) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    currentSessionId: String? = null,
    onSetSessionPinned: suspend (String, Boolean) -> Result<AgentSession> = { _, _ -> Result.failure(Exception("unsupported")) },
    onSetSessionArchived: suspend (String, Boolean) -> Result<AgentSession> = { _, _ -> Result.failure(Exception("unsupported")) },
    onSearch: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val colors = LocalAAColors.current
    var account by remember(userId, role) { mutableStateOf<AuthMeResponse?>(null) }
    var railCollapsed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(userId, role) {
        onLoadAccount().onSuccess { account = it }
    }
    val accountName = account?.accountLabel?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.rail_default_user)
    val railWidth by animateDpAsState(if (railCollapsed) 64.dp else 300.dp, label = "railWidth")

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(railWidth)
                .clip(RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp))
                .background(colors.subtle)
                .statusBarsPadding(),
        ) {
            if (railCollapsed) {
                RailCollapsed(
                    account = account,
                    projects = projects,
                    projectSessionsById = projectSessionsById,
                    onNavigate = onNavigate,
                    onExpand = { railCollapsed = false },
                    onOpenSettings = onOpenSettings,
                    onNewSession = { onNavigate(AppDestination.NewSession) },
                    onSearch = onSearch,
                    onOpenProject = { id ->
                        railCollapsed = false
                        onProjectExpandedChange(id, true)
                    },
                )
            } else {
                RailHeader(
                    account = account,
                    accountName = accountName,
                    onEdit = onOpenSettings,
                    onToggleCollapse = { railCollapsed = true },
                )
                Spacer(Modifier.height(10.dp))
                RailRow(
                    icon = Lucide.Search,
                    label = stringResource(R.string.rail_search_sessions),
                    selected = currentDestination == AppDestination.Sessions,
                    onClick = onSearch,
                )
                RailRow(
                    icon = Lucide.Archive,
                    label = stringResource(R.string.profile_archived_sessions),
                    selected = currentDestination == AppDestination.ArchivedSessions,
                    onClick = { onNavigate(AppDestination.ArchivedSessions) },
                )
                Spacer(Modifier.height(6.dp))
                RailChips(
                    onSessions = { onNavigate(AppDestination.Sessions) },
                    onNew = { onNavigate(AppDestination.NewSession) },
                )
                RailProjects(
                    modifier = Modifier.weight(1f),
                    projects = projects,
                    projectSessionsById = projectSessionsById,
                    expandedProjectIds = expandedProjectIds,
                    onProjectExpandedChange = onProjectExpandedChange,
                    onOpenSession = onOpenSession,
                    currentSessionId = currentSessionId,
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionArchived = onSetSessionArchived,
                )
                RailAssistantRow(onDevices = { onNavigate(AppDestination.Devices) })
                Spacer(Modifier.height(6.dp))
                RailCircleActions(
                    onNavigate = onNavigate,
                    onSettings = onOpenSettings,
                )
            }
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
        ) {
            content()
        }
    }
}

@Composable
private fun RailHeader(
    account: AuthMeResponse?,
    accountName: String,
    onEdit: () -> Unit,
    onToggleCollapse: () -> Unit,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 14.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RailAvatar(account)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = accountName,
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .clickable(onClick = onEdit),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Pencil,
                        contentDescription = stringResource(R.string.rail_edit_profile),
                        tint = colors.muted,
                        modifier = Modifier.size(15.dp),
                    )
                }
            }
            Text(
                text = railGreeting(),
                color = colors.muted,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 1,
            )
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable(onClick = onToggleCollapse),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Lucide.ChevronLeft,
                contentDescription = stringResource(R.string.rail_collapse),
                tint = colors.muted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// 折叠态图标栏：仅图标，点击项目展开并恢复完整侧边栏
@Composable
private fun RailCollapsed(
    account: AuthMeResponse?,
    projects: List<AgentProject>,
    projectSessionsById: Map<String, List<AgentSession>>,
    onNavigate: (AppDestination) -> Unit,
    onExpand: () -> Unit,
    onOpenSettings: () -> Unit,
    onNewSession: () -> Unit,
    onSearch: () -> Unit,
    onOpenProject: (String) -> Unit,
) {
    val colors = LocalAAColors.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(onClick = onOpenSettings),
        ) {
            RailAvatar(account)
        }
        Spacer(Modifier.height(10.dp))
        RailCollapsedIcon(Lucide.ChevronRight, stringResource(R.string.rail_expand), onExpand)
        Spacer(Modifier.height(6.dp))
        RailCollapsedIcon(Lucide.Search, stringResource(R.string.rail_search_sessions), onSearch)
        RailCollapsedIcon(
            Lucide.Plus,
            stringResource(R.string.rail_chip_new),
            onClick = onNewSession,
        )
        RailCollapsedIcon(
            Lucide.Archive,
            stringResource(R.string.profile_archived_sessions),
        ) {
            onExpand()
            onNavigate(AppDestination.ArchivedSessions)
        }
        Spacer(Modifier.height(6.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            projects.forEach { project ->
                val attention = projectSessionsById[project.id].orEmpty()
                    .any { railSessionStatus(it).attention }
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenProject(project.id) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Lucide.Folder,
                        contentDescription = project.name,
                        tint = colors.muted,
                        modifier = Modifier.size(22.dp),
                    )
                    if (attention) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 6.dp, end = 6.dp)
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(RailStatusWaiting),
                        )
                    }
                }
            }
        }
        RailCollapsedIcon(Lucide.Server, stringResource(R.string.rail_default_device)) {
            onExpand()
            onNavigate(AppDestination.Devices)
        }
    }
}

@Composable
private fun RailCollapsedIcon(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = colors.ink,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun RailAvatar(account: AuthMeResponse?) {
    val letter = account?.accountLabel?.trim()?.firstOrNull()?.uppercaseChar()?.toString() ?: "A"
    Box(
        modifier = Modifier
            .size(50.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF6EC6A5), Color(0xFF7B68D8)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (!account?.avatar.isNullOrBlank()) {
            AsyncImage(
                model = account?.avatar,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = letter,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun RailRow(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) colors.ink else colors.muted,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = label,
            color = colors.ink,
            fontSize = 16.sp,
            maxLines = 1,
        )
    }
}

@Composable
private fun RailChips(
    onSessions: () -> Unit,
    onNew: () -> Unit,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
                .clickable(onClick = onSessions),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.rail_chip_sessions),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 1,
            )
        }
        Row(
            modifier = Modifier
                .height(32.dp)
                .clip(CircleShape)
                .clickable(onClick = onNew),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Lucide.Plus,
                contentDescription = null,
                tint = colors.ink,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.rail_chip_new),
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RailAssistantRow(onDevices: () -> Unit) {
    val colors = LocalAAColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(onClick = onDevices)
            .padding(start = 24.dp, end = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Lucide.Server,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = stringResource(R.string.rail_default_device),
            color = colors.ink,
            fontSize = 16.sp,
            maxLines = 1,
        )
        Spacer(Modifier.weight(1f))
    }
}

@Composable
private fun RailProjects(
    modifier: Modifier,
    projects: List<AgentProject>,
    projectSessionsById: Map<String, List<AgentSession>>,
    expandedProjectIds: Set<String>,
    onProjectExpandedChange: (String, Boolean) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    currentSessionId: String?,
    onSetSessionPinned: suspend (String, Boolean) -> Result<AgentSession>,
    onSetSessionArchived: suspend (String, Boolean) -> Result<AgentSession>,
) {
    val colors = LocalAAColors.current
    val scope = rememberCoroutineScope()
    val attentionSessions = projects
        .flatMap { project -> projectSessionsById[project.id].orEmpty() }
        .filter { railSessionStatus(it).attention }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(top = 10.dp, bottom = 6.dp),
    ) {
        if (attentionSessions.isNotEmpty()) {
            RailAttentionBanner(
                count = attentionSessions.size,
                onClick = { onOpenSession(attentionSessions.first()) },
            )
        }
        if (projects.isEmpty()) {
            Text(
                text = stringResource(R.string.home_no_projects),
                color = colors.muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        projects.forEach { project ->
            val expanded = project.id in expandedProjectIds
            val sessions = projectSessionsById[project.id].orEmpty()
            val chevronRotation by animateFloatAsState(
                if (expanded) 180f else 0f,
                label = "railProjectChevron",
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clickable { onProjectExpandedChange(project.id, !expanded) }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (expanded) Lucide.FolderOpen else Lucide.Folder,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = project.name,
                    color = colors.ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val attentionCount = sessions.count { railSessionStatus(it).attention }
                val runningCount = sessions.count { it.status == SessionStatus.Running }
                when {
                    attentionCount > 0 -> {
                        Text(
                            text = stringResource(R.string.rail_badge_attention, attentionCount),
                            color = RailStatusWaiting,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    runningCount > 0 -> {
                        Text(
                            text = stringResource(R.string.rail_badge_running, runningCount),
                            color = RailStatusRunning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                }
                Icon(
                    imageVector = Lucide.ChevronDown,
                    contentDescription = null,
                    tint = colors.muted,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(chevronRotation),
                )
            }
            if (expanded) {
                if (sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_project_no_sessions),
                        color = colors.muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 52.dp, end = 20.dp, top = 2.dp, bottom = 8.dp),
                    )
                } else {
                    sessions
                        .sortedBy { railSessionOrder(it) }
                        .forEach { session ->
                            RailSessionRow(
                                session = session,
                                selected = session.id == currentSessionId,
                                onOpen = { onOpenSession(session) },
                                onTogglePin = {
                                    scope.launch { onSetSessionPinned(session.id, !session.pinned) }
                                },
                                onArchive = {
                                    scope.launch { onSetSessionArchived(session.id, true) }
                                },
                            )
                        }
                }
            }
        }
    }
}

// 会话行：左滑露出 置顶/归档 操作，点击打开会话
@Composable
private fun RailSessionRow(
    session: AgentSession,
    selected: Boolean,
    onOpen: () -> Unit,
    onTogglePin: () -> Unit,
    onArchive: () -> Unit,
) {
    val colors = LocalAAColors.current
    val scope = rememberCoroutineScope()
    val offset = remember(session.id) { Animatable(0f) }
    val actionWidth = 144.dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .padding(start = 44.dp, end = 12.dp),
    ) {
        // 背景操作层
        Row(
            modifier = Modifier
                .matchParentSize()
                .clip(RailBannerShape)
                .background(colors.subtle),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RailSwipeAction(
                icon = Lucide.Pin,
                label = stringResource(if (session.pinned) R.string.home_unpin else R.string.home_pin),
                tint = colors.ink,
                onClick = {
                    scope.launch { offset.animateTo(0f) }
                    onTogglePin()
                },
            )
            RailSwipeAction(
                icon = Lucide.Archive,
                label = stringResource(R.string.home_archive),
                tint = RailStatusError,
                onClick = {
                    scope.launch { offset.animateTo(0f) }
                    onArchive()
                },
            )
        }
        // 前景内容层：轻点打开会话；左滑（水平拖拽）露出 置顶/归档 操作层
        // 顺序：tap 检测器靠内（优先接收 down-up 轻点），drag 检测器靠外（只响应明确水平位移）
        Row(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { translationX = offset.value }
                .pointerInput(session.id) {
                    detectHorizontalDragGestures(
                        onDragStart = { },
                        onDragEnd = {
                            scope.launch {
                                if (offset.value < -actionWidthPx / 2f) {
                                    offset.animateTo(-actionWidthPx)
                                } else {
                                    offset.animateTo(0f)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offset.value + dragAmount).coerceIn(-actionWidthPx, 0f)
                            scope.launch { offset.snapTo(next) }
                        },
                    )
                }
                .pointerInput(session.id) {
                    detectTapGestures(
                        onTap = {
                            scope.launch { offset.animateTo(0f) }
                            onOpen()
                        },
                    )
                }
                .clip(RailBannerShape)
                // 选中背景必须不透明：行背后是 置顶/归档 滑动操作层，
                // 半透明色会把操作按钮透出来。lerp 混色保证不透明且随主题。
                .background(
                    if (selected) {
                        lerp(colors.canvas, colors.primaryAction, if (colors.isDark) 0.32f else 0.15f)
                    } else {
                        colors.subtle
                    },
                )
                .then(
                    if (selected) {
                        Modifier.border(1.dp, colors.primaryAction.copy(alpha = 0.28f), RailBannerShape)
                    } else {
                        Modifier
                    },
                )
                .padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = session.title.ifBlank { session.runtime },
                color = colors.ink,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RailSessionStatusChip(session = session)
        }
    }
}

@Composable
private fun RailSwipeAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .fillMaxHeight()
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 10.sp,
            maxLines = 1,
        )
    }
}

private val RailBannerShape = RoundedCornerShape(12.dp)

@Composable
private fun RailAttentionBanner(
    count: Int,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clip(RailBannerShape)
            .background(RailStatusWaiting.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(RailStatusWaiting),
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = stringResource(R.string.rail_attention_banner, count),
            color = RailStatusWaiting,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = RailStatusWaiting,
            modifier = Modifier.size(15.dp),
        )
    }
}

@Composable
private fun RailSessionStatusChip(session: AgentSession) {
    val status = railSessionStatus(session)
    val dotColor = status.color
    Row(
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        if (status.labelRes != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(status.labelRes),
                color = dotColor,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun RailCircleActions(
    onNavigate: (AppDestination) -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 4.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RailCircle(
            icon = Lucide.Server,
            label = stringResource(R.string.devices_title),
            onClick = { onNavigate(AppDestination.Devices) },
        )
        RailCircle(
            icon = Lucide.Terminal,
            label = stringResource(R.string.common_terminal),
            onClick = { onNavigate(AppDestination.Terminal) },
        )
        RailCircle(
            icon = Lucide.FileText,
            label = stringResource(R.string.common_files),
            onClick = { onNavigate(AppDestination.Files) },
        )
        RailCircle(
            icon = Lucide.Archive,
            label = stringResource(R.string.profile_archived_sessions),
            onClick = { onNavigate(AppDestination.ArchivedSessions) },
        )
        RailCircle(
            icon = Lucide.Clock,
            label = stringResource(R.string.schedules_title),
            onClick = { onNavigate(AppDestination.Schedules) },
        )
        Spacer(Modifier.weight(1f))
        RailCircle(
            icon = Lucide.Settings,
            label = stringResource(R.string.rail_settings),
            onClick = onSettings,
        )
    }
}

@Composable
private fun RailCircle(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(20.dp),
        )
    }
}

@Composable
private fun railGreeting(): String {
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val res = when {
        hour in 5..10 -> R.string.rail_greeting_morning
        hour in 11..17 -> R.string.rail_greeting_afternoon
        hour in 18..22 -> R.string.rail_greeting_evening
        else -> R.string.rail_greeting_late_night
    }
    return stringResource(res)
}
