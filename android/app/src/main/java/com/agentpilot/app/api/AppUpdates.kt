package com.agentpilot.app.api

import com.agentpilot.app.config.AppConfig

data class AndroidAppRelease(
    val versionName: String,
    val downloadUrl: String,
)

class AppUpdatesApi(private val client: ApiClient = ApiClient()) {
    fun check(serverUrl: String, currentVersionName: String): AndroidAppRelease? {
        val payload = client.getJson(
            serverUrl = serverUrl,
            path = "/health",
        )
        if (payload.optString("status") != "ok") throw ApiException("Server health check failed.")
        val latestVersion = payload.optString("version").trim()
        val comparison = compareUpdateVersions(latestVersion, currentVersionName)
            ?: throw ApiException("Server health response has no valid version.")
        if (comparison <= 0) return null
        // The APK channel is derived from the signed-in server so self-hosted and
        // tunnel deployments serve their own updates (server hosts <origin>/apk/latest.apk).
        val downloadUrl = AppConfig.updateDownloadUrl(serverUrl) ?: return null
        return AndroidAppRelease(
            versionName = latestVersion,
            downloadUrl = downloadUrl,
        )
    }
}
