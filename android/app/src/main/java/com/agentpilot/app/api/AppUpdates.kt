package com.agentpilot.app.api

import com.agentpilot.app.config.AppConfig
import org.json.JSONObject

data class AndroidAppRelease(
    val versionName: String,
    val downloadUrl: String,
    /** Present when the served APK described itself via `apk/version.json`. */
    val versionCode: Int? = null,
)

/**
 * What `<origin>/apk/version.json` says about the APK that same origin serves.
 *
 * This is deliberately not the Server's own version. Server and Android PATCH
 * versions are released independently, so `/health`
 * describes the Server — comparing it against an installed Android version
 * makes an Android-only release invisible to phones.
 */
data class ApkReleaseManifest(
    val versionName: String?,
    val versionCode: Int?,
    val publishedAt: String?,
) {
    /** Label for the update UI; falls back to the code when no name was given. */
    val displayVersionName: String
        get() = versionName ?: versionCode?.toString().orEmpty()
}

/**
 * Network seam for the update check: it reads exactly two URLs, and the version
 * logic above them is pure, so the decision can be tested without a network.
 */
interface UpdateTransport {
    /** Raw body of `<origin>/apk/version.json`, or null when it is unavailable. */
    fun fetchManifest(manifestUrl: String): String?

    /** `<origin>/api/v2/health` — the contract predating the manifest. */
    fun fetchHealth(serverUrl: String): JSONObject
}

internal class HttpUpdateTransport(
    private val client: ApiClient = ApiClient(),
) : UpdateTransport {
    override fun fetchManifest(manifestUrl: String): String? = client.getTextOrNull(manifestUrl)

    override fun fetchHealth(serverUrl: String): JSONObject =
        client.getJson(serverUrl = serverUrl, path = "/health")
}

/**
 * Parse the body of `<origin>/apk/version.json`.
 *
 * Returns null for anything unusable — not JSON, or carrying no version at all —
 * which is what makes the caller fall back to the server-version comparison
 * instead of failing. Never throws, whatever the file contains.
 */
internal fun parseApkReleaseManifest(body: String?): ApkReleaseManifest? {
    if (body.isNullOrBlank()) return null
    return runCatching {
        val json = JSONObject(body)
        val versionName = json.optNullableString("versionName")
        val versionCode = positiveVersionCode(json, "versionCode")
        if (versionName == null && versionCode == null) return@runCatching null
        ApkReleaseManifest(
            versionName = versionName,
            versionCode = versionCode,
            publishedAt = json.optNullableString("publishedAt"),
        )
    }.getOrNull()
}

/**
 * Read a usable Android versionCode: only a positive integer counts.
 *
 * Deliberately not `optNullableInt`, which reports 0 for a non-numeric value and
 * would read a garbage manifest as "version 0" rather than "no code".
 */
private fun positiveVersionCode(json: JSONObject, key: String): Int? {
    val raw = json.opt(key)
    if (raw == null || raw === JSONObject.NULL) return null
    val value = when (raw) {
        is Int -> raw
        is Long -> raw.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()
        is Number -> raw.toInt()
        is String -> raw.trim().toIntOrNull()
        else -> null
    }
    return value?.takeIf { it > 0 }
}

/**
 * Is the APK the server is serving newer than what is installed?
 *
 * `versionCode` decides whenever the manifest carries one: Android gates installs
 * on that integer, and comparing an Android PATCH name against a Server PATCH
 * name is meaningless because the two lines are versioned independently. The
 * name is consulted only when the manifest has no usable code.
 */
internal fun isServedApkNewer(
    manifest: ApkReleaseManifest,
    currentVersionName: String,
    currentVersionCode: Int,
): Boolean {
    manifest.versionCode?.let { return it > currentVersionCode }
    val versionName = manifest.versionName ?: return false
    return (compareUpdateVersions(versionName, currentVersionName) ?: 0) > 0
}

class AppUpdatesApi(private val transport: UpdateTransport = HttpUpdateTransport()) {
    constructor(client: ApiClient) : this(HttpUpdateTransport(client))

    /**
     * Best-effort update check.
     *
     * The manifest is read first and, when it is present and valid, it is
     * authoritative: an APK that is not newer yields no update even if the
     * Server reports a higher version. When the manifest is missing (404),
     * unreachable, malformed, or carries no usable version, the pre-manifest
     * `/health` comparison runs unchanged so older self-hosted servers keep
     * working. A manifest failure never escapes as an exception.
     */
    fun check(
        serverUrl: String,
        currentVersionName: String,
        currentVersionCode: Int,
    ): AndroidAppRelease? {
        val manifest = runCatching {
            AppConfig.updateManifestUrl(serverUrl)?.let(transport::fetchManifest)
        }.getOrNull()?.let(::parseApkReleaseManifest)
        if (manifest != null) {
            if (!isServedApkNewer(manifest, currentVersionName, currentVersionCode)) return null
            val downloadUrl = AppConfig.updateDownloadUrl(serverUrl) ?: return null
            return AndroidAppRelease(
                versionName = manifest.displayVersionName,
                downloadUrl = downloadUrl,
                versionCode = manifest.versionCode,
            )
        }
        return checkServerVersion(serverUrl, currentVersionName)
    }

    /**
     * Legacy contract: the Server's `/health` version against the installed
     * version name. Kept exactly as it was, including its failures, so a
     * deployment without `apk/version.json` behaves as it always has.
     */
    private fun checkServerVersion(
        serverUrl: String,
        currentVersionName: String,
    ): AndroidAppRelease? {
        val payload = transport.fetchHealth(serverUrl)
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
