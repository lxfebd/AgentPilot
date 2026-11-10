package com.agentpilot.app.ui.screens.dsh

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.agentpilot.app.R
import com.agentpilot.app.ui.designsystem.BackPill
import com.agentpilot.app.ui.designsystem.LocalAAColors
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Terminal

/**
 * 真 DSH Web UI 的服务器侧免登录入口（由服务器 nginx 提供）：
 * 该路径会写入 30 天有效的 DSH 会话 Cookie 并 302 到 /dsh/。
 * 与服务器 /etc/nginx/conf.d/agents-anywhere.conf 中的
 * `location = /__dsh-setup/<token>` 保持一致；换 token 时同步改这里。
 */
internal const val DSH_CONSOLE_SETUP_PATH = "/__dsh-setup/REDACTED_IN_HISTORY"

/**
 * 旧版 Android WebView（Chrome < 119）缺失的 Web API 垫片。
 *
 * 真机/模拟器上大量设备的 WebView 还停在 Chrome/110：
 * - `Promise.withResolvers`（Chrome 119）——DSH web boot 的插件加载用到；
 * - `AbortSignal.any`（Chrome 119）——api-gateway 的 RemoteStream 用到；
 * - `CSSRuleList` / `StyleSheetList` / `NamedNodeMap` 不可迭代——DSH 的
 *   sheet-rewrite 用 `for (const rule of sheet.cssRules)` 直接炸
 *   `TypeError: e is not iterable`，导致主界面不渲染。
 * 缺了它们 web boot 抛 TypeError，控制台直接显示 "entries did not activate"。
 * 在 onPageStarted 注入，早于页面脚本执行。
 */
private const val WEB_API_POLYFILL_JS = """
(function () {
  function makeIterable(proto) {
    if (!proto || typeof proto[Symbol.iterator] === 'function') return;
    try {
      Object.defineProperty(proto, Symbol.iterator, {
        value: function () {
          var self = this, i = 0;
          return { next: function () { return i < self.length ? { value: self[i++], done: false } : { value: undefined, done: true }; } };
        },
        writable: true, configurable: true,
      });
    } catch (e) {}
  }
  try {
    if (typeof Promise.withResolvers !== 'function') {
      Promise.withResolvers = function () {
        var resolve, reject;
        var promise = new Promise(function (res, rej) { resolve = res; reject = rej; });
        return { promise: promise, resolve: resolve, reject: reject };
      };
    }
    if (typeof AbortSignal !== 'undefined' && typeof AbortSignal.any !== 'function') {
      AbortSignal.any = function (signals) {
        var controller = new AbortController();
        var list = signals || [];
        for (var i = 0; i < list.length; i++) {
          (function (signal) {
            if (!signal) return;
            if (signal.aborted) { controller.abort(signal.reason); return; }
            signal.addEventListener('abort', function () { controller.abort(signal.reason); }, { once: true });
          })(list[i]);
        }
        return controller.signal;
      };
    }
    makeIterable(window.CSSRuleList && window.CSSRuleList.prototype);
    makeIterable(window.StyleSheetList && window.StyleSheetList.prototype);
    makeIterable(window.NamedNodeMap && window.NamedNodeMap.prototype);
    makeIterable(window.DOMStringList && window.DOMStringList.prototype);
    makeIterable(window.FileList && window.FileList.prototype);
  } catch (e) {}
})();
"""

/** 清理历史遗留的 Service Worker 注册与 Cache Storage（插件 chunk 的陈旧 HTML）。 */
private const val WEB_SW_PURGE_JS = """
(async function () {
  try {
    if (navigator.serviceWorker) {
      var regs = await navigator.serviceWorker.getRegistrations();
      for (var i = 0; i < regs.length; i++) { await regs[i].unregister(); }
    }
    if (window.caches) {
      var keys = await caches.keys();
      for (var j = 0; j < keys.length; j++) { await caches.delete(keys[j]); }
    }
  } catch (e) {}
})();
"""

/**
 * 在 App 内嵌完整 DSH Web 控制台。
 *
 * 数据链路：App WebView → 服务器 nginx（鉴权 Cookie 注入 + WebSocket 透传）
 * → SSH 反向隧道 → 本机中继（自动发现 DSH GUI 端口）→ DSH Web GUI。
 * 打开后每次先经过免登录入口刷新 Cookie（30 天有效），再落到 /dsh/。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun DshRemoteScreen(
    serverUrl: String,
    onBack: () -> Unit,
) {
    val colors = LocalAAColors.current
    val context = LocalContext.current
    val base = serverUrl.trim().trimEnd('/').ifBlank { "https://smartcare.aifacex.top" }
    val entryUrl = base + DSH_CONSOLE_SETUP_PATH
    val originHost = Uri.parse(base).host ?: ""
    var pageLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var reloadSignal by remember { mutableIntStateOf(0) }

    // DSH 附件上传：系统文件选择器
    var fileChooserCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val fileChooserLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val callback = fileChooserCallback
        fileChooserCallback = null
        val data = result.data
        val clip = data?.clipData
        val uris: Array<Uri>? = if (clip != null && clip.itemCount > 0) {
            Array(clip.itemCount) { index -> clip.getItemAt(index).uri }
        } else {
            data?.data?.let { arrayOf(it) }
        }
        callback?.onReceiveValue(uris)
    }

    BackHandler(enabled = canGoBack) { webView?.goBack() }

    // Keyed on Unit on purpose: keying the effect on the WebView state makes the
    // previous effect's onDispose observe the freshly created view (the state is
    // already assigned when the old effect is disposed) and destroy it while it is
    // still attached, leaving a blank pane with a spinner that never stops.
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                loadUrl("about:blank")
                destroy()
            }
            webView = null
        }
    }

    // 顶栏“刷新”/错误重试：重新走一次免登录入口（同时刷新 Cookie）
    LaunchedEffect(reloadSignal) {
        if (reloadSignal > 0) {
            pageError = null
            pageLoading = true
            // 兜底：清掉可能已存在的 Service Worker 注册与 Cache Storage，
            // 避免历史脏缓存继续返回脚本 chunk 的 404-HTML。
            webView?.evaluateJavascript(WEB_SW_PURGE_JS, null)
            webView?.clearCache(true)
            webView?.loadUrl(entryUrl)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BackPill(label = stringResource(R.string.common_back), onClick = onBack)
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.dsh_console_title),
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.dsh_console_refresh),
                color = colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { reloadSignal++ }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { viewContext ->
                    CookieManager.getInstance().apply { setAcceptCookie(true) }
                    WebView(viewContext).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        overScrollMode = android.view.View.OVER_SCROLL_NEVER
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            setSupportZoom(false)
                            builtInZoomControls = false
                            displayZoomControls = false
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            allowFileAccess = false
                            allowContentAccess = false
                            mediaPlaybackRequiresUserGesture = false
                            cacheMode = WebSettings.LOAD_DEFAULT
                        }
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url
                                // 同域导航留在 WebView 内；外部链接交给系统浏览器
                                if (url.host != null && url.host == originHost) return false
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, url)) }
                                return true
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                // 旧 WebView API 垫片 + Service Worker 清理，都在页面脚本之前注入。
                                // DSH 会注册 Service Worker；早期（nginx 未代理 /dsh-* 时）它把插件
                                // 的 404-HTML 写进了 SW 缓存，之后服务端修好了也一直读到陈旧 HTML
                                // （chunk parse 报 SyntaxError，且请求根本不出现在 nginx 日志里）。
                                // 每次开页都注销旧 SW 并清掉 Cache Storage，保证拿到真实 chunk。
                                view.evaluateJavascript(WEB_API_POLYFILL_JS, null)
                                view.evaluateJavascript(WEB_SW_PURGE_JS, null)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                canGoBack = view.canGoBack()
                                pageLoading = false
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    pageError = error.description?.toString()
                                        ?: context.getString(R.string.dsh_console_error_title)
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                if (newProgress < 100) pageLoading = true
                            }

                            override fun onShowFileChooser(
                                view: WebView,
                                filePathCallback: ValueCallback<Array<Uri>>,
                                fileChooserParams: FileChooserParams,
                            ): Boolean {
                                fileChooserCallback?.onReceiveValue(null)
                                fileChooserCallback = filePathCallback
                                return try {
                                    fileChooserLauncher.launch(fileChooserParams.createIntent())
                                    true
                                } catch (error: ActivityNotFoundException) {
                                    fileChooserCallback = null
                                    filePathCallback.onReceiveValue(null)
                                    false
                                }
                            }
                        }
                        loadUrl(entryUrl)
                        webView = this
                    }
                },
                update = { },
            )

            if (pageLoading && pageError == null) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(28.dp)
                        .align(Alignment.Center),
                    color = colors.muted,
                    strokeWidth = 2.5.dp,
                )
            }

            pageError?.let { message ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 40.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Lucide.Terminal,
                        contentDescription = null,
                        tint = colors.faint,
                        modifier = Modifier.size(30.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = stringResource(R.string.dsh_console_error_title),
                        color = colors.ink,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = message,
                        color = colors.muted,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = stringResource(R.string.dsh_console_retry),
                        color = colors.ink,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(colors.ink.copy(alpha = 0.08f))
                            .clickable { reloadSignal++ }
                            .padding(horizontal = 26.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}

/**
 * 设备详情页的「DSH 控制台」入口卡片。
 */
@Composable
internal fun DshConsoleEntryCard(
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalAAColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(colors.ink.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = Lucide.Terminal,
            contentDescription = null,
            tint = colors.ink,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.dsh_console_entry_title),
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.dsh_console_entry_subtitle),
                color = colors.muted,
                fontSize = 12.sp,
            )
        }
        Icon(
            imageVector = Lucide.ChevronRight,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(16.dp),
        )
    }
}