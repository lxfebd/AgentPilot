package com.agentpilot.app.ui.screens.sessiondetail

import android.content.Context
import org.json.JSONArray

/**
 * 会话搜索历史（2.0.8）：全局共享（跨会话），最多保留 [MAX_ENTRIES] 条最近搜索词，
 * 去重后最新在前。query 为空时展示在搜索 overlay 中供一键复用。
 */
internal class SessionSearchHistoryStore(context: Context? = null) {
    private val preferences = context?.getSharedPreferences(
        "session-search-history",
        Context.MODE_PRIVATE,
    )

    internal fun load(): List<String> {
        val raw = preferences?.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            List(array.length()) { index -> array.optString(index) }
                .filter(String::isNotBlank)
        }.getOrDefault(emptyList())
    }

    internal fun record(query: String) {
        val normalized = query.trim().take(MAX_QUERY_LENGTH)
        if (normalized.isBlank()) return
        val updated = buildList {
            add(normalized)
            addAll(load().filterNot { it == normalized })
        }.take(MAX_ENTRIES)
        preferences?.edit()?.putString(KEY, JSONArray(updated).toString())?.apply()
    }

    internal fun clear() {
        preferences?.edit()?.remove(KEY)?.apply()
    }

    private companion object {
        const val KEY = "recent_queries"
        const val MAX_ENTRIES = 8
        const val MAX_QUERY_LENGTH = 60
    }
}
