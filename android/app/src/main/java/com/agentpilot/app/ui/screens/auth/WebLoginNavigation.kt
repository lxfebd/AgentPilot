package com.agentpilot.app.ui.screens.auth

import java.net.URI

/**
 * True when [url] stays on the login origin the WebView was opened for.
 *
 * Anything else HTTP(S) — a redirect to another host, a link dragged off the
 * sign-in page — must leave the WebView (opened in the system browser) instead
 * of being followed in place. Kept free of Android imports so JVM unit tests
 * can cover it directly.
 */
internal fun isSameLoginOrigin(url: String, allowedOrigin: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    val scheme = uri.scheme?.lowercase() ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = uri.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return false
    val port = if (uri.port != -1) ":${uri.port}" else ""
    return "$scheme://$host$port" == allowedOrigin.lowercase()
}
