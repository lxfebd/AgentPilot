package com.agentpilot.app.feature.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.agentpilot.app.MainActivity
import com.agentpilot.app.R
import com.agentpilot.app.model.AgentSession

/**
 * 会话状态通知：会话进入"需要人处理"（等待回答/等待批准/出错/已中断/有新回复）时
 * 发送系统通知，点通知直接打开对应会话。
 *
 * 2.0.8 起新增两类"结果通知"：
 *  - [NotifyKind.Completed] 会话从 running/stopping 转为 idle（任务跑完），使用独立的低打扰渠道；
 *  - [NotifyKind.Error]    会话进入 error/blocked（任务出错/被中断），保持高优先级。
 * 这两类与 [NotifyKind.Attention] 使用不同的通知 id，互不覆盖；点通知同样打开对应会话。
 */
object SessionNotifier {
    /** 需要人处理类通知的渠道。 */
    private const val CHANNEL_ID = "session_status"
    /** 任务完成类通知的低打扰渠道。 */
    private const val CHANNEL_ID_COMPLETED = "session_completed"

    /** 通知点击 Intent 的 extra：要打开的会话 id。 */
    const val EXTRA_OPEN_SESSION_ID = "open_session_id"

    /** 通知 id 偏移，区分 attention（会话 id hash）与结果（完成/出错）通知，避免互相覆盖。 */
    private const val RESULT_NOTIFICATION_OFFSET = 0x4000_0000

    enum class NotifyKind {
        /** 需要人处理：等待回答/审批/出错/中断/新回复（高优先级）。 */
        Attention,

        /** 任务完成：running → idle（低打扰渠道）。 */
        Completed,

        /** 任务出错或中断（高优先级）。 */
        Error,
    }

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = context.getString(R.string.notify_channel_description)
                }
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID_COMPLETED,
                    context.getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = context.getString(R.string.notify_channel_description)
                }
            )
        }
    }

    fun canNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun notifySession(
        context: Context,
        session: AgentSession,
        label: String,
        color: Int,
        kind: NotifyKind = NotifyKind.Attention,
    ) {
        if (!canNotify(context)) return
        ensureChannels(context)
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_SESSION_ID, session.id)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            session.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val channelId = if (kind == NotifyKind.Completed) CHANNEL_ID_COMPLETED else CHANNEL_ID
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setColor(color)
            .setContentTitle(session.title.ifBlank { session.runtime })
            .setContentText(label)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (kind == NotifyKind.Completed) {
                    NotificationCompat.PRIORITY_DEFAULT
                } else {
                    NotificationCompat.PRIORITY_HIGH
                }
            )
            .setOnlyAlertOnce(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(session.id, kind), notification)
    }

    fun cancelSession(context: Context, sessionId: String) {
        val manager = NotificationManagerCompat.from(context)
        manager.cancel(sessionId.hashCode())
        manager.cancel(sessionId.hashCode() xor RESULT_NOTIFICATION_OFFSET)
    }

    private fun notificationId(sessionId: String, kind: NotifyKind): Int =
        if (kind == NotifyKind.Attention) {
            sessionId.hashCode()
        } else {
            sessionId.hashCode() xor RESULT_NOTIFICATION_OFFSET
        }
}
