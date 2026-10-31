package com.agentpilot.app.api

import org.json.JSONArray
import org.json.JSONObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import okio.source
import org.json.JSONException

class ApiClient(
    private val onUnauthorized: (accessToken: String) -> Unit = {},
    private val accessTokenRefresher: AccessTokenRefresher? = null,
) {
    fun requireHtmlDocument(
        serverUrl: String,
        path: String = "/",
    ) {
        try {
            val origin = requireNotNull(normalizeServerOrigin(serverUrl)) {
                "Server URL must be an HTTP(S) origin."
            }
            val normalizedPath = if (path.startsWith('/')) path else "/$path"
            val request = Request.Builder()
                .url("$origin$normalizedPath")
                .header("Accept", "text/html")
                .header("ngrok-skip-browser-warning", "true")
                .get()
                .build()

            JSON_HTTP_CLIENT.newCall(request).execute().use { response ->
                val contentType = response.header("Content-Type").orEmpty()
                if (!response.isSuccessful || !contentType.contains("text/html", ignoreCase = true)) {
                    throw ApiException(
                        message = "The web login is unavailable at $origin. Check the server's web login deployment.",
                        statusCode = response.code,
                    )
                }
            }
        } catch (exc: ApiException) {
            throw exc
        } catch (exc: IllegalArgumentException) {
            throw ApiException("The server URL is invalid.", cause = exc)
        } catch (exc: IOException) {
            throw ApiException("Could not reach the server. Check the URL and network.", cause = exc)
        }
    }

    /**
     * GET an absolute URL and return the raw response body, or null when the
     * resource is unavailable (non-2xx, unreachable, or a bad URL).
     *
     * For optional resources only — an older server never serves
     * `<origin>/apk/version.json`, and that must be a fallback, not an error.
     * The body is returned unparsed so the caller decides what is malformed.
     */
    fun getTextOrNull(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("ngrok-skip-browser-warning", "true")
                .get()
                .build()
            JSON_HTTP_CLIENT.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (exc: IOException) {
            null
        } catch (exc: IllegalArgumentException) {
            null
        }
    }

    fun getJson(
        serverUrl: String,
        path: String,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "GET",
            bodyText = null,
            authorizationToken = authorizationToken,
        )
    }

    fun postJson(
        serverUrl: String,
        path: String,
        body: JSONObject,
        authorizationToken: String? = null,
        readTimeoutSeconds: Long? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "POST",
            bodyText = body.toString(),
            authorizationToken = authorizationToken,
            readTimeoutSeconds = readTimeoutSeconds,
        )
    }

    fun postForm(
        serverUrl: String,
        path: String,
        fields: Map<String, String>,
    ): JSONObject {
        val body = FormBody.Builder().apply {
            fields.forEach { (name, value) -> add(name, value) }
        }.build()
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "POST",
            bodyText = null,
            authorizationToken = null,
            requestBody = body,
        )
    }

    fun postJson(
        serverUrl: String,
        path: String,
        body: JSONArray,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "POST",
            bodyText = body.toString(),
            authorizationToken = authorizationToken,
        )
    }

    fun postJson(
        serverUrl: String,
        path: String,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "POST",
            bodyText = null,
            authorizationToken = authorizationToken,
        )
    }

    fun patchJson(
        serverUrl: String,
        path: String,
        body: JSONObject,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "PATCH",
            bodyText = body.toString(),
            authorizationToken = authorizationToken,
        )
    }

    fun putJson(
        serverUrl: String,
        path: String,
        body: JSONObject,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "PUT",
            bodyText = body.toString(),
            authorizationToken = authorizationToken,
        )
    }

    fun deleteJson(
        serverUrl: String,
        path: String,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestJson(
            serverUrl = serverUrl,
            path = path,
            method = "DELETE",
            bodyText = null,
            authorizationToken = authorizationToken,
        )
    }

    fun postMultipart(
        serverUrl: String,
        path: String,
        files: List<UploadFilePart>,
        authorizationToken: String? = null,
    ): JSONObject {
        return requestWithAutoRefresh(serverUrl = serverUrl, authorizationToken = authorizationToken) { token ->
            executeMultipartRequest(
                serverUrl = serverUrl,
                path = path,
                files = files,
                authorizationToken = token,
            )
        }
    }

    private fun executeMultipartRequest(
        serverUrl: String,
        path: String,
        files: List<UploadFilePart>,
        authorizationToken: String?,
    ): JSONObject {
        return try {
            val endpoint = URL(apiUrl(serverUrl, path))
            val boundary = "AA-${System.currentTimeMillis()}"
            // Stream each part from disk instead of buffering whole files in
            // memory, and reuse the shared OkHttp connection pool instead of
            // opening a fresh HttpURLConnection per request.
            val body = object : RequestBody() {
                override fun contentType(): okhttp3.MediaType =
                    "multipart/form-data; boundary=$boundary".toMediaType()

                override fun writeTo(sink: okio.BufferedSink) {
                    files.forEach { file ->
                        sink.writeUtf8("--$boundary\r\n")
                        sink.writeUtf8(
                            "Content-Disposition: form-data; name=\"files\"; filename=\"${file.name.httpQuoted()}\"\r\n",
                        )
                        sink.writeUtf8("Content-Type: ${file.mediaType.ifBlank { "application/octet-stream" }}\r\n\r\n")
                        val stream = file.openStream?.invoke()
                        if (stream != null) {
                            stream.use { input -> sink.writeAll(input.source()) }
                        } else {
                            sink.write(file.bytes)
                        }
                        sink.writeUtf8("\r\n")
                    }
                    sink.writeUtf8("--$boundary--\r\n")
                }
            }
            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .header("Accept", "application/json")
                .header("ngrok-skip-browser-warning", "true")
                .apply {
                    if (!authorizationToken.isNullOrBlank()) {
                        header("Authorization", "Bearer $authorizationToken")
                    }
                }
                .build()
            JSON_HTTP_CLIENT.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()
                val responseCode = response.code
                if (responseCode !in 200..299) {
                    throw ApiException(
                        message = parseErrorMessage(responseText) ?: defaultErrorMessage(responseCode),
                        statusCode = responseCode,
                        errorCode = parseErrorCode(responseText),
                    )
                }
                if (responseText.isBlank()) JSONObject() else JSONObject(responseText)
            }
        } catch (exc: ApiException) {
            throw exc
        } catch (exc: IllegalArgumentException) {
            throw ApiException("The server URL is invalid.", cause = exc)
        } catch (exc: IOException) {
            throw ApiException("Could not reach the server. Check the URL and network.", cause = exc)
        }
    }

    private fun requestJson(
        serverUrl: String,
        path: String,
        method: String,
        bodyText: String?,
        authorizationToken: String?,
        readTimeoutSeconds: Long? = null,
        requestBody: RequestBody? = null,
    ): JSONObject {
        return requestWithAutoRefresh(serverUrl = serverUrl, authorizationToken = authorizationToken) { token ->
            executeJsonRequest(
                serverUrl = serverUrl,
                path = path,
                method = method,
                bodyText = bodyText,
                authorizationToken = token,
                readTimeoutSeconds = readTimeoutSeconds,
                requestBody = requestBody,
            )
        }
    }

    private fun executeJsonRequest(
        serverUrl: String,
        path: String,
        method: String,
        bodyText: String?,
        authorizationToken: String?,
        readTimeoutSeconds: Long?,
        requestBody: RequestBody?,
    ): JSONObject {
        return try {
            val resolvedRequestBody = requestBody ?: when {
                bodyText != null -> bodyText.toRequestBody(JSON_MEDIA_TYPE)
                method == "POST" || method == "PUT" || method == "PATCH" -> EMPTY_JSON_BODY
                else -> null
            }
            val httpClient = if (readTimeoutSeconds == null) {
                JSON_HTTP_CLIENT
            } else {
                JSON_HTTP_CLIENT.newBuilder()
                    .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                    .build()
            }

            val request = Request.Builder()
                .url(apiUrl(serverUrl, path))
                .header("Accept", "application/json")
                .header("ngrok-skip-browser-warning", "true")
                .method(method, resolvedRequestBody)
                .apply {
                    if (!authorizationToken.isNullOrBlank()) {
                        header("Authorization", "Bearer $authorizationToken")
                    }
                }
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseText = response.body?.string().orEmpty()

                if (!response.isSuccessful) {
                    throw ApiException(
                        message = parseErrorMessage(responseText) ?: defaultErrorMessage(response.code),
                        statusCode = response.code,
                        errorCode = parseErrorCode(responseText),
                    )
                }

                return parseJsonResponse(responseText)
            }
        } catch (exc: ApiException) {
            throw exc
        } catch (exc: IllegalArgumentException) {
            throw ApiException("The server URL is invalid.", cause = exc)
        } catch (exc: IOException) {
            throw ApiException("Could not reach the server. Check the URL and network.", cause = exc)
        }
    }

    /**
     * Run [execute] and, when it fails with a 401 and a refresher is available,
     * rotate the access token once and replay the request exactly once.
     *
     * The intermediate 401 is not reported to [onUnauthorized]: a session only
     * ends when the refresh itself is rejected. A refresh that fails for
     * network reasons keeps the session and surfaces a retryable error.
     */
    private fun requestWithAutoRefresh(
        serverUrl: String,
        authorizationToken: String?,
        execute: (authorizationToken: String?) -> JSONObject,
    ): JSONObject {
        var token = authorizationToken
        var alreadyRefreshed = false
        while (true) {
            try {
                return execute(token)
            } catch (exc: ApiException) {
                if (!alreadyRefreshed && exc.statusCode == 401 && !token.isNullOrBlank()) {
                    when (val refresh = requestAccessTokenRefresh(serverUrl, token)) {
                        is AccessTokenRefreshResult.Refreshed -> {
                            if (refresh.accessToken.isNotBlank() && refresh.accessToken != token) {
                                token = refresh.accessToken
                                alreadyRefreshed = true
                                continue
                            }
                        }
                        is AccessTokenRefreshResult.Rejected -> {
                            notifyUnauthorized(401, token)
                            throw exc
                        }
                        is AccessTokenRefreshResult.Failed -> throw ApiException(
                            message = "Could not refresh the session. Check the network and try again.",
                            cause = refresh.error,
                        )
                        null -> Unit
                    }
                }
                notifyUnauthorized(exc.statusCode ?: 0, token)
                throw exc
            }
        }
    }

    private fun requestAccessTokenRefresh(
        serverUrl: String,
        failedAccessToken: String,
    ): AccessTokenRefreshResult? {
        val refresher = accessTokenRefresher ?: return null
        return runCatching { refresher.refresh(serverUrl, failedAccessToken) }.getOrNull()
    }

    internal fun notifyUnauthorized(statusCode: Int, authorizationToken: String?) {
        if (!shouldNotifyUnauthorized(statusCode, authorizationToken)) return
        runCatching { onUnauthorized(authorizationToken.orEmpty()) }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_JSON_BODY = ByteArray(0).toRequestBody(JSON_MEDIA_TYPE)
        val JSON_HTTP_CLIENT: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private fun readResponseText(connection: HttpURLConnection, responseCode: Int): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
    }

    private fun parseErrorCode(responseText: String): String? = runCatching {
        val body = JSONObject(responseText)
        (body.optJSONObject("detail") ?: body).optString("code").takeIf(String::isNotBlank)
    }.getOrNull()

    private fun parseErrorMessage(responseText: String): String? {
        return runCatching {
            val detail = JSONObject(responseText).opt("detail")
            when (detail) {
                is String -> detail.takeIf { it.isNotBlank() }
                is JSONObject -> detail.optString("message")
                    .ifBlank { detail.optString("code") }
                    .takeIf { it.isNotBlank() }
                is JSONArray -> detail.optJSONObject(0)
                    ?.optString("msg")
                    ?.takeIf { it.isNotBlank() }
                else -> detail?.toString()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    private fun parseJsonResponse(responseText: String): JSONObject {
        if (responseText.isBlank()) return JSONObject()
        return try {
            JSONObject(responseText)
        } catch (exc: JSONException) {
            throw ApiException("The server returned an invalid response.", cause = exc)
        }
    }

    private fun defaultErrorMessage(statusCode: Int): String {
        return when (statusCode) {
            401 -> "Unauthorized request."
            404 -> "Endpoint was not found on this server."
            else -> "Request failed with status $statusCode."
        }
    }

    private fun String.httpQuoted(): String {
        return replace("\\", "\\\\").replace("\"", "\\\"").replace("\r", "").replace("\n", "")
    }
}

internal fun shouldNotifyUnauthorized(statusCode: Int, authorizationToken: String?): Boolean {
    return statusCode == 401 && !authorizationToken.isNullOrBlank()
}

data class UploadFilePart(
    val name: String,
    val mediaType: String,
    val bytes: ByteArray,
    /** Lazy file stream; when set the upload streams from disk instead of buffering [bytes] in memory. */
    val openStream: (() -> java.io.InputStream)? = null,
)

class ApiException(
    override val message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null,
    val errorCode: String? = null,
) : Exception(message, cause)
