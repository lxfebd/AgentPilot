package com.agentpilot.app.feature.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import androidx.core.content.FileProvider
import java.io.File

/**
 * Installs a downloaded APK update.
 *
 * Uses ACTION_VIEW with the system package installer — this is the most reliable
 * method across all Android ROMs (including MIUI / EMUI / ColorOS which often
 * silently block the PackageInstaller.Session API).  The system installer shows
 * the standard install-confirmation dialog and handles the "Install unknown apps"
 * permission prompt automatically.
 */
object AppUpdateInstaller {
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.attachments",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

/**
 * Legacy receiver kept for backward compatibility with in-flight sessions
 * that were started before the ACTION_VIEW migration.
 */
class AppUpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirmation?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmation?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                context.packageManager.getLaunchIntentForPackage(context.packageName)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    ?.let(context::startActivity)
            }
        }
    }
}
