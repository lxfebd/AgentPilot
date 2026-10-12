package com.agentpilot.app.feature.auth

import com.agentpilot.app.BuildConfig
import com.agentpilot.app.api.normalizeServerOrigin
import com.agentpilot.app.api.usesLocalNetworkHost
import java.net.URI

internal data class LoginServerConnection(
    val serverUrl: String,
    val oauthWebOrigin: String,
)

/** Port the local web dev server listens on for the debug OAuth flow. */
private const val WEB_DEV_PORT = 5174

internal fun resolveLoginServer(
    serverUrl: String,
    development: Boolean = BuildConfig.DEBUG,
): LoginServerConnection {
    val origin = requireNotNull(normalizeServerOrigin(serverUrl)) {
        "Enter a valid backend address."
    }
    val uri = URI(origin)
    // The local stack serves the API and the login page on different ports
    // (dev: API 8000 + web 5174, but any local port pairing is possible when
    // the API port is taken). Redirect the OAuth web origin to the web dev
    // server for LAN hosts and emulator gateways so a debug APK works on a
    // phone or emulator against a local stack.
    val webOrigin = if (development && uri.port != -1 && usesLocalNetworkHost(uri.host)) {
        URI(uri.scheme, null, uri.host, WEB_DEV_PORT, null, null, null).toString()
    } else {
        origin
    }
    return LoginServerConnection(serverUrl = origin, oauthWebOrigin = webOrigin)
}
