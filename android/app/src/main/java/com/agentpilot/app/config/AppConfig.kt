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

    /**
     * Manifest describing the APK that [updateDownloadUrl] points at, served by
     * the same deployment next to it: `<origin>/apk/version.json`.
     *
     * Both URLs come from one origin on purpose — a manifest read from a
     * different host than the download would describe a different APK.
     */
    fun updateManifestUrl(serverUrl: String): String? {
        val origin = normalizeServerOrigin(serverUrl) ?: return null
        return "$origin/apk/version.json"
    }

    fun isOfficialServer(serverUrl: String): Boolean {
        if (OFFICIAL_SERVER_URL.isBlank()) return false
        val officialOrigin = normalizeServerOrigin(OFFICIAL_SERVER_URL) ?: return false
        return normalizeServerOrigin(serverUrl) == officialOrigin
    }
}
