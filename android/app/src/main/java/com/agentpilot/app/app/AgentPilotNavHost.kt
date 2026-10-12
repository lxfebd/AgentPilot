package com.agentpilot.app.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.agentpilot.app.feature.auth.WebLoginViewModel
import com.agentpilot.app.feature.devices.DeviceRuntime
import com.agentpilot.app.feature.devices.DevicePairingStatus
import com.agentpilot.app.feature.devices.DeviceRuntimeList
import com.agentpilot.app.feature.devices.DeviceSetupCredential
import com.agentpilot.app.feature.files.FilesController
import com.agentpilot.app.feature.realtime.SessionRealtimeController
import com.agentpilot.app.feature.sessiondetail.SessionDetailController
import com.agentpilot.app.feature.sessions.NewSessionCreateDraft
import com.agentpilot.app.feature.sessions.NewSessionCreateOutcome
import com.agentpilot.app.feature.sessions.NewSessionDirectory
import com.agentpilot.app.feature.sessions.NewSessionDraft
import com.agentpilot.app.feature.sessions.ProjectSessionLoadKey
import com.agentpilot.app.feature.sessions.ProjectSessionStatusFilter
import com.agentpilot.app.feature.sessions.NewSessionModelCatalog
import com.agentpilot.app.feature.sessions.NewSessionPermissionCatalog
import com.agentpilot.app.feature.sessions.NewSessionRuntimeCapabilities
import com.agentpilot.app.feature.sessions.SessionBatchUpdate
import com.agentpilot.app.feature.sessions.SessionsState
import com.agentpilot.app.feature.terminal.RemoteTerminalPool
import com.agentpilot.app.feature.update.AppUpdateViewModel
import com.agentpilot.app.model.AgentDevice
import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.MobileLoginQrPayload
import com.agentpilot.app.navigation.AppDestination
import com.agentpilot.app.ui.designsystem.AppTabletRail
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.agentpilot.app.ui.designsystem.LocalAAWindowSize
import com.agentpilot.app.ui.screens.auth.LoginMethodsScreen
import com.agentpilot.app.ui.screens.auth.QrLoginScreen
import com.agentpilot.app.ui.screens.auth.QrWaitingScreen
import com.agentpilot.app.ui.screens.auth.WebLoginHostScreen
import com.agentpilot.app.ui.screens.devices.AddDeviceScreen
import com.agentpilot.app.ui.screens.devices.DeviceDetailScreen
import com.agentpilot.app.ui.screens.devices.DevicesScreen
import com.agentpilot.app.ui.screens.devices.rememberDeviceAgentPreviews
import com.agentpilot.app.ui.screens.files.FilesScreen
import com.agentpilot.app.ui.screens.home.ArchivedSessionsScreen
import com.agentpilot.app.ui.screens.home.HomeScreen
import com.agentpilot.app.ui.screens.home.HomeTab
import com.agentpilot.app.ui.screens.home.rememberHomeProjectPreferences
import com.agentpilot.app.ui.screens.home.NewSessionScreen
import com.agentpilot.app.ui.screens.sessiondetail.SessionComposerDraftStore
import com.agentpilot.app.ui.screens.sessiondetail.SessionDetailScreen
import com.agentpilot.app.ui.screens.schedules.SchedulesScreen
import com.agentpilot.app.ui.screens.terminal.TerminalScreen

@Composable
internal fun AgentPilotNavHost(
    currentDestination: AppDestination,
    sessionsState: SessionsState,
    isRefreshingSessions: Boolean,
    selectedSessionId: String?,
    preparedSessionDraft: NewSessionDraft?,
    selectedDeviceId: String?,
    deviceDetailReturnDestination: AppDestination,
    deviceSetupReturnDestination: AppDestination,
    selectedHomeTab: HomeTab,
    userId: String,
    role: String,
    serverUrl: String,
    appearanceMode: String,
    themeTone: String,
    amoledDark: Boolean,
    languageMode: String,
    sidebarViewMode: String,
    projectSessionsById: Map<String, List<AgentSession>>,
    loadingProjectRequests: Set<ProjectSessionLoadKey>,
    projectSessionErrors: Map<ProjectSessionLoadKey, String>,
    initialNewSessionProjectId: String?,
    sessionDetailController: SessionDetailController,
    sessionRealtimeController: SessionRealtimeController,
    filesController: FilesController,
    remoteTerminalPool: RemoteTerminalPool,
    pendingMobileLoginQr: MobileLoginQrPayload?,
    webLoginViewModel: WebLoginViewModel,
    appUpdateViewModel: AppUpdateViewModel,
    navigate: (AppDestination) -> Unit,
    onRefreshSessions: () -> Unit,
    onLoadMoreSessions: (Boolean) -> Unit,
    onOpenSession: (AgentSession) -> Unit,
    onOpenDevice: (AgentDevice) -> Unit,
    onHomeTabSelected: (HomeTab) -> Unit,
    onAppearanceModeChange: (String) -> Unit,
    onThemeToneChange: (String) -> Unit,
    onAmoledDarkChange: (Boolean) -> Unit,
    onLanguageModeChange: (String) -> Unit,
    onSidebarViewModeChange: (String) -> Unit,
    onLoadAccount: suspend () -> Result<com.agentpilot.app.api.AuthMeResponse>,
    onLoadAccountAuthConfig: suspend () -> Result<com.agentpilot.app.api.AuthConfigResponse>,
    onUpdateDisplayName: suspend (String) -> Result<com.agentpilot.app.api.AuthMeResponse>,
    onSendEmailCode: suspend (String) -> Result<com.agentpilot.app.api.EmailCodeResponse>,
    onBindEmail: suspend (String, String?) -> Result<com.agentpilot.app.api.AuthMeResponse>,
    onUpdateAvatar: suspend (String) -> Result<com.agentpilot.app.api.AuthMeResponse>,
    onClearAvatar: suspend () -> Result<com.agentpilot.app.api.AuthMeResponse>,
    onChangePassword: suspend (String) -> Result<Unit>,
    onSignOut: () -> Unit,
    onRenameDevice: suspend (String, String) -> Result<AgentDevice>,
    onDeleteDevice: suspend (String) -> Result<Unit>,
    onPrepareDeviceSetup: suspend (String) -> Result<DeviceSetupCredential>,
    onCreateDeviceSetup: suspend (String) -> Result<DeviceSetupCredential>,
    onClaimDevicePairCode: suspend (DeviceSetupCredential, String) -> Result<AgentDevice>,
    devicePairingStates: Map<String, DevicePairingStatus>,
    onWaitForPairingDevice: (String) -> Unit,
    onClearDevicePairing: (String) -> Unit,
    onDevicePairingComplete: () -> Unit,
    onListDeviceRuntimes: suspend (String) -> Result<DeviceRuntimeList>,
    onSetDeviceRuntimeActive: suspend (String, String, Boolean) -> Result<DeviceRuntime>,
    onDeleteDeviceRuntimeConfig: suspend (String, String) -> Result<DeviceRuntime>,
    onBulkSetSessionsArchived: suspend (List<String>, Boolean) -> Result<SessionBatchUpdate>,
    onArchiveAllDeviceSessions: suspend (String, Boolean, String) -> Result<List<AgentSession>>,
    onRenameSession: suspend (String, String) -> Result<AgentSession>,
    onSetSessionPinned: suspend (String, Boolean) -> Result<AgentSession>,
    onSetSessionArchived: suspend (String, Boolean) -> Result<AgentSession>,
    onLoadProjectSessions: (String, ProjectSessionStatusFilter) -> Unit,
    onLoadProjects: suspend () -> Result<List<AgentProject>>,
    onLoadArchivedPage: suspend (String?, String?) -> Result<com.agentpilot.app.feature.sessions.SessionPageAppend>,
    onRestoreProject: suspend (String) -> Result<List<AgentSession>>,
    onUpdateProject: suspend (String, String?, Boolean?) -> Result<AgentProject>,
    onArchiveProjectSessions: suspend (String) -> Result<List<AgentSession>>,
    onCreateProject: suspend (String, String, String) -> Result<AgentProject>,
    onNewSessionInProject: (AgentProject) -> Unit,
    onCreateSession: suspend (NewSessionCreateDraft) -> NewSessionCreateOutcome,
    onListSchedules: suspend () -> Result<org.json.JSONArray>,
    onCreateSchedule: suspend (String, String, String, String?, String?, Int?, String, String?) -> Result<org.json.JSONObject>,
    onDeleteSchedule: suspend (String) -> Result<Unit>,
    onSetScheduleEnabled: suspend (String, Boolean) -> Result<org.json.JSONObject>,
    onPrepareSession: (NewSessionDraft) -> Unit,
    onPreparedSessionCreated: (AgentSession) -> Unit,
    onListDirectory: suspend (String, String, String) -> Result<NewSessionDirectory>,
    onListNewSessionRuntimes: suspend (String) -> Result<DeviceRuntimeList>,
    onLoadNewSessionRuntimeCapabilities: suspend (String, String) -> Result<NewSessionRuntimeCapabilities>,
    onLoadNewSessionModelCatalog: suspend (String, String) -> Result<NewSessionModelCatalog>,
    onLoadNewSessionPermissionCatalog: suspend (String, String) -> Result<NewSessionPermissionCatalog>,
    onSessionChanged: (AgentSession) -> Unit,
    onMobileLoginQrRequested: (MobileLoginQrPayload) -> Unit,
) {
    val context = LocalContext.current
    val colors = LocalAAColors.current
    var profileOpen by rememberSaveable(serverUrl, userId) { mutableStateOf(false) }
    var searchRequestSignal by remember { mutableIntStateOf(0) }
    var deviceAgentPreviewRefreshKey by remember { mutableLongStateOf(0L) }
    val deviceAgentPreviews = rememberDeviceAgentPreviews(
        devices = sessionsState.devices,
        isRefreshing = isRefreshingSessions,
        refreshKey = deviceAgentPreviewRefreshKey,
        onListDeviceRuntimes = onListDeviceRuntimes,
    )
    val sessionComposerDraftStore = remember(context, userId) {
        SessionComposerDraftStore(context.applicationContext, userId)
    }

    val windowSize = LocalAAWindowSize.current
    val tablet = windowSize.isTablet
    val projectPreferences = rememberHomeProjectPreferences(serverUrl, userId)
    // 侧边栏项目会话：按项目懒加载的列表 + 主会话列表里已知的会话合并，
    // 避免"项目展开但懒加载未返回"时显示成"暂无会话"。
    // 实时会话（sessionsState.sessions）是权威状态，必须覆盖懒加载缓存中的
    // 同 id 旧条目（例如会话转 idle 后 badge 从"进行中"改为"待处理"/无）。
    val railProjectSessionsById = remember(projectSessionsById, sessionsState.sessions) {
        val merged = LinkedHashMap<String, MutableList<AgentSession>>()
        projectSessionsById.forEach { (id, list) -> merged[id] = list.toMutableList() }
        sessionsState.sessions
            .filter { session -> session.projectId != null && !session.archived }
            .forEach { session ->
                val list = merged.getOrPut(session.projectId!!) { mutableListOf() }
                val index = list.indexOfFirst { existing -> existing.id == session.id }
                if (index >= 0) {
                    list[index] = session
                } else {
                    list.add(session)
                }
            }
        merged
    }

    val pageContent: @Composable () -> Unit = {
        AnimatedContent(
            targetState = currentDestination,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val enterOffset: (Int) -> Int = { width -> if (forward) width / 5 else -width / 5 }
                val exitOffset: (Int) -> Int = { width -> if (forward) -width / 5 else width / 5 }

                slideInHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    initialOffsetX = enterOffset,
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 180),
                ) togetherWith slideOutHorizontally(
                    animationSpec = tween(durationMillis = 260),
                    targetOffsetX = exitOffset,
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160),
                )
            },
            label = "App destination transition",
        ) { destination ->
            when (destination) {
                AppDestination.LoginMethods -> LoginMethodsScreen(navigate)
                AppDestination.ServerSetup -> WebLoginHostScreen(webLoginViewModel, navigate)
                AppDestination.QrLogin -> QrLoginScreen(
                    navigate = navigate,
                    onMobileLoginQrRequested = onMobileLoginQrRequested,
                )
                AppDestination.QrWaiting -> QrWaitingScreen(
                    navigate = navigate,
                    mobileLoginQr = pendingMobileLoginQr,
                )
                AppDestination.Sessions -> HomeScreen(
                    navigate = navigate,
                    state = sessionsState,
                    selectedTab = selectedHomeTab,
                    onLoadProjects = onLoadProjects,
                    isRefreshing = isRefreshingSessions,
                    userId = userId,
                    role = role,
                    serverUrl = serverUrl,
                    appearanceMode = appearanceMode,
                    themeTone = themeTone,
                    amoledDark = amoledDark,
                    languageMode = languageMode,
                    sidebarViewMode = sidebarViewMode,
                    projectPreferences = projectPreferences,
                    appUpdateViewModel = appUpdateViewModel,
                    projectSessionsById = projectSessionsById,
                    loadingProjectRequests = loadingProjectRequests,
                    projectSessionErrors = projectSessionErrors,
                    onRefresh = onRefreshSessions,
                    onLoadMore = { tab -> onLoadMoreSessions(tab == HomeTab.Archived) },
                    onTabSelected = onHomeTabSelected,
                    onAppearanceModeChange = onAppearanceModeChange,
                    onThemeToneChange = onThemeToneChange,
                    onAmoledDarkChange = onAmoledDarkChange,
                    onLanguageModeChange = onLanguageModeChange,
                    onSidebarViewModeChange = onSidebarViewModeChange,
                    profileOpen = profileOpen,
                    onProfileOpenChange = { profileOpen = it },
                    onOpenArchivedSessions = { navigate(AppDestination.ArchivedSessions) },
                    onLoadAccount = onLoadAccount,
                    onLoadAccountAuthConfig = onLoadAccountAuthConfig,
                    onUpdateDisplayName = onUpdateDisplayName,
                    onSendEmailCode = onSendEmailCode,
                    onBindEmail = onBindEmail,
                    onUpdateAvatar = onUpdateAvatar,
                    onClearAvatar = onClearAvatar,
                    onChangePassword = onChangePassword,
                    onSignOut = onSignOut,
                    onRenameSession = onRenameSession,
                    onSetSessionPinned = onSetSessionPinned,
                    onSetSessionArchived = onSetSessionArchived,
                    onLoadProjectSessions = onLoadProjectSessions,
                    onUpdateProject = onUpdateProject,
                    onArchiveProjectSessions = onArchiveProjectSessions,
                    onNewSessionInProject = onNewSessionInProject,
                    onOpenSession = onOpenSession,
                    onOpenDevice = onOpenDevice,
                    deviceAgentPreviews = deviceAgentPreviews,
                    searchRequestSignal = searchRequestSignal,
                    onPairDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.NewSession, AppDestination.NewProject -> androidx.compose.runtime.key(serverUrl, userId, destination) { NewSessionScreen(
                    navigate = navigate,
                    sessionsState = sessionsState,
                    projectSessionsById = projectSessionsById,
                    serverUrl = serverUrl,
                    userId = userId,
                    onListDirectory = onListDirectory,
                    onListRuntimes = onListNewSessionRuntimes,
                    onLoadRuntimeCapabilities = onLoadNewSessionRuntimeCapabilities,
                    onLoadModelCatalog = onLoadNewSessionModelCatalog,
                    onLoadPermissionCatalog = onLoadNewSessionPermissionCatalog,
                    onPrepareSession = onPrepareSession,
                    onRefreshDevices = onRefreshSessions,
                    devicesRefreshing = isRefreshingSessions,
                    initialProjectId = initialNewSessionProjectId.takeIf { destination == AppDestination.NewSession },
                    projectOnly = destination == AppDestination.NewProject,
                    sidebarViewMode = sidebarViewMode,
                    onLoadProjects = onLoadProjects,
                    onCreateProject = onCreateProject,
                ) }
                AppDestination.SessionDetail -> SessionDetailScreen(
                    navigate = navigate,
                    sessionId = selectedSessionId,
                    initialSession = preparedSessionDraft?.previewSession() ?: sessionsState.sessions
                        .asSequence()
                        .plus(sessionsState.archivedSessions.asSequence())
                        .firstOrNull { it.id == selectedSessionId },
                    preparedSession = preparedSessionDraft,
                    onCreatePreparedSession = onCreateSession,
                    onPreparedSessionCreated = onPreparedSessionCreated,
                    onLoadPreparedModelCatalog = onLoadNewSessionModelCatalog,
                    onLoadPreparedPermissionCatalog = onLoadNewSessionPermissionCatalog,
                    devices = sessionsState.devices,
                    controller = sessionDetailController,
                    realtimeController = sessionRealtimeController,
                    filesController = filesController,
                    terminalPool = remoteTerminalPool,
                    composerDraftStore = sessionComposerDraftStore,
                    onSessionChanged = onSessionChanged,
                )
                AppDestination.DeviceDetail -> DeviceDetailScreen(
                    navigate = navigate,
                    state = sessionsState,
                    selectedDeviceId = selectedDeviceId,
                    backDestination = deviceDetailReturnDestination,
                    onOpenSession = onOpenSession,
                    onRenameDevice = onRenameDevice,
                    onDeleteDevice = onDeleteDevice,
                    onPrepareDeviceSetup = onPrepareDeviceSetup,
                    onListDeviceRuntimes = onListDeviceRuntimes,
                    onSetDeviceRuntimeActive = { connectorId, runtime, active ->
                        onSetDeviceRuntimeActive(connectorId, runtime, active).onSuccess {
                            deviceAgentPreviewRefreshKey += 1L
                        }
                    },
                    onDeleteDeviceRuntimeConfig = { connectorId, runtime ->
                        onDeleteDeviceRuntimeConfig(connectorId, runtime).onSuccess {
                            deviceAgentPreviewRefreshKey += 1L
                        }
                    },
                    onBulkSetSessionsArchived = onBulkSetSessionsArchived,
                    onArchiveAllDeviceSessions = onArchiveAllDeviceSessions,
                )
                AppDestination.Devices -> DevicesScreen(
                    state = sessionsState,
                    isRefreshing = isRefreshingSessions,
                    onRefresh = onRefreshSessions,
                    onOpenDevice = onOpenDevice,
                    onBack = { navigate(AppDestination.Sessions) },
                    agentPreviews = deviceAgentPreviews,
                    onAddDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.Terminal -> TerminalScreen(
                    navigate = navigate,
                    state = sessionsState,
                    terminalPool = remoteTerminalPool,
                    onPairDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.Files -> FilesScreen(
                    navigate = navigate,
                    state = sessionsState,
                    controller = filesController,
                    onPairDevice = { navigate(AppDestination.DeviceSetup) },
                )
                AppDestination.DeviceSetup -> androidx.compose.runtime.key(serverUrl, userId) {
                    AddDeviceScreen(
                        devices = sessionsState.devices,
                        pairingStates = devicePairingStates,
                        onBack = { navigate(deviceSetupReturnDestination) },
                        onComplete = {
                            onDevicePairingComplete()
                            navigate(deviceSetupReturnDestination)
                        },
                        onCreateCredential = onCreateDeviceSetup,
                        onRenameDevice = onRenameDevice,
                        onClaimPairCode = onClaimDevicePairCode,
                        onWaitForDevice = onWaitForPairingDevice,
                        onClearPairing = onClearDevicePairing,
                    )
                }
                AppDestination.ArchivedSessions -> androidx.compose.runtime.key(serverUrl, userId) {
                    ArchivedSessionsScreen(
                        projects = sessionsState.projects,
                        onLoadPage = onLoadArchivedPage,
                        onRestoreSession = { onSetSessionArchived(it, false) },
                        onRestoreProject = onRestoreProject,
                        onBack = { navigate(AppDestination.Sessions) },
                    )
                }
                AppDestination.Schedules -> SchedulesScreen(
                    onBack = { navigate(AppDestination.Sessions) },
                    sessions = sessionsState.sessions,
                    onListSchedules = onListSchedules,
                    onCreateSchedule = onCreateSchedule,
                    onDeleteSchedule = onDeleteSchedule,
                    onSetScheduleEnabled = onSetScheduleEnabled,
                )
            }
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = colors.canvas,
    ) {
        if (tablet && currentDestination.isRailDestination()) {
            AppTabletRail(
                currentDestination = currentDestination,
                onNavigate = navigate,
                userId = userId,
                role = role,
                onLoadAccount = onLoadAccount,
                onOpenSettings = {
                    if (currentDestination != AppDestination.Sessions) {
                        navigate(AppDestination.Sessions)
                    }
                    profileOpen = true
                },
                projects = sessionsState.projects,
                projectSessionsById = railProjectSessionsById,
                expandedProjectIds = projectPreferences.expandedIds,
                onProjectExpandedChange = { id, expanded ->
                    projectPreferences.setProjectExpanded(id, expanded)
                    if (expanded) onLoadProjectSessions(id, projectPreferences.sessionStatus)
                },
                onOpenSession = onOpenSession,
                currentSessionId = selectedSessionId,
                onSetSessionPinned = onSetSessionPinned,
                onSetSessionArchived = onSetSessionArchived,
                onSearch = { searchRequestSignal++ },
            ) {
                pageContent()
            }
        } else {
            pageContent()
        }
    }
}

private fun AppDestination.isRailDestination(): Boolean = when (this) {
    AppDestination.Sessions,
    AppDestination.Devices,
    AppDestination.DeviceDetail,
    AppDestination.Terminal,
    AppDestination.Files,
    AppDestination.SessionDetail,
    AppDestination.ArchivedSessions,
    AppDestination.Schedules,
    AppDestination.NewSession,
    AppDestination.NewProject,
    AppDestination.DeviceSetup,
    -> true

    AppDestination.LoginMethods,
    AppDestination.ServerSetup,
    AppDestination.QrLogin,
    AppDestination.QrWaiting,
    -> false
}
