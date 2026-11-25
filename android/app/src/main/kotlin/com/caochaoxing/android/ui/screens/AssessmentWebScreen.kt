// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import android.annotation.SuppressLint
import android.widget.Toast
import android.graphics.Bitmap
import android.os.Message
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.DesktopWindows
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.state.AssessmentPageExporter
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingStoredCookie
import kotlinx.coroutines.launch

/**
 * 只实现跳转相关的桥接通知：打开网页、退出页面。
 * 不涉及答题、提交、签到等任何业务接口。
 */
private const val BRIDGE_COMPAT_JS = """
(function(){
  try {
    if (!window.jsBridge || window.__dshBridgeCompat) return;
    window.__dshBridgeCompat = true;
    var original = window.jsBridge.postNotification;
    window.jsBridge.postNotification = function(name, info){
      try {
        info = info || {};
        if (name === 'CLIENT_OPEN_URL' && info.webUrl) {
          window.location.href = info.webUrl;
          return;
        }
        if (name === 'CLIENT_EXIT_LEVEL' || name === 'CLIENT_EXIT_WEBAPP') {
          window.history.back();
          return;
        }
      } catch (e) {}
      try { if (original) original.call(this, name, info); } catch (e) {}
    };
  } catch (e) {}
})();
"""

private const val DESKTOP_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

private const val OPEN_COURSE_HOMEWORK_JS = """
(function() {
  var tab = document.querySelector('[title="作业"][data-url], [aria-label="作业"][data-url]');
  if (!tab) return false;
  tab.click();
  return true;
})();
"""

// 只解除有内容溢出的根节点滚动限制。带 iframe 的课程页可能在子页面中加载作业。
private const val CHECK_DESKTOP_LAYOUT_JS = """
(function() {
  var root = document.documentElement;
  var body = document.body;
  if (!root || !body || document.readyState !== 'complete') return false;
  function clipsScroll(element) {
    var overflow = getComputedStyle(element).overflowY;
    return overflow === 'hidden' || overflow === 'clip';
  }
  if (Math.max(root.scrollHeight, body.scrollHeight) > window.innerHeight + 1) {
    if (clipsScroll(root)) root.style.setProperty('overflow-y', 'auto', 'important');
    if (clipsScroll(body)) body.style.setProperty('overflow-y', 'visible', 'important');
  }
  if (body.innerText.trim()) return false;
  return !Array.from(body.querySelectorAll('iframe, frame, embed, object, canvas, video, input, button, a, [aria-busy="true"]'))
    .some(function(element) { return element.getClientRects().length > 0; });
})();
"""

/**
 * 用当前账号的 Cookie 在应用内打开作业 / 考试网页。
 *
 * 电脑版使用桌面 UA 和宽视口。只有提供了手机链接时才可切换版本。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AssessmentWebScreen(
    appState: AndroidAppState,
    url: String,
    mobileUrl: String?,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val hasMobileVariant = !mobileUrl.isNullOrBlank() && mobileUrl != url && !isDesktopAssessmentUrl(mobileUrl)
    val opensHomework = remember(url) { isHomeworkDetailUrl(url) }
    // 单项电脑版作业页默认进电脑版；课程入口/列表回退则默认进手机版，避免一开始就是易白页的列表。
    var desktopMode by remember(url) { mutableStateOf(isIndividualDesktopAssessmentUrl(url)) }
    val currentDesktopMode by rememberUpdatedState(desktopMode)
    val currentDesktopCourseFallback by rememberUpdatedState(remember(url) { desktopAssessmentCourseFallback(url) })
    var triedDesktopCourse by remember(url) { mutableStateOf(false) }
    var openedHomeworkTab by remember(url) { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var redirectedToMobile by remember { mutableStateOf(false) }
    var blocked by remember { mutableStateOf(false) }
    var canGoBack by remember { mutableStateOf(false) }
    var clearHistoryOnPageFinish by remember { mutableStateOf(false) }
    var currentUrl by remember(url) { mutableStateOf(url) }
    var showAi by remember { mutableStateOf(false) }
    var aiQuestions by remember { mutableStateOf(emptyList<com.caochaoxing.core.service.AiQuestion>()) }
    var aiPageUrl by remember { mutableStateOf(url) }
    var aiReadError by remember { mutableStateOf("") }
    var aiReadWarning by remember { mutableStateOf("") }
    var readingAi by remember { mutableStateOf(false) }
    val aiReadScope = androidx.compose.runtime.rememberCoroutineScope()
    val aiController = com.caochaoxing.android.CaochaoxingApp.from(context).aiStudyController

    val popupViews = remember { mutableListOf<WebView>() }
    val consoleMessages = remember { mutableListOf<String>() }

    fun tryDesktopCourse(view: WebView): Boolean {
        val course = currentDesktopCourseFallback ?: return false
        if (triedDesktopCourse) return false
        triedDesktopCourse = true
        view.post {
            if (view.isAttachedToWindow && currentDesktopMode && triedDesktopCourse) view.loadUrl(course)
        }
        return true
    }

    fun recoverHomework(message: String) {
        if (currentDesktopMode && hasMobileVariant) {
            desktopMode = false
        } else {
            errorMessage = message
            isLoading = false
        }
    }

    fun blockNavigation(view: WebView, request: WebResourceRequest): Boolean {
        val target = request.url.toString()
        if (!isAllowedAssessmentUrl(target)) return true
        if (opensHomework && !request.hasGesture() && (isCourseStudyUrl(target) || isHomeworkListUrl(target))) {
            recoverHomework("学习通未打开这份作业，请刷新后重试。")
            return true
        }
        if (!currentDesktopMode || !isMobileHomeworkUrl(target)) return false
        if (opensHomework) {
            recoverHomework("学习通未提供这份作业的电脑版页面。")
            return true
        }
        if (!tryDesktopCourse(view)) {
            redirectedToMobile = true
            isLoading = false
        }
        return true
    }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                // 学习通列表里的条目常用 window.open 打开作业页；关闭多窗口会导致点了没反应。
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = true
            }
            val main = this
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                    if (message != null) {
                        if (consoleMessages.size > 60) consoleMessages.removeAt(0)
                        consoleMessages += "[${message.messageLevel()}] ${message.message()} " +
                            "(${message.sourceId()}:${message.lineNumber()})"
                    }
                    return true
                }

                // 学习通电脑版列表用 window.open 打开作业页，这里把新窗口的地址接回主 WebView。
                override fun onCreateWindow(
                    view: WebView,
                    isDialog: Boolean,
                    isUserGesture: Boolean,
                    resultMsg: Message,
                ): Boolean {
                    val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                    val child = WebView(main.context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val target = request.url?.toString().orEmpty()
                                if (request.isForMainFrame && !blockNavigation(main, request)) {
                                    main.loadUrl(target)
                                }
                                return true
                            }
                        }
                    }
                    popupViews += child
                    transport.webView = child
                    resultMsg.sendToTarget()
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                private var navigationVersion = 0

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.isForMainFrame && blockNavigation(view, request)

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    navigationVersion++
                    isLoading = true
                    errorMessage = null
                    if (!url.isNullOrBlank()) currentUrl = url
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    if (clearHistoryOnPageFinish) {
                        view?.clearHistory()
                        clearHistoryOnPageFinish = false
                    }
                    canGoBack = view?.canGoBack() == true
                    if (!url.isNullOrBlank()) currentUrl = url
                    // 学习通 H5 / 电脑版页面靠 jsBridge 的 CLIENT_OPEN_URL 跳转；官方 App 之外
                    // 这个调用会被丢弃，点条目没反应。这里只补一个「收到打开请求就跳转」的兼容层。
                    view?.evaluateJavascript(BRIDGE_COMPAT_JS, null)
                    if (view == null || url == null || !currentDesktopMode || !isDesktopAssessmentUrl(url)) return
                    val version = navigationVersion
                    fun isCurrentPage(): Boolean = currentDesktopMode && view.isAttachedToWindow &&
                        version == navigationVersion && view.url == url && errorMessage == null

                    if (!openedHomeworkTab && isDesktopAssessmentCourseUrl(url)) {
                        view.evaluateJavascript(OPEN_COURSE_HOMEWORK_JS) { opened ->
                            if (opened == "true") openedHomeworkTab = true
                        }
                    }
                    view.evaluateJavascript(CHECK_DESKTOP_LAYOUT_JS) { empty ->
                        if (empty != "true" || !isCurrentPage()) return@evaluateJavascript
                        view.postDelayed({
                            if (isCurrentPage()) {
                                view.evaluateJavascript(CHECK_DESKTOP_LAYOUT_JS) { stillEmpty ->
                                    if (stillEmpty == "true" && isCurrentPage()) {
                                        if (opensHomework) recoverHomework("学习通电脑版没有返回这份作业的内容。")
                                        else if (!tryDesktopCourse(view)) errorMessage = "学习通电脑版没有返回作业内容。"
                                    }
                                }
                            }
                        }, 1200)
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        isLoading = false
                        errorMessage = error?.description?.toString()?.takeIf { it.isNotBlank() }
                            ?: "页面加载失败"
                    }
                }
            }
        }
    }
    val defaultUserAgent = remember(webView) { webView.settings.userAgentString }

    fun applyMode(desktop: Boolean) {
        webView.settings.apply {
            userAgentString = if (desktop) DESKTOP_USER_AGENT else defaultUserAgent
            useWideViewPort = desktop
            loadWithOverviewMode = desktop
            builtInZoomControls = desktop
            displayZoomControls = false
            setSupportZoom(desktop)
        }
    }

    DisposableEffect(webView) {
        onDispose {
            popupViews.forEach { popup ->
                popup.stopLoading()
                popup.destroy()
            }
            popupViews.clear()
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(url, mobileUrl, desktopMode) {
        val target = if (desktopMode) url else (mobileUrl ?: url)
        triedDesktopCourse = false
        openedHomeworkTab = false
        redirectedToMobile = false
        if (!isAllowedAssessmentUrl(target)) {
            blocked = true
            isLoading = false
            return@LaunchedEffect
        }
        blocked = false
        webView.stopLoading()
        clearHistoryOnPageFinish = true
        canGoBack = false
        currentUrl = target
        applyMode(desktopMode)
        injectChaoxingCookies(appState, target)
        webView.loadUrl(target)
    }

    if (showAi) AiDraftSheet(aiPageUrl, title, aiQuestions, readError = aiReadError, readWarning = aiReadWarning,
        onApply = { index, answer ->
            val script = aiController.applyAnswerScript(index, answer)
            webView.evaluateJavascript(script) { raw ->
                val result = aiController.parseApplyResult(raw)
                Toast.makeText(context, result.message, Toast.LENGTH_SHORT).show()
            }
        }) { showAi = false }

    BackHandler(enabled = canGoBack && !showAi) {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = CxPalette.canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title.ifBlank { "作业页面" },
                            style = CxTypography.title,
                            color = CxPalette.ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = (if (desktopMode) "电脑版  " else "手机版  ") +
                                currentUrl.substringAfter("://", currentUrl).substringBefore("?"),
                            style = MaterialTheme.typography.labelSmall,
                            color = CxPalette.inkTertiary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CxPalette.canvas,
                    titleContentColor = CxPalette.ink,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val page = webView.url ?: return@TextButton
                        if (!isAllowedAssessmentUrl(page)) return@TextButton
                        aiReadScope.launch {
                            readingAi = true
                            aiReadError = ""
                            aiReadWarning = ""
                            aiQuestions = emptyList()
                            try {
                                val page = aiController.readPage(webView)
                                aiQuestions = page.questions
                                aiReadWarning = page.warning
                            }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { aiReadError = e.message ?: "读取题目失败，请刷新页面后重试" }
                            finally { readingAi = false }
                            if (webView.url == page) { aiPageUrl = page; showAi = true }
                        }
                    }, enabled = !isLoading && !blocked && !readingAi) { Text(if (readingAi) "读题中…" else "AI 作答") }
                    if (hasMobileVariant) {
                        IconButton(onClick = { desktopMode = !desktopMode }) {
                            Icon(
                                imageVector = if (desktopMode) {
                                    Icons.Rounded.Smartphone
                                } else {
                                    Icons.Rounded.DesktopWindows
                                },
                                contentDescription = if (desktopMode) "切换到手机版" else "切换到电脑版",
                                tint = CxPalette.accent,
                            )
                        }
                    }
                    IconButton(
                        onClick = {
                            val extra = buildString {
                                appendLine("URL: ${webView.url ?: currentUrl}")
                                appendLine("UA: ${webView.settings.userAgentString}")
                                appendLine("--- console ---")
                                consoleMessages.forEach { appendLine(it) }
                            }
                            AssessmentPageExporter.export(context, webView, extra) { saved ->
                                Toast.makeText(
                                    context,
                                    saved?.let { "网页源码已保存到 $it" } ?: "保存失败",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        enabled = !blocked,
                    ) {
                        Icon(
                            Icons.Rounded.BugReport,
                            contentDescription = "保存网页源码（排查用）",
                            tint = CxPalette.inkTertiary,
                        )
                    }
                    IconButton(
                        onClick = {
                            if (redirectedToMobile || errorMessage != null) {
                                triedDesktopCourse = false
                                openedHomeworkTab = false
                                redirectedToMobile = false
                                errorMessage = null
                                webView.loadUrl(if (desktopMode) url else (mobileUrl ?: url))
                            } else {
                                webView.reload()
                            }
                        },
                        enabled = !blocked,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = CxPalette.accent)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading && !blocked) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                if (!blocked) {
                    AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                        update = { it.visibility = if (redirectedToMobile || errorMessage != null) View.INVISIBLE else View.VISIBLE },
                    )
                }
                when {
                    blocked -> WebNotice(
                        title = "这个链接不在允许范围内",
                        detail = "只允许打开 https 的 chaoxing.com 页面，避免会话被带到外部站点。",
                    )
                    redirectedToMobile -> WebNotice(
                        title = "学习通返回了手机版",
                        detail = if (hasMobileVariant) "可点右上角的手机图标打开手机版。" else "可在电脑浏览器中打开课程作业。",
                    )
                    errorMessage != null -> WebNotice(
                        title = "网页加载失败",
                        detail = "$errorMessage\n可返回后重试，或先在网页版确认该作业是否还能打开。",
                    )
                    else -> Unit
                }
            }
            Text(
                text = "页面由学习通提供，提交等操作需你自己点击",
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CxPalette.surfaceSecondary)
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = CxPalette.inkSecondary,
            )
        }
    }
}

@Composable
private fun WebNotice(title: String, detail: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(CxPalette.canvas).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title, style = CxTypography.headline, color = CxPalette.ink)
        Text(
            detail,
            modifier = Modifier.padding(top = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = CxPalette.inkSecondary,
        )
    }
}

/** 把当前账号的 Cookie 写进 WebView，使网页保持登录态。只写 chaoxing.com 域。 */
internal fun injectChaoxingCookies(appState: AndroidAppState, url: String) {
    val cookies: List<ChaoxingStoredCookie> = appState.activeClient?.session?.cookies.orEmpty()
    if (cookies.isEmpty()) return
    val manager = CookieManager.getInstance()
    manager.setAcceptCookie(true)
    cookies
        .filter { cookie -> cookie.domain.contains("chaoxing.com", ignoreCase = true) }
        .forEach { cookie ->
            val value = buildString {
                append(cookie.name).append('=').append(cookie.value)
                append("; path=").append(cookie.path.ifBlank { "/" })
                val domain = cookie.domain.trim()
                if (domain.isNotBlank()) {
                    append("; domain=").append(domain)
                }
                if (cookie.isSecure) append("; secure")
            }
            manager.setCookie(url, value)
            val domain = cookie.domain.trim().trimStart('.')
            if (domain.isNotBlank()) {
                manager.setCookie("https://$domain/", value)
            }
        }
    manager.flush()
}
