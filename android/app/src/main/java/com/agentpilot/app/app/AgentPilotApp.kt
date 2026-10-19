package com.agentpilot.app.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Network
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.agentpilot.app.api.ApiClient
import com.agentpilot.app.R
import com.agentpilot.app.api.AnnouncementsApi
import com.agentpilot.app.api.AuthApi
import com.agentpilot.app.api.DevicesApi
import com.agentpilot.app.api.FilesApi
import com.agentpilot.app.api.RealtimeApi
import com.agentpilot.app.api.SessionsApi
import com.agentpilot.app.api.TerminalApi
import com.agentpilot.app.config.AppConfig
import com.agentpilot.app.feature.auth.AuthController
import com.agentpilot.app.feature.auth.AuthSessionStore
import com.agentpilot.app.feature.auth.WebLoginState
import com.agentpilot.app.feature.auth.WebLoginViewModel
import com.agentpilot.app.feature.notify.NotifyPreferences
import com.agentpilot.app.feature.notify.SessionNotifier
import com.agentpilot.app.feature.update.AppUpdateViewModel
import com.agentpilot.app.feature.devices.DevicesController
import com.agentpilot.app.feature.devices.DevicePairingMonitor
import com.agentpilot.app.feature.files.FilesController
import com.agentpilot.app.feature.realtime.DashboardRealtimeController
import com.agentpilot.app.feature.realtime.RealtimeClientIdStore
import com.agentpilot.app.feature.realtime.SessionRealtimeController
import com.agentpilot.app.feature.sessions.SessionsController
import com.agentpilot.app.feature.sessions.SessionsState
import com.agentpilot.app.feature.sessions.NewSessionCreateOutcome
import com.agentpilot.app.feature.sessions.NewSessionDraft
import com.agentpilot.app.feature.sessions.NewSessionPreferenceStore
import com.agentpilot.app.feature.sessions.projectHasActiveSessions
import com.agentpilot.app.feature.sessions.ProjectSessionLoadKey
import com.agentpilot.app.feature.sessions.ProjectSessionStatusFilter
import com.agentpilot.app.feature.sessions.beginSessionRequest
import com.agentpilot.app.feature.sessions.mergedWithRefresh
import com.agentpilot.app.feature.sessions.replacedByDashboardSnapshot
import com.agentpilot.app.feature.sessions.withDeletedDevice
import com.agentpilot.app.feature.sessions.withPatchedDevice
import com.agentpilot.app.feature.sessions.withPatchedProject
import com.agentpilot.app.feature.sessions.withPatchedSession
import com.agentpilot.app.feature.sessions.withPatchedSessions
import com.agentpilot.app.feature.sessions.withMissingSessionsRemoved
import com.agentpilot.app.feature.sessions.withAppendedSessionPage
import com.agentpilot.app.feature.sessions.withSessionPageLoading
import com.agentpilot.app.feature.sessions.railSessionStatus
import com.agentpilot.app.feature.sessions.RailStatusBlocked
import com.agentpilot.app.feature.sessions.RailStatusDone
import com.agentpilot.app.feature.sessions.RailStatusError
import com.agentpilot.app.feature.sessiondetail.SessionDetailController
import com.agentpilot.app.feature.terminal.RemoteTerminalPool
import com.agentpilot.app.feature.terminal.TerminalController
import com.agentpilot.app.model.MobileLoginQrPayload
import com.agentpilot.app.model.AgentSession
import com.agentpilot.app.model.AgentProject
import com.agentpilot.app.model.SessionStatus
import com.agentpilot.app.navigation.AppDestination
import com.agentpilot.app.ui.designsystem.AgentPilotTheme
import com.agentpilot.app.ui.designsystem.ProvideAAWindowSize
import com.agentpilot.app.ui.designsystem.AALanguageMode
import com.agentpilot.app.ui.screens.home.HomeTab
import com.agentpilot.app.ui.screens.announcements.AnnouncementGate
import com.agentpilot.app.ui.screens.announcements.AnnouncementPage
import com.agentpilot.app.ui.screens.update.AppUpdatePromptDialog
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import android.os.SystemClock
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

@Composable
fun AgentPilotApp(
    appearanceMode: String = "system",
    themeTone: String = com.agentpilot.app.ui.designsystem.AAThemeTone.Dynamic,
    amoledDark: Boolean = false,
    languageMode: String = AALanguageMode.System,
    sidebarViewMode: String = com.agentpilot.app.ui.screens.home.HomeSidebarViewMode.Project,
    onAppearanceModeChange: (String) -> Unit = {},
    onThemeToneChange: (String) -> Unit = {},
    onAmoledDarkChange: (Boolean) -> Unit = {},
    onLanguageModeChange: (String) -> Unit = {},
    onSidebarViewModeChange: (String) -> Unit = {},
    oauthCallbackUri: Uri? = null,
    onOAuthCallbackConsumed: () -> Unit = {},
    pendingOpenSessionId: String? = null,
    onOpenSessionIdConsumed: () -> Unit = {},
    webLoginViewModel: WebLoginViewModel,
    appUpdateViewModel: AppUpdateViewModel,
    onInstallUpdate: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val sessionStore = remember(context) { AuthSessionStore(context) }
    var destinationName by rememberSaveable {
        mutableStateOf(
            if (sessionStore.hasAuthSession()) {
                AppDestination.Sessions.name
            } else {
                AppDestination.LoginMethods.name
            },
        )
    }
    var pendingMobileLoginQr by remember { mutableStateOf<MobileLoginQrPayload?>(null) }
    var selectedSessionId by rememberSaveable { mutableStateOf<String?>(null) }
    var initialNewSessionProjectId by rememberSaveable { mutableStateOf<String?>(null) }
    var preparedSessionDraft by rememberSaveable(stateSaver = NewSessionDraftSaver) {
        mutableStateOf<NewSessionDraft?>(null)
    }
    var selectedDeviceId by rememberSaveable { mutableStateOf<String?>(null) }
    var deviceDetailReturnDestinationName by rememberSaveable { mutableStateOf(AppDestination.Devices.name) }
    var deviceSetupReturnDestinationName by rememberSaveable { mutableStateOf(AppDestination.Devices.name) }
    var selectedHomeTabName by rememberSaveable { mutableStateOf(HomeTab.Active.name) }
    val unauthorizedTokens = remember { Channel<String>(capacity = Channel.UNLIMITED) }
    val apiClient = remember(unauthorizedTokens) {
        ApiClient(onUnauthorized = { accessToken ->
            unauthorizedTokens.trySend(accessToken)
        })
    }
    val realtimeApi = remember(apiClient) { RealtimeApi(client = apiClient) }
    val announcementsApi = remember(apiClient) { AnnouncementsApi(apiClient) }
    val realtimeClientId = remember(context) { RealtimeClientIdStore(context).readOrCreate() }
    val authController = remember(context, sessionStore, apiClient) {
        AuthController(
            api = AuthApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val sessionsController = remember(context, sessionStore, apiClient) {
        SessionsController(
            sessionsApi = SessionsApi(apiClient),
            devicesApi = DevicesApi(apiClient),
            filesApi = FilesApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val devicesController = remember(context, sessionStore, apiClient) {
        DevicesController(
            devicesApi = DevicesApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val sessionDetailController = remember(context, sessionStore, apiClient) {
        SessionDetailController(
            sessionsApi = SessionsApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val filesController = remember(context, sessionStore, apiClient) {
        FilesController(
            filesApi = FilesApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val terminalController = remember(context, sessionStore, apiClient) {
        TerminalController(
            terminalApi = TerminalApi(apiClient),
            sessionStore = sessionStore,
        )
    }
    val remoteTerminalPool = remember(terminalController) {
        RemoteTerminalPool(terminalController)
    }
    val dashboardRealtimeController = remember(realtimeApi, sessionStore, realtimeClientId) {
        DashboardRealtimeController(realtimeApi, sessionStore, realtimeClientId)
    }
    val sessionRealtimeController = remember(realtimeApi, sessionStore, realtimeClientId) {
        SessionRealtimeController(realtimeApi, sessionStore, realtimeClientId)
    }
    val currentDestination = AppDestination.valueOf(destinationName)
    val hasAuthSession = sessionStore.hasAuthSession()
    val updateServerUrl = sessionStore.readServerUrl()
    val updatesAllowed = hasAuthSession && updateServerUrl.isNotBlank() && currentDestination !in setOf(
        AppDestination.LoginMethods, AppDestination.ServerSetup, AppDestination.QrLogin, AppDestination.QrWaiting,
    )
    LaunchedEffect(updatesAllowed, updateServerUrl) {
        appUpdateViewModel.syncSession(updatesAllowed)
    }
    var sessionsState by remember(sessionsController) {
        mutableStateOf(
            if (hasAuthSession) {
                SessionsState(isLoading = true)
            } else {
                SessionsState()
            },
        )
    }
    val nextOrderExpiry = sessionsState.sessions.map { it.optimisticTopUntil }.filter { it > 0L }.minOrNull()
    LaunchedEffect(nextOrderExpiry) {
        if (nextOrderExpiry != null) {
            kotlinx.coroutines.delay((nextOrderExpiry - System.currentTimeMillis()).coerceAtLeast(1L))
            val now = System.currentTimeMillis()
            sessionsState = sessionsState.copy(sessions = sessionsState.sessions.map {
                if (it.optimisticTopUntil <= now) it.copy(optimisticTopUntil = 0L) else it
            }.sortedWith(com.agentpilot.app.feature.sessions.sessionListComparator(now)))
        }
    }
    var isRefreshingSessions by remember { mutableStateOf(false) }
    var projectSessionsById by remember { mutableStateOf<Map<String, List<AgentSession>>>(emptyMap()) }
    var loadingProjectRequests by remember { mutableStateOf<Set<ProjectSessionLoadKey>>(emptySet()) }
    var projectSessionErrors by remember { mutableStateOf<Map<ProjectSessionLoadKey, String>>(emptyMap()) }
    var projectSessionsLoadedAt by remember { mutableStateOf<Map<ProjectSessionLoadKey, Long>>(emptyMap()) }
    val projectLoadSlots = remember { Semaphore(3) }
    var projectsRequestVersion by remember { mutableStateOf(0L) }
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    var appVisible by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> appVisible = true
                Lifecycle.Event.ON_STOP -> appVisible = false
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        appVisible = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // 通知权限（Android 13+）与渠道
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
        ) {}
        LaunchedEffect(Unit) {
            SessionNotifier.ensureChannels(context)
            if (androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    } else {
        LaunchedEffect(Unit) { SessionNotifier.ensureChannels(context) }
    }
    // 会话状态变化 → 系统通知（等待回答/等待批准/出错/已中断/有新回复 / 任务完成/出错/中断）
    val notifyPreferences = remember { NotifyPreferences(context) }
    val previousAttentionFlags = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    // 上一次看到的会话状态，用于检测 running→idle（完成）与 →error/blocked（出错/中断）的结果转变
    val previousStatuses = remember { androidx.compose.runtime.mutableStateMapOf<String, SessionStatus>() }
    LaunchedEffect(sessionsState.sessions, hasAuthSession) {
        if (!hasAuthSession) return@LaunchedEffect
        val viewingSessionId = if (currentDestination == AppDestination.SessionDetail) selectedSessionId else null
        sessionsState.sessions.forEach { session ->
            val attention = railSessionStatus(session).attention
            val previous = previousAttentionFlags[session.id]
            if (previous != null && previous != attention) {
                if (attention) {
                    // 用户正停在这个会话里时不打扰
                    if (viewingSessionId != session.id) {
                        val status = railSessionStatus(session)
                        val label = status.labelRes?.let { context.getString(it) }
                            ?: context.getString(R.string.notify_new_reply)
                        SessionNotifier.notifySession(
                            context,
                            session,
                            label,
                            status.color.toArgb(),
                            kind = SessionNotifier.NotifyKind.Attention,
                        )
                    }
                } else {
                    SessionNotifier.cancelSession(context, session.id)
                }
            }
            previousAttentionFlags[session.id] = attention
            // 结果通知：任务完成（running/stopping → idle）与出错/中断
            val previousStatus = previousStatuses[session.id]
            if (notifyPreferences.completionAlertsEnabled && previousStatus != null &&
                previousStatus != session.status && viewingSessionId != session.id
            ) {
                val wasRunning = previousStatus == SessionStatus.Running ||
                    previousStatus == SessionStatus.Stopping ||
                    previousStatus == SessionStatus.Unknown
                when {
                    session.status == SessionStatus.Error &&
                        previousStatus != SessionStatus.Error -> {
                        SessionNotifier.notifySession(
                            context,
                            session,
                            context.getString(R.string.notify_session_error),
                            RailStatusError.toArgb(),
                            kind = SessionNotifier.NotifyKind.Error,
                        )
                    }
                    session.status == SessionStatus.Blocked &&
                        previousStatus != SessionStatus.Blocked -> {
                        SessionNotifier.notifySession(
                            context,
                            session,
                            context.getString(R.string.notify_session_interrupted),
                            RailStatusBlocked.toArgb(),
                            kind = SessionNotifier.NotifyKind.Error,
                        )
                    }
                    session.status == SessionStatus.Idle && !session.unread && wasRunning -> {
                        SessionNotifier.notifySession(
                            context,
                            session,
                            context.getString(R.string.notify_session_completed),
                            RailStatusDone.toArgb(),
                            kind = SessionNotifier.NotifyKind.Completed,
                        )
                    }
                }
            }
            previousStatuses[session.id] = session.status
        }
        val currentIds = sessionsState.sessions.mapTo(mutableSetOf()) { it.id }
        previousAttentionFlags.keys.retainAll(currentIds)
        previousStatuses.keys.retainAll(currentIds)
    }
    // 通知点击 → 打开对应会话
    LaunchedEffect(pendingOpenSessionId, hasAuthSession) {
        val sessionId = pendingOpenSessionId ?: return@LaunchedEffect
        if (!hasAuthSession) return@LaunchedEffect
        selectedSessionId = sessionId
        destinationName = AppDestination.SessionDetail.name
        onOpenSessionIdConsumed()
    }
    DisposableEffect(context, dashboardRealtimeController, sessionRealtimeController) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val networkWasLost = AtomicBoolean(false)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (networkWasLost.getAndSet(false)) {
                    dashboardRealtimeController.requestImmediateReconnect()
                    sessionRealtimeController.requestImmediateReconnect()
                }
            }

            override fun onLost(network: Network) {
                networkWasLost.set(true)
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    fun clearSessionAndReturnToLogin(expectedToken: String? = null) {
        val didClearSession = if (expectedToken == null) {
            authController.signOut()
            true
        } else {
            sessionStore.clearAuthSessionIfTokenMatches(expectedToken)
        }
        if (!didClearSession) return

        appUpdateViewModel.syncSession(false)
        remoteTerminalPool.disposeLocal()
        sessionsState = SessionsState()
        isRefreshingSessions = false
        projectSessionsById = emptyMap()
        loadingProjectRequests = emptySet()
        projectSessionErrors = emptyMap()
        projectSessionsLoadedAt = emptyMap()
        selectedSessionId = null
        initialNewSessionProjectId = null
        preparedSessionDraft = null
        selectedDeviceId = null
        pendingMobileLoginQr = null
        webLoginViewModel.resetForSignedOutEntry()
        destinationName = AppDestination.LoginMethods.name
    }

    LaunchedEffect(unauthorizedTokens) {
        for (accessToken in unauthorizedTokens) {
            clearSessionAndReturnToLogin(expectedToken = accessToken)
        }
    }

    DisposableEffect(lifecycleOwner, hasAuthSession, authController, context) {
        if (!hasAuthSession) {
            return@DisposableEffect onDispose {}
        }

        var validationJob: Job? = null
        fun validateSessionIfOnline() {
            if (!context.hasUsableNetwork() || validationJob?.isActive == true) return
            validationJob = scope.launch {
                authController.me()
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) {
                validateSessionIfOnline()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            validateSessionIfOnline()
        }

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            validationJob?.cancel()
        }
    }

    suspend fun refreshSessions(showInitialLoading: Boolean, showRefreshIndicator: Boolean) {
        val request = sessionsState.beginSessionRequest()
        sessionsState = request.state
        if (showRefreshIndicator) {
            isRefreshingSessions = true
        } else if (showInitialLoading && !sessionsState.hasLoaded && sessionsState.sessions.isEmpty() && sessionsState.devices.isEmpty()) {
            sessionsState = sessionsState.copy(isLoading = true, errorMessage = null)
        }

        try {
            sessionsController.loadSessions()
                .onSuccess { loadedState ->
                    projectsRequestVersion++
                    sessionsState = sessionsState.mergedWithRefresh(loadedState, request.generation)
                }
                .onFailure { error ->
                    val hasAnyCachedData = sessionsState.sessions.isNotEmpty() || sessionsState.devices.isNotEmpty()
                    val initialLoadFailed = !sessionsState.hasLoaded && !hasAnyCachedData
                    sessionsState = sessionsState.copy(
                        isLoading = false,
                        errorMessage = when {
                            initialLoadFailed -> error.message ?: "Could not load sessions."
                            hasAnyCachedData -> null
                            else -> sessionsState.errorMessage
                        },
                        hasLoaded = sessionsState.hasLoaded || hasAnyCachedData,
                    )
                }
        } finally {
            if (showRefreshIndicator) {
                isRefreshingSessions = false
            }
        }
    }
    fun loadMoreSessions(archived: Boolean) {
        val hasMore = if (archived) sessionsState.archivedHasMore else sessionsState.activeHasMore
        val cursor = if (archived) sessionsState.archivedNextCursor else sessionsState.activeNextCursor
        val loading = if (archived) sessionsState.isLoadingMoreArchived else sessionsState.isLoadingMoreActive
        if (!hasMore || cursor == null || loading) return
        sessionsState = sessionsState.withSessionPageLoading(archived, true)
        scope.launch {
            sessionsController.loadMoreSessions(
                archived = archived,
                cursor = cursor,
                devices = sessionsState.devices,
            ).onSuccess { page ->
                sessionsState = sessionsState.withAppendedSessionPage(page)
            }.onFailure {
                sessionsState = sessionsState.withSessionPageLoading(archived, false)
            }
        }
    }

    suspend fun reloadProjects(): Result<List<AgentProject>> {
        val token = sessionStore.readAccessToken()
        val version = ++projectsRequestVersion
        return sessionsController.loadProjects().onSuccess { projects ->
            if (sessionStore.readAccessToken() == token && projectsRequestVersion == version) {
                sessionsState = sessionsState.copy(projects = projects)
            }
        }
    }

    fun loadProjectSessions(projectId: String, status: ProjectSessionStatusFilter = ProjectSessionStatusFilter.Active) {
        if (projectId.isBlank()) return
        val token = sessionStore.readAccessToken()
        status.archiveStates.forEach { archived ->
            val key = ProjectSessionLoadKey(projectId, archived)
            if (key in loadingProjectRequests) return@forEach
            val loadedAt = projectSessionsLoadedAt[key]
            if (loadedAt != null && SystemClock.elapsedRealtime() - loadedAt < 30_000L) return@forEach
            loadingProjectRequests = loadingProjectRequests + key
            projectSessionErrors = projectSessionErrors - key
            scope.launch {
                try {
                    projectLoadSlots.withPermit {
                        if (sessionStore.readAccessToken() != token || sessionsState.projects.none { it.id == projectId }) return@withPermit
                        sessionsController.loadProjectSessions(projectId, sessionsState.devices, archived)
                            .onSuccess { sessions ->
                                if (sessionStore.readAccessToken() == token && sessionsState.projects.any { it.id == projectId }) {
                                    val retained = projectSessionsById[projectId].orEmpty().filter { it.archived != archived }
                                    projectSessionsById = projectSessionsById + (projectId to (retained + sessions).associateBy { it.id }.values.toList())
                                    projectSessionsLoadedAt = projectSessionsLoadedAt + (key to SystemClock.elapsedRealtime())
                                }
                            }.onFailure { error ->
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                if (sessionStore.readAccessToken() == token) {
                                    projectSessionErrors = projectSessionErrors + (key to (error.message ?: "Could not load project sessions."))
                                }
                            }
                    }
                } finally {
                    if (sessionStore.readAccessToken() == token) {
                        loadingProjectRequests = loadingProjectRequests - key
                    }
                }
            }
        }
    }

    val navigate: (AppDestination) -> Unit = { destination ->
        if (destination == AppDestination.QrLogin) {
            pendingMobileLoginQr = null
        }
        if (destination == AppDestination.LoginMethods) {
            webLoginViewModel.returnToHostChoice()
        }
        if (
            destination == AppDestination.ServerSetup &&
            destinationName == AppDestination.LoginMethods.name
        ) {
            webLoginViewModel.returnToHostChoice()
        }
        if (destination != AppDestination.SessionDetail) {
            preparedSessionDraft = null
        }
        if (destination == AppDestination.NewSession || destination == AppDestination.NewProject) {
            initialNewSessionProjectId = null
        }
        if (
            destination == AppDestination.DeviceSetup &&
            destinationName != AppDestination.DeviceSetup.name
        ) {
            deviceSetupReturnDestinationName = destinationName
        }
        destinationName = destination.name
    }

    val realtimeServerUrl = sessionStore.readServerUrl()
    val realtimeAccessToken = sessionStore.readAccessToken()
    val devicePairingMonitor = remember(realtimeServerUrl, realtimeAccessToken) { DevicePairingMonitor() }
    val devicePairingStates by devicePairingMonitor.states.collectAsState()
    LaunchedEffect(devicePairingMonitor, appVisible, hasAuthSession) {
        if (!hasAuthSession || !appVisible) return@LaunchedEffect
        devicePairingMonitor.observe(
            loadDevice = devicesController::getDevice,
            onOnline = { device ->
                if (sessionStore.readServerUrl() == realtimeServerUrl && sessionStore.readAccessToken() == realtimeAccessToken) {
                    sessionsState = sessionsState.withPatchedDevice(device)
                    scope.launch { refreshSessions(showInitialLoading = false, showRefreshIndicator = false) }
                }
            },
        )
    }
    LaunchedEffect(
        hasAuthSession,
        realtimeServerUrl,
        realtimeAccessToken,
        dashboardRealtimeController,
    ) {
        if (!hasAuthSession) {
            sessionsState = SessionsState()
            return@LaunchedEffect
        }
        // 应用在后台时也保持实时连接，否则状态变化（等待回答/出错等）无法触发通知
        dashboardRealtimeController.start(
            scope = this,
            onSnapshot = { snapshot ->
                scope.launch {
                    if (sessionStore.readServerUrl() != realtimeServerUrl ||
                        sessionStore.readAccessToken() != realtimeAccessToken
                    ) return@launch
                    // Dashboard snapshots are authoritative for every session in the
                    // inventory. Patch the per-project caches so sidebar rail badges
                    // (running / attention counts) refresh without a manual pull-refresh
                    // or a 30s cache expiry. Prefer the newer updatedSeq when both the
                    // cached and snapshot copies exist. The whole merge is pure data
                    // transformation: run it off the main thread and commit only the
                    // resulting immutable values on the main thread.
                    val current = sessionsState
                    val currentProjects = projectSessionsById
                    val (nextState, nextProjects) = withContext(Dispatchers.Default) {
                        val snapshotState = sessionsController.dashboardSnapshotState(snapshot)
                        val next = current.replacedByDashboardSnapshot(snapshotState)
                        val snapshotSessions = snapshotState.sessions
                            .filterNot { it.archived }
                        val mergedProjects = if (snapshotSessions.isNotEmpty()) {
                            val snapshotById = snapshotSessions.associateBy { it.id }
                            val merged = currentProjects.mapValues { (projectId, cached) ->
                                val mergedById = cached.associateBy { it.id }.toMutableMap()
                                snapshotById.forEach { (id, incoming) ->
                                    if (incoming.projectId == projectId) {
                                        val existing = mergedById[id]
                                        if (existing == null || incoming.updatedSeq >= existing.updatedSeq) {
                                            mergedById[id] = incoming
                                        }
                                    }
                                }
                                mergedById.values.toList()
                            }
                            // Also add snapshot sessions whose project has no cache entry yet.
                            val cachedProjectIds = merged.keys
                            val missingByProject = snapshotSessions
                                .filter { it.projectId != null && it.projectId !in cachedProjectIds }
                                .groupBy { it.projectId!! }
                            merged + missingByProject.mapValues { (_, sessions) ->
                                sessions.associateBy { it.id }.values.toList()
                            }
                        } else {
                            currentProjects
                        }
                        next to mergedProjects
                    }
                    projectsRequestVersion++
                    sessionsState = nextState
                    projectSessionsById = nextProjects
                }
            },
            onInitialFailure = {
                scope.launch {
                    if (sessionStore.readServerUrl() != realtimeServerUrl ||
                        sessionStore.readAccessToken() != realtimeAccessToken
                    ) return@launch
                    if (!sessionsState.hasLoaded) {
                        refreshSessions(showInitialLoading = true, showRefreshIndicator = false)
                    }
                }
            },
        ).join()
    }

    // 兜底轮询：WebSocket 推送断连或丢帧时，周期性静默刷新让会话状态最终收敛
    // （进行中→已结束、待处理→已完成 等不会一直停留在旧值）。
    LaunchedEffect(hasAuthSession, realtimeServerUrl, realtimeAccessToken) {
        if (!hasAuthSession) return@LaunchedEffect
        val serverUrl = realtimeServerUrl
        val accessToken = realtimeAccessToken
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (sessionStore.readServerUrl() != serverUrl ||
                sessionStore.readAccessToken() != accessToken
            ) return@LaunchedEffect
            refreshSessions(showInitialLoading = false, showRefreshIndicator = false)
        }
    }

    LaunchedEffect(oauthCallbackUri) {
        val uri = oauthCallbackUri ?: return@LaunchedEffect
        if (currentDestination == AppDestination.ServerSetup &&
            webLoginViewModel.state is WebLoginState.WebLogin
        ) {
            webLoginViewModel.handleCallback(uri.toString())
        }
        onOAuthCallbackConsumed()
    }

    ProvideAAWindowSize {
        AgentPilotNavHost(
            currentDestination = currentDestination,
        sessionsState = sessionsState,
        isRefreshingSessions = isRefreshingSessions,
        selectedSessionId = selectedSessionId,
        preparedSessionDraft = preparedSessionDraft,
        selectedDeviceId = selectedDeviceId,
        deviceDetailReturnDestination = AppDestination.valueOf(deviceDetailReturnDestinationName),
        deviceSetupReturnDestination = AppDestination.valueOf(deviceSetupReturnDestinationName),
        selectedHomeTab = HomeTab.valueOf(selectedHomeTabName),
        userId = authController.savedUserId(),
        role = authController.savedRole(),
        serverUrl = authController.savedServerUrl(),
        appearanceMode = appearanceMode,
        themeTone = themeTone,
        amoledDark = amoledDark,
        languageMode = languageMode,
        sidebarViewMode = sidebarViewMode,
        projectSessionsById = projectSessionsById,
        loadingProjectRequests = loadingProjectRequests,
        projectSessionErrors = projectSessionErrors,
        initialNewSessionProjectId = initialNewSessionProjectId,
        sessionDetailController = sessionDetailController,
        sessionRealtimeController = sessionRealtimeController,
        filesController = filesController,
        remoteTerminalPool = remoteTerminalPool,
        pendingMobileLoginQr = pendingMobileLoginQr,
        webLoginViewModel = webLoginViewModel,
        appUpdateViewModel = appUpdateViewModel,
        navigate = navigate,
        onRefreshSessions = {
            if (!hasAuthSession || isRefreshingSessions) return@AgentPilotNavHost
            scope.launch {
                refreshSessions(
                    showInitialLoading = false,
                    showRefreshIndicator = true,
                )
                val previousRequests = projectSessionsLoadedAt.keys + projectSessionErrors.keys + loadingProjectRequests
                projectSessionsLoadedAt = emptyMap()
                if (sidebarViewMode == com.agentpilot.app.ui.screens.home.HomeSidebarViewMode.Project) {
                    val loaded = (projectSessionsById.values.flatten() + sessionsState.sessions + sessionsState.archivedSessions)
                        .associateBy { it.id }.values
                    sessionsState.projects.filter { projectHasActiveSessions(it, loaded) }.forEach { loadProjectSessions(it.id) }
                    previousRequests.filter { key -> sessionsState.projects.any { it.id == key.projectId } }.forEach { key ->
                        loadProjectSessions(key.projectId, if (key.archived) ProjectSessionStatusFilter.Archived else ProjectSessionStatusFilter.Active)
                    }
                }
            }
        },
        onLoadMoreSessions = ::loadMoreSessions,
        onOpenSession = { session ->
            preparedSessionDraft = null
            selectedSessionId = session.id
            destinationName = AppDestination.SessionDetail.name
            if (session.unread) {
                val request = sessionsState.beginSessionRequest(listOf(session.id))
                sessionsState = request.state
                scope.launch {
                    sessionsController.markSessionRead(session.id, sessionsState.devices)
                        .onSuccess { updated ->
                            sessionsState = sessionsState.withPatchedSession(updated, request.generation)
                        }
                        .onFailure { error ->
                            Toast.makeText(
                                context,
                                error.message ?: "Could not mark this session as read.",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                }
            }
        },
        onOpenDevice = { device ->
            deviceDetailReturnDestinationName = destinationName
            selectedDeviceId = device.id
            destinationName = AppDestination.DeviceDetail.name
        },
        onHomeTabSelected = { tab ->
            selectedHomeTabName = tab.name
        },
        onAppearanceModeChange = onAppearanceModeChange,
        onThemeToneChange = onThemeToneChange,
        onAmoledDarkChange = onAmoledDarkChange,
        onLanguageModeChange = onLanguageModeChange,
        onSidebarViewModeChange = onSidebarViewModeChange,
        onLoadAccount = { authController.me() },
        onLoadAccountAuthConfig = { authController.accountAuthConfig() },
        onUpdateDisplayName = { displayName -> authController.updateDisplayName(displayName) },
        onSendEmailCode = { email -> authController.sendEmailCode(email) },
        onBindEmail = { email, code -> authController.bindEmail(email, code) },
        onUpdateAvatar = { avatar -> authController.updateAvatar(avatar) },
        onClearAvatar = { authController.clearAvatar() },
        onChangePassword = { password -> authController.changePassword(password) },
        onSignOut = {
            clearSessionAndReturnToLogin()
        },
        onRenameDevice = { connectorId, name ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to rename this device."))
            } else {
                devicesController.renameDevice(connectorId, name)
                    .onSuccess { device ->
                        sessionsState = sessionsState.withPatchedDevice(device)
                    }
            }
        },
        onDeleteDevice = { connectorId ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to delete this device."))
            } else {
                devicesController.deleteDevice(connectorId)
                    .onSuccess {
                        sessionsState = sessionsState.withDeletedDevice(connectorId)
                        if (selectedDeviceId == connectorId) selectedDeviceId = null
                        destinationName = AppDestination.Devices.name
                    }
            }
        },
        onPrepareDeviceSetup = { connectorId ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to set up this device."))
            } else {
                devicesController.prepareDeviceSetup(connectorId)
                    .onSuccess { credential ->
                        sessionsState = sessionsState.withPatchedDevice(credential.device)
                    }
            }
        },
        onCreateDeviceSetup = { name ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to pair a new device."))
            } else {
                devicesController.createDeviceSetup(name)
                    .onSuccess { credential ->
                        sessionsState = sessionsState.withPatchedDevice(credential.device)
                    }
            }
        },
        onClaimDevicePairCode = { credential, code ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to claim this pair code."))
            } else {
                devicesController.claimDevicePairCode(credential, code)
                    .onSuccess { device ->
                        sessionsState = sessionsState.withPatchedDevice(device)
                    }
            }
        },
        devicePairingStates = devicePairingStates,
        onWaitForPairingDevice = devicePairingMonitor::waitForDevice,
        onClearDevicePairing = devicePairingMonitor::clear,
        onDevicePairingComplete = {
            if (hasAuthSession) scope.launch {
                refreshSessions(showInitialLoading = false, showRefreshIndicator = false)
            }
        },
        onListDeviceRuntimes = { connectorId ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to load runtimes."))
            } else {
                devicesController.listDeviceRuntimes(connectorId)
            }
        },
        onSetDeviceRuntimeActive = { connectorId, runtime, active ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update this runtime."))
            } else {
                devicesController.setDeviceRuntimeActive(connectorId, runtime, active)
            }
        },
        onDeleteDeviceRuntimeConfig = { connectorId, runtime ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to delete runtime configuration."))
            } else {
                devicesController.deleteDeviceRuntimeConfig(connectorId, runtime)
            }
        },
        onBulkSetSessionsArchived = { ids, archived ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update sessions."))
            } else {
                val request = sessionsState.beginSessionRequest(ids)
                sessionsState = request.state
                sessionsController.bulkSetSessionsArchived(ids, archived, sessionsState.devices)
                    .onSuccess { update ->
                        sessionsState = sessionsState
                            .withPatchedSessions(update.sessions, request.generation)
                            .withMissingSessionsRemoved(update.notFound, request.generation)
                        reloadProjects()
                    }
            }
        },
        onArchiveAllDeviceSessions = { connectorId, archived, scope ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update sessions."))
            } else {
                val targetIds = (sessionsState.sessions + sessionsState.archivedSessions)
                    .filter { session ->
                        session.connectorId == connectorId && when (scope) {
                            "active" -> !session.archived
                            "archived" -> session.archived
                            else -> true
                        }
                    }
                    .map { it.id }
                val request = sessionsState.beginSessionRequest(targetIds)
                sessionsState = request.state
                sessionsController.archiveAllDeviceSessions(connectorId, archived, scope, sessionsState.devices)
                    .onSuccess { sessions ->
                        sessionsState = sessionsState.withPatchedSessions(sessions, request.generation)
                        reloadProjects()
                    }
            }
        },
        onRenameSession = { sessionId, title ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update this session."))
            } else {
                val request = sessionsState.beginSessionRequest(listOf(sessionId))
                sessionsState = request.state
                sessionsController.renameSession(sessionId, title, sessionsState.devices)
                    .onSuccess { session ->
                        sessionsState = sessionsState.withPatchedSession(session, request.generation)
                    }
            }
        },
        onSetSessionPinned = { sessionId, pinned ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update this session."))
            } else {
                val request = sessionsState.beginSessionRequest(listOf(sessionId))
                sessionsState = request.state
                sessionsController.setSessionPinned(sessionId, pinned, sessionsState.devices)
                    .onSuccess { session ->
                        sessionsState = sessionsState.withPatchedSession(session, request.generation)
                        reloadProjects()
                    }
            }
        },
        onSetSessionArchived = { sessionId, archived ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update this session."))
            } else {
                val request = sessionsState.beginSessionRequest(listOf(sessionId))
                sessionsState = request.state
                sessionsController.setSessionArchived(sessionId, archived, sessionsState.devices)
                    .onSuccess { session ->
                        sessionsState = sessionsState.withPatchedSession(session, request.generation)
                        reloadProjects()
                    }
            }
        },
        onLoadProjectSessions = ::loadProjectSessions,
        onLoadProjects = ::reloadProjects,
        onLoadArchivedPage = { projectId, cursor ->
            sessionsController.loadArchivedSessionPage(projectId, cursor, sessionsState.devices)
        },
        onRestoreProject = { projectId ->
            val ids = sessionsState.archivedSessions.filter { it.projectId == projectId }.map { it.id }
            val request = sessionsState.beginSessionRequest(ids)
            sessionsState = request.state
            sessionsController.archiveProjectSessions(projectId, sessionsState.devices, archived = false, scope = "archived")
                .mapCatching { sessions ->
                    check(sessions.isNotEmpty() && sessions.none { it.archived }) { "Could not restore all sessions in this project." }
                    sessions
                }.onSuccess { sessions ->
                    sessionsState = sessionsState.withPatchedSessions(sessions, request.generation)
                    projectSessionsById = projectSessionsById + (projectId to
                        (sessions + projectSessionsById[projectId].orEmpty()).distinctBy { it.id })
                    reloadProjects()
                }
        },
        onUpdateProject = { projectId, name, pinned ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to update this project."))
            } else {
                sessionsController.updateProject(projectId, name, pinned)
                    .onSuccess { project ->
                        projectsRequestVersion++
                        sessionsState = sessionsState.withPatchedProject(project)
                    }
            }
        },
        onArchiveProjectSessions = { projectId ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to archive project sessions."))
            } else {
                val targetIds = (projectSessionsById[projectId].orEmpty() + sessionsState.sessions)
                    .filter { it.projectId == projectId && !it.archived }
                    .map(AgentSession::id)
                    .distinct()
                val request = sessionsState.beginSessionRequest(targetIds)
                sessionsState = request.state
                sessionsController.archiveProjectSessions(projectId, sessionsState.devices, scope = "all")
                    .onSuccess { sessions ->
                        sessionsState = sessionsState.withPatchedSessions(sessions, request.generation)
                        projectSessionsById = projectSessionsById + (projectId to
                            (projectSessionsById[projectId].orEmpty() + sessions).associateBy { it.id }.values.toList())
                        projectSessionsLoadedAt = projectSessionsLoadedAt.filterKeys { it.projectId != projectId }
                        reloadProjects()
                    }
            }
        },
        onCreateProject = { name, connectorId, workspacePath ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to create a project."))
            } else {
                sessionsController.createProject(name, connectorId, workspacePath)
                    .onSuccess { project ->
                        projectsRequestVersion++
                        sessionsState = sessionsState.withPatchedProject(project)
                    }
            }
        },
        onNewSessionInProject = { project ->
            initialNewSessionProjectId = project.id
            destinationName = AppDestination.NewSession.name
        },
        onCreateSession = { draft ->
            if (!hasAuthSession) {
                NewSessionCreateOutcome.Failed(IllegalStateException("Sign in again to create a session."))
            } else {
                val refresh = sessionsState.beginSessionRequest()
                sessionsState = refresh.state
                NewSessionPreferenceStore(context, sessionStore.readServerUrl(), sessionStore.readUserId()).save(
                    connectorId = draft.connectorId,
                    runtimeId = draft.runtimeId,
                    selections = draft.selections,
                )
                val outcome = sessionsController.createAndStartSession(
                    draft = draft,
                    devices = sessionsState.devices,
                    projects = sessionsState.projects,
                )
                val refreshedState = when (outcome) {
                    is NewSessionCreateOutcome.Created -> outcome.refreshedState
                    is NewSessionCreateOutcome.Failed -> outcome.refreshedState
                }
                if (refreshedState != null) {
                    sessionsState = sessionsState.mergedWithRefresh(refreshedState, refresh.generation)
                }
                if (outcome is NewSessionCreateOutcome.Created) {
                    sessionsState = sessionsState.withPatchedSession(outcome.session.copy(optimisticTopUntil = System.currentTimeMillis() + 1_000), refresh.generation)
                    reloadProjects()
                }
                outcome
            }
        },
        onListSchedules = { sessionsController.listSchedules() },
        onCreateSchedule = { title, prompt, kind, runAt, runTime, weekday, tz, sessionId ->
            sessionsController.createSchedule(title, prompt, kind, runAt, runTime, weekday, tz, sessionId)
        },
        onDeleteSchedule = { sessionsController.deleteSchedule(it) },
        onSetScheduleEnabled = { id, enabled -> sessionsController.setScheduleEnabled(id, enabled) },
        onPrepareSession = { draft ->
            preparedSessionDraft = draft
            selectedSessionId = null
            destinationName = AppDestination.SessionDetail.name
        },
        onPreparedSessionCreated = { session ->
            sessionsState = sessionsState.withPatchedSession(session)
            preparedSessionDraft = null
            selectedSessionId = session.id
        },
        onListDirectory = { connectorId, root, path ->
            if (!hasAuthSession) {
                Result.failure(IllegalStateException("Sign in again to browse files."))
            } else {
                sessionsController.listNewSessionDirectory(
                    connectorId = connectorId,
                    root = root,
                    path = path,
                )
            }
        },
        onListNewSessionRuntimes = { connectorId ->
            sessionsController.listNewSessionRuntimes(connectorId)
        },
        onLoadNewSessionRuntimeCapabilities = { connectorId, runtime ->
            sessionsController.loadNewSessionRuntimeCapabilities(connectorId, runtime)
        },
        onLoadNewSessionModelCatalog = { connectorId, runtime ->
            sessionsController.loadNewSessionModelCatalog(connectorId, runtime)
        },
        onLoadNewSessionPermissionCatalog = { connectorId, runtime ->
            sessionsController.loadNewSessionPermissionCatalog(connectorId, runtime)
        },
        onSessionChanged = { session ->
            sessionsState = sessionsState.withPatchedSession(session)
        },
        onMobileLoginQrRequested = { payload ->
            pendingMobileLoginQr = payload
            destinationName = AppDestination.QrWaiting.name
        },
        )
    }
    AnnouncementGate(
        api = announcementsApi,
        serverUrl = updateServerUrl.ifBlank { AppConfig.OFFICIAL_SERVER_URL },
        page = when {
            currentDestination == AppDestination.LoginMethods -> AnnouncementPage.Login
            updatesAllowed -> AnnouncementPage.App
            else -> null
        },
        appVisible = appVisible,
        blocked = updatesAllowed && (
            appUpdateViewModel.state.checking || appUpdateViewModel.state.promptVisible ||
                appUpdateViewModel.state.downloading || appUpdateViewModel.state.preparingInstall
            ),
    )
    if (updatesAllowed) {
        AppUpdatePromptDialog(
            state = appUpdateViewModel.state,
            onUpdate = appUpdateViewModel::downloadUpdate,
            onIgnore = appUpdateViewModel::ignoreVersion,
        )
    }
    LaunchedEffect(updatesAllowed, appUpdateViewModel.state.installFile) {
        if (updatesAllowed) appUpdateViewModel.state.installFile?.let(onInstallUpdate)
    }
}


private fun Context.hasUsableNetwork(): Boolean {
    val connectivityManager = getSystemService(ConnectivityManager::class.java)
    val activeNetwork = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

private val NewSessionDraftSaver = listSaver<NewSessionDraft?, Any>(
    save = { draft ->
        if (draft == null) {
            emptyList()
        } else {
            listOf(
                draft.connectorId,
                draft.runtime,
                draft.title.orEmpty(),
                draft.cwd.orEmpty(),
                draft.deviceName,
                draft.runtimeLabel,
                ArrayList(draft.knownSessionIds),
                draft.selections.model.orEmpty(),
                draft.selections.permission.orEmpty(),
                draft.runtimeId,
                draft.runtimeType,
                draft.runtimeName,
                draft.attachmentsEnabled,
                draft.localSessionId,
                draft.projectId,
            )
        }
    },
    restore = { values ->
        if (values.isEmpty()) {
            null
        } else {
            @Suppress("UNCHECKED_CAST")
            NewSessionDraft(
                connectorId = values[0] as String,
                runtime = values[1] as String,
                title = (values[2] as String).takeIf(String::isNotBlank),
                cwd = (values[3] as String).takeIf(String::isNotBlank),
                deviceName = values[4] as String,
                runtimeLabel = values[5] as String,
                knownSessionIds = (values[6] as ArrayList<String>).toSet(),
                selections = com.agentpilot.app.feature.sessions.NewSessionSelections(
                    model = (values[7] as String).takeIf(String::isNotBlank),
                    permission = (values[8] as String).takeIf(String::isNotBlank),
                ),
                runtimeId = (values.getOrNull(9) as? String)?.takeIf(String::isNotBlank)
                    ?: values[1] as String,
                runtimeType = (values.getOrNull(10) as? String)?.takeIf(String::isNotBlank)
                    ?: values[1] as String,
                runtimeName = (values.getOrNull(11) as? String)?.takeIf(String::isNotBlank)
                    ?: values[5] as String,
                attachmentsEnabled = values.getOrNull(12) as? Boolean ?: true,
                localSessionId = (values.getOrNull(13) as? String)?.takeIf(String::isNotBlank)
                    ?: (values.getOrNull(12) as? String)?.takeIf(String::isNotBlank)
                    ?: com.agentpilot.app.feature.sessions.NewSessionDraft.newLocalSessionId(),
                projectId = (values.getOrNull(14) as? String).orEmpty(),
            )
        }
    },
)

@Preview(showBackground = true, widthDp = 390, heightDp = 844)
@Composable
private fun AgentPilotAppPreview() {
    AgentPilotTheme {
        val context = LocalContext.current
        val application = context.applicationContext as android.app.Application
        AgentPilotApp(
            webLoginViewModel = WebLoginViewModel(application),
            appUpdateViewModel = AppUpdateViewModel(application),
        )
    }
}
