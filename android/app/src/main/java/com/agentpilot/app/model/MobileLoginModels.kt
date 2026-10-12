package com.agentpilot.app.model

data class MobileLoginQrPayload(
    val serverUrl: String,
    val userId: String,
    val loginToken: String,
    val expiresAt: String?,
)
