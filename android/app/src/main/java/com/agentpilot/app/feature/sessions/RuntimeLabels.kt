package com.agentpilot.app.feature.sessions

import com.agentpilot.app.model.runtimeTypeLabel

fun String.runtimeLabel(): String {
    return runtimeTypeLabel()
}
