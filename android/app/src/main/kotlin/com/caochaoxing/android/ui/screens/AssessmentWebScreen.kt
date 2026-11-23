// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

@file:OptIn(ExperimentalMaterial3Api::class)

package com.caochaoxing.android.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.caochaoxing.android.state.AndroidAppState
import com.caochaoxing.android.ui.theme.CxPalette
import com.caochaoxing.android.ui.theme.CxTypography
import com.caochaoxing.core.model.ChaoxingStoredCookie
import java.net.URI

/**
 * 用当前账号的 Cookie 在应用内打开作业 / 考试的网页，只展示页面，不注入脚本、不自动提交。
 *
 * 只允许 https 的 `*.chaoxing.com` 地址；其他链接会被拒绝，避免把会话带到外部站点。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AssessmentWebScreen(
    appState: AndroidAppState,
    url: String,
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val allowed = remember(url) { isAllowedAssessmentUrl(url) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var canGoBack by remember { mutableStateOf(false) }

    val webView = remember {
        WebView(context).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
                javaScriptCanOpenWindowsAutomatically = false
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    isLoading = true
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    isLoading = false
                    canGoBack = view?.canGoBack() == true
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

    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.destroy()
        }
    }

    LaunchedEffect(url, allowed) {
        if (!allowed) return@LaunchedEffect
        errorMessage = null
        injectChaoxingCookies(appState, url)
        webView.loadUrl(url)
    }

    BackHandler(enabled = canGoBack) {
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
                    Text(
                        text = title.ifBlank { "作业页面" },
                        style = CxTypography.title,
                        color = CxPalette.ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
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
                    IconButton(onClick = { if (allowed) webView.reload() }, enabled = allowed) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "刷新", tint = CxPalette.accent)
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            if (isLoading && allowed) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Box(modifier = Modifier.fillMaxSize()) {
                when {
                    !allowed -> WebNotice(
                        title = "这个链接不在允许范围内",
                        detail = "只允许打开 https 的 chaoxing.com 页面，避免会话被带到外部站点。",
                    )
                    errorMessage != null -> WebNotice(
                        title = "网页加载失败",
                        detail = "$errorMessage\n可返回后重试，或先在网页版确认该作业是否还能打开。",
                    )
                    else -> AndroidView(
                        factory = { webView },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (!allowed || errorMessage != null) {
                    // 错误态已经覆盖，隐藏 WebView 的空白区域
                }
            }
            Text(
                text = "只显示页面，不会自动开始或提交作业",
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
        modifier = Modifier.fillMaxSize().padding(24.dp),
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

/** 只接受 https 且主机名为 chaoxing.com 或其子域名。 */
internal fun isAllowedAssessmentUrl(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host?.lowercase() ?: return false
    uri.scheme.equals("https", ignoreCase = true) &&
        (host == "chaoxing.com" || host.endsWith(".chaoxing.com"))
}.getOrDefault(false)

/** 把当前账号的 Cookie 写进 WebView，使网页保持登录态。只写 chaoxing.com 域。 */
private fun injectChaoxingCookies(appState: AndroidAppState, url: String) {
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
            // 同时按域名写入，兼容子域页面（例如 mobilelearn / mooc1-api）。
            val domain = cookie.domain.trim().trimStart('.')
            if (domain.isNotBlank()) {
                manager.setCookie("https://$domain/", value)
            }
        }
    manager.flush()
}
