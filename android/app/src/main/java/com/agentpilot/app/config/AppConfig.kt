package com.agentpilot.app.config

import com.agentpilot.app.BuildConfig
import com.agentpilot.app.api.normalizeServerOrigin

object AppConfig {
    // Official server is not bundled with the open-source build. Users connect to
    // their own server (self-hosted / LAN / tunnel) from the login screen.
    // Personal builds can inject a default via android/local.properties
    // (agentPilot.serverUrl=https://your-server.example) — that file is gitignored.
    val OFFICIAL_SERVER_URL: String = BuildConfig.OFFICIAL_SERVER_URL
    // Desktop app download is configured by each deployment; blank hides the button.
    const val DESKTOP_DOWNLOAD_URL = ""

    /**
     * APK update channel for a server the user is signed in to.
     * Derived from the logged-in server origin, so self-hosted / tunnel users
     * get updates from their own deployment without hardcoding any host.
     */
    fun updateDownloadUrl(serverUrl: String): String? {
        val origin = normalizeServerOrigin(serverUrl) ?: return null
        return "$origin/apk/latest.apk"
    }

    fun isOfficialServer(serverUrl: String): Boolean {
        if (OFFICIAL_SERVER_URL.isBlank()) return false
        val officialOrigin = normalizeServerOrigin(OFFICIAL_SERVER_URL) ?: return false
        return normalizeServerOrigin(serverUrl) == officialOrigin
    }
}
