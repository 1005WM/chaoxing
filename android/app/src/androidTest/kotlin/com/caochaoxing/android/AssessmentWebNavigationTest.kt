// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 Pybsama.

package com.caochaoxing.android

import android.graphics.Bitmap
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.caochaoxing.android.ui.screens.AssessmentWebScreen
import com.caochaoxing.android.ui.screens.desktopAssessmentCourseFallback
import com.caochaoxing.android.ui.theme.CaochaoxingTheme
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AssessmentWebNavigationTest {
    @Test
    fun desktopListRejectsMobileNavigationAndRecoversFromBlankPage() = checkHomeworkNavigation(opensDetail = false)

    @Test
    fun desktopDetailFallsBackToTheSameMobileAssignment() = checkHomeworkNavigation(opensDetail = true)

    private fun checkHomeworkNavigation(opensDetail: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val capture = InstrumentationRegistry.getArguments().getString("capture") == "true"
        val app = instrumentation.targetContext.applicationContext as CaochaoxingApp
        assumeTrue(app.appState.uiState.value.accounts.isEmpty())
        val desktopPath = if (opensDetail) "task" else "list"
        val desktop = "https://fixture.chaoxing.com/mooc-ans/mooc2/work/$desktopPath?courseId=3&classId=2&cpi=7&workId=9"
        val mobile = "https://fixture.chaoxing.com/work/phone/task-work?workId=9"
        val blank = "https://fixture.chaoxing.com/mooc-ans/mooc2/work/task?workId=10"
        val course = desktopAssessmentCourseFallback(desktop)
        val chapters = "https://fixture.chaoxing.com/mooc-ans/mycourse/studentstudy?chapterId=11"
        val list = "https://mooc1.chaoxing.com/mooc2/work/list?enc=fixture-list"
        val automation = instrumentation.uiAutomation
        val ready = ArrayBlockingQueue<WebView>(1)

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val decor = activity.window.decorView
                decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        val web = decor.findWebView() ?: return
                        decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        val original = web.webViewClient
                        web.stopLoading()
                        web.webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                                original.shouldOverrideUrlLoading(view, request)

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) =
                                original.onPageStarted(view, url, favicon)

                            override fun onPageFinished(view: WebView, url: String) =
                                original.onPageFinished(view, url)

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) =
                                original.onReceivedError(view, request, error)

                            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                                val page = when (request.url.toString()) {
                                    desktop -> fixture(
                                        "模拟电脑版作业",
                                        "第 3 章 软件测试",
                                        "<a id='mobile' href='$mobile'>模拟跳到手机版</a><br>" +
                                            "<a id='blank' href='$blank'>模拟白屏</a>",
                                    )
                                    blank -> "<!doctype html><img width='20' height='20' src='data:image/svg+xml," +
                                        "%3Csvg xmlns=%22http://www.w3.org/2000/svg%22%3E%3C/svg%3E'>"
                                    course -> fixture(
                                        "模拟电脑版课程",
                                        "第 3 章 软件测试",
                                        "<a title='作业' data-url='$list' href='$list'>作业</a>",
                                    )
                                    list -> fixture(
                                        "模拟电脑版作业列表",
                                        "第 3 章 软件测试",
                                        "<style>html,body{height:100%;overflow-y:hidden!important}</style>" +
                                            (1..24).joinToString("") { "<p style='padding:20px;background:#f1f5f9'>作业项目 $it</p>" },
                                    )
                                    chapters -> error("作业页跳到了章节")
                                    mobile -> fixture("模拟手机版作业", "第 3 章 软件测试", "<p>已手动切换到手机版</p>")
                                    else -> ""
                                }
                                return WebResourceResponse("text/html", "UTF-8", page.byteInputStream())
                            }
                        }
                        web.loadUrl(desktop)
                        ready.offer(web)
                    }
                })
                activity.setContent {
                    CaochaoxingTheme {
                        AssessmentWebScreen(app.appState, desktop, mobile, "第 3 章 软件测试", onBack = {})
                    }
                }
            }

            val web = checkNotNull(ready.poll(10, TimeUnit.SECONDS)) { "WebView did not attach" }
            fun evaluate(script: String): String? {
                val result = ArrayBlockingQueue<String>(1)
                scenario.onActivity { web.evaluateJavascript(script) { result.offer(it ?: "null") } }
                return result.poll(4, TimeUnit.SECONDS)
            }
            fun waitFor(condition: String, message: String) {
                val deadline = SystemClock.uptimeMillis() + 12_000
                while (evaluate(condition) != "true") {
                    check(SystemClock.uptimeMillis() < deadline) { message }
                    SystemClock.sleep(100)
                }
            }
            fun capturePage(name: String) {
                if (!capture) return
                automation.waitForIdle(400, 5000)
                val bitmap = checkNotNull(automation.takeScreenshot())
                File(app.cacheDir, "assessment-$name.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
            }
            fun checkPage(heading: String, agent: String) {
                val condition = "document.querySelector('h1')?.textContent === '$heading' && navigator.userAgent.includes('$agent')"
                waitFor(condition, "Page not shown: $heading")
            }
            fun switchTo(description: String) {
                var switch = checkNotNull(automation.rootInActiveWindow.findDescription(description))
                while (!switch.isClickable) switch = checkNotNull(switch.parent)
                assertTrue(switch.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            }

            checkPage("模拟电脑版作业", "Windows NT")
            evaluate("document.getElementById('blank').click()")
            if (opensDetail) {
                checkPage("模拟手机版作业", "Android")
                switchTo("切换到电脑版")
                checkPage("模拟电脑版作业", "Windows NT")
                evaluate("location.href = '$chapters'")
                checkPage("模拟手机版作业", "Android")
                assertTrue(evaluate("location.href === '$mobile'") == "true")
                return@use
            }
            checkPage("模拟电脑版作业列表", "Windows NT")
            waitFor("getComputedStyle(document.documentElement).overflowY === 'auto'", "Desktop page still blocks scrolling")
            capturePage("recovered")
            var swipe = ""
            scenario.onActivity {
                val location = IntArray(2)
                web.getLocationOnScreen(location)
                val x = location[0] + web.width / 2
                swipe = "input swipe $x ${location[1] + web.height * 3 / 4} $x ${location[1] + web.height / 4} 400"
            }
            ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(swipe)).use { it.readBytes() }
            waitFor("window.scrollY > 100 || document.body.scrollTop > 100", "A finger swipe did not scroll the desktop page")
            capturePage("scrolled")

            switchTo("切换到手机版")
            checkPage("模拟手机版作业", "Android")
            switchTo("切换到电脑版")
            checkPage("模拟电脑版作业", "Windows NT")
            evaluate("document.getElementById('mobile').click()")
            checkPage("模拟电脑版作业列表", "Windows NT")
        }
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).findWebView()?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo?.findDescription(text: String): AccessibilityNodeInfo? {
        if (this == null) return null
        if (contentDescription?.toString() == text) return this
        for (index in 0 until childCount) getChild(index).findDescription(text)?.let { return it }
        return null
    }

    private fun fixture(heading: String, title: String, body: String): String = """
        <!doctype html><html lang="zh"><meta name="viewport" content="width=device-width,initial-scale=1">
        <meta charset="utf-8"><style>
        body{font:16px system-ui,sans-serif;margin:0;background:#f1f5f9;color:#14213d}
        main{max-width:800px;margin:24px auto;padding:24px;background:#fff}
        h1{margin:0 0 20px;font-size:25px} a{color:#2259ba}
        </style><main><h1>$heading</h1><h2>$title</h2>$body</main></html>
    """.trimIndent()
}
