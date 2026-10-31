package com.agentpilot.app.feature.realtime

import com.agentpilot.app.api.ApiException
import com.agentpilot.app.api.RealtimeTransport
import com.agentpilot.app.api.RemoteDashboardSnapshot
import com.agentpilot.app.feature.auth.AuthSessionReader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class DashboardRealtimeController(
    private val transport: RealtimeTransport,
    private val sessionStore: AuthSessionReader,
    private val clientId: String,
    private val retryDelay: (Int) -> Long = ::reconnectDelayMillis,
    private val firstSnapshotTimeoutMillis: Long = 2_500L,
) {
    private val retryNow = Channel<Unit>(Channel.CONFLATED)
    private val activeSocket = AtomicReference<WebSocket?>()
    private val activeJob = AtomicReference<Job?>()

    fun requestImmediateReconnect() {
        activeSocket.getAndSet(null)?.cancel()
        retryNow.trySend(Unit)
    }

    fun start(
        scope: CoroutineScope,
        onSnapshot: (RemoteDashboardSnapshot) -> Unit,
        onConnectionChanged: (connected: Boolean, attempt: Int) -> Unit = { _, _ -> },
        onInitialFailure: () -> Unit = {},
    ): Job {
        val job = scope.launch(Dispatchers.IO) {
        var attempt = 0
        var initialFailureReported = false
        while (isActive) {
            val serverUrl = sessionStore.readServerUrl()
            val accessToken = sessionStore.readAccessToken()
            if (serverUrl.isBlank() || accessToken.isBlank()) return@launch

            // Dashboard snapshots are authoritative full state: only the latest
            // matters, so a conflated channel is correct and bounds memory even
            // if the consumer is briefly slower than the server.
            val incoming = Channel<String>(Channel.CONFLATED)
            var socket: WebSocket? = null
            var receivedSnapshot = false
            val opened = AtomicBoolean(false)
            val openSignal = CompletableDeferred<Boolean>()
            try {
                val ticket = transport.createDashboardTicket(serverUrl, accessToken, clientId)
                socket = transport.openWebSocket(
                    transport.dashboardWebSocketUrl(serverUrl, ticket.ticket),
                    channelListener(incoming) {
                        opened.set(true)
                        openSignal.complete(true)
                        onConnectionChanged(true, 0)
                    }.also { listener ->
                        listener.onTerminated = { openSignal.complete(false) }
                        listener.onOverflow = {
                            // A conflated channel drops intermediate snapshots; the
                            // consumer still receives the latest. Nothing to do.
                        }
                    },
                )
                activeSocket.set(socket)
                if (!openSignal.await()) throw IllegalStateException("Dashboard WebSocket handshake failed.")
                val firstSnapshot: RemoteDashboardSnapshot? = withTimeoutOrNull(firstSnapshotTimeoutMillis) {
                    var parsed: RemoteDashboardSnapshot? = null
                    while (parsed == null) {
                        val message = incoming.receiveCatching().getOrNull() ?: break
                        parsed = transport.parseDashboardMessage(message)
                    }
                    parsed
                }
                if (firstSnapshot != null) {
                    receivedSnapshot = true
                    onSnapshot(firstSnapshot)
                }
                if (!receivedSnapshot && !initialFailureReported) {
                    initialFailureReported = true
                    onInitialFailure()
                }
                for (message in incoming) {
                    transport.parseDashboardMessage(message)?.let { snapshot ->
                        receivedSnapshot = true
                        onSnapshot(snapshot)
                    }
                    attempt = 0
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (authFailure: ApiException) {
                if (authFailure.statusCode == 401) {
                    // Session is gone; reconnect would only ever 401 again.
                    if (!receivedSnapshot && !initialFailureReported) {
                        initialFailureReported = true
                        onInitialFailure()
                    }
                    return@launch
                }
                if (!receivedSnapshot && !initialFailureReported) {
                    initialFailureReported = true
                    onInitialFailure()
                }
            } catch (_: Exception) {
                if (!receivedSnapshot && !initialFailureReported) {
                    initialFailureReported = true
                    onInitialFailure()
                }
            } finally {
                activeSocket.compareAndSet(socket, null)
                socket?.cancel()
                incoming.close()
                onConnectionChanged(false, attempt)
            }
            if (!isActive) break
            if (opened.get() || receivedSnapshot) attempt = 0 else attempt = (attempt + 1).coerceAtMost(30)
            // attempt counts failures (1-based, for the UI); the first retry waits
            // the 1s base delay, the next one 2s, and so on.
            val requested = withTimeoutOrNull(retryDelay((attempt - 1).coerceAtLeast(0))) { retryNow.receive() } != null
            if (requested) attempt = 0
        }
        }
        activeJob.getAndSet(job)?.cancel()
        job.invokeOnCompletion { activeJob.compareAndSet(job, null) }
        return job
    }
}

internal class ChannelWebSocketListener(
    private val incoming: Channel<String>,
    private val onOpenCallback: () -> Unit = {},
) : WebSocketListener() {
    var onTerminated: () -> Unit = {}
    /** Invoked when [incoming] rejected a message because its buffer was full. */
    var onOverflow: (() -> Unit)? = null

    override fun onOpen(webSocket: WebSocket, response: Response) {
        onOpenCallback()
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        val accepted = incoming.trySend(text).isSuccess
        if (!accepted) onOverflow?.invoke()
    }

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        onTerminated()
        incoming.close()
    }

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
        onTerminated()
        incoming.close(t)
    }
}

internal fun channelListener(
    incoming: Channel<String>,
    onOpen: () -> Unit = {},
): ChannelWebSocketListener = ChannelWebSocketListener(incoming, onOpen)
