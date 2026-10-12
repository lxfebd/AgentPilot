package com.agentpilot.app.config

import com.agentpilot.app.BuildConfig
import com.agentpilot.app.api.normalizeServerOrigin

object AppConfig {
    // Debug builds can override the backend in android/local.properties.
    val OFFICIAL_SERVER_URL: String = BuildConfig.OFFICIAL_SERVER_URL
    const val DESKTOP_DOWNLOAD_URL = "https://agent-pilot.com/download"
    // Self-hosted update channel: serves the APK over HTTPS from the operator's VPS.
    // 固定别名 latest.apk：随版本号变的硬编码 URL 会让旧版更新时下载到旧文件（自更新循环）。
    // 每次发布把最新 APK 同步为服务器上的 latest.apk 即可，此常量不再随版本改动。
    const val UPDATE_DOWNLOAD_URL = "https://example.com/apk/latest.apk"

    fun isOfficialServer(serverUrl: String): Boolean {
        val officialOrigin = normalizeServerOrigin(OFFICIAL_SERVER_URL) ?: return false
        return normalizeServerOrigin(serverUrl) == officialOrigin
    }
}
