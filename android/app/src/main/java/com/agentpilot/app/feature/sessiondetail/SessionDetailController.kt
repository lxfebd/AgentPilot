package com.agentpilot.app.feature.sessiondetail

import com.agentpilot.app.api.ApiException
import com.agentpilot.app.api.RemoteRpcResponse
import com.agentpilot.app.api.RemoteSessionEventEnvelope
import com.agentpilot.app.api.RemoteSessionShareResponse
import com.agentpilot.app.api.RemoteRuntimeModelCatalog
import com.agentpilot.app.api.RemoteRuntimePermissionCatalog
import com.agentpilot.app.api.SessionsApi
import com.agentpilot.app.api.UploadFilePart
import com.agentpilot.app.api.toMap
import com.agentpilot.app.feature.auth.AuthSessionReader
import com.agentpilot.app.feature.sessions.toAgentSession
import com.agentpilot.app.model.AgentDevice
import com.agentpilot.app.model.AgentSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.max
import org.json.JSONObject

private const val INITIAL_TIMELINE_LIMIT = 100
private const val TIMELINE_PAGE_LIMIT = 100

/** How many newest timeline items to scan for the echo of an indeterminate send. */
private const val SEND_ECHO_LOOKUP_LIMIT = 100

/** Wait between echo polls; the server can take seconds to fan the message back through the connector. */
private const val SEND_ECHO_RETRY_DELAY_MILLIS = 2_000L

/** Total echo polls before giving up and surfacing the failure (2s * 6 = up to 12s). */
private const val SEND_ECHO_CONFIRM_ATTEMPTS = 6

class SessionDetailController(
    private val sessionsApi: SessionsApi,
    private val sessionStore: AuthSessionReader,
) {
    private val optimisticStore = SessionOptimisticMessageStore()
    private val attachmentTransfer = SessionAttachmentTransfer(sessionsApi)

    suspend fun nativeHistoryAction(sessionId: String, body: org.json.JSONObject, close: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val workflow = com.agentpilot.app.api.RuntimeWorkflowApi()
                if (close) {
                    val result = workflow.closeSession(auth.serverUrl, auth.accessToken, sessionId, body)
                    if (!result.optBoolean("closed")) {
                        throw com.agentpilot.app.feature.sessions.RuntimeOperationFailure(
                            "native_history_release_unconfirmed", "Execution release is unconfirmed.",
                        )
                    }
                } else {
                    sessionsApi.enableTakeover(auth.serverUrl, auth.accessToken, sessionId)
                    var completed = false
                    repeat(360) {
                        if (!completed) {
                            val result = workflow.recover(auth.serverUrl, auth.accessToken, sessionId, body)
                            when (result.optString("phase")) {
                                "completed" -> completed = true
                                "running" -> kotlinx.coroutines.delay(1000)
                                "rejected", "failed" -> {
                                    val failure = result.optJSONObject("failure")
                                    throw NativeHistoryRejected(
                                        failure?.optString("code")?.takeIf(String::isNotBlank)
                                            ?: "native_history_recovery_failed",
                                        failure?.optString("message")?.takeIf(String::isNotBlank) ?: "Recovery failed.",
                                    )
                                }
                                else -> throw com.agentpilot.app.feature.sessions.RuntimeOperationFailure(
                                    "native_history_recovery_unconfirmed",
                                    "Recovery is unconfirmed. Check the existing operation.",
                                )
                            }
                        }
                    }
                    if (!completed) {
                        throw com.agentpilot.app.feature.sessions.RuntimeOperationFailure(
                            "native_history_recovery_unconfirmed",
                            "Recovery is unconfirmed. Check the existing operation.",
                        )
                    }
                }
                Unit
            }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
        }

    suspend fun loadPlanning(sessionId: String): Result<com.agentpilot.app.api.RemotePlanningState?> =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                sessionsApi.planning(auth.serverUrl, auth.accessToken, sessionId)
            }
        }

    suspend fun submitPlanning(sessionId: String, command: PlanningCommand, attachments: List<TimelineAttachment>) {
        withContext(Dispatchers.IO) {
            val auth = authSession()
            sessionsApi.executePlanning(
                auth.serverUrl, auth.accessToken, sessionId,
                JSONObject()
                    .put("command", command.command)
                    .put("content", command.content)
                    .put("operationId", java.util.UUID.randomUUID().toString())
                    .put("attachments", org.json.JSONArray(attachments.map { JSONObject().put("fileId", it.fileId) })),
            )
        }
    }

    suspend fun respondPlanning(sessionId: String, target: com.agentpilot.app.api.RemotePlanningState, action: String, feedback: String) {
        withContext(Dispatchers.IO) {
            val auth = authSession()
            sessionsApi.executePlanning(
                auth.serverUrl, auth.accessToken, sessionId,
                JSONObject()
                    .put("command", action)
                    .put("operationId", java.util.UUID.randomUUID().toString())
                    .put("reviewId", target.review?.reviewId)
                    .put("planId", target.review?.planId)
                    .put("planRevision", target.review?.planRevision)
                    .put("contentHash", target.review?.contentHash)
                    .put("epoch", target.epoch)
                    .put("feedback", feedback),
            )
        }
    }

    fun clearPlanningEntry(sessionId: String) { optimisticStore.clear(sessionId) }


    suspend fun load(
        sessionId: String,
        devices: List<AgentDevice>,
        currentState: SessionDetailState? = null,
    ): Result<SessionDetailState> = loadInitialSnapshot(sessionId, devices, currentState)

    suspend fun loadInitialSnapshot(
        sessionId: String,
        devices: List<AgentDevice>,
        currentState: SessionDetailState? = null,
    ): Result<SessionDetailState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val snapshot = sessionsApi.getSessionSnapshot(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    sessionId = sessionId,
                    limit = INITIAL_TIMELINE_LIMIT,
                )
                val projection = mergeRemoteTimelineItems(
                    currentOrdering = emptyList(),
                    currentMessages = emptyList(),
                    incoming = snapshot.timeline.items,
                    replace = true,
                )
                val messages = mergeOptimistic(
                    sessionId = sessionId,
                    realMessages = projection.messages,
                    currentMessages = currentState?.messages.orEmpty(),
                    orderingItems = projection.orderingItems,
                )
                SessionDetailState(
                    meta = SessionMeta(
                        session = snapshot.session.toAgentSession(devices.associateBy { it.id }),
                        serverTime = snapshot.serverTime,
                    ),
                    timeline = SessionTimelineState(
                        messages = messages,
                        orderingItems = projection.orderingItems,
                        nextSeq = snapshot.timeline.nextSeq,
                        hasMore = snapshot.timeline.hasMore,
                        eventCursor = snapshot.eventCursor,
                    ),
                    runtime = snapshot.state.toSessionRuntimeState(snapshot.serverTime),
                    capabilities = snapshot.effectiveCapabilities.toEffectiveCapabilities(
                        connectorId = snapshot.session.connectorId,
                        serverTime = snapshot.serverTime,
                    ),
                    runtimeCapabilities = snapshot.runtimeCapabilities.toRuntimeCapabilities(),
                    notices = RuntimeNotices(
                        notices = mergeRuntimeNotices(emptyList(), snapshot.notices, replace = true),
                        serverTime = snapshot.serverTime,
                        isLoaded = true,
                        eventSequence = snapshot.eventCursor.removePrefix("seq:").toLongOrNull() ?: 0L,
                    ),
                    catalogs = RuntimeCatalogs(
                        model = snapshot.catalogs.model,
                        permission = snapshot.catalogs.permission,
                    ),
                    commands = currentState?.commands ?: RuntimeCommands(),
                    realtime = (currentState?.realtime ?: SessionRealtimeState()).copy(
                        cursor = snapshot.eventCursor,
                        recovering = false,
                        lastErrorMessage = null,
                    ),
                    initialized = true,
                    actionError = currentState?.actionError,
                    takeoverInFlight = currentState?.takeoverInFlight ?: false,
                    sending = currentState?.sending ?: messages.hasPendingOptimisticSend(),
                    interrupting = currentState?.interrupting ?: false,
                    selectionUpdating = currentState?.selectionUpdating ?: false,
                    commandExecuting = currentState?.commandExecuting ?: false,
                    respondingNoticeIds = currentState?.respondingNoticeIds.orEmpty(),
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not load messages.", error)
            }
        }
    }

    fun applyRealtimeEvent(
        current: SessionDetailState,
        event: RemoteSessionEventEnvelope,
        devices: List<AgentDevice>,
    ): SessionDetailState {
        val next = reduceRealtimeEvent(current, event, devices)
        current.session?.id?.let { sessionId ->
            optimisticStore.replace(sessionId, next.messages.filter { it.optimistic })
        }
        return next
    }

    fun applyRealtimeEvents(
        current: SessionDetailState,
        events: List<RemoteSessionEventEnvelope>,
        devices: List<AgentDevice>,
    ): SessionDetailState {
        val next = reduceRealtimeEvents(current, events, devices)
        current.session?.id?.let { sessionId ->
            optimisticStore.replace(sessionId, next.messages.filter { it.optimistic })
        }
        return next
    }

    fun mergeRuntimeLiveState(
        current: SessionDetailState,
        requestState: SessionDetailState,
        refreshed: SessionDetailState,
    ): SessionDetailState = reduceRuntimeLiveState(current, requestState, refreshed)

    fun mergeSnapshotWithLiveState(
        sessionId: String,
        snapshot: SessionDetailState,
        live: SessionDetailState,
    ): SessionDetailState {
        val next = reduceSnapshotWithLiveState(snapshot, live)
        optimisticStore.replace(sessionId, next.messages.filter { it.optimistic })
        return next
    }

    suspend fun refreshDomains(
        sessionId: String,
        devices: List<AgentDevice>,
        current: SessionDetailState,
    ): SessionDetailState = withContext(Dispatchers.IO) {
        val auth = authSession()
        coroutineScope {
            val metaRequest = async {
                runCatching { sessionsApi.getSessionMeta(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val timelineRequest = async {
                runCatching {
                    sessionsApi.getSessionTimelineChanges(
                        auth.serverUrl,
                        auth.accessToken,
                        sessionId,
                        afterSeq = current.timeline.nextSeq,
                        limit = TIMELINE_PAGE_LIMIT,
                    )
                }
            }
            val runtimeRequest = async {
                runCatching { sessionsApi.getSessionRuntimeState(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val capabilitiesRequest = async {
                runCatching { sessionsApi.getSessionRuntimeCapabilities(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val noticesRequest = async {
                runCatching { sessionsApi.getSessionRuntimeNotices(auth.serverUrl, auth.accessToken, sessionId) }
            }

            var next = current
            metaRequest.await().fold(
                onSuccess = { response ->
                    next = next.applyMetaObservation(
                        response.session.toAgentSession(devices.associateBy { it.id }),
                        response.serverTime,
                    )
                },
                onFailure = { error ->
                    next = next.copy(
                        meta = next.meta.copy(isLoading = false, errorMessage = error.userMessage()),
                    )
                },
            )
            timelineRequest.await().fold(
                onSuccess = { page ->
                    val projection = mergeRemoteTimelineItems(
                        currentOrdering = next.timeline.orderingItems,
                        currentMessages = next.messages.filterNot { it.optimistic },
                        incoming = page.items,
                        replace = false,
                    )
                    next = next.copy(
                        timeline = next.timeline.copy(
                            messages = mergeOptimistic(
                                sessionId,
                                projection.messages,
                                next.messages,
                                projection.orderingItems,
                            ),
                            orderingItems = projection.orderingItems,
                            nextSeq = max(next.timeline.nextSeq, page.nextSeq),
                            eventCursor = "seq:${max(next.timeline.nextSeq, page.nextSeq)}",
                            isLoading = false,
                            errorMessage = null,
                        ),
                    )
                },
                onFailure = { error ->
                    next = next.copy(
                        timeline = next.timeline.copy(isLoading = false, errorMessage = error.userMessage()),
                    )
                },
            )
            runtimeRequest.await().fold(
                onSuccess = { response ->
                    val observed = response.state.toSessionRuntimeState(response.serverTime)
                    next = next.applyRuntimeObservation(observed)
                },
                onFailure = { error ->
                    next = next.copy(
                        runtime = next.runtime.copy(isLoading = false, errorMessage = error.userMessage()),
                    )
                },
            )
            capabilitiesRequest.await().fold(
                onSuccess = { response ->
                    val observed = response.capabilitySet.toEffectiveCapabilities(
                        connectorId = response.connectorId,
                        serverTime = response.serverTime,
                    )
                    next = next.applyCapabilitiesObservation(observed)
                },
                onFailure = { error ->
                    next = next.copy(
                        capabilities = next.capabilities.copy(
                            isLoading = false,
                            errorMessage = error.userMessage(),
                        ),
                    )
                },
            )
            noticesRequest.await().fold(
                onSuccess = { response ->
                    next = next.applyNoticeObservation(
                        incoming = response.notices,
                        serverTime = response.serverTime,
                        replace = true,
                    )
                },
                onFailure = { error ->
                    next = next.copy(
                        notices = next.notices.copy(isLoading = false, errorMessage = error.userMessage()),
                    )
                },
            )
            next.copy(initialized = true)
        }
    }

    suspend fun refreshRuntimeLiveDomains(
        sessionId: String,
        current: SessionDetailState,
    ): SessionDetailState = withContext(Dispatchers.IO) {
        val auth = authSession()
        coroutineScope {
            val runtimeRequest = async {
                runCatching { sessionsApi.getSessionRuntimeState(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val capabilitiesRequest = async {
                runCatching { sessionsApi.getSessionRuntimeCapabilities(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val noticesRequest = async {
                runCatching { sessionsApi.getSessionRuntimeNotices(auth.serverUrl, auth.accessToken, sessionId) }
            }
            val modelCatalogRequest = current.catalogs.model?.let {
                async {
                    runCatching {
                        sessionsApi.getSessionRuntimeModelCatalog(
                            auth.serverUrl,
                            auth.accessToken,
                            sessionId,
                        ).catalog
                    }
                }
            }
            val permissionCatalogRequest = current.catalogs.permission?.let {
                async {
                    runCatching {
                        sessionsApi.getSessionRuntimePermissionCatalog(
                            auth.serverUrl,
                            auth.accessToken,
                            sessionId,
                        ).catalog
                    }
                }
            }
            var next = current
            runtimeRequest.await().fold(
                onSuccess = { response ->
                    next = next.applyRuntimeObservation(response.state.toSessionRuntimeState(response.serverTime))
                },
                onFailure = { error ->
                    next = next.copy(runtime = next.runtime.copy(errorMessage = error.userMessage()))
                },
            )
            capabilitiesRequest.await().fold(
                onSuccess = { response ->
                    next = next.applyCapabilitiesObservation(
                        response.capabilitySet.toEffectiveCapabilities(response.connectorId, response.serverTime),
                    )
                },
                onFailure = { error ->
                    // 能力事实已经被 UI 消费过；connector RPC 抖动会把
                    // /runtime/capabilities 打成一次 409/500（重试即 200）。
                    // 此时覆盖 errorMessage 会让 capabilityFactsFresh 变
                    // false，整个 composer 被锁死成"无法发送"。保留最近
                    // 已知事实——服务端每次操作仍会重新校验能力，客户端
                    // 只是提示层，过期的"可用"最多让服务端先报错。
                    val capabilities = next.capabilities
                    next = if (capabilities.isLoaded) {
                        next.copy(capabilities = capabilities.copy(errorMessage = null))
                    } else {
                        next.copy(capabilities = capabilities.copy(errorMessage = error.userMessage()))
                    }
                },
            )
            noticesRequest.await().fold(
                onSuccess = { response ->
                    next = next.applyNoticeObservation(response.notices, response.serverTime, replace = true)
                },
                onFailure = { error ->
                    next = next.copy(notices = next.notices.copy(errorMessage = error.userMessage()))
                },
            )
            modelCatalogRequest?.await()?.fold(
                onSuccess = { catalog ->
                    if (next.catalogs.model?.let { catalog.revision >= it.revision } != false) {
                        next = next.copy(
                            catalogs = next.catalogs.copy(
                                model = catalog,
                                modelLoading = false,
                                modelStale = false,
                                modelErrorMessage = null,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    next = next.copy(
                        catalogs = next.catalogs.copy(
                            modelLoading = false,
                            modelStale = next.catalogs.model != null,
                            modelErrorMessage = error.userMessage(),
                        ),
                    )
                },
            )
            permissionCatalogRequest?.await()?.fold(
                onSuccess = { catalog ->
                    if (next.catalogs.permission?.let { catalog.revision >= it.revision } != false) {
                        next = next.copy(
                            catalogs = next.catalogs.copy(
                                permission = catalog,
                                permissionLoading = false,
                                permissionStale = false,
                                permissionErrorMessage = null,
                            ),
                        )
                    }
                },
                onFailure = { error ->
                    next = next.copy(
                        catalogs = next.catalogs.copy(
                            permissionLoading = false,
                            permissionStale = next.catalogs.permission != null,
                            permissionErrorMessage = error.userMessage(),
                        ),
                    )
                },
            )
            next
        }
    }

    suspend fun loadOlder(
        sessionId: String,
        beforeOrderSeq: Int,
    ): Result<SessionTimelineState> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val page = sessionsApi.getSessionTimelineHistory(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    sessionId = sessionId,
                    beforeOrderSeq = beforeOrderSeq,
                    limit = TIMELINE_PAGE_LIMIT,
                )
                val projection = mergeRemoteTimelineItems(
                    currentOrdering = emptyList(),
                    currentMessages = emptyList(),
                    incoming = page.items,
                    replace = true,
                )
                SessionTimelineState(
                    messages = projection.messages,
                    orderingItems = projection.orderingItems,
                    nextSeq = page.nextSeq,
                    hasMore = page.hasMore,
                )
            }.recoverCatching { error ->
                if (error is ApiException) throw error
                throw IllegalStateException(error.message ?: "Could not load older messages.", error)
            }
        }
    }

    suspend fun sendMessage(
        sessionId: String,
        content: String,
        clientMessageId: String,
        attachments: List<UploadFilePart> = emptyList(),
        uploadedAttachments: List<TimelineAttachment> = emptyList(),
        steer: Boolean = false,
    ): Result<SendMessageResult> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val uploaded = if (uploadedAttachments.isNotEmpty()) {
                    uploadedAttachments
                } else if (attachments.isEmpty()) {
                    emptyList()
                } else {
                    attachmentTransfer.upload(auth.serverUrl, auth.accessToken, sessionId, attachments)
                }
                val response = if (steer) {
                    // Mid-turn steering: insert guidance while the agent is running.
                    sessionsApi.steerSession(
                        serverUrl = auth.serverUrl,
                        authorizationToken = auth.accessToken,
                        sessionId = sessionId,
                        content = content,
                        clientMessageId = clientMessageId,
                        attachments = uploaded.map { it.toRemoteAttachmentRef() },
                    )
                } else {
                    sessionsApi.sendSessionMessage(
                        serverUrl = auth.serverUrl,
                        authorizationToken = auth.accessToken,
                        sessionId = sessionId,
                        content = content,
                        clientMessageId = clientMessageId,
                        attachments = uploaded.map { it.toRemoteAttachmentRef() },
                    )
                }
                if (!response.ok) {
                    throw IllegalStateException(
                        response.failureMessage(
                            if (steer) "Runtime rejected the steering message."
                            else "Runtime rejected the message.",
                        ),
                    )
                }
                SendMessageResult(attachments = uploaded)
            }
        }
    }

    suspend fun uploadAttachments(
        sessionId: String,
        attachments: List<UploadFilePart>,
    ): Result<List<TimelineAttachment>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                attachmentTransfer.upload(auth.serverUrl, auth.accessToken, sessionId, attachments)
            }
        }
    }

    suspend fun setTakeover(
        sessionId: String,
        enabled: Boolean,
        devices: List<AgentDevice>,
    ): Result<AgentSession> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val session = if (enabled) {
                    sessionsApi.enableTakeover(auth.serverUrl, auth.accessToken, sessionId)
                } else {
                    sessionsApi.disableTakeover(auth.serverUrl, auth.accessToken, sessionId)
                }
                session.toAgentSession(devices.associateBy { it.id })
            }
        }
    }

    fun attachmentImageRequest(
        sessionId: String,
        attachment: TimelineAttachment,
    ): Result<AttachmentImageRequest> {
        return runCatching {
            val auth = authSession()
            attachmentTransfer.imageRequest(auth.serverUrl, auth.accessToken, sessionId, attachment)
        }
    }

    suspend fun downloadAttachment(
        sessionId: String,
        attachment: TimelineAttachment,
    ): Result<DownloadedAttachment> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                attachmentTransfer.download(auth.serverUrl, auth.accessToken, sessionId, attachment)
            }
        }
    }

    suspend fun createShare(
        sessionId: String,
        scope: String,
        itemIds: List<String>,
    ): Result<RemoteSessionShareResponse> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                sessionsApi.createSessionShare(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    sessionId = sessionId,
                    scope = scope,
                    itemIds = itemIds,
                )
            }
        }
    }

    suspend fun interrupt(sessionId: String): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val response = sessionsApi.interruptSession(auth.serverUrl, auth.accessToken, sessionId)
                if (!response.ok) {
                    throw IllegalStateException(response.failureMessage("Runtime rejected the interrupt."))
                }
                Unit
            }
        }
    }

    suspend fun loadSessionModelCatalog(sessionId: String): Result<RemoteRuntimeModelCatalog> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                sessionsApi.getSessionRuntimeModelCatalog(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                ).catalog
            }
        }
    }

    suspend fun loadSessionPermissionCatalog(sessionId: String): Result<RemoteRuntimePermissionCatalog> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                sessionsApi.getSessionRuntimePermissionCatalog(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                ).catalog
            }
        }
    }

    suspend fun updateSelections(
        sessionId: String,
        selections: Map<String, String?>,
    ): Result<SessionRuntimeState?> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val response = sessionsApi.patchSessionRuntimeSelections(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                    selections,
                )
                if (!response.ok) throw IllegalStateException("Runtime rejected the selection update.")
                response.state?.toSessionRuntimeState(response.serverTime)
            }
        }
    }

    suspend fun loadCommands(sessionId: String): Result<List<RuntimeCommand>> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                sessionsApi.getSessionRuntimeCommands(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                ).commands.map { it.toRuntimeCommand() }
            }
        }
    }

    suspend fun executeCommand(
        sessionId: String,
        request: RuntimeCommandRequest,
    ): RuntimeCommandOutcome {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val response = sessionsApi.executeSessionRuntimeCommand(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                    request.command,
                    request.args,
                    request.raw,
                )
                RuntimeCommandOutcome.fromResponse(
                    ok = response.ok,
                    code = response.code,
                    message = response.message,
                    result = (response.result as? JSONObject).toMap(),
                )
            }.getOrElse(RuntimeCommandOutcome::fromTransportFailure)
        }
    }

    suspend fun respondNotice(
        sessionId: String,
        noticeId: String,
        actionId: String,
        input: Map<String, Any?>?,
    ): Result<Unit> {
        return withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val response = sessionsApi.respondRuntimeNotice(
                    auth.serverUrl,
                    auth.accessToken,
                    sessionId,
                    noticeId,
                    actionId,
                    input,
                )
                if (!response.ok) {
                    throw RuntimeNoticeResponseException(
                        code = response.errorCode,
                        message = response.failureMessage("Runtime rejected the response."),
                    )
                }
                Unit
            }
        }
    }

    private fun RemoteRpcResponse.failureMessage(fallback: String): String {
        return errorMessage?.takeIf(String::isNotBlank)
            ?: errorCode?.takeIf(String::isNotBlank)
            ?: fallback
    }

    fun applyOlder(
        sessionId: String,
        current: SessionDetailState,
        older: SessionTimelineState,
    ): SessionDetailState {
        val orderingItems = mergeTimelineOrderingItems(
            current.timeline.orderingItems,
            older.orderingItems,
        )
        val realMessages = mergeTimelineMessages(
            current = current.messages.filterNot { it.optimistic },
            incoming = older.messages,
            orderingItems = orderingItems,
        )
        val messages = mergeOptimistic(
            sessionId = sessionId,
            realMessages = realMessages,
            currentMessages = current.messages,
            orderingItems = orderingItems,
        )
        return current.copy(
            timeline = current.timeline.copy(
                messages = messages,
                orderingItems = orderingItems,
                nextSeq = max(current.nextSeq, older.nextSeq),
                hasMore = older.hasMore,
                loadingOlder = false,
                historyErrorMessage = null,
            ),
        )
    }

    fun addOptimisticMessage(
        sessionId: String,
        state: SessionDetailState,
        text: String,
        clientMessageId: String,
        attachments: List<TimelineAttachment> = emptyList(),
    ): SessionDetailState {
        val lastOrderSeq = maxOf(
            state.timeline.orderingItems.maxOfOrNull { it.orderSeq } ?: 0,
            state.messages.maxOfOrNull { it.orderSeq } ?: 0,
        )
        val optimisticOrderSeq = maxOf(lastOrderSeq + 1, state.nextSeq + 1)
        val message = TimelineMessage(
            id = clientMessageId,
            sourceItemId = clientMessageId,
            author = MessageAuthor.User,
            text = text,
            attachments = attachments,
            status = "pending",
            badge = "Sending",
            orderSeq = optimisticOrderSeq,
            updatedSeq = optimisticOrderSeq,
            clientMessageId = clientMessageId,
            optimistic = true,
        )
        optimisticStore.upsert(sessionId, message)
        return state.copy(
            timeline = state.timeline.copy(
                messages = mergeOptimistic(
                    sessionId = sessionId,
                    realMessages = state.messages,
                    currentMessages = state.messages + message,
                    orderingItems = state.timeline.orderingItems,
                ),
            ),
            sending = true,
            actionError = null,
        )
    }

    fun markOptimisticMessage(
        sessionId: String,
        state: SessionDetailState,
        clientMessageId: String,
        status: String,
        attachments: List<TimelineAttachment> = emptyList(),
        errorMessage: String? = null,
        badge: String? = null,
    ): SessionDetailState {
        val updatedMessages = state.messages.map { message ->
            if (message.id == clientMessageId && message.optimistic) {
                message.copy(
                    status = status,
                    badge = badge ?: status.statusLabel(),
                    attachments = attachments.ifEmpty { message.attachments },
                    errorMessage = errorMessage,
                )
            } else {
                message
            }
        }
        optimisticStore.replace(
            sessionId = sessionId,
            messages = updatedMessages.filter { it.optimistic },
        )
        return state.copy(
            timeline = state.timeline.copy(
                messages = mergeOptimistic(
                    sessionId = sessionId,
                    realMessages = state.messages,
                    currentMessages = updatedMessages,
                    orderingItems = state.timeline.orderingItems,
                ),
            ),
            sending = status == "pending",
            actionError = if (status == "failed") state.actionError else null,
        )
    }

    fun hasServerEcho(state: SessionDetailState, clientMessageId: String): Boolean {
        return state.messages.any { message ->
            !message.optimistic && message.matchesClientMessage(clientMessageId)
        }
    }

    /**
     * Reads only the newest timeline page and reports whether the server ever echoed the
     * [clientMessageId] back as a real user message. Used after a send whose HTTP outcome is
     * unknown (transport error / 5xx), so a message that was actually admitted server-side is
     * never shown pinned as failed.
     */
    suspend fun findSendEcho(sessionId: String, clientMessageId: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val auth = authSession()
                val page = sessionsApi.getSessionTimelineLatest(
                    serverUrl = auth.serverUrl,
                    authorizationToken = auth.accessToken,
                    sessionId = sessionId,
                    limit = SEND_ECHO_LOOKUP_LIMIT,
                )
                page.items.any { item ->
                    item.type == "message" &&
                        item.source.optString("clientMessageId") == clientMessageId
                }
            }.getOrDefault(false)
        }

    /**
     * Polls the newest timeline page for the echo of an indeterminate send. Returns true as soon
     * as the message shows up; otherwise keeps polling for up to ~12s before giving up.
     */
    suspend fun confirmSendEcho(
        sessionId: String,
        clientMessageId: String,
        attempts: Int = SEND_ECHO_CONFIRM_ATTEMPTS,
    ): Boolean {
        repeat(attempts) {
            if (findSendEcho(sessionId, clientMessageId)) return true
            delay(SEND_ECHO_RETRY_DELAY_MILLIS)
        }
        return false
    }

    fun optimisticMessages(sessionId: String): List<TimelineMessage> = optimisticStore.read(sessionId)

    fun bindOptimisticSession(localSessionId: String, sessionId: String) {
        optimisticStore.move(localSessionId, sessionId)
    }

    private fun authSession(): ApiAuth {
        val serverUrl = sessionStore.readServerUrl()
        val accessToken = sessionStore.readAccessToken()
        if (serverUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("Sign in again to load this session.")
        }
        return ApiAuth(serverUrl = serverUrl, accessToken = accessToken)
    }

    private fun Throwable.userMessage(): String {
        return message ?: "Could not refresh this session."
    }

    private fun mergeOptimistic(
        sessionId: String,
        realMessages: List<TimelineMessage>,
        currentMessages: List<TimelineMessage>,
        orderingItems: List<TimelineOrderingItem>,
    ): List<TimelineMessage> {
        val merged = mergeOptimisticTimelineMessages(
            realMessages = realMessages,
            currentMessages = currentMessages,
            storedMessages = optimisticStore.read(sessionId),
            orderingItems = orderingItems,
        )
        optimisticStore.replace(sessionId, merged.pending)
        return merged.messages
    }

    private data class ApiAuth(
        val serverUrl: String,
        val accessToken: String,
    )

}

internal class RuntimeNoticeResponseException(
    val code: String?,
    message: String,
) : IllegalStateException(message)

data class SendMessageResult(
    val attachments: List<TimelineAttachment>,
)


/** The server refused a native-history operation; the UI shows the translated [code]. */
class NativeHistoryRejected(val code: String?, message: String) : IllegalStateException(message)
