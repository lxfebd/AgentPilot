package com.agentpilot.app.feature.notify

import android.content.Context
import android.content.SharedPreferences

/**
 * 通知偏好：是否开启"任务完成/出错"结果通知（区别于 attention 类提醒）。
 * 默认开启；关闭后 completed/error 两类通知不再发送，attention 提醒不受影响。
 */
internal class NotifyPreferences(context: Context) {
    private val preferences: SharedPreferences = context.applicationContext.getSharedPreferences(
        "notify-preferences",
        Context.MODE_PRIVATE,
    )

    /** 任务完成/出错结果通知开关，默认开启。 */
    var completionAlertsEnabled: Boolean
        get() = preferences.getBoolean(KEY_COMPLETION_ALERTS, true)
        set(value) {
            preferences.edit().putBoolean(KEY_COMPLETION_ALERTS, value).apply()
        }

    private companion object {
        const val KEY_COMPLETION_ALERTS = "completion_alerts_enabled"
    }
}
