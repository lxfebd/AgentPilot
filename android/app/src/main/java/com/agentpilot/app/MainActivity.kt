package com.agentpilot.app

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import com.agentpilot.app.app.AgentPilotApp
import com.agentpilot.app.feature.auth.WebLoginViewModel
import com.agentpilot.app.feature.notify.SessionNotifier
import com.agentpilot.app.feature.update.AppUpdateInstaller
import com.agentpilot.app.feature.update.AppUpdateViewModel
import com.agentpilot.app.ui.designsystem.AAAppearanceMode
import com.agentpilot.app.ui.designsystem.AALanguageMode
import com.agentpilot.app.ui.designsystem.AAThemePreference
import com.agentpilot.app.ui.designsystem.AAThemeTone
import com.agentpilot.app.ui.designsystem.AgentPilotTheme
import com.agentpilot.app.ui.screens.home.HomeSidebarViewMode
import java.io.File
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

class MainActivity : ComponentActivity() {
    private val oauthCallbackUri = mutableStateOf<Uri?>(null)
    private val webLoginViewModel by viewModels<WebLoginViewModel>()
    private val appUpdateViewModel by viewModels<AppUpdateViewModel>()
    private var appearanceMode by mutableStateOf(AAAppearanceMode.System)
    private var themeTone by mutableStateOf(AAThemeTone.Dynamic)
    private var amoledDark by mutableStateOf(false)
    private var languageMode by mutableStateOf(AALanguageMode.System)
    private var sidebarViewMode by mutableStateOf(HomeSidebarViewMode.Project)
    private var pendingUpdateApk: File? = null
    private var installingUpdate = false
    private var pendingOpenSessionId by mutableStateOf<String?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase.withSavedLanguage())
    }

    /**
     * 向系统申请面板的最高刷新率。
     *
     * 不显式指定 preferredDisplayModeId 时，OEM 定制（含 HyperOS）常把应用窗口
     * 钉在 60Hz，即便面板是 120/144Hz，Compose 动画就会按 60fps 节流。只接受
     * 与当前分辨率相同的 mode：切换分辨率会引发全量布局重排。
     */
    private fun requestHighRefreshRate() {
        @Suppress("DEPRECATION")
        val display = window.windowManager.defaultDisplay ?: return
        @Suppress("DEPRECATION")
        val current = display.mode ?: return
        @Suppress("DEPRECATION")
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        if (best.refreshRate <= current.refreshRate) return
        @Suppress("DEPRECATION")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighRefreshRate()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context)
                .memoryCache {
                    MemoryCache.Builder()
                        .maxSizePercent(context, 0.25)
                        .build()
                }
                .diskCache {
                    DiskCache.Builder()
                        .directory(File(context.cacheDir, "attachment-images").toOkioPath())
                        .maxSizeBytes(100L * 1024L * 1024L)
                        .build()
                }
                .build()
        }
        val preferences = getSharedPreferences(UI_PREFERENCES_NAME, MODE_PRIVATE)
        appearanceMode = preferences.getString(KEY_APPEARANCE_MODE, AAAppearanceMode.System)
            ?: AAAppearanceMode.System
        themeTone = preferences.getString(KEY_THEME_TONE, AAThemeTone.Dynamic)
            ?: AAThemeTone.Dynamic
        amoledDark = preferences.getBoolean(KEY_AMOLED_DARK, false)
        languageMode = preferences.getString(KEY_LANGUAGE_MODE, AALanguageMode.System)
            ?: AALanguageMode.System
        sidebarViewMode = HomeSidebarViewMode.normalize(
            preferences.getString(KEY_SIDEBAR_VIEW_MODE, HomeSidebarViewMode.Project),
        )
        oauthCallbackUri.value = intent?.data
        pendingOpenSessionId = intent?.getStringExtra(SessionNotifier.EXTRA_OPEN_SESSION_ID)
        setContent {
            AgentPilotTheme(
                appearanceMode = appearanceMode,
                themeTone = themeTone,
                amoledDark = amoledDark,
            ) {
                AgentPilotApp(
                    appearanceMode = appearanceMode,
                    themeTone = themeTone,
                    amoledDark = amoledDark,
                    languageMode = languageMode,
                    sidebarViewMode = sidebarViewMode,
                    onAppearanceModeChange = { mode ->
                        appearanceMode = mode
                        preferences.edit().putString(KEY_APPEARANCE_MODE, mode).apply()
                    },
                    onThemeToneChange = { tone ->
                        themeTone = tone
                        preferences.edit().putString(KEY_THEME_TONE, tone).apply()
                    },
                    onAmoledDarkChange = { enabled ->
                        amoledDark = enabled
                        preferences.edit().putBoolean(KEY_AMOLED_DARK, enabled).apply()
                    },
                    onLanguageModeChange = { mode ->
                        preferences.edit().putString(KEY_LANGUAGE_MODE, mode).apply()
                        if (mode != languageMode) {
                            languageMode = mode
                            recreate()
                        }
                    },
                    onSidebarViewModeChange = { mode ->
                        sidebarViewMode = HomeSidebarViewMode.normalize(mode)
                        preferences.edit().putString(KEY_SIDEBAR_VIEW_MODE, sidebarViewMode).apply()
                    },
                    oauthCallbackUri = oauthCallbackUri.value,
                    onOAuthCallbackConsumed = { oauthCallbackUri.value = null },
                    pendingOpenSessionId = pendingOpenSessionId,
                    onOpenSessionIdConsumed = { pendingOpenSessionId = null },
                    webLoginViewModel = webLoginViewModel,
                    appUpdateViewModel = appUpdateViewModel,
                    onInstallUpdate = ::requestUpdateInstall,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        oauthCallbackUri.value = intent.data
        pendingOpenSessionId = intent.getStringExtra(SessionNotifier.EXTRA_OPEN_SESSION_ID)
    }

    override fun onResume() {
        super.onResume()
        val pending = pendingUpdateApk ?: return
        if (packageManager.canRequestPackageInstalls()) {
            requestUpdateInstall(pending)
        } else {
            pendingUpdateApk = null
            appUpdateViewModel.reportInstallFailure()
        }
    }

    private fun requestUpdateInstall(apk: File) {
        if (installingUpdate) return
        if (!apk.isFile) {
            appUpdateViewModel.reportInstallFailure()
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            pendingUpdateApk = apk
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }
        pendingUpdateApk = null
        installingUpdate = true
        lifecycleScope.launch {
            runCatching {
                withContext(Dispatchers.IO) { AppUpdateInstaller.install(this@MainActivity, apk) }
            }.onSuccess {
                appUpdateViewModel.markInstallStarted()
            }.onFailure {
                appUpdateViewModel.reportInstallFailure()
            }
            installingUpdate = false
        }
    }

    private fun Context.withSavedLanguage(): Context {
        val languageMode = getSharedPreferences(UI_PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE_MODE, AALanguageMode.System)
            ?: AALanguageMode.System
        val languageTag = when (languageMode) {
            AALanguageMode.English -> "en"
            AALanguageMode.SimplifiedChinese -> "zh-CN"
            else -> return this
        }
        val config = Configuration(resources.configuration)
        config.setLocale(Locale.forLanguageTag(languageTag))
        return createConfigurationContext(config)
    }

    companion object {
        private const val UI_PREFERENCES_NAME = "agent_pilot_ui"
        private const val KEY_APPEARANCE_MODE = AAThemePreference.KEY_APPEARANCE_MODE
        private const val KEY_THEME_TONE = AAThemePreference.KEY_THEME_TONE
        private const val KEY_AMOLED_DARK = AAThemePreference.KEY_AMOLED_DARK
        private const val KEY_LANGUAGE_MODE = "language_mode"
        private const val KEY_SIDEBAR_VIEW_MODE = "sidebar_view_mode"
    }
}
